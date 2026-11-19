import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

manifest = json.loads(Path('manifest.json').read_text())
assert manifest['tag'] == 'chitalka-v1.5.3'
assert manifest['sourceCommit'] == os.environ['SOURCE_COMMIT']
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '35.0.0'
out = Path('out')
out.mkdir(exist_ok=True)
checksums = []
assert {entry['abi'] for entry in manifest['files']} == {'arm64-v8a', 'armeabi-v7a'}
for entry in manifest['files']:
    expected_name = f"chitalka-vsluh-1.5.3-{entry['abi']}.apk"
    assert entry['name'] == expected_name
    apk = out / expected_name
    digest = hashlib.sha256()
    with apk.open('wb') as target:
        for index, name in enumerate(entry['parts']):
            assert name == f'{expected_name}.part{index:03d}'
            data = Path(name).read_bytes()
            target.write(data)
            digest.update(data)
    assert apk.stat().st_size == entry['size'], 'APK size mismatch'
    assert digest.hexdigest() == entry['sha256'], 'APK checksum mismatch'
    certs = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    fingerprints = re.findall(r'Signer #\d+ certificate SHA-256 digest: (\w+)', certs)
    assert fingerprints == [manifest['signerSha256']], 'APK signing certificate mismatch'
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    package = next(line for line in badging.splitlines() if line.startswith('package:'))
    assert "name='com.voicereader'" in package
    assert "versionName='1.5.3'" in package and "versionCode='9'" in package
    assert f"native-code: '{entry['abi']}'" in badging
    checksums.append(f"{entry['sha256']}  {expected_name}\n")
    print(f'Verified {expected_name}')
(out / 'SHA256SUMS.txt').write_text(''.join(checksums))
