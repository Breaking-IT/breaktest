# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to you under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
# https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Run an isolated client and TLS peer; retain logs and per-process RSS samples."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
users = sys.argv[2]
java_home = os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home")
classpath = Path("/tmp/ws-load/classpath.txt").read_text()
classes = Path("/tmp/ws-load/harness")
classes.mkdir(exist_ok=True)
subprocess.run([java_home + "/bin/javac", "-cp", classpath, "-d", str(classes),
                str(root / "extras/websocket-benchmark/WebSocketLoadBenchmark.java")], check=True)
server_log = open(out / "server.log", "w")
server = subprocess.Popen([sys.executable, str(root / "extras/websocket-benchmark/server.py"),
                           "/tmp/ws-load/cert.pem", "/tmp/ws-load/key.pem", str(out / "server.jsonl")],
                          stdout=server_log, stderr=subprocess.STDOUT)
client = None
try:
    time.sleep(1)
    with open(out / "client.log", "w") as log:
        client = subprocess.Popen([java_home + "/bin/java", "-Xms256m", "-Xmx1g", "-Xss256k",
                                   "-Djava.awt.headless=true", "-cp", str(classes) + os.pathsep + classpath,
                                   "org.apache.jmeter.protocol.websocket.sampler.WebSocketLoadBenchmark",
                                   str(root), users, sys.argv[3] if len(sys.argv) > 3 else "20", "1200"], cwd=root, stdout=log, stderr=subprocess.STDOUT)
        start = time.monotonic()
        with open(out / "processes.jsonl", "w") as samples:
            while client.poll() is None:
                text = subprocess.check_output(["ps", "-o", "pid=,rss=,%cpu=",
                                                "-p", str(client.pid) + "," + str(server.pid)], text=True)
                samples.write(json.dumps(dict(seconds=time.monotonic()-start, ps=text)) + "\n")
                samples.flush()
                if time.monotonic()-start > 180:
                    client.kill()
                    raise RuntimeError("Benchmark exceeded 180-second watchdog")
                time.sleep(1)
        print("client exit", client.returncode)
finally:
    if client is not None and client.poll() is None:
        client.kill()
    server.terminate()
    server.wait(timeout=10)
    server_log.close()
