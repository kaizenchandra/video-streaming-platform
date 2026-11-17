#!/usr/bin/env python3
"""Independently decode every generated HLS rendition offline, with no network access."""
import argparse
import json
import re
import subprocess
from pathlib import Path

p = argparse.ArgumentParser();
p.add_argument('directory', type=Path);
p.add_argument('--expected-frames', type=int, required=True);
args = p.parse_args()
root = args.directory.resolve();
variants = [x for x in (root / 'master.m3u8').read_text().splitlines() if x and not x.startswith('#')]
report = {}
for variant in variants:
    if not re.fullmatch(r'[0-9]+p/index\.m3u8', variant): raise ValueError('Unsafe generated variant')
    command = ['docker', 'run', '--rm', '--network', 'none', '--memory', '512m', '--cpus', '2', '-v',
               str(root) + ':/fixture:ro', '--entrypoint', 'ffprobe', 'streaming-worker:verify', '-v', 'error',
               '-threads', '2', '-protocol_whitelist', 'file', '-count_frames', '-select_streams', 'v:0',
               '-show_entries', 'stream=codec_name,width,height,nb_read_frames', '-of', 'json', '/fixture/' + variant]
    output = subprocess.check_output(command, text=True);
    streams = json.loads(output)['streams'];
    s = streams[0]
    if s['codec_name'] != 'h264' or int(s['nb_read_frames']) != args.expected_frames: raise AssertionError(s)
    report[variant] = s
print(
    json.dumps({'renditions': report, 'expectedFrames': args.expected_frames, 'network': 'disabled', 'result': 'PASS'},
               indent=2))
