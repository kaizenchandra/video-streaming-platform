#!/usr/bin/env python3
"""Real synthetic upload, duplicate completion, HLS fetch/decode, visibility revocation."""
import concurrent.futures
import hashlib
import http.cookiejar
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid

base = os.environ.get('BASE', 'http://localhost:8090');
jar = http.cookiejar.CookieJar();
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def call(path, method='GET', body=None, auth=True, headers=None):
    req = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={**({'Authorization': 'Bearer ' + token} if auth else {}),
                                          'Content-Type': 'application/json', **(headers or {})})
    with client.open(req, timeout=60) as r: return json.loads(r.read() or 'null')


token = call('/dev/token?user=smoke', 'POST', auth=False)['token']
source = '/tmp/streaming-fixture.mp4'
subprocess.run(
    ['docker', 'run', '--rm', '--entrypoint', 'ffmpeg', 'streaming-worker:verify', '-v', 'error', '-f', 'lavfi', '-i',
     'testsrc2=size=640x360:rate=24', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '8', '-c:v',
     'libx264', '-threads', '2', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-f', 'mp4', '-movflags',
     'frag_keyframe+empty_moov', 'pipe:1'], stdout=open(source, 'wb'), check=True)
data = open(source, 'rb').read();
v = call('/api/v1/videos', 'POST',
         {'title': 'Synthetic smoke ' + str(uuid.uuid4()), 'description': 'Test fixture', 'category': 'test',
          'tags': 'synthetic', 'visibility': 'PUBLIC', 'published': False});
vid = v['id'];
key = str(uuid.uuid4());
body = {'videoId': vid, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()};
u = call('/api/v1/videos/uploads', 'POST', body, headers={'Idempotency-Key': key});
assert call('/api/v1/videos/uploads', 'POST', body, headers={'Idempotency-Key': key})['id'] == u['id']
part = call('/api/v1/videos/uploads/' + u['id'] + '/parts', 'POST', {'first': 1, 'count': 1})[0]
with urllib.request.urlopen(
        urllib.request.Request(part['url'], data=data, method='PUT')) as response: assert response.status == 200
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
    for result in executor.map(lambda _: call('/api/v1/videos/uploads/' + u['id'] + '/complete', 'POST', {}),
                               range(2)): assert result['state'] == 'COMPLETED'
for _ in range(120):
    v = call('/api/v1/videos/' + vid)
    if v['state'] == 'READY': break
    if v['state'] == 'FAILED': raise AssertionError(call('/api/v1/videos/' + vid + '/processing-status'))
    time.sleep(2)
assert v['state'] == 'READY', v
if v['moderation_status'] == 'PENDING':
    owner_token = token
    token = call('/dev/token?user=moderator', 'POST', auth=False)['token']
    review = call('/api/v1/videos/' + vid + '/review-playback-sessions', 'POST', {})
    with client.open(base + review['manifest']) as response: assert b'#EXTM3U' in response.read()
    call('/api/v1/videos/' + vid + '/moderation-decisions', 'POST',
         {'attempt': v['current_attempt'], 'decision': 'APPROVED', 'reason': 'Synthetic conformance review'},
         headers={'If-Match': str(v['version'])})
    token = owner_token;
    v = call('/api/v1/videos/' + vid)
call('/api/v1/videos/' + vid, 'PATCH',
     {k: v[k] for k in ['title', 'description', 'category', 'tags', 'visibility']} | {'published': True},
     headers={'If-Match': str(v['version'])})
session = call('/api/v1/videos/' + vid + '/playback-sessions', 'POST', {})
with client.open(base + session['manifest']) as response: master = response.read().decode()
variants = [x for x in master.splitlines() if x and not x.startswith('#')];
assert len(variants) >= 2, master
prefix = session['manifest'].rsplit('/', 1)[0] + '/'
for variant in variants:
    with client.open(base + prefix + variant) as response:
        playlist = response.read().decode()
    segments = [x for x in playlist.splitlines() if x and not x.startswith('#')];
    assert '#EXT-X-ENDLIST' in playlist
    for segment in segments:
        with client.open(base + prefix + variant.rsplit('/', 1)[0] + '/' + segment) as response: assert len(
            response.read()) > 0
# Session-bound authorization and anonymous denial.
try:
    urllib.request.urlopen(base + session['manifest']);
    raise AssertionError('Anonymous playback allowed')
except urllib.error.HTTPError as e:
    assert e.code == 403
for sequence in [2, 1, 2]: call('/api/v1/videos/' + vid + '/watch-progress', 'PUT',
                                {'sessionId': session['sessionId'], 'sequence': sequence, 'position': 3,
                                 'completed': False})
for _ in range(2): call('/api/v1/videos/' + vid + '/likes/me', 'PUT')
assert any(x['video_id'] == vid for x in call('/api/v1/users/me/continue-watching'))
call('/api/v1/videos/' + vid + '/comments', 'POST', {'body': 'Synthetic test comment'})
assert call('/api/v1/videos/' + vid + '/comments')
time.sleep(7)
for _ in range(2): call('/api/v1/videos/' + vid + '/watch-progress', 'PUT',
                        {'sessionId': session['sessionId'], 'sequence': 3, 'position': 7, 'completed': False})
time.sleep(6)
public = call('/api/v1/videos');
assert next(x for x in public if x['id'] == vid)['views'] == 1, public
call('/api/v1/videos/' + vid, 'DELETE')
try:
    client.open(base + session['manifest']);
    raise AssertionError('Deleted playback allowed')
except urllib.error.HTTPError as e:
    assert e.code == 403
print(json.dumps({'video': vid, 'renditions': variants, 'source_bytes': len(data), 'result': 'PASS'}, indent=2))
