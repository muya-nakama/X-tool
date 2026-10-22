"""Fetch the isolated Android browser transport from a pinned upstream revision."""
from pathlib import Path
import hashlib
import os
import shutil
import time
import urllib.request

REVISION = '35f2609d80ce26b6683a0b2b6bc52f32efe4c81c'
BASE = f'https://raw.githubusercontent.com/hussain-najeb/youtubedl-android/{REVISION}/library/src/main/jniLibs/'
DIGESTS = {
    'arm64-v8a': {
        'libpython.so': '4f8d78ede7fc26e96e99342dd83ce42d32c9b4d0806bc9a99ff7151396269b25',
        'libpython.zip.so': 'dfd2013985b16f63c69dc5a408488ca3ad96b01141e717d57d89e4ade15da296',
    },
    'x86_64': {
        'libpython.so': '1b6d03301c6ef10ad266dc7ddf7b5ef1e7060f1a18cf1a924c7c922625b8c779',
        'libpython.zip.so': '31ee24c1c503f15183e72f09bbc86245b7d956198b1aa2c048c6d07219f09f1f',
    },
}
ROOT = Path(__file__).resolve().parents[1]

def prepare():
    abi = os.environ.get('APP_RUNTIME_ABI', 'arm64-v8a')
    if abi not in DIGESTS:
        raise ValueError('Unsupported runtime ABI')
    destination = ROOT / 'app/build/generated/spankbang/jniLibs' / abi
    destination.mkdir(parents=True, exist_ok=True)
    for name, digest in DIGESTS[abi].items():
        target = destination / {'libpython.so': 'libsbpython.so', 'libpython.zip.so': 'libsbpythonpayload.so'}[name]
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == digest:
            continue
        temp = target.with_suffix('.part')
        for attempt in range(3):
            try:
                with urllib.request.urlopen(BASE + abi + '/' + name, timeout=60) as source, temp.open('wb') as out:
                    shutil.copyfileobj(source, out)
                if hashlib.sha256(temp.read_bytes()).hexdigest() != digest:
                    raise ValueError('Runtime checksum mismatch; refusing to package')
                temp.replace(target)
                break
            except (OSError, TimeoutError):
                temp.unlink(missing_ok=True)
                if attempt == 2:
                    raise
                time.sleep(attempt + 1)
        print(f'Verified isolated runtime: {abi}/{target.name}')

    engine = ROOT / 'app/build/generated/spankbang/assets/spankbang-yt-dlp'
    engine.parent.mkdir(parents=True, exist_ok=True)
    digest = 'b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14'
    if not engine.exists() or hashlib.sha256(engine.read_bytes()).hexdigest() != digest:
        temp = engine.with_suffix('.part')
        for attempt in range(3):
            try:
                with urllib.request.urlopen('https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.21/yt-dlp', timeout=60) as source, temp.open('wb') as out:
                    shutil.copyfileobj(source, out)
                if hashlib.sha256(temp.read_bytes()).hexdigest() != digest:
                    raise ValueError('SpankBang engine checksum mismatch')
                temp.replace(engine)
                break
            except (OSError, TimeoutError):
                temp.unlink(missing_ok=True)
                if attempt == 2:
                    raise
                time.sleep(attempt + 1)
    print('Verified isolated yt-dlp: 2026.10.21')

if __name__ == '__main__':
    prepare()
