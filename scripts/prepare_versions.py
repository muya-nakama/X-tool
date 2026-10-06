"""Prepare the old slot from the last published version, before building the new slot."""
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)

def number(version):
    if not re.fullmatch(r"[0-9]{1,3}(\.[0-9]{1,3}){1,2}", version):
        raise ValueError("Invalid release version")
    return tuple(int(x) for x in version.split("."))

def prepare():
    current = json.loads((ROOT / "new/version.json").read_text())
    published = json.loads((ROOT / "app-update.json").read_text())["version"]
    if number(current["version"]) < number(published):
        raise ValueError("The new slot cannot be older than the published release")
    old = ROOT / "old"
    if current["version"] != published:
        # Only a successfully published tag can become the previous version.
        subprocess.run(["git", "fetch", "--depth=1", "origin", f"refs/tags/v{published}"], cwd=ROOT, check=True)
        names = git("ls-tree", "-r", "--name-only", "FETCH_HEAD", "new").decode().splitlines()
        if names:
            files = {Path(name).name: git("show", f"FETCH_HEAD:{name}") for name in names
                     if name.endswith(".kt") or name == "new/version.json"}
        else:
            # Initial v1.00 predates the two-slot layout.
            prefix = "app/src/main/java/jp/muya/xsaver/"
            names = git("ls-tree", "-r", "--name-only", "FETCH_HEAD", prefix).decode().splitlines()
            files = {Path(name).name: git("show", f"FETCH_HEAD:{name}") for name in names
                     if name.endswith(".kt") and "/legacy/" not in name}
            files["version.json"] = json.dumps({"version": published, "versionCode": 1}).encode()
        if "MainActivity.kt" not in files or "DownloadService.kt" not in files:
            raise ValueError("The published release has no complete source snapshot")
        previous = json.loads(files["version.json"])
        if previous["version"] != published:
            raise ValueError("The published snapshot version does not match its tag")
        shutil.rmtree(old, ignore_errors=True)
        old.mkdir()
        for name, content in files.items():
            (old / name).write_bytes(content)
    previous = json.loads((old / "version.json").read_text())
    if number(previous["version"]) >= number(current["version"]):
        raise ValueError("The previous slot must be older than the new slot")
    if int(current["versionCode"]) <= int(previous["versionCode"]):
        raise ValueError("Android versionCode must increase")
    print(f"new={current['version']} old={previous['version']}")

if __name__ == "__main__":
    prepare()
