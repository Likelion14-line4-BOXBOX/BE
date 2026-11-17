"""Root-only runner for a short-lived, single-host BOXBOX deployment."""
import argparse
import base64
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time

HOME = Path('/opt/boxbox')
SETTINGS = Path('/etc/boxbox')
CERTBOT = 'certbot/certbot:v5.10.0'
NGINX = 'nginx:1.28-alpine'


def run(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, encoding='utf-8', **kwargs).stdout.strip()


def exists(name):
    return subprocess.run(['docker', 'container', 'inspect', name], capture_output=True).returncode == 0


def private_file(path, content):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as output:
        output.write(content)


def credentials():
    path = SETTINGS / 'secrets.json'
    if not path.exists():
        values = {'root_password': secrets.token_hex(24), 'db_password': secrets.token_hex(24),
                  'jwt': base64.b64encode(secrets.token_bytes(48)).decode()}
        private_file(path, json.dumps(values))
    return json.loads(path.read_text())


def mysql(sql):
    return run(['docker', 'exec', '-i', 'boxbox-mysql', 'sh', '-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot -N -B boxbox'], input=sql)


def ensure_schema(root):
    schema = (root / 'db/schema.sql').read_text(encoding='utf-8')
    expected = set(re.findall(r'CREATE TABLE `([a-z_]+)`', schema))
    actual = set(mysql('SHOW TABLES;').splitlines())
    if not expected or (actual and actual != expected):
        raise RuntimeError('Partial or unexpected schema; manual review required')
    if not actual:
        mysql(schema)
        mysql((root / 'db/board-seed.sql').read_text(encoding='utf-8'))
    mysql("REVOKE ALL PRIVILEGES ON boxbox.* FROM 'boxbox'@'%'; "
          "GRANT SELECT, INSERT, UPDATE, DELETE ON boxbox.* TO 'boxbox'@'%';")


def initialize(root):
    values = credentials()
    if subprocess.run(['docker', 'network', 'inspect', 'boxbox'], capture_output=True).returncode:
        run(['docker', 'network', 'create', 'boxbox'])
    if not exists('boxbox-mysql'):
        path = SETTINGS / 'mysql.env'
        private_file(path, f"MYSQL_DATABASE=boxbox\nMYSQL_USER=boxbox\nMYSQL_PASSWORD={values['db_password']}\nMYSQL_ROOT_PASSWORD={values['root_password']}\n")
        run(['docker', 'run', '-d', '--name', 'boxbox-mysql', '--restart', 'unless-stopped',
             '--network', 'boxbox', '--env-file', str(path), '--memory', '512m',
             '--mount', 'type=volume,source=boxbox-mysql-data,target=/var/lib/mysql',
             '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3', 'mysql:8.4',
             '--innodb-buffer-pool-size=128M', '--max-connections=60', '--performance-schema=OFF'])
    for attempt in range(90):
        try:
            mysql('SELECT 1;')
            break
        except subprocess.CalledProcessError:
            if attempt == 89:
                raise RuntimeError('Local MySQL did not become ready') from None
            time.sleep(2)
    ensure_schema(root)
    print('Local database ready; no test accounts or race data imported')


