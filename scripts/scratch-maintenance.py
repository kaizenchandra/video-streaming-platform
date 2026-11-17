#!/usr/bin/env python3
"""Collect this Compose volume; legacy adoption additionally requires every volume user stopped."""
import argparse
import json
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser();
p.add_argument('--project', default='streaming');
p.add_argument('-f', '--file', action='append', type=Path);
p.add_argument('--adopt-legacy', action='store_true');
args = p.parse_args()
if not re.fullmatch('[a-z0-9][a-z0-9_-]*', args.project): raise ValueError('Invalid project name')
command = ['docker', 'compose', '-p', args.project]
for file in args.file or [root / 'compose.yml']: command += ['-f', str(file.resolve())]


def run(*parts): return subprocess.check_output(command + list(parts), cwd=root, text=True)


if args.adopt_legacy:
    config = json.loads(run('config', '--format', 'json'));
    mounts = config['services']['worker'].get('volumes', [])
    sources = [m['source'] for m in mounts if m.get('target') == '/tmp/streaming' and m.get('type') == 'volume']
    if len(sources) != 1: raise RuntimeError(
        'Cannot establish named scratch volume ownership; drain and inspect custom mounts manually')
    volume = config['volumes'][sources[0]]['name']
    ids = subprocess.check_output(['docker', 'ps', '-q'], text=True).split()
    if ids:
        output = subprocess.check_output(['docker', 'inspect', '--format', '{{json .Mounts}}', *ids], text=True)
        if any(m.get('Name') == volume for line in output.splitlines() for m in json.loads(line)):
            raise RuntimeError(
                'Legacy adoption refused: a running container still mounts this scratch volume. Drain all workers first.')
parts = ['run', '--rm', '--no-deps', '--entrypoint', 'java', 'worker',
         '-Dloader.main=com.example.streaming.processing.ScratchMaintenance', '-cp', '/app/app.jar',
         'org.springframework.boot.loader.launch.PropertiesLauncher']
if args.adopt_legacy: parts += ['--adopt-legacy', '--workers-drained']
print(run(*parts), end='')
