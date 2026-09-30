#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Paired JMH runs against one local Caddy TLS endpoint; requires Docker/openssl."""
import argparse
import hashlib
import itertools
import json
import pathlib
import platform
import subprocess
import tempfile
import time
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--baseline', type=pathlib.Path, required=True)
p.add_argument('--changed', type=pathlib.Path, default=pathlib.Path.cwd())
p.add_argument('--java', required=True)
p.add_argument('--output', type=pathlib.Path, required=True)
p.add_argument('--rounds', type=int, default=4)
p.add_argument('--seconds', type=int, default=1)
p.add_argument('--iterations', type=int, default=3)
p.add_argument('--protocol', choices=['HTTP/1.1', 'HTTP/2'])
p.add_argument('--threads', type=int)
p.add_argument('--auth', choices=['none', 'basic'])
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)

def run(args):
    return subprocess.check_output([str(x) for x in args], stderr=subprocess.STDOUT, text=True).strip()

roots = {'baseline': a.baseline.resolve(), 'changed': a.changed.resolve()}
jars = {k: next(v.glob('src/protocol/http/build/libs/*-jmh.jar')) for k, v in roots.items()}
# The JMH fat jar drops the Multi-Release flag required by bundled dnsjava.
# Repair benchmark copies identically; never alter either production build.
for variant, source in list(jars.items()):
    target = (a.output / f'{variant}-jmh.jar').resolve()
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, 'w') as fixed:
        for entry in original.infolist():
            data = original.read(entry)
            if entry.filename == 'META-INF/MANIFEST.MF':
                data = data.replace(b'\r\n\r\n', b'\r\nMulti-Release: true\r\n\r\n', 1)
            fixed.writestr(entry, data)
    jars[variant] = target
metadata = {'started': time.ctime(), 'platform': platform.platform(), 'java': run([a.java, '-version']),
            'image': run(['docker', 'image', 'inspect', 'caddy:2', '--format', '{{.Id}}']),
            'arguments': vars(a).copy(), 'builds': {}}
metadata['arguments'] = {k: str(v) if isinstance(v, pathlib.Path) else v for k, v in metadata['arguments'].items()}
for k, jar in jars.items():
    metadata['builds'][k] = {'jar': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                            'head': run(['git', '-C', roots[k], 'rev-parse', 'HEAD'])}
    (a.output / f'{k}-production.diff').write_text(run(['git', '-C', roots[k], 'diff', '2c36d9c0782e88ef226a8a4c01d666e4cfe92fe2', '--', 'src/protocol/http/src/main']))
(a.output / 'metadata.json').write_text(json.dumps(metadata, indent=2))
container = None
try:
    with tempfile.TemporaryDirectory(prefix='breaktest-http-bench-') as temp:
        temp = pathlib.Path(temp)
        run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', temp/'key.pem',
             '-out', temp/'cert.pem', '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'])
        (temp/'Caddyfile').write_text('{\n auto_https off\n servers {\n protocols h1 h2\n }\n}\n:8443 {\n tls /fixture/cert.pem /fixture/key.pem\n header X-Bench-Protocol {http.request.proto}\n respond "' + 'x'*512 + '" 200\n}\n')
        (temp/'log4j2.xml').write_text('<Configuration status="ERROR"><Appenders><Console name="out" target="SYSTEM_ERR"><PatternLayout pattern="%m%n"/></Console></Appenders><Loggers><Root level="error"><AppenderRef ref="out"/></Root></Loggers></Configuration>')
        container = run(['docker', 'run', '--rm', '-d', '-p', '127.0.0.1::8443', '-v', f'{temp}:/fixture:ro',
                         'caddy:2', 'caddy', 'run', '--config', '/fixture/Caddyfile', '--adapter', 'caddyfile'])
        port = run(['docker', 'port', container, '8443/tcp']).split(':')[-1]
        endpoint = f'https://localhost:{port}/'
        for attempt in range(30):
            try:
                run(['curl', '-ksSf', endpoint])
                break
            except subprocess.CalledProcessError:
                time.sleep(0.2)
        scenarios = list(itertools.product(['HTTP/1.1', 'HTTP/2'], [1, 8], ['none', 'basic']))
        scenarios = [(p, t, auth) for p, t, auth in scenarios
                     if (a.protocol is None or p == a.protocol)
                     and (a.threads is None or t == a.threads)
                     and (a.auth is None or auth == a.auth)]
        for repeat in range(a.rounds):
            for protocol, threads, auth in scenarios:
                for variant in (['baseline', 'changed'] if repeat % 2 == 0 else ['changed', 'baseline']):
                    name = f'r{repeat+1}-{protocol.replace("/", "").replace(".", "")}-t{threads}-{auth}-{variant}'
                    command = [a.java, '-jar', str(jars[variant]), '.*HttpSamplerRequestBenchmark.request',
                               '-p', f'protocol={protocol}', '-p', f'authentication={auth}', '-p', f'endpoint={endpoint}',
                               '-t', str(threads), '-f', '1', '-wi', str(a.iterations), '-w', f'{a.seconds}s',
                               '-i', str(a.iterations), '-r', f'{a.seconds}s', '-prof', 'gc',
                               '-prof', 'org.apache.jmeter.protocol.http.sampler.HttpRequestProcessProfiler',
                               '-rf', 'json', '-rff', str((a.output/f'{name}.json').resolve()), '-foe', 'true',
                               '-jvmArgsAppend', f'-Xms512m -Xmx512m -Djmeter.home={roots[variant]} -Dlog4j.configurationFile={temp}/log4j2.xml']
                    print(f'{time.strftime("%H:%M:%S")} {name}', flush=True)
                    with (a.output/f'{name}.log').open('w') as log:
                        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
                    result = json.loads((a.output/f'{name}.json').read_text())[0]
                    print(f'  {result["secondaryMetrics"]["request.rate"]["score"]:.0f} ops/s; {result["primaryMetric"]["score"]:.1f} us', flush=True)
finally:
    if container:
        run(['docker', 'stop', container])