def app_env(public_ip=None):
    values = credentials()
    config = json.loads((SETTINGS / 'config.json').read_text())
    origins = config['allowed_origins']
    if not re.fullmatch(r'https?://[A-Za-z0-9.:-]+(,https?://[A-Za-z0-9.:-]+)*', origins):
        raise RuntimeError('Invalid allowed origins')
    extended_ids = config.get('extended_access_user_ids', [])
    if not isinstance(extended_ids, list) or any(type(value) is not int or not 0 < value < 2**63 for value in extended_ids):
        raise RuntimeError('Invalid extended access user IDs')
    extended_ids = sorted(set(extended_ids))
    if extended_ids:
        expected = set(map(str, extended_ids))
        actual = set(mysql('SELECT id FROM users WHERE id IN (' + ','.join(expected)
                           + ") AND status='ACTIVE' AND deleted_at IS NULL;").splitlines())
        if actual != expected:
            raise RuntimeError('Extended access requires existing active users')
    extended_lifetime = config.get('extended_access_lifetime', 'P10D')
    if not isinstance(extended_lifetime, str) or not re.fullmatch(r'P([1-9]|1[0-4])D', extended_lifetime):
        raise RuntimeError('Invalid extended access lifetime')
    path = SETTINGS / 'app.env'
    private_file(path, '\n'.join([
        # Only this same-host Docker network uses non-TLS MySQL; port 3306 is never published.
        'DB_URL=jdbc:mysql://boxbox-mysql:3306/boxbox?connectionTimeZone=UTC&sslMode=DISABLED&allowPublicKeyRetrieval=true',
        'DB_USERNAME=boxbox', 'DB_PASSWORD=' + values['db_password'], 'AUTH_JWT_SECRET=' + values['jwt'],
        'AUTH_ALLOWED_ORIGINS=' + origins, 'AUTH_COOKIE_SAME_SITE=None',
        'AUTH_EXTENDED_ACCESS_USER_IDS=' + ','.join(map(str, extended_ids)),
        'AUTH_EXTENDED_ACCESS_LIFETIME=' + extended_lifetime,
        'SERVER_SERVLET_SESSION_COOKIE_SECURE=true', 'SERVER_SERVLET_SESSION_COOKIE_SAME_SITE=None',
        'MARKET_PREPARATION_ENABLED=true',
        'POST_MEDIA_DIRECTORY=/var/lib/boxbox/media/uploads',
        'POST_MEDIA_PUBLIC_BASE_URL=' + ('https://' + str(ipaddress.IPv4Address(public_ip)) if public_ip else ''),
    ]) + '\n')
    return path


