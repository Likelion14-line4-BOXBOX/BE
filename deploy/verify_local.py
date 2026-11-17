"""Real HTTP/SSE against disposable containers; never touches an existing database."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, encoding="utf-8", **kwargs).stdout.strip()


def verify(image, mysql_image):
    suffix = secrets.token_hex(4)
    network, database, app = ["boxbox46-" + name + "-" + suffix for name in ["net", "db", "api"]]
    created = []
    checks = []
    run("docker", "network", "create", network)
    try:
        with tempfile.TemporaryDirectory() as directory:
            password = secrets.token_hex(24)
            environment = Path(directory) / "mysql.env"
            environment.write_text("MYSQL_ROOT_PASSWORD=" + password + "\nMYSQL_DATABASE=boxbox\nMYSQL_USER=boxbox\nMYSQL_PASSWORD=" + password + "\n")
            os.chmod(environment, 0o600)
            run("docker", "run", "--detach", "--name", database, "--network", network,
                "--env-file", str(environment), "--memory", "512m", mysql_image,
                "--innodb-buffer-pool-size=128M", "--max-connections=60", "--performance-schema=OFF")
            created.append(database)

            def sql(statement):
                return run("docker", "exec", "-i", database, "sh", "-c",
                           'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -N -B boxbox', input=statement)

            for attempt in range(90):
                try:
                    sql("SELECT 1;")
                    break
                except subprocess.CalledProcessError:
                    if attempt == 89:
                        raise RuntimeError("Disposable MySQL startup failed") from None
                    time.sleep(2)
            sql((ROOT / "deploy/db/schema.sql").read_text(encoding="utf-8"))
            sql((ROOT / "deploy/db/board-seed.sql").read_text(encoding="utf-8"))
            sql("REVOKE ALL PRIVILEGES ON boxbox.* FROM 'boxbox'@'%'; GRANT SELECT, INSERT, UPDATE, DELETE ON boxbox.* TO 'boxbox'@'%';")
            environment = Path(directory) / "app.env"
            signing_key = base64.b64encode(secrets.token_bytes(48)).decode()
            environment.write_text("\n".join([
                # This matches the final same-host, unpublished Docker MySQL connection.
                "DB_URL=jdbc:mysql://" + database + ":3306/boxbox?connectionTimeZone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true",
                "DB_USERNAME=boxbox", "DB_PASSWORD=" + password,
                "AUTH_JWT_SECRET=" + signing_key,
                "AUTH_ALLOWED_ORIGINS=http://localhost:5173", "AUTH_COOKIE_SAME_SITE=None",
                "SERVER_SERVLET_SESSION_COOKIE_SECURE=true", "SERVER_SERVLET_SESSION_COOKIE_SAME_SITE=None",
                "MARKET_PREPARATION_ENABLED=false",
            ]) + "\n")
            os.chmod(environment, 0o600)
            run("docker", "run", "--detach", "--name", app, "--network", network,
                "--env-file", str(environment), "--publish", "127.0.0.1::8080", "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=128m", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--memory", "1024m", image)
            created.append(app)
            port = json.loads(run("docker", "inspect", app))[0]["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
            base = "http://127.0.0.1:" + port

            def ready():
                for attempt in range(90):
                    state = json.loads(run("docker", "inspect", "--format", "{{json .State}}", app))
                    if not state["Running"]:
                        raise RuntimeError("Application exited; inspect local container before cleanup")
                    if state.get("Health", {}).get("Status") == "healthy":
                        return
                    time.sleep(2)
                raise RuntimeError("Application readiness timeout")

            def request(name, path, status=200, body=None, method=None, headers=None):
                data = None if body is None else json.dumps(body).encode()
                values = {"Accept": "application/json", **(headers or {})}
                if data is not None:
                    values["Content-Type"] = "application/json"
                req = urllib.request.Request(base + path, data=data, method=method, headers=values)
                try:
                    response = urllib.request.urlopen(req, timeout=15)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    content = response.read()
                    assert response.status == status, name + " status=" + str(response.status)
                    checks.append({"name": name, "status": response.status})
                    return json.loads(content) if content else None, response.headers

            ready()
            boards, _ = request("board list", "/api/boards")
            assert len(boards["data"]["items"]) == 2
            assert [board["name"] for board in boards["data"]["items"]] == ["자유 게시판", "입문자 게시판"]
            tags, _ = request("Korean seed tags", "/api/boards/" + boards["data"]["items"][0]["boardId"] + "/tags")
            assert "굿즈" in json.dumps(tags, ensure_ascii=False)
            team, _ = request("empty team filter", "/api/boards?type=TEAM")
            assert team["data"]["items"] == []
            request("invalid filter", "/api/boards?type=INVALID", 400)
            request("missing board", "/api/boards/999999/tags", 404)
            request("authentication required", "/api/me", 401)
            request("CORS preflight", "/api/boards", 204, method="OPTIONS", headers={
                "Origin": "http://localhost:5173", "Access-Control-Request-Method": "GET",
                "Access-Control-Request-Headers": "Authorization"})
            request("untrusted origin", "/api/boards", 403, headers={"Origin": "https://untrusted.invalid"})
            login = {"loginId": "verify46", "password": secrets.token_hex(12)}
            request("signup", "/api/auth/signup", 201, body={**login, "nickname": "배포검증"})
            token, headers = request("login", "/api/auth/login", body=login)
            cookie = headers["Set-Cookie"]
            assert "Secure" in cookie and "HttpOnly" in cookie and "SameSite=None" in cookie
            bearer = {"Authorization": "Bearer " + token["data"]["accessToken"]}
            request("profile with real token", "/api/me", headers=bearer)
            request("wrong password", "/api/auth/login", 401, body={**login, "password": "incorrect"})
            refreshed, headers = request("refresh", "/api/auth/refresh", method="POST", headers={
                "Origin": "http://localhost:5173", "Cookie": cookie.split(";", 1)[0]})
            bearer = {"Authorization": "Bearer " + refreshed["data"]["accessToken"]}
            cookie = headers["Set-Cookie"].split(";", 1)[0]
            request("missing SSE room JSON error", "/api/chat-rooms/999999/events", 404,
                    headers={**bearer, "Accept": "text/event-stream"})
            # A synthetic LIVE room exists only in this isolated, disposable verification database.
            sql("INSERT INTO races(id,season_year,round_number,name,country_name,circuit_name,circuit_key,weekend_starts_at,weekend_ends_at) "
                "VALUES (1,2026,1,'verification','test','test','verify',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 1 HOUR);"
                "INSERT INTO chat_rooms(id,race_id,status,opens_at,closes_at) VALUES "
                "(1,1,'LIVE',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 1 HOUR);")
            stream = urllib.request.Request(base + "/api/chat-rooms/1/events", headers={**bearer, "Accept": "text/event-stream"})
            with urllib.request.urlopen(stream, timeout=20) as response:
                lines = []
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    line = response.readline()
                    if not line:
                        raise RuntimeError("SSE ended before STREAM_READY")
                    lines.append(line.decode())
                    if any("STREAM_READY" in item for item in lines) and line.strip() == b"":
                        break
                else:
                    raise RuntimeError("SSE did not deliver STREAM_READY within 30 seconds")
                assert response.status == 200 and response.headers["Content-Type"].startswith("text/event-stream")
            checks.append({"name": "authenticated SSE STREAM_READY", "status": 200})
            for attempt in range(20):
                if sql("SELECT COUNT(*) FROM chat_participants WHERE stamp_recorded_at IS NOT NULL;") == "1":
                    break
                time.sleep(0.2)
            assert sql("SELECT COUNT(*) FROM chat_participants WHERE stamp_recorded_at IS NOT NULL;") == "1"
            checks.append({"name": "SSE participation stamp persisted", "status": "DB match"})
            assert sql("SELECT coin_balance FROM users WHERE login_id='verify46';") == "1000000"
            assert sql("SELECT COUNT(*) FROM coin_ledger WHERE entry_type='INITIAL_GRANT';") == "1"
            checks.append({"name": "signup balance and initial grant", "status": "DB match"})
            run("docker", "restart", app)
            ready()
            # Docker may allocate a new ephemeral host port when the container restarts.
            port = json.loads(run("docker", "inspect", app))[0]["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
            base = "http://127.0.0.1:" + port
            request("token and data survive restart", "/api/me", headers=bearer)
            request("logout", "/api/auth/logout", 204, method="POST", headers={
                "Origin": "http://localhost:5173", "Cookie": cookie})
            return {"database": "disposable Docker MySQL", "mysql_image": mysql_image,
                    "transport": "local HTTP; public HTTPS and FE browser are separate checks", "checks": checks}
    except Exception:
        if app in created:
            result = subprocess.run(["docker", "logs", "--tail", "1000", app], capture_output=True, text=True)
            log = (result.stdout + result.stderr).replace(password, "[REDACTED]").replace(signing_key, "[REDACTED]")
            (ROOT / "build/deployment-startup-failure.log").write_text(log, encoding="utf-8")
        raise
    finally:
        for name in reversed(created):
            subprocess.run(["docker", "rm", "--force", "--volumes", name], capture_output=True)
        subprocess.run(["docker", "network", "rm", network], capture_output=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", default="boxbox-verify:46")
    parser.add_argument("--mysql-image", default="mysql:8.4")
    parser.add_argument("--output")
    arguments = parser.parse_args()
    report = verify(arguments.image, arguments.mysql_image)
    if arguments.output:
        Path(arguments.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
