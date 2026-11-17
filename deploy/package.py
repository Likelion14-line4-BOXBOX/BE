"""Create the same runtime and release inputs locally and in GitHub Actions."""
from pathlib import Path
import shutil
import tarfile
import gzip
import io

ROOT = Path(__file__).resolve().parents[1]


def package():
    jars = [p for p in (ROOT / "build/libs").glob("*.jar") if not p.name.endswith("-plain.jar")]
    if len(jars) != 1:
        raise SystemExit("Expected exactly one executable JAR; run clean test bootJar first")
    target = ROOT / "build/docker"
    target.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(jars[0], target / "app.jar")
    with (ROOT / "build/release.tar.gz").open("wb") as raw:
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as archive:
                for path in sorted((ROOT / "deploy").rglob("*")):
                    if path.is_file() and "__pycache__" not in path.parts:
                        info = archive.gettarinfo(str(path), arcname=path.relative_to(ROOT).as_posix())
                        info.mtime = info.uid = info.gid = 0
                        info.uname = info.gname = ""
                        info.mode = 0o644
                        content = path.read_bytes().replace(b"\r\n", b"\n")
                        info.size = len(content)
                        archive.addfile(info, io.BytesIO(content))


if __name__ == "__main__":
    package()
