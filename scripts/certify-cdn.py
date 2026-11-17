#!/usr/bin/env python3
"""Live CloudFront check: staging CDN TTL must be <=60s; never logs credential values."""
import copy
import http.cookiejar
import json
import os
import time
import urllib.error
import urllib.request
import uuid

base = os.environ['STAGING_ORIGIN'];
token = os.environ['TOKEN'];
video = str(uuid.UUID(os.environ['VIDEO_ID']))
if not base.startswith('https://') or base.endswith('/'): raise ValueError(
    'Use exact HTTPS staging origin without trailing slash')
jar = http.cookiejar.CookieJar();
authorized = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
request = urllib.request.Request(base + '/api/v1/videos/' + video + '/playback-sessions', data=b'{}',
                                 headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'},
                                 method='POST')
with authorized.open(request, timeout=30) as r: session = json.load(r)
assert 30 <= session['credentialTtl'] <= 60, 'Set CDN_TTL_SECONDS=30..60 for this bounded expiry test'
path = session['manifest'];
assert path.startswith('/videos/' + video + '/') and '..' not in path
with authorized.open(base + path, timeout=30) as r:
    manifest = r.read().decode();
    assert 'cloudfront' in r.headers.get('Via', '').lower()
assert '#EXTM3U' in manifest


# Warm cache must still reject an anonymous viewer.
def denied(opener, url):
    try:
        opener.open(url, timeout=30);
        raise AssertionError('Unauthorized media allowed')
    except urllib.error.HTTPError as e:
        assert e.code == 403, e.code


anonymous = urllib.request.build_opener();
denied(anonymous, base + path)
badjar = http.cookiejar.CookieJar()
for cookie in jar:
    altered = copy.copy(cookie)
    if altered.name == 'CloudFront-Signature': altered.value = 'z' + altered.value[1:]
    badjar.set_cookie(altered)
denied(urllib.request.build_opener(urllib.request.HTTPCookieProcessor(badjar)), base + path)
variants = [x for x in manifest.splitlines() if x and not x.startswith('#')];
assert variants
prefix = path.rsplit('/', 1)[0] + '/'
for variant in variants:
    assert variant.replace('p/index.m3u8', '').isdigit()
    with authorized.open(base + prefix + variant, timeout=30) as r: playlist = r.read().decode()
    segment = next(x for x in playlist.splitlines() if x and not x.startswith('#'))
    assert segment.startswith('segment_') and segment.endswith('.ts') and '/' not in segment
    with authorized.open(base + prefix + variant.rsplit('/', 1)[0] + '/' + segment, timeout=30) as r: assert len(
        r.read()) > 0
other = path.replace(video, str(uuid.uuid4()), 1);
denied(authorized, base + other)
time.sleep(session['credentialTtl'] + 2);
denied(authorized, base + path)
print(json.dumps({'result': 'PASS',
                  'scope': 'live CloudFront manifest/segments, warm-cache anonymous denial, tampering, resource scope and credential expiry',
                  'variants': len(variants)}))
