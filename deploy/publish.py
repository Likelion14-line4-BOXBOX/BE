"""Publish main and deploy over short-lived Lightsail SSH credentials from OIDC."""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import urllib.request


def run(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def aws(*args):
    return json.loads(run(['aws', *args, '--output', 'json', '--no-cli-pager']))


def outputs(stack):
    item = aws('cloudformation', 'describe-stacks', '--stack-name', stack)['Stacks'][0]
    if item['StackStatus'] not in {'CREATE_COMPLETE', 'UPDATE_COMPLETE', 'UPDATE_ROLLBACK_COMPLETE'}:
        raise RuntimeError('Infrastructure is not ready')
    return {p['OutputKey']: p['OutputValue'] for p in item['Outputs']}


def ssh_options(details, config, directory):
    if details['ipAddress'] != config['PublicIp'] or not re.fullmatch(r'[a-z_][a-z0-9_-]*', details['username']):
        raise RuntimeError('Unexpected Lightsail access identity')
    key = Path(directory) / 'key'
    cert = Path(directory) / 'key-cert.pub'
    hosts = Path(directory) / 'known_hosts'
    key.write_text(details['privateKey'])
    key.chmod(0o600)
    cert.write_text(details['certKey'])
    entries = []
    for host in details.get('hostKeys', []):
        public = host.get('publicKey', '').strip()
        if not public:
            continue
        if not public.startswith(('ssh-', 'ecdsa-')):
            public = host['algorithm'] + ' ' + public
        entries.append(config['PublicIp'] + ' ' + public)
    if not entries:
        raise RuntimeError('AWS did not provide SSH host keys; refusing unverified connection')
    hosts.write_text('\n'.join(entries) + '\n')
    return ['-i', str(key), '-o', 'CertificateFile=' + str(cert), '-o', 'IdentitiesOnly=yes',
            '-o', 'UserKnownHostsFile=' + str(hosts), '-o', 'StrictHostKeyChecking=yes',
            '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15']


def remote_release(config, revision, digest, initialize=False):
    details = aws('lightsail', 'get-instance-access-details', '--instance-name', config['InstanceName'], '--protocol', 'ssh')['accessDetails']
    with tempfile.TemporaryDirectory() as directory:
        options = ssh_options(details, config, directory)
        destination = details['username'] + '@' + details['ipAddress']

        def ssh(args, **kwargs):
            return run(['ssh', *options, destination, shlex.join(args)], **kwargs)

        # UserData must finish before operator initialization or deployment can proceed.
        ssh(['sudo', 'test', '-f', '/etc/boxbox/config.json'])
        remote_archive = '/tmp/boxbox-' + revision + '.tar.gz'
        run(['scp', *options, 'build/release.tar.gz', destination + ':' + remote_archive])
        local_hash = hashlib.sha256(Path('build/release.tar.gz').read_bytes()).hexdigest()
        if ssh(['sha256sum', remote_archive]).split()[0] != local_hash:
            raise RuntimeError('Release transfer checksum mismatch')
        root = '/opt/boxbox/releases/' + revision
        ssh(['sudo', 'install', '-d', '-m', '700', root])
        ssh(['sudo', 'tar', '-xzf', remote_archive, '-C', root, '--no-same-owner'])
        runner = ['sudo', 'python3', root + '/deploy/runtime.py']
        if initialize:
            if os.environ.get('GITHUB_ACTIONS') == 'true':
                raise RuntimeError('Database and certificate initialization is operator-only')
            print(ssh([*runner, '--initialize-db']))
            print(ssh([*runner, '--configure-https', config['PublicIp']]))
        registry = config['ImageRepository'].split('/')[0]
        token = run(['aws', 'ecr', 'get-login-password'])
        ssh(['sudo', 'docker', 'login', '--username', 'AWS', '--password-stdin', registry], input=token)
        try:
            print(ssh([*runner, '--repository', config['ImageRepository'], '--revision', revision, '--digest', digest,
                        '--public-ip', config['PublicIp']]))
        finally:
            ssh(['sudo', 'docker', 'logout', registry])
        ssh(['rm', '--', remote_archive])


def main():
    revision = os.environ['RELEASE_REVISION']
    if not re.fullmatch(r'[a-f0-9]{40}', revision):
        raise RuntimeError('Expected full commit SHA')
    config = outputs(os.environ['AWS_STACK_NAME'])
    repository = config['ImageRepository']
    registry, name = repository.split('/', 1)
    images = aws('ecr', 'list-images', '--repository-name', name, '--filter', 'tagStatus=TAGGED')['imageIds']
    known = next((item for item in images if item.get('imageTag') == revision), None)
    if known:
        digest = known['imageDigest']
    else:
        token = run(['aws', 'ecr', 'get-login-password'])
        run(['docker', 'login', '--username', 'AWS', '--password-stdin', registry], input=token)
        try:
            subprocess.run(['docker', 'build', '--platform', 'linux/amd64', '-t', repository + ':' + revision, '.'], check=True)
            subprocess.run(['docker', 'push', repository + ':' + revision], check=True)
        finally:
            run(['docker', 'logout', registry])
        digest = aws('ecr', 'describe-images', '--repository-name', name, '--image-ids', 'imageTag=' + revision)['imageDetails'][0]['imageDigest']
    remote_release(config, revision, digest, '--initialize-db' in sys.argv)
    with urllib.request.urlopen(config['ApiUrl'] + '/api/boards', timeout=30) as response:
        body = json.load(response)
        if response.status != 200 or not isinstance(body.get('data', {}).get('items'), list):
            raise RuntimeError('Public HTTPS readiness check failed')
    print('Verified HTTPS API: ' + config['ApiUrl'])


if __name__ == '__main__':
    main()
