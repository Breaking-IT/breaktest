# Copyright 2024-2026 Breaking IT
#
# Licensed under the BreakTest Community Source License 1.0.
# You may not use this file except in compliance with that license.
# See the LICENSE file at the root of this distribution.
"""Verify real GSS tokens and report the frontend protocol for transport assertions."""
import base64
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

import gssapi


ready_requests = set()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def reply(self, status, body=b"", headers=()):
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Fixture-Protocol", self.headers.get("X-Fixture-Frontend", self.request_version))
        self.send_header("X-Fixture-Method", self.command)
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        path = urlsplit(self.path).path
        payload = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        auth = self.headers.get("Authorization", "")
        if path == "/slow-ready":
            request_id = parse_qs(urlsplit(self.path).query).get("id", [""])[0]
            self.reply(200 if request_id in ready_requests else 404)
            return
        if path == "/public":
            self.reply(200, b"public", [("X-Fixture-Auth", auth.split(" ", 1)[0] if auth else "none")])
            return
        if path == "/redirect":
            self.reply(302, headers=[("Location", "/secure")])
            return
        if path == "/basic":
            # A regression that sends Basic must be observable, not hidden by another 401.
            self.reply(400 if auth.startswith("Basic ") else 401,
                       headers=[("WWW-Authenticate", 'Basic realm="fixture"')])
            return
        if not auth.startswith("Negotiate "):
            headers = [("WWW-Authenticate", "Negotiate")]
            if path == "/mixed":
                headers.append(("WWW-Authenticate", 'Basic realm="fixture"'))
            self.reply(401, headers=headers)
            return
        try:
            context = gssapi.SecurityContext(usage="accept")
            token = context.step(base64.b64decode(auth.split(" ", 1)[1]))
            if not context.complete:
                raise ValueError("Incomplete Kerberos context")
            principal = str(context.initiator_name)
            headers = [("X-Fixture-Principal", principal)]
            if token:
                headers.append(("WWW-Authenticate", "Negotiate " + base64.b64encode(token).decode("ascii")))
            if path == "/slow":
                ready_requests.add(parse_qs(urlsplit(self.path).query).get("id", [""])[0])
                time.sleep(3)
            self.reply(200, payload if path == "/echo" else principal.encode("utf-8"), headers)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Expected when testing interruption/timeouts.
        except Exception as error:
            self.reply(403, str(error).encode("utf-8"))

    do_POST = do_GET
    do_PUT = do_GET
    do_HEAD = do_GET


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
