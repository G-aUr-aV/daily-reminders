#!/usr/bin/env python3
"""Generate a private DEVELOPMENT key outside this source tree; never print passwords.
Run: python3 tools/new_development_key.py /absolute/private/directory
The resulting signing.env is secret: source it locally before the explicit Gradle opt-in.
Do not archive, upload, commit or share the directory. Requires JDK17 keytool on PATH.
"""
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit(__doc__)
root = Path(__file__).resolve().parents[1]
target = Path(sys.argv[1]).expanduser().resolve()
if target == root or root in target.parents:
    raise SystemExit('Refusing to place signing material inside the source tree')
# Refuse reuse/overwriting, including an existing directory.
target.mkdir(mode=0o700, parents=True, exist_ok=False)
os.umask(0o077)
password = secrets.token_urlsafe(48)
keyfile = target / 'review-development.p12'
env = dict(os.environ, DAILY_RHYTHM_STORE_PASSWORD=password)
subprocess.run(['keytool', '-genkeypair', '-noprompt', '-keystore', str(keyfile),
                '-storetype', 'PKCS12', '-storepass:env', 'DAILY_RHYTHM_STORE_PASSWORD',
                '-alias', 'daily-rhythm-review-development', '-keyalg', 'RSA',
                '-keysize', '3072', '-sigalg', 'SHA256withRSA', '-validity', '3650',
                '-dname', 'CN=Daily Rhythm Fresh Review Development, O=Personal Development'],
               env=env, check=True)
values = {'DAILY_RHYTHM_KEYSTORE':str(keyfile),
          'DAILY_RHYTHM_STORE_PASSWORD':password,
          'DAILY_RHYTHM_KEY_ALIAS':'daily-rhythm-review-development',
          'DAILY_RHYTHM_KEY_PASSWORD':password}
(target/'signing.env').write_text(''.join('export '+k+'='+shlex.quote(v)+'\n' for k,v in values.items()))
keyfile.chmod(0o600)
print('Private development key and signing.env generated outside source. No production or upgrade assurance.')
