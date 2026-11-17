#!/usr/bin/env python3
"""Real process/storage/scanner faults, isolated from the user's normal Compose stack."""
import hashlib
import http.cookiejar
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1];
project = 'stream-chaos-' + uuid.uuid4().hex[:8]
live = os.environ.get('LOCAL_LIVE_SCANNER') == 'true'
compose = ['docker', 'compose', '-p', project, '-f', str(root / 'compose.yml'), '-f',
           str(root / 'docker/minio.compose.yml')]
if live: compose += ['-f', str(root / 'docker/scanner-network.compose.yml')]
compose += ['-f', str(root / 'docker/chaos.compose.yml')]
os.environ['CHAOS_WORKER_JSON'] = '{}'
scanner = ['docker', 'compose', '-f', str(root / 'docker/scanner-qualification.compose.yml')]


def docker(*args): return subprocess.check_output(compose + list(args), cwd=root, text=True).strip()


def poll(fn, timeout=120):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = fn()
        if result: return result
        time.sleep(.15)
    raise TimeoutError('Chaos condition timed out')


def sql(query): return docker('exec', '-T', 'postgres', 'psql', '-U', 'streaming', '-d', 'streaming', '-Atc', query)


started = time.monotonic();
results = {};
fixture = root / 'target' / ('chaos-fixture-' + project + '.mp4')
(root / 'target/chaos-results.json').unlink(missing_ok=True)
(root / 'target/chaos-partial-results.json').unlink(missing_ok=True)
(root / 'target/chaos-failure.log').unlink(missing_ok=True)
try:
    docker('up', '-d', '--no-build', 'postgres', 'storage')
    poll(lambda: json.loads(docker('ps', '--format', 'json')) if False else 'healthy' in docker('ps', 'postgres',
                                                                                                'storage'), 60)
    os.environ['CHAOS_S3_URL'] = 'http://' + docker('port', 'storage', '4566')
    docker('up', '-d', '--no-build')
    base = 'http://' + docker('port', 'web', '80')


    def ready():
        try:
            urllib.request.urlopen('http://' + docker('port', 'api', '8080') + '/actuator/health/readiness', timeout=2);
            return True
        except Exception:
            return False


    poll(ready, 60)
    with urllib.request.urlopen(urllib.request.Request(base + '/dev/token?user=chaos', method='POST')) as response:
        token = json.load(response)['token']
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


    def api(path, method='GET', body=None):
        request = urllib.request.Request(base + '/api/v1' + path, method=method,
                                         data=None if body is None else json.dumps(body).encode(),
                                         headers={'Authorization': 'Bearer ' + token,
                                                  'Content-Type': 'application/json',
                                                  'Idempotency-Key': str(uuid.uuid4())})
        with client.open(request, timeout=45) as response: return json.loads(response.read() or 'null')


    fixture.parent.mkdir(exist_ok=True)
    with fixture.open('wb') as out:
        subprocess.run(
            ['docker', 'run', '--rm', '--entrypoint', 'ffmpeg', 'streaming-worker:verify', '-v', 'error', '-f', 'lavfi',
             '-i', 'testsrc2=size=1280x720:rate=24', '-t', '30', '-c:v', 'libx264', '-threads', '2', '-preset',
             'ultrafast', '-pix_fmt', 'yuv420p', '-f', 'mp4', '-movflags', 'frag_keyframe+empty_moov', 'pipe:1'],
            stdout=out, check=True)
    data = fixture.read_bytes()


    def upload(payload=None):
        content = data if payload is None else payload
        v = api('/videos', 'POST',
                {'title': 'Chaos synthetic', 'category': 'test', 'visibility': 'PUBLIC', 'published': False});
        u = api('/videos/uploads', 'POST',
                {'videoId': v['id'], 'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()});
        chunk = 8 * 1024 * 1024
        for n, start in enumerate(range(0, len(content), chunk), 1):
            url = api('/videos/uploads/' + u['id'] + '/parts', 'POST', {'first': n, 'count': 1})[0]['url']
            if n == 1:
                try:
                    urllib.request.urlopen(urllib.request.Request(url, data=b'', method='PUT'));
                    raise AssertionError(
                        'Unreserved part length accepted')
                except urllib.error.HTTPError as e:
                    assert e.code in (400, 403), e.code
                results['presigned_part_length_enforced'] = 'PASS'
            with urllib.request.urlopen(
                    urllib.request.Request(url, data=content[start:start + chunk], method='PUT')) as r:
                assert r.status == 200
        return v['id'], u['id']


    def stage_video(payload=None):
        video, upload_id = upload(payload);
        api('/videos/uploads/' + upload_id + '/complete', 'POST', {});
        return video, upload_id


    def processing(video):
        return api('/videos/' + video + '/processing-status')[0]


    def failed(video):
        j = processing(video);
        return j if j['attempts'] > 0 and j['state'] in ('QUEUED', 'FAILED', 'BLOCKED') and j['error'] else False


    def maintenance():
        out = docker('run', '--rm', '--no-deps', '--entrypoint', 'java', 'worker',
                     '-Dloader.main=com.example.streaming.processing.ScratchMaintenance', '-cp', '/app/app.jar',
                     'org.springframework.boot.loader.launch.PropertiesLauncher')
        return json.loads(next(line for line in reversed(out.splitlines()) if line.startswith('{')))


    def age(attempt):
        sql("update processing_attempts set finished_at=now()-interval '2 days' where id='" + str(
            uuid.UUID(attempt)) + "'")


    def fault(properties):
        os.environ['CHAOS_WORKER_JSON'] = json.dumps({'media': properties});
        docker('up', '-d', '--no-deps', '--force-recreate', '--scale', 'worker=1', 'worker')


    video, session = upload();
    api('/videos/uploads/' + session + '/complete', 'POST', {})
    poll(lambda: any(j['state'] == 'RUNNING' for j in api('/videos/' + video + '/processing-status')), 60)
    crashed_attempt = sql("select attempt from jobs where video_id='" + str(uuid.UUID(video)) + "'")
    docker('kill', '-s', 'SIGKILL', 'worker')
    assert api('/videos/' + video)['current_attempt'] is None
    # Accelerated lease clock: kill is real; avoid a ninety-second wall-clock wait in CI.
    sql("update jobs set lease_until=now()-interval '1 second' where video_id='" + str(uuid.UUID(video)) + "'")
    docker('up', '-d', '--no-deps', 'worker')
    poll(lambda: api('/videos/' + video)['state'] == 'READY', 180)
    assert api('/videos/' + video + '/processing-status')[0]['attempts'] >= 2
    results['worker_sigkill_and_expired_lease'] = 'PASS (lease clock accelerated)'
    age(crashed_attempt);
    maintenance()
    assert sql("select scratch_purged_at is not null from processing_attempts where id='" + str(
        uuid.UUID(crashed_attempt)) + "'") == 't'
    results['crash_scratch_retention'] = 'PASS (retention clock accelerated)'
    recovered_video = video;
    recovered_upload = session
    video, session = upload();
    docker('stop', 'storage')
    try:
        api('/videos/uploads/' + session + '/complete', 'POST', {});
        raise AssertionError('Expected storage failure')
    except urllib.error.HTTPError as e:
        assert e.code == 503, e.code
    assert api('/videos/uploads/' + session)['state'] == 'UPLOADING'
    results['storage_outage_no_false_completion'] = 'PASS'
    docker('up', '-d', 'storage')
    poll(lambda: 'healthy' in docker('ps', 'storage'), 60)
    docker('stop', 'postgres')
    try:
        api('/videos');
        raise AssertionError('Expected database failure')
    except urllib.error.HTTPError as e:
        assert e.code == 503, e.code
    docker('up', '-d', 'postgres')


    def recovered_database():
        try:
            return api('/videos/' + recovered_video)['state'] == 'READY'
        except (urllib.error.URLError, urllib.error.HTTPError):
            return False


    poll(recovered_database, 60)
    assert api('/videos/' + recovered_video)['state'] == 'READY'
    playback = api('/videos/' + recovered_video + '/playback-sessions', 'POST', {})
    with client.open(base + playback['manifest'], timeout=30) as response:
        assert b'#EXTM3U' in response.read()
    results['database_restart_and_media_persistence'] = 'PASS'
    api('/videos/' + recovered_video, 'DELETE')
    sql("update erasure_jobs set not_before=now()-interval '1 second' where video_id='" + str(
        uuid.UUID(recovered_video)) + "'")
    poll(lambda: api('/videos/' + recovered_video + '/erasure-status')['state'] == 'DONE', 30)
    docker('run', '--rm', '--no-deps', 'storage-init', 'verify-erased', recovered_video, recovered_upload)
    results['minio_versioned_physical_erasure'] = 'PASS (retention clock accelerated)'
    # Docker may remap an ephemeral host port when storage restarts. Refresh only the disposable stack.
    os.environ['CHAOS_S3_URL'] = 'http://' + docker('port', 'storage', '4566')
    docker('up', '-d', '--no-deps', '--force-recreate', 'api', 'worker', 'web')
    base = 'http://' + docker('port', 'web', '80');
    poll(ready, 60)
    results['storage_provider'] = 'source-built MinIO RELEASE.2025-09-07T16-13-09Z local fixture'

    # A stopped JVM still holds its OS lock. A second worker may win the expired job, but janitor must skip the old files.
    locked, locked_upload = stage_video();
    poll(lambda: processing(locked)['state'] == 'RUNNING', 60)
    old_attempt = sql("select attempt from jobs where video_id='" + str(uuid.UUID(locked)) + "'")
    poll(lambda: sql("select scratch_volume is not null from processing_attempts where id='" + str(
        uuid.UUID(old_attempt)) + "'") == 't', 20)
    old_worker = docker('ps', '-q', 'worker');
    subprocess.run(['docker', 'pause', old_worker], check=True, stdout=subprocess.DEVNULL)
    assert subprocess.check_output(['docker', 'inspect', old_worker, '--format', '{{.State.Paused}}'],
                                   text=True).strip() == 'true'
    sql("update jobs set lease_until=now()-interval '1 second' where video_id='" + str(uuid.UUID(locked)) + "'")
    takeover = project + '-takeover';
    docker('run', '--no-deps', '-d', '--name', takeover, 'worker');
    poll(lambda: api('/videos/' + locked)['state'] == 'READY', 180)
    age(old_attempt);
    maintenance();
    assert sql("select scratch_purged_at is null from processing_attempts where id='" + str(
        uuid.UUID(old_attempt)) + "'") == 't'
    subprocess.run(['docker', 'rm', '-f', old_worker], check=True, stdout=subprocess.DEVNULL)
    sql("update videos set legal_hold=true where id='" + str(uuid.UUID(locked)) + "'");
    maintenance()
    assert sql("select scratch_purged_at is null from processing_attempts where id='" + str(
        uuid.UUID(old_attempt)) + "'") == 't'
    sql("update videos set legal_hold=false where id='" + str(uuid.UUID(locked)) + "'");
    maintenance()
    assert sql("select scratch_purged_at is not null from processing_attempts where id='" + str(
        uuid.UUID(old_attempt)) + "'") == 't'
    results['paused_writer_lock_and_legal_hold'] = 'PASS (cgroup freezer; lease/retention clocks accelerated)'
    subprocess.run(['docker', 'rm', '-f', takeover], check=True, stdout=subprocess.DEVNULL)

    fault({'ffmpeg': '/chaos/hang-ffmpeg.sh', 'ffmpeg-timeout-seconds': 2})
    hung, _ = stage_video();
    j = poll(lambda: failed(hung), 60);
    assert j['error'] == 'TimeoutException', j
    assert api('/videos/' + hung)['current_attempt'] is None
    assert 'sleep 3600' not in docker('top', 'worker')
    results['encoder_timeout_kills_child'] = 'PASS';
    api('/videos/' + hung, 'DELETE')

    fault({'temp': '/tiny', 'scratch-min-free-bytes': 1})
    full, _ = stage_video();
    j = poll(lambda: failed(full), 60);
    assert j['error'] == 'SCRATCH_FULL', j
    assert api('/videos/' + full)['current_attempt'] is None
    results['tmpfs_disk_exhaustion'] = 'PASS (1 MiB tmpfs, real ENOSPC)';
    api('/videos/' + full, 'DELETE')

    fault({'ffmpeg': '/chaos/hang-ffmpeg.sh', 'ffmpeg-timeout-seconds': 60})
    oom, _ = stage_video();
    poll(lambda: processing(oom)['state'] == 'RUNNING', 60)
    poll(lambda: sql(
        "select scratch_volume is not null from processing_attempts where id=(select attempt from jobs where video_id='" + str(
            uuid.UUID(oom)) + "')") == 't', 20)
    oom_worker = docker('ps', '-q', 'worker');
    subprocess.run(['docker', 'update', '--memory', '96m', '--memory-swap', '96m', oom_worker], check=True,
                   stdout=subprocess.DEVNULL)
    poll(lambda: subprocess.check_output(['docker', 'inspect', oom_worker, '--format', '{{.State.OOMKilled}}'],
                                         text=True).strip() == 'true', 30)
    assert api('/videos/' + oom)['current_attempt'] is None
    sql("update jobs set lease_until=now()-interval '1 second' where video_id='" + str(uuid.UUID(oom)) + "'")
    fault({});
    poll(lambda: api('/videos/' + oom)['state'] == 'READY', 180)
    results['worker_cgroup_oom_and_recovery'] = 'PASS (96 MiB injected limit, lease clock accelerated)'

    if live:
        import base64

        eicar = base64.b64decode(
            'WDVPIVAlQEFQWzRcUFpYNTQoUF4pN0NDKTd9JEVJQ0FSLVNUQU5EQVJELUFOVElWSVJVUy1URVNULUZJTEUhJEgrSCo=')
        infected, _ = stage_video(eicar);
        poll(lambda: processing(infected)['state'] == 'BLOCKED', 60)
        assert api('/videos/' + infected)['current_attempt'] is None
        assert sql(
            "select a.scan_result from processing_attempts a join jobs j on j.attempt=a.id where j.video_id='" + str(
                uuid.UUID(infected)) + "'") == 'INFECTED'
        results['live_eicar_quarantine'] = 'PASS'
        subprocess.run(scanner + ['stop', 'clamav'], check=True, stdout=subprocess.DEVNULL)
        try:
            down, _ = stage_video();
            poll(lambda: failed(down), 60);
            assert api('/videos/' + down)['current_attempt'] is None
            results['scanner_outage_fails_closed'] = 'PASS'
        finally:
            subprocess.run(scanner + ['up', '-d', 'clamav'], check=True, stdout=subprocess.DEVNULL)
        poll(lambda: 'healthy' in subprocess.check_output(scanner + ['ps', 'clamav'], text=True), 120)
        sql("update jobs set available_at=now() where video_id='" + str(uuid.UUID(down)) + "'");
        poll(lambda: api('/videos/' + down)['state'] == 'READY', 180)
        assert sql(
            "select a.scan_result='CLEAN' and a.scan_database is not null and a.scan_database_built_at is not null from processing_attempts a join jobs j on j.attempt=a.id where j.video_id='" + str(
                uuid.UUID(down)) + "'") == 't'
        results['live_scanner_recovery_and_audit'] = 'PASS'
    results['elapsed_seconds'] = round(time.monotonic() - started, 3)
    print(json.dumps(results, indent=2))
    (root / 'target/chaos-results.json').write_text(json.dumps(results, indent=2) + '\n')
except Exception as error:
    (root / 'target/chaos-partial-results.json').write_text(
        json.dumps({'completed': results, 'failure': type(error).__name__}, indent=2) + '\n')
    try:
        (root / 'target/chaos-failure.log').write_text(docker('logs', '--no-color', '--tail', '100'))
    except Exception:
        pass
    raise
finally:
    # Only this random disposable project's containers and volumes are removed.
    try:
        docker('down', '-v', '--remove-orphans')
    finally:
        fixture.unlink(missing_ok=True)
