"""Reject releases lacking matching version and change notes."""
import json
import re
from pathlib import Path
root = Path(__file__).resolve().parents[1]
current = json.loads((root / 'new/version.json').read_text())
previous = json.loads((root / 'old/version.json').read_text())
v = current['version']
assert tuple(map(int, v.split('.'))) > tuple(map(int, previous['version'].split('.')))
assert current['versionCode'] > previous['versionCode']
assert re.search(r'^## ' + re.escape(v) + r' — ', (root / 'CHANGELOG.md').read_text(), re.M)
assert 'v' + v + ' · ' in (root / 'new/MainActivity.kt').read_text()
assert '仲間六夜' not in (root / 'new/MainActivity.kt').read_text()
print('Version and change notes verified:', v)

# A published version may be rebuilt, but changed code must receive a new version.
import subprocess
published = json.loads((root / 'app-update.json').read_text())['version']
if v == published:
    subprocess.run(['git', 'fetch', '--depth=1', 'origin', f'refs/tags/v{v}'], cwd=root, check=True)
    changed = subprocess.check_output(['git', 'diff', '--name-only', 'FETCH_HEAD', '--', 'new', 'shell', 'app', 'build.gradle.kts', 'settings.gradle.kts', 'CHANGELOG.md'], cwd=root).decode().strip()
    assert not changed, 'Code changed without a new version and change notes: ' + changed
