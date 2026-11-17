"""EC2-only release runner. Secrets never leave the host or enter release artifacts."""
import argparse
import base64
import contextlib
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import time

HOME = Path("/opt/boxbox")
CONFIG = Path("/etc/boxbox/config.json")


def run(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def secret(arn, region):
    return json.loads(run(["aws", "secretsmanager", "get-secret-value", "--region", region,
                           "--secret-id", arn, "--query", "SecretString", "--output", "text"]))


def mysql(config, credentials, sql):
    environment = dict(os.environ, MYSQL_PWD=credentials["password"])
    return run(["mysql", "--host", config["db_host"], "--user", credentials["username"],
                "--ssl-ca=/etc/boxbox/rds-ca.pem", "--ssl-verify-server-cert",
                "--batch", "--skip-column-names", "boxbox"], env=environment, input=sql)


def initialize(config, root):
    admin = secret(config["admin_secret"], config["region"])
    database = secret(config["database_secret"], config["region"])
    schema = (root / "db/schema.sql").read_text(encoding="utf-8")
    expected = set(re.findall(r"CREATE TABLE `([a-z_]+)`", schema))
    actual = set(mysql(config, admin, "SHOW TABLES;").splitlines())
    if not expected:
        raise RuntimeError("Missing schema")
    if actual:
        if actual != expected:
            raise RuntimeError("Database is partially initialized or has another schema; manual review required")
        print("Existing schema preserved; no DDL or seed executed")
    else:
        mysql(config, admin, schema)
        mysql(config, admin, (root / "db/board-seed.sql").read_text(encoding="utf-8"))
        print("Empty database initialized; no user/test/race data imported")
    if database["username"] != "boxbox_app" or not re.fullmatch(r"[A-Za-z0-9]{48}", database["password"]):
        raise RuntimeError("Unexpected database secret format")
    mysql(config, admin, "CREATE USER IF NOT EXISTS 'boxbox_app'@'%' IDENTIFIED BY '"
          + database["password"] + "'; GRANT SELECT, INSERT, UPDATE, DELETE ON boxbox.* TO 'boxbox_app'@'%';")
    mysql(config, database, "SELECT 1;")


@contextlib.contextmanager
def runtime_env(config):
    database = secret(config["database_secret"], config["region"])
    jwt = secret(config["jwt_secret"], config["region"])["key"]
    if len(base64.b64decode(jwt, validate=True)) < 32:
        raise RuntimeError("Invalid signing secret")
    values = {
        "DB_URL": "jdbc:mysql://" + config["db_host"] + ":3306/boxbox?connectionTimeZone=UTC&characterEncoding=UTF-8&sslMode=VERIFY_IDENTITY",
        "DB_USERNAME": database["username"], "DB_PASSWORD": database["password"],
        "AUTH_JWT_SECRET": jwt, "AUTH_ALLOWED_ORIGINS": config["allowed_origins"],
        "AUTH_COOKIE_SAME_SITE": "None", "MARKET_PREPARATION_ENABLED": "false",
        "SERVER_SERVLET_SESSION_COOKIE_SECURE": "true", "SERVER_SERVLET_SESSION_COOKIE_SAME_SITE": "None",
        "SPRING_PROFILES_ACTIVE": "production",
    }
    path = HOME / "runtime.env"
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        for key, value in values.items():
            if "\n" in value or "\r" in value:
                raise RuntimeError("Invalid environment value")
            output.write(f"{key}={value}\n")
    try:
        yield path
    finally:
        path.unlink(missing_ok=True)


def exists(name):
    return subprocess.run(["docker", "container", "inspect", name], capture_output=True).returncode == 0


def healthy(name, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        state = json.loads(run(["docker", "inspect", "--format", "{{json .State}}", name]))
        if not state.get("Running"):
            return False
        if state.get("Health", {}).get("Status") == "healthy":
            return True
        time.sleep(3)
    return False


def start(name, image, port, env_file, restart="no"):
    run(["docker", "run", "--detach", "--name", name, "--restart", restart,
         "--env-file", str(env_file), "--publish", port, "--read-only",
         "--tmpfs", "/tmp:rw,noexec,nosuid,size=128m", "--cap-drop", "ALL",
         "--security-opt", "no-new-privileges", "--memory", "1536m", "--cpus", "2",
         "--log-opt", "max-size=10m", "--log-opt", "max-file=3", image])


def deploy(config, digest, revision):
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", digest) or not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise RuntimeError("Invalid immutable release identity")
    image = config["image_repository"] + "@" + digest
    registry = config["image_repository"].split("/")[0]
    password = run(["aws", "ecr", "get-login-password", "--region", config["region"]])
    run(["docker", "login", "--username", "AWS", "--password-stdin", registry], input=password)
    run(["docker", "pull", image])
    try:
        with runtime_env(config) as env_file:
            if exists("boxbox-candidate"):
                run(["docker", "rm", "--force", "boxbox-candidate"])
            start("boxbox-candidate", image, "127.0.0.1:18080:8080", env_file)
            try:
                if not healthy("boxbox-candidate"):
                    raise RuntimeError("Candidate failed readiness; current server preserved")
            finally:
                run(["docker", "rm", "--force", "boxbox-candidate"])
            if exists("boxbox-previous"):
                run(["docker", "rm", "--force", "boxbox-previous"])
            previous = exists("boxbox")
            if previous:
                run(["docker", "stop", "--time", "30", "boxbox"])
                run(["docker", "rename", "boxbox", "boxbox-previous"])
            try:
                start("boxbox", image, "8080:8080", env_file, "unless-stopped")
                if not healthy("boxbox"):
                    raise RuntimeError("New server failed readiness")
            except Exception:
                if exists("boxbox"):
                    run(["docker", "rm", "--force", "boxbox"])
                if previous:
                    run(["docker", "rename", "boxbox-previous", "boxbox"])
                    run(["docker", "start", "boxbox"])
                    if not healthy("boxbox"):
                        raise RuntimeError("Deployment failed and previous server could not recover") from None
                    print("Previous server restored; database unchanged")
                raise
        # Stop/rename state is reflected in the Docker restart policy, including across host restarts.
        (HOME / "current-release.json").write_text(json.dumps({"revision": revision, "digest": digest}))
        print("Healthy release: " + revision)
    finally:
        run(["docker", "logout", registry])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--initialize-db", action="store_true")
    parser.add_argument("--digest")
    parser.add_argument("--revision")
    args = parser.parse_args()
    HOME.mkdir(parents=True, exist_ok=True)
    with (HOME / "deploy.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        config = json.loads(CONFIG.read_text())
        if args.initialize_db:
            initialize(config, Path(__file__).resolve().parent)
        else:
            deploy(config, args.digest or "", args.revision or "")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Child output can contain connection details; never expose it in SSM/GitHub logs.
        reason = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print("Release operation failed: " + reason)
        raise SystemExit(1)
