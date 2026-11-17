#!/usr/bin/env python3
"""Exercise the pinned, private live scanner with benign fixtures; save a machine-readable report."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--build', action='store_true', help='Build scanner and verify worker; package the Java JAR first')
p.add_argument('--keep', action='store_true', help='Keep this private daemon for LOCAL_LIVE_SCANNER chaos tests')
args = p.parse_args()
(root / 'target/scanner-qualification.json').unlink(missing_ok=True)
compose = ['docker', 'compose', '-f', str(root / 'docker/scanner-qualification.compose.yml')]


def run(*parts):
    return subprocess.check_output(compose + list(parts), cwd=root, text=True)


if args.build:
    subprocess.run(['docker', 'build', '--platform', 'linux/amd64', '-f', 'docker/clamav/Dockerfile', '-t',
                    'streaming-clamav:qualified', '.'], cwd=root, check=True)
    subprocess.run(['docker', 'build', '--platform', 'linux/amd64', '-f', 'docker/clamav/coverage.Dockerfile', '-t',
                    'streaming-clamav:coverage-qualified', '.'], cwd=root, check=True)
    subprocess.run(['docker', 'build', '-f', 'docker/worker.Dockerfile', '-t', 'streaming-worker:verify', '.'],
                   cwd=root, check=True)
try:
    run('up', '-d', '--pull', 'never', 'clamav')
    end = time.monotonic() + 300
    while time.monotonic() < end:
        container = run('ps', '-q', 'clamav').strip()
        if container and subprocess.check_output(
                ['docker', 'inspect', container, '--format', '{{.State.Health.Status}}'],
                text=True).strip() == 'healthy':
            break
        time.sleep(1)
    else:
        raise TimeoutError('Scanner not healthy')
    # Freshclam may finish while clamd initially loads older bundled definitions. Explicit reload and
    # a VERSION check prove that the daemon is using the downloaded database, rather than just files on disk.
    metadata = run('exec', '-T', 'clamav', 'sh', '-c',
                   'sigtool --info /var/lib/clamav/daily.cld 2>/dev/null || sigtool --info /var/lib/clamav/daily.cvd')
    version = re.search(r'^Version:\s*(\d+)', metadata, re.M)
    if not version: raise RuntimeError('Cannot determine signature version on disk')
    os.environ['CLAMAV_EXPECT_DATABASE'] = version.group(1)
    run('exec', '-T', 'clamav', 'clamdscan', '--reload')
    output = run('run', '--rm', '--no-deps', 'qualify')
    report = json.loads(next(line for line in reversed(output.splitlines()) if line.startswith('{')))
    if report.get('result') != 'PASS': raise RuntimeError('Qualification did not pass')
    report['downloadedDatabase'] = int(version.group(1))
    report['coverageCanaryRuleSha256'] = hashlib.sha256(
        (root / 'docker/clamav/coverage-canary.ndb').read_bytes()).hexdigest()
    report[
        'coverageCanaryScope'] = 'Qualification-only any-offset body signature; original EICAR fixture tests still use native signatures'
    report[
        'platform'] = 'linux/amd64 (emulated on this ARM64 local host)' if os.uname().machine == 'arm64' else 'linux/amd64'
    report[
        'fixtureImage'] = 'clamav/clamav:1.4.6@sha256:cbc30de94b6e9b33ac14136ff45306cee02a1f9ae522394a3cc5b9215d1affe9'
    report['upstreamImage'] = report['fixtureImage']
    report['fixtureImage'] = 'streaming-clamav:coverage-qualified'
    (root / 'target').mkdir(exist_ok=True)
    (root / 'target/scanner-qualification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
finally:
    if not args.keep: run('down', '--remove-orphans')
