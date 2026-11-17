"""Publish a tested release and wait for EC2 health verification through SSM."""
import json
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request


def run(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def aws(*args):
    return json.loads(run(["aws", *args, "--output", "json", "--no-cli-pager"]))


def outputs(stack):
    result = aws("cloudformation", "describe-stacks", "--stack-name", stack)
    item = result["Stacks"][0]
    if item["StackStatus"] not in {"CREATE_COMPLETE", "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE"}:
        raise RuntimeError("Infrastructure is not ready")
    return {p["OutputKey"]: p["OutputValue"] for p in item["Outputs"]}


def send(config, revision, digest=None):
    parameters = {"Revision": [revision]}
    document = config["InitializeDocument"] if digest is None else config["DeployDocument"]
    if digest:
        parameters["ImageDigest"] = [digest]
    result = aws("ssm", "send-command", "--document-name", document,
                 "--instance-ids", config["InstanceId"], "--parameters", json.dumps(parameters),
                 "--timeout-seconds", "600", "--comment", "BOXBOX " + revision)
    command = result["Command"]["CommandId"]
    print("SSM command: " + command, flush=True)
    deadline = time.monotonic() + 1500
    while time.monotonic() < deadline:
        try:
            status = aws("ssm", "get-command-invocation", "--command-id", command,
                         "--instance-id", config["InstanceId"])["Status"]
        except subprocess.CalledProcessError as error:
            if "InvocationDoesNotExist" not in error.stderr:
                raise
            status = "Pending"
        if status == "Success":
            return
        if status not in {"Pending", "InProgress", "Delayed"}:
            raise RuntimeError("EC2 release failed: " + status + "; inspect SSM in the AWS console")
        time.sleep(5)
    raise RuntimeError("SSM wait timed out; inspect the command before retrying")


def main():
    revision = os.environ["RELEASE_REVISION"]
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise RuntimeError("Expected full commit SHA")
    config = outputs(os.environ["AWS_STACK_NAME"])
    repository = config["ImageRepository"]
    registry, name = repository.split("/", 1)
    result = aws("ecr", "list-images", "--repository-name", name,
                 "--filter", "tagStatus=TAGGED")
    known = next((p for p in result["imageIds"] if p.get("imageTag") == revision), None)
    if known:
        # Immutable tags make workflow retries reuse exactly the already-published image.
        digest = known["imageDigest"]
    else:
        token = run(["aws", "ecr", "get-login-password"])
        run(["docker", "login", "--username", "AWS", "--password-stdin", registry], input=token)
        try:
            subprocess.run(["docker", "build", "--platform", "linux/amd64", "--tag", f"{repository}:{revision}", "."], check=True)
            subprocess.run(["docker", "push", f"{repository}:{revision}"], check=True)
        finally:
            run(["docker", "logout", registry])
        digest = aws("ecr", "describe-images", "--repository-name", name,
                     "--image-ids", f"imageTag={revision}")["imageDetails"][0]["imageDigest"]
    # Every retry of a revision must use the same artifact. Never replace an existing S3 key.
    key = f"releases/{revision}/release.tar.gz"
    checksum = hashlib.sha256(Path("build/release.tar.gz").read_bytes()).hexdigest()
    try:
        run(["aws", "s3api", "put-object", "--bucket", config["ReleaseBucket"],
             "--key", key, "--body", "build/release.tar.gz", "--metadata", "sha256=" + checksum,
             "--if-none-match", "*"])
    except subprocess.CalledProcessError as error:
        if "PreconditionFailed" not in error.stderr:
            raise
        existing = aws("s3api", "head-object", "--bucket", config["ReleaseBucket"], "--key", key)
        if existing.get("Metadata", {}).get("sha256") != checksum:
            raise RuntimeError("An artifact already exists for this commit with different contents") from None
    if "--initialize-db" in sys.argv:
        send(config, revision)
    send(config, revision, digest)
    request = urllib.request.Request(config["ApiUrl"] + "/api/boards", headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        body = json.load(response)
        if response.status != 200 or not isinstance(body.get("data", {}).get("items"), list):
            raise RuntimeError("Public HTTPS readiness check failed")
    print("Verified HTTPS API: " + config["ApiUrl"], flush=True)


if __name__ == "__main__":
    main()
