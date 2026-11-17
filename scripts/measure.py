#!/usr/bin/env python3
"""Small local control-plane measurement, not a global-scale benchmark."""
import concurrent.futures
import json
import os
import statistics
import time
import urllib.request

base = os.environ.get('BASE', 'http://localhost:8090')
with urllib.request.urlopen(
        urllib.request.Request(base + '/dev/token?user=measure', method='POST')) as response: token = \
json.load(response)[
    'token']


def one(_):
    start = time.perf_counter()
    with urllib.request.urlopen(
            urllib.request.Request(base + '/api/v1/videos', headers={'Authorization': 'Bearer ' + token}),
            timeout=10) as response: response.read();assert response.status == 200
    return (time.perf_counter() - start) * 1000


for i in range(10): one(i)
start = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor: values = sorted(executor.map(one, range(200)))
elapsed = time.perf_counter() - start
print(json.dumps(
    {'requests': len(values), 'concurrency': 8, 'elapsed_seconds': elapsed, 'achieved_rps': len(values) / elapsed,
     'latency_ms': {'p50': values[99], 'p95': values[189], 'p99': values[197]},
     'note': 'Local warm catalog; tiny dataset, no media bytes or production inference'}, indent=2))
