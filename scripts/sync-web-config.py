#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = root / 'helm/platform.yaml';
text = manifest.read_text()
start = text.index('apiVersion: v1\nkind: ConfigMap\nmetadata: {name: streaming-web}\ndata:')
end = text.index('\n---\n', start)
block = 'apiVersion: v1\nkind: ConfigMap\nmetadata: {name: streaming-web}\ndata:\n'
configs = {name: (root / 'web-player' / name).read_text() for name in ['index.html', 'app.js']}
configs['default.conf'] = (root / 'docker/nginx.conf').read_text().replace('http://api:8080',
                                                                           'http://streaming-api:8080').replace(
    'resolver 127.0.0.11 valid=10s ipv6=off;', 'resolver kube-dns.kube-system.svc.cluster.local valid=10s ipv6=off;')
for name, value in configs.items(): block += '  ' + name + ': |\n' + ''.join(
    '    ' + line + '\n' for line in value.splitlines())
manifest.write_text(text[:start] + block.rstrip() + text[end:])
