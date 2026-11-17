#!/usr/bin/env python3
"""Disposable real multipart → limited window scan → HLS/manual review qualification.
Default is >10 GiB of real synthetic 4K v210 samples, not sparse/padded media.
"""
import argparse
import concurrent.futures
import hashlib
import http.cookiejar
import json
import os
import resource
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser();
p.add_argument('--frames', type=int, default=486);
p.add_argument('--reuse-generated', action='store_true');
p.add_argument('--keep-fixture', action='store_true');
args = p.parse_args()
if not 6 <= args.frames <= 900: raise ValueError('Frame count must be6–900 within the20GiB product limit')
project = 'stream-large-' + uuid.uuid4().hex[:8];
target = root / 'target';
target.mkdir(exist_ok=True)
work = Path(tempfile.mkdtemp(prefix=project + '-', dir=target));
source = work / 'source.mov'
compose = ['docker', 'compose', '-p', project, '-f', str(root / 'compose.yml'), '-f',
           str(root / 'docker/minio.compose.yml'), '-f', str(root / 'docker/large-video.compose.yml')]
scanner = ['docker', 'compose', '-f', str(root / 'docker/scanner-qualification.compose.yml')]
report = {'project': project, 'frames': args.frames,
          'scope': '10GiB qualification' if args.frames >= 486 else 'small CI variant; no10GB claim', 'checks': {}}
started = time.monotonic();
phase = 'setup';
success = False
report_path = target / (
    'large-video-qualification.json' if args.frames >= 486 else 'large-video-small-qualification.json');
report_path.unlink(missing_ok=True)


def docker(*parts): return subprocess.check_output(compose + list(parts), cwd=root, text=True).strip()


def sql(query): return docker('exec', '-T', 'postgres', 'psql', '-U', 'streaming', '-d', 'streaming', '-Atc', query)


def poll(fn, timeout):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = fn()
        if value: return value
        time.sleep(.5)
    raise TimeoutError('Timed out during ' + phase)


def fixture_container(main, *extra, write=False):
    return ['docker', 'run', '--rm', '--memory', '512m', '--cpus', '2', '--user', f'{os.getuid()}:{os.getgid()}',
            '--network', 'streaming-scanner-qualification_default', '-e', 'CLAMAV_HOST=clamav', '-v',
            f'{work}:/fixture' + ('' if write else ':ro'), '--entrypoint', 'java', 'streaming-worker:verify',
            '-Xmx192m', '-Dloader.main=com.example.streaming.processing.' + main, '-cp', '/app.jar',
            'org.springframework.boot.loader.launch.PropertiesLauncher', *extra]


