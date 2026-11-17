"""Exercise failures that would otherwise interrupt an already-running server."""
import contextlib
import importlib.util
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

if sys.platform == "win32":
    sys.modules.setdefault("fcntl", types.SimpleNamespace())
spec = importlib.util.spec_from_file_location("runtime", Path(__file__).parents[1] / "runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
package_spec = importlib.util.spec_from_file_location("package_release", Path(__file__).parents[1] / "package.py")
packager = importlib.util.module_from_spec(package_spec)
package_spec.loader.exec_module(packager)

REVISION = "a" * 40
DIGEST = "sha256:" + "b" * 64
CONFIG = {"region": "ap-northeast-2", "image_repository": "registry.invalid/boxbox"}


class DeployTests(unittest.TestCase):
    def execute_release(self, health):
        self.containers = {"boxbox": "previous-image"}
        self.calls = []

        def run(args, **kwargs):
            self.calls.append(args)
            if args[:2] == ["aws", "ecr"]:
                return "token"
            if args[:2] == ["docker", "run"]:
                self.containers[args[args.index("--name") + 1]] = args[-1]
            if args[:2] == ["docker", "rm"]:
                self.containers.pop(args[-1], None)
            if args[:2] == ["docker", "rename"]:
                self.containers[args[3]] = self.containers.pop(args[2])
            return ""

        @contextlib.contextmanager
        def env(_):
            yield Path("/fake/env")

        with tempfile.TemporaryDirectory() as directory:
            with patch.object(runtime, "HOME", Path(directory)), patch.object(runtime, "run", side_effect=run), \
                 patch.object(runtime, "runtime_env", env), patch.object(runtime, "exists", side_effect=lambda n: n in self.containers), \
                 patch.object(runtime, "healthy", side_effect=health):
                runtime.deploy(CONFIG, DIGEST, REVISION)
                self.saved_release = json.loads((Path(directory) / "current-release.json").read_text())

    def test_candidate_failure_preserves_running_server(self):
        with self.assertRaisesRegex(RuntimeError, "Candidate failed"):
            self.execute_release([False])
        self.assertEqual(self.containers, {"boxbox": "previous-image"})
        self.assertFalse(any(call[:2] == ["docker", "stop"] for call in self.calls))

    def test_failed_replacement_restores_previous_server(self):
        with self.assertRaisesRegex(RuntimeError, "New server failed"):
            self.execute_release([True, False, True])
        self.assertEqual(self.containers, {"boxbox": "previous-image"})
        self.assertIn(["docker", "start", "boxbox"], self.calls)

    def test_failed_rollback_is_not_reported_as_recovery(self):
        with self.assertRaisesRegex(RuntimeError, "could not recover"):
            self.execute_release([True, False, False])

    def test_success_uses_digest_and_keeps_previous_image(self):
        self.execute_release([True, True])
        self.assertEqual(self.containers["boxbox"], CONFIG["image_repository"] + "@" + DIGEST)
        self.assertEqual(self.containers["boxbox-previous"], "previous-image")
        self.assertEqual(self.saved_release, {"revision": REVISION, "digest": DIGEST})

    def test_invalid_identity_never_calls_docker_or_aws(self):
        with patch.object(runtime, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "Invalid immutable"):
                runtime.deploy(CONFIG, "latest; anything", REVISION)
            run.assert_not_called()


class InitializationTests(unittest.TestCase):
    def test_partial_schema_does_not_run_ddl_or_seed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "db").mkdir()
            (root / "db/schema.sql").write_text("CREATE TABLE `users` (); CREATE TABLE `teams` ();")
            config = {"admin_secret": "admin", "database_secret": "app", "region": "region"}
            with patch.object(runtime, "secret", return_value={}), patch.object(runtime, "mysql", return_value="users") as mysql:
                with self.assertRaisesRegex(RuntimeError, "partially initialized"):
                    runtime.initialize(config, root)
                self.assertEqual(mysql.call_count, 1)
                self.assertEqual(mysql.call_args.args[2], "SHOW TABLES;")


class PackageTests(unittest.TestCase):
    def test_windows_and_linux_checkouts_produce_same_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "build/libs").mkdir(parents=True)
            (root / "build/libs/app.jar").write_bytes(b"test-jar")
            (root / "deploy").mkdir()
            source = root / "deploy/runner.py"
            source.write_bytes(b"first\r\nsecond\r\n")
            with patch.object(packager, "ROOT", root):
                packager.package()
                first = hashlib.sha256((root / "build/release.tar.gz").read_bytes()).hexdigest()
                source.write_bytes(b"first\nsecond\n")
                packager.package()
                second = hashlib.sha256((root / "build/release.tar.gz").read_bytes()).hexdigest()
            self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
