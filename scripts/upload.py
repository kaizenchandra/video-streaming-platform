#!/usr/bin/env python3
"""Resumable 8-MiB multipart uploader, bounded memory. State file contains scoped upload identity."""
import argparse
import hashlib
import json
import urllib.request
import uuid
from pathlib import Path

p = argparse.ArgumentParser();
p.add_argument('file', type=Path);
p.add_argument('--base', default='http://localhost:8090');
p.add_argument('--token', required=True);
p.add_argument('--state', type=Path, default=Path('upload-state.json'));
args = p.parse_args()


def request(path, method='GET', body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(args.base + '/api/v1' + path, data=data, method=method,
                                 headers={'Authorization': 'Bearer ' + args.token, 'Content-Type': 'application/json',
                                          **(headers or {})})
    with urllib.request.urlopen(req, timeout=600) as r: return json.loads(r.read() or 'null')


size = args.file.stat().st_size
with args.file.open('rb') as f: checksum = hashlib.file_digest(f, 'sha256').hexdigest()
if args.state.exists():
    state = json.loads(args.state.read_text());
    assert state['sha256'] == checksum, 'State file belongs to another source'
else:
    v = request('/videos', 'POST',
                {'title': args.file.name, 'description': '', 'category': 'general', 'tags': '', 'visibility': 'PUBLIC',
                 'published': False})
    u = request('/videos/uploads', 'POST', {'videoId': v['id'], 'bytes': size, 'sha256': checksum},
                {'Idempotency-Key': str(uuid.uuid4())})
    state = {'upload': u['id'], 'video': v['id'], 'sha256': checksum, 'next': 1};
    args.state.write_text(json.dumps(state))
chunk = 8 * 1024 * 1024
with args.file.open('rb') as f:
    f.seek((state['next'] - 1) * chunk)
    while data := f.read(chunk):
        number = state['next'];
        part = request('/videos/uploads/' + state['upload'] + '/parts', 'POST', {'first': number, 'count': 1})[0]
        with urllib.request.urlopen(urllib.request.Request(part['url'], data=data, method='PUT'),
                                    timeout=600) as response: assert response.status == 200
        state['next'] += 1;
        args.state.write_text(json.dumps(state));
        print('Uploaded part', number)
print(request('/videos/uploads/' + state['upload'] + '/complete', 'POST', {}));
print('Video:', state['video'])
