"""Failures that could interrupt the one-host demo or lose its data."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

if sys.platform == 'win32':
    sys.modules.setdefault('fcntl', types.SimpleNamespace())


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


runtime = load('runtime', 'runtime.py')
packager = load('package_release', 'package.py')
publisher = load('publisher', 'publish.py')
REVISION = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64
REPOSITORY = '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/boxbox'


class DeployTests(unittest.TestCase):
    def execute_release(self, health, fail_pull=False):
        self.containers = {'boxbox': 'previous-image', 'boxbox-mysql': 'database'}
        self.calls = []

        def run(args, **kwargs):
            self.calls.append(args)
            if fail_pull and args[:2] == ['docker', 'pull']:
                raise subprocess.CalledProcessError(1, args)
            if args[:2] == ['docker', 'run']:
                self.containers[args[args.index('--name') + 1]] = args[-1]
            if args[:2] == ['docker', 'rm']:
                self.containers.pop(args[-1], None)
            if args[:2] == ['docker', 'rename']:
                self.containers[args[3]] = self.containers.pop(args[2])
            return ''

        with tempfile.TemporaryDirectory() as directory:
            with patch.object(runtime, 'HOME', Path(directory)), patch.object(runtime, 'run', side_effect=run), \
                 patch.object(runtime, 'app_env', return_value=Path('/fake/env')), \
                 patch.object(runtime, 'exists', side_effect=lambda n: n in self.containers), \
                 patch.object(runtime, 'healthy', side_effect=health):
                runtime.deploy(REPOSITORY, DIGEST, REVISION)
                self.saved_release = json.loads((Path(directory) / 'current-release.json').read_text())

    def test_pull_failure_preserves_running_server(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.execute_release([], fail_pull=True)
        self.assertEqual(self.containers['boxbox'], 'previous-image')
        self.assertFalse(any(call[:2] == ['docker', 'stop'] for call in self.calls))

    def test_failed_replacement_restores_previous_server_and_preserves_database(self):
        with self.assertRaisesRegex(RuntimeError, 'New server failed'):
            self.execute_release([False, True])
        self.assertEqual(self.containers, {'boxbox': 'previous-image', 'boxbox-mysql': 'database'})
        self.assertIn(['docker', 'start', 'boxbox'], self.calls)
        self.assertFalse(any('boxbox-mysql' in call for call in self.calls))

    def test_failed_rollback_is_not_reported_as_recovery(self):
        with self.assertRaisesRegex(RuntimeError, 'could not recover'):
            self.execute_release([False, False])

    def test_success_uses_digest_and_keeps_previous_image(self):
        self.execute_release([True])
        self.assertEqual(self.containers['boxbox'], REPOSITORY + '@' + DIGEST)
        self.assertEqual(self.containers['boxbox-mysql'], 'database')
        self.assertEqual(self.saved_release, {'revision': REVISION, 'digest': DIGEST})

    def test_invalid_identity_never_calls_docker(self):
        with patch.object(runtime, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'Invalid immutable'):
                runtime.deploy(REPOSITORY, 'latest; anything', REVISION)
            run.assert_not_called()


class InitializationTests(unittest.TestCase):
    def test_partial_schema_does_not_run_ddl_or_seed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'db').mkdir()
            (root / 'db/schema.sql').write_text('CREATE TABLE `users` (); CREATE TABLE `teams` ();')
            with patch.object(runtime, 'mysql', return_value='users') as mysql:
                with self.assertRaisesRegex(RuntimeError, 'Partial'):
                    runtime.ensure_schema(root)
                self.assertEqual(mysql.call_count, 1)
                self.assertEqual(mysql.call_args.args[0], 'SHOW TABLES;')

    def test_signing_and_database_secrets_survive_redeployment(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(runtime, 'SETTINGS', Path(directory)):
            initial = runtime.credentials()
            self.assertEqual(initial, runtime.credentials())
            self.assertNotEqual(initial['root_password'], initial['db_password'])


class SshTests(unittest.TestCase):
    def details(self):
        return {'ipAddress': '192.0.2.1', 'username': 'ubuntu', 'privateKey': 'test-only-key', 'certKey': 'test-only-certificate', 'hostKeys': []}

    def test_missing_host_keys_refuses_connection(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, 'refusing unverified'):
                publisher.ssh_options(self.details(), {'PublicIp': '192.0.2.1'}, directory)

    def test_unexpected_address_refuses_connection(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, 'Unexpected'):
                publisher.ssh_options(self.details(), {'PublicIp': '192.0.2.2'}, directory)


class PackageTests(unittest.TestCase):
    def test_windows_and_linux_checkouts_produce_same_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'build/libs').mkdir(parents=True)
            (root / 'build/libs/app.jar').write_bytes(b'test-jar')
            (root / 'deploy').mkdir()
            source = root / 'deploy/runner.py'
            source.write_bytes(b'first\r\nsecond\r\n')
            with patch.object(packager, 'ROOT', root):
                packager.package()
                first = hashlib.sha256((root / 'build/release.tar.gz').read_bytes()).hexdigest()
                source.write_bytes(b'first\nsecond\n')
                packager.package()
                second = hashlib.sha256((root / 'build/release.tar.gz').read_bytes()).hexdigest()
            self.assertEqual(first, second)


if __name__ == '__main__':
    unittest.main()
