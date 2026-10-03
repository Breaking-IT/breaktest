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

"""Local TLS WebSocket benchmark peer; Python standard library only."""
import asyncio
import base64
import hashlib
import json
import signal
import ssl
import struct
import sys
import time

active = 0
opened = 0
pings = 0
pongs = 0
latencies = []
errors = 0


async def handle(reader, writer):
    global active, opened, pings, pongs, errors
    task = None
    counted = False
    try:
        headers = (await reader.readuntil(b"\r\n\r\n")).decode("ascii")
        key = next(line.split(":", 1)[1].strip() for line in headers.split("\r\n")
                   if line.lower().startswith("sec-websocket-key:"))
        accept = base64.b64encode(hashlib.sha1(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
        writer.write(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                     b"Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + b"\r\n\r\n")
        await writer.drain()
        active += 1
        opened += 1
        counted = True
        number = opened

        async def ping():
            global pings
            await asyncio.sleep((number % 200) / 100)
            while True:
                writer.write(b"\x89\x08" + struct.pack("!d", time.monotonic()))
                await writer.drain()
                pings += 1
                await asyncio.sleep(2)
        task = asyncio.create_task(ping())
        while True:
            first, second = await reader.readexactly(2)
            size = second & 127
            if size == 126:
                size = struct.unpack("!H", await reader.readexactly(2))[0]
            elif size == 127:
                size = struct.unpack("!Q", await reader.readexactly(8))[0]
            mask = await reader.readexactly(4) if second & 128 else b"\x00" * 4
            data = await reader.readexactly(size)
            data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
            if first & 15 == 10:
                pongs += 1
                latencies.append((time.monotonic() - struct.unpack("!d", data)[0]) * 1000)
            elif first & 15 == 8:
                writer.write(bytes([0x88, len(data)]) + data)
                await writer.drain()
                break
    except (asyncio.IncompleteReadError, ConnectionError, ssl.SSLError):
        pass
    except Exception:
        errors += 1
    finally:
        if task:
            task.cancel()
        if counted:
            active -= 1
        writer.close()


async def main():
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(sys.argv[1], sys.argv[2])
    stop = asyncio.Event()
    asyncio.get_running_loop().add_signal_handler(signal.SIGTERM, stop.set)
    server = await asyncio.start_server(handle, "127.0.0.1", 19443, ssl=context, backlog=4096)
    print("READY", flush=True)
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), 1)
        except asyncio.TimeoutError:
            pass
        values = sorted(latencies)
        result = dict(active=active, opened=opened, pings=pings, pongs=pongs, errors=errors,
                      p50_ms=values[len(values)//2] if values else 0,
                      p99_ms=values[min(len(values)-1, int(len(values)*.99))] if values else 0,
                      max_ms=values[-1] if values else 0)
        with open(sys.argv[3], "a") as output:
            output.write(json.dumps(result) + "\n")
        latencies.clear()
    server.close()
    await server.wait_closed()

asyncio.run(main())