def healthy(name, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        state = json.loads(run(['docker', 'inspect', '--format', '{{json .State}}', name]))
        if not state.get('Running'):
            return False
        if state.get('Health', {}).get('Status') == 'healthy':
            return True
        time.sleep(3)
    return False


def start(image, env_file):
    run(['docker', 'run', '-d', '--name', 'boxbox', '--restart', 'unless-stopped',
         '--network', 'boxbox', '--env-file', str(env_file), '-p', '127.0.0.1:8080:8080',
         '--mount', 'type=volume,source=boxbox-post-media,target=/var/lib/boxbox/media',
         '--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,size=128m', '--cap-drop', 'ALL',
         '--security-opt', 'no-new-privileges', '--memory', '1024m', '--cpus', '2',
         '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3', image])


def deploy(repository, digest, revision, public_ip=None):
    if not re.fullmatch(r'\d{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/[a-z0-9/_-]+', repository):
        raise RuntimeError('Invalid ECR repository')
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', digest) or not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise RuntimeError('Invalid immutable release identity')
    if not exists('boxbox-mysql'):
        raise RuntimeError('Operator must initialize the database first')
    image = repository + '@' + digest
    run(['docker', 'pull', image])
    environment = app_env(public_ip)
    if exists('boxbox-previous'):
        run(['docker', 'rm', '--force', 'boxbox-previous'])
    previous = exists('boxbox')
    if previous:
        run(['docker', 'stop', '--time', '30', 'boxbox'])
        run(['docker', 'rename', 'boxbox', 'boxbox-previous'])
    try:
        start(image, environment)
        if not healthy('boxbox'):
            raise RuntimeError('New server failed readiness')
    except Exception:
        if exists('boxbox'):
            run(['docker', 'rm', '--force', 'boxbox'])
        if previous:
            run(['docker', 'rename', 'boxbox-previous', 'boxbox'])
            run(['docker', 'start', 'boxbox'])
            if not healthy('boxbox'):
                raise RuntimeError('Deployment failed and previous server could not recover') from None
            print('Previous server restored; database volume preserved')
        raise
    (HOME / 'current-release.json').write_text(json.dumps({'revision': revision, 'digest': digest}))
    print('Healthy release: ' + revision)


def nginx_config(address, tls):
    ssl = f'''listen 443 ssl;
      ssl_protocols TLSv1.2 TLSv1.3;
      ssl_certificate /etc/letsencrypt/live/{address}/fullchain.pem;
      ssl_certificate_key /etc/letsencrypt/live/{address}/privkey.pem;
      location / {{
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 360s;
        client_max_body_size 20m;
      }}''' if tls else ''
    return f'''events {{ worker_connections 512; }}
http {{ server {{ listen 80; server_name {address};
  location /.well-known/acme-challenge/ {{ root /var/www/acme; }}
  location / {{ return 308 https://{address}$request_uri; }}
}} {('server { server_name ' + address + '; ' + ssl + ' }') if tls else ''} }}
'''


def configure_https(address):
    ipaddress.IPv4Address(address)
    acme = Path('/var/www/boxbox-acme')
    acme.mkdir(parents=True, exist_ok=True)
    Path('/etc/letsencrypt').mkdir(parents=True, exist_ok=True)
    config = SETTINGS / 'nginx.conf'
    certificate = Path('/etc/letsencrypt/live') / address / 'fullchain.pem'
    config.write_text(nginx_config(address, certificate.exists()))
    if not exists('boxbox-proxy'):
        run(['docker', 'run', '-d', '--name', 'boxbox-proxy', '--restart', 'unless-stopped',
             '--network', 'host', '--memory', '96m',
             '-v', str(config) + ':/etc/nginx/nginx.conf:ro',
             '-v', '/etc/letsencrypt:/etc/letsencrypt:ro', '-v', str(acme) + ':/var/www/acme:ro',
             '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3', NGINX])
    if not certificate.exists():
        # The operator must approve the CA subscriber agreement before invoking this operation.
        run(['docker', 'run', '--rm', '--network', 'host', '--memory', '256m',
             '-v', '/etc/letsencrypt:/etc/letsencrypt', '-v', str(acme) + ':/var/www/acme', CERTBOT,
             'certonly', '--non-interactive', '--agree-tos', '--register-unsafely-without-email',
             '--preferred-profile', 'shortlived', '--webroot', '-w', '/var/www/acme', '--ip-address', address])
    config.write_text(nginx_config(address, True))
    run(['docker', 'exec', 'boxbox-proxy', 'nginx', '-t'])
    run(['docker', 'exec', 'boxbox-proxy', 'nginx', '-s', 'reload'])
    renew = SETTINGS / 'renew-certificate.sh'
    renew.write_text(f'''#!/bin/sh
set -eu
docker run --rm --network host --memory 256m -v /etc/letsencrypt:/etc/letsencrypt -v /var/www/boxbox-acme:/var/www/acme {CERTBOT} renew --quiet --no-random-sleep-on-renew
docker exec boxbox-proxy nginx -t
docker exec boxbox-proxy nginx -s reload
''')
    renew.chmod(0o700)
    Path('/etc/systemd/system/boxbox-cert-renew.service').write_text('[Unit]\nDescription=Renew BOXBOX IP certificate\nAfter=docker.service\n[Service]\nType=oneshot\nExecStart=/etc/boxbox/renew-certificate.sh\n')
    Path('/etc/systemd/system/boxbox-cert-renew.timer').write_text('[Unit]\nDescription=Renew BOXBOX certificate twice daily\n[Timer]\nOnCalendar=*-*-* 00,12:00:00\nRandomizedDelaySec=1800\nPersistent=true\n[Install]\nWantedBy=timers.target\n')
    run(['systemctl', 'daemon-reload'])
    run(['systemctl', 'enable', '--now', 'boxbox-cert-renew.timer'])
    print('HTTPS configured with automatic short-lived certificate renewal')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--initialize-db', action='store_true')
    parser.add_argument('--configure-https')
    parser.add_argument('--repository')
    parser.add_argument('--digest')
    parser.add_argument('--revision')
    parser.add_argument('--public-ip')
    args = parser.parse_args()
    HOME.mkdir(parents=True, exist_ok=True)
    SETTINGS.mkdir(parents=True, exist_ok=True)
    with (HOME / 'deploy.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.initialize_db:
            initialize(Path(__file__).resolve().parent)
        elif args.configure_https:
            configure_https(args.configure_https)
        else:
            deploy(args.repository or '', args.digest or '', args.revision or '', args.public_ip)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Release operation failed: ' + (str(error) if isinstance(error, RuntimeError) else type(error).__name__))
        raise SystemExit(1)
