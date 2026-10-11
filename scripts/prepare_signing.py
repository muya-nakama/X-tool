"""Restore the fixed publishing key; never fall back to a debug key for a release."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
path = Path(os.environ.get('RUNNER_TEMP', tempfile.gettempdir())) / 'movie-audio-signing.jks'
publishing = os.environ.get('APP_PUBLISH') == 'true'
if publishing:
    bundle = os.environ.get('APP_SIGNING_BUNDLE', '')
    if not bundle:
        raise SystemExit('Publishing stopped: fixed signing key is not configured')
    data = json.loads(bundle)
    password = data['password']
    if not isinstance(password, str) or '\n' in password or len(password) < 20:
        raise SystemExit('Invalid signing password')
    path.write_bytes(base64.b64decode(data['keystore'], validate=True))
    path.chmod(0o600)
else:
    # Test-only APK. This private key is temporary and is never uploaded or published.
    password = 'local-test-key-not-for-publishing'
    path.unlink(missing_ok=True)
    env = dict(os.environ, APP_SIGNING_PASSWORD=password)
    subprocess.run(['keytool', '-genkeypair', '-noprompt', '-keystore', str(path), '-storetype', 'JKS',
                    '-storepass:env', 'APP_SIGNING_PASSWORD', '-keypass:env', 'APP_SIGNING_PASSWORD',
                    '-alias', 'app', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                    '-dname', 'CN=Test APK'], env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
cert = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(path), '-alias', 'app',
                                '-storepass:env', 'APP_SIGNING_PASSWORD'], env=dict(os.environ, APP_SIGNING_PASSWORD=password), stderr=subprocess.DEVNULL)
fingerprint = hashlib.sha256(cert).hexdigest()
if publishing:
    pin = root / 'signing-certificate.sha256'
    if not pin.exists() or pin.read_text().strip() != fingerprint:
        path.unlink(missing_ok=True)
        raise SystemExit('Publishing stopped: signing certificate changed or its pin is missing')
    print('::add-mask::' + password)
with open(os.environ['GITHUB_ENV'], 'a') as env_file:
    env_file.write('APP_SIGNING_STORE=' + str(path) + '\n')
    env_file.write('APP_SIGNING_PASSWORD=' + password + '\n')
print('Fixed publishing signature verified' if publishing else 'Temporary test signature prepared; publishing disabled')
