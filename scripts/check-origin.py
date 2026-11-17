#!/usr/bin/env python3
"""Local cloud-origin nginx template check; no AWS deployment."""
import subprocess
import time
import urllib.error
import uuid

name = 'origin-check-' + uuid.uuid4().hex[:8];
secret = 'localVerificationSecretForTestOnly01234567'
try:
    subprocess.run(
        ['docker', 'run', '-d', '--name', name, '--network', 'streaming_default', '-p', '127.0.0.1::80', '-e',
         'ORIGIN_VERIFY_SECRET=' + secret, '-e', 'API_UPSTREAM=api:8080', 'streaming-cloud-web:verify'], check=True,
        stdout=subprocess.DEVNULL)
    address = 'http://' + subprocess.check_output(['docker', 'port', name, '80'], text=True).strip()
    for _ in range(40):
        try:
            urllib.request.urlopen(address, timeout=1);
            raise AssertionError('Unverified origin request allowed')
        except urllib.error.HTTPError as e:
            assert e.code == 403;
            break
        except urllib.error.URLError:
            time.sleep(.1)
    else:
        raise TimeoutError('Origin failed to start')
    with urllib.request.urlopen(urllib.request.Request(address, headers={'X-Streaming-Origin': secret})) as r:
        assert r.status == 200
    print('PASS: direct origin rejected; matching CloudFront origin header accepted')
finally:
    subprocess.run(['docker', 'rm', '-f', name], check=False, stdout=subprocess.DEVNULL)