try:
    phase = 'fixture generation'
    if args.reuse_generated:
        (target / 'large-video-local/source.mov').rename(source)
    else:
        subprocess.run(
            ['docker', 'run', '--rm', '--memory', '1g', '--cpus', '2', '--user', f'{os.getuid()}:{os.getgid()}', '-v',
             f'{work}:/work', '--entrypoint', 'ffmpeg', 'streaming-worker:verify', '-nostdin', '-v', 'error',
             '-filter_threads', '2', '-f', 'lavfi', '-i', 'testsrc2=size=3840x2160:rate=24', '-frames:v',
             str(args.frames), '-c:v', 'v210', '-threads', '2', '/work/source.mov'], check=True)
    size = source.stat().st_size
    if args.frames >= 486: assert size >= 10 * 1024 ** 3
    if size > 20 * 1024 ** 3: raise ValueError('Synthetic source exceeds upload limit')
    metadata = json.loads(subprocess.check_output(
        ['docker', 'run', '--rm', '--memory', '512m', '--cpus', '2', '-v', f'{work}:/fixture:ro', '--entrypoint',
         'ffprobe', 'streaming-worker:verify', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
         'stream=codec_name,width,height,avg_frame_rate,nb_frames,duration', '-of', 'json', '/fixture/source.mov'],
        text=True))['streams'][0]
    assert metadata['codec_name'] == 'v210' and metadata['width'] == 3840 and metadata['height'] == 2160
    assert int(metadata['nb_frames']) == args.frames, metadata
    report['source'] = {'bytes': size, 'metadata': metadata, 'padding': 'none; real uncompressed video samples'}
    with source.open('rb') as f:
        checksum = hashlib.file_digest(f, 'sha256').hexdigest()
    print(f'Generated/probed {size} bytes of 4K v210; qualifying live detections', flush=True)
    phase = 'live scanner adversarial detections'
    scan_output = subprocess.check_output(
        fixture_container('LargeScannerQualification', '/fixture/source.mov', write=True), text=True)
    detections = json.loads(next(line for line in reversed(scan_output.splitlines()) if line.startswith('{')))
    report['liveDetectionQualification'] = detections
    with source.open('rb') as f:
        assert hashlib.file_digest(f, 'sha256').hexdigest() == checksum, 'Benign EICAR fixture not restored'
    report['coverageCanaryRuleSha256'] = hashlib.sha256(
        (root / 'docker/clamav/coverage-canary.ndb').read_bytes()).hexdigest();
    report['checks']['injections_restored_sha256'] = 'PASS';
    report['source']['sha256'] = checksum
    phase = 'storage initialization';
    docker('up', '-d', '--no-build', 'postgres', 'storage')
    poll(lambda: 'healthy' in docker('ps', 'storage'), 120)
    os.environ['LARGE_S3_URL'] = 'http://' + docker('port', 'storage', '4566')
    docker('up', '-d', '--no-build', 'api', 'web')
    base = 'http://' + docker('port', 'web', '80');
    api_base = 'http://' + docker('port', 'api', '8080')


    def ready():
        try:
            with urllib.request.urlopen(api_base + '/actuator/health/readiness', timeout=2) as r:
                return r.status == 200
        except Exception:
            return False


    poll(ready, 90)


    def token(user):
        with urllib.request.urlopen(
                urllib.request.Request(base + '/dev/token?user=' + user, method='POST')) as response: return \
            json.load(response)['token']


    owner = token('large-creator');
    reviewer = token('moderator')


    def api(path, method='GET', body=None, bearer=None, headers=None):
        req = urllib.request.Request(base + '/api/v1' + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={'Authorization': 'Bearer ' + (bearer or owner),
                                              'Content-Type': 'application/json', **(headers or {})})
        with urllib.request.urlopen(req, timeout=600) as response: return json.loads(response.read() or 'null')


    video = api('/videos', 'POST',
                {'title': 'Synthetic 4K large qualification', 'visibility': 'PRIVATE', 'category': 'test',
                 'published': False});
    vid = str(uuid.UUID(video['id']))
    initiation = {'videoId': vid, 'bytes': size, 'sha256': checksum};
    key = str(uuid.uuid4())
    upload = api('/videos/uploads', 'POST', initiation, headers={'Idempotency-Key': key});
    uid = str(uuid.UUID(upload['id']))
    assert api('/videos/uploads', 'POST', initiation, headers={'Idempotency-Key': key})['id'] == uid
    report['checks']['large_reservation_and_idempotency'] = 'PASS'
    phase = 'multipart upload';
    upload_start = time.monotonic();
    chunk = 8 * 1024 * 1024;
    count = (size + chunk - 1) // chunk


    def parts(first, count):
        return api('/videos/uploads/' + uid + '/parts', 'POST', {'first': first, 'count': count})


    def put(part, data):
        assert len(data) == part['contentLength']
        with urllib.request.urlopen(urllib.request.Request(part['url'], data=data, method='PUT'), timeout=600) as r:
            assert r.status == 200;
            return r.headers.get('ETag')


    # Persist a checkpoint, reopen the file as an interrupted client would, and replay a lost acknowledgment.
    with source.open('rb') as f:
        first = parts(1, 3)
        for part in first:
            data = f.read(chunk);
            etag = put(part, data)
        assert put(first[-1], data) == etag
    state = {'upload': uid, 'video': vid, 'next': 4, 'sha256': checksum};
    (work / 'resume.json').write_text(json.dumps(state))
    resumed = json.loads((work / 'resume.json').read_text());
    assert api('/videos/uploads/' + resumed['upload'])['state'] == 'UPLOADING'
    with source.open('rb') as f:
        f.seek((resumed['next'] - 1) * chunk)
        for first in range(resumed['next'], count + 1, 100):
            for part in parts(first, min(100, count - first + 1)): put(part, f.read(chunk))
            if first % 100 == 4: print('Uploaded parts through', min(first + 99, count), '/', count, flush=True)
    report['upload'] = {'parts': count, 'seconds': time.monotonic() - upload_start, 'sourceBytes': size,
                        'clientChunkBytes': chunk, 'resumedAtPart': 4, 'lostAckReplay': 'same ETag'}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        completed = list(pool.map(lambda _: api('/videos/uploads/' + uid + '/complete', 'POST', {}), range(2)))
    assert all(x['state'] == 'COMPLETED' for x in completed)
    assert sql("select count(*) from jobs where video_id='" + vid + "'") == '1'
    assert sql("select used=" + str(size) + " and reserved=0 from accounts where id='large-creator'") == 't'
    report['checks']['concurrent_completion_and_quota'] = 'PASS'
    phase = 'scan and encode';
    processing_start = time.monotonic();
    docker('up', '-d', '--no-build', '--no-deps', 'worker')


    def processed():
        v = api('/videos/' + vid)
        if v['state'] in ('FAILED', 'BLOCKED'): raise AssertionError(api('/videos/' + vid + '/processing-status'))
        return v if v['state'] == 'READY' else False


    video = poll(processed, 1800);
    report['processingSeconds'] = time.monotonic() - processing_start
    evidence = json.loads(sql("select scan_evidence from processing_attempts where id='" + str(
        uuid.UUID(video['current_attempt'])) + "'"))
    assert evidence['complete'] and evidence['coveredBytes'] == size and evidence['sha256'] == checksum, evidence
    assert sql("select scan_result from processing_attempts where id='" + str(
        uuid.UUID(video['current_attempt'])) + "'") == 'WINDOWED_MP4_V1'
    assert video['moderation_status'] == 'PENDING' and not video['published'], 'Windowed result was auto-approved'
    report['coverage'] = evidence;
    report['checks']['full_byte_coverage_matching_hash_and_no_autoapproval'] = 'PASS'


    def edit(v, published):
        return api('/videos/' + vid, 'PATCH',
                   {k: v[k] for k in ['title', 'description', 'category', 'tags', 'visibility']} | {
                       'published': published}, headers={'If-Match': str(v['version'])})


    try:
        edit(video, True);
        raise AssertionError('Unreviewed publication accepted')
    except urllib.error.HTTPError as e:
        assert e.code == 409
    try:
        api('/videos/' + vid + '/moderation-decisions', 'POST',
            {'attempt': video['current_attempt'], 'decision': 'APPROVED', 'reason': 'Synthetic manual review'},
            headers={'If-Match': str(video['version'])});
        raise AssertionError('Owner self-approved')
    except urllib.error.HTTPError as e:
        assert e.code == 403
    api('/videos/' + vid + '/review-playback-sessions', 'POST', {}, bearer=reviewer)
    api('/videos/' + vid + '/moderation-decisions', 'POST',
        {'attempt': video['current_attempt'], 'decision': 'APPROVED',
         'reason': 'Synthetic bounded-window policy qualification'}, bearer=reviewer,
        headers={'If-Match': str(video['version'])})
    video = api('/videos/' + vid);
    edit(video, True)
    report['checks']['manual_review_and_owner_role_enforcement'] = 'PASS'
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    req = urllib.request.Request(base + '/api/v1/videos/' + vid + '/playback-sessions', method='POST', data=b'{}',
                                 headers={'Authorization': 'Bearer ' + owner, 'Content-Type': 'application/json'})
    with client.open(req) as r:
        playback = json.load(r)
    manifest = base + playback['manifest'];
    prefix = manifest.rsplit('/', 1)[0] + '/'
    with client.open(manifest) as r:
        master = r.read().decode()
    variants = [x for x in master.splitlines() if x and not x.startswith('#')];
    assert len(variants) >= 2
    segments = 0;
    playback_dir = work / 'playback-check';
    playback_dir.mkdir();
    (playback_dir / 'master.m3u8').write_text(master)
    for variant in variants:
        with client.open(prefix + variant) as r:
            playlist = r.read().decode()
        assert '#EXT-X-ENDLIST' in playlist
        local_playlist = playback_dir / variant;
        local_playlist.parent.mkdir(parents=True, exist_ok=True);
        local_playlist.write_text(playlist)
        for segment in [x for x in playlist.splitlines() if x and not x.startswith('#')]:
            with client.open(prefix + variant.rsplit('/', 1)[0] + '/' + segment) as r: payload = r.read();assert len(
                payload) > 0
            (local_playlist.parent / segment).write_bytes(payload)
            segments += 1
    try:
        urllib.request.urlopen(manifest);
        raise AssertionError('Anonymous access accepted')
    except urllib.error.HTTPError as e:
        assert e.code == 403
    decoded = json.loads(subprocess.check_output(
        [sys.executable, str(root / 'scripts/decode-hls.py'), str(playback_dir), '--expected-frames', str(args.frames)],
        text=True))
    report['playback'] = {'variants': variants, 'segmentsFetched': segments, 'anonymousDenied': True,
                          'offlineDecodeOfFetchedMedia': decoded}
    receipt = sql("select scratch_purged_at is not null from processing_attempts where id='" + str(
        uuid.UUID(video['current_attempt'])) + "'")
    assert receipt == 't';
    report['checks']['scratch_receipt'] = 'PASS'
    api('/videos/' + vid, 'DELETE')
    try:
        client.open(manifest);
        raise AssertionError('Deleted media accessible')
    except urllib.error.HTTPError as e:
        assert e.code == 403
    report['checks']['deletion_revocation'] = 'PASS'
    report['pythonPeakRssBytes'] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (
        1 if sys.platform == 'darwin' else 1024)
    report['elapsedSeconds'] = time.monotonic() - started;
    report['result'] = 'PASS';
    success = True
    report_path.write_text(json.dumps(report, indent=2) + '\n');
    print(json.dumps(report, indent=2))
except Exception as error:
    report['result'] = 'FAIL';
    report['failedPhase'] = phase;
    report['errorClass'] = type(error).__name__
    (target / ('large-video-failure.json' if args.frames >= 486 else 'large-video-small-failure.json')).write_text(
        json.dumps(report, indent=2) + '\n')
    try:
        (target / 'large-video-failure.txt').write_text(docker('logs', '--no-color', '--tail', '150'))
    except Exception:
        pass
    raise
finally:
    try:
        docker('down', '-v', '--remove-orphans')
    finally:
        if not args.keep_fixture: shutil.rmtree(work)
