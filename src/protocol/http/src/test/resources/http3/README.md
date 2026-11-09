<!--
Copyright 2024-2026 BreakTest contributors

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy of the
License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
-->

# HTTP/3 certificate integration tests

Requires Java 27, Docker, OpenSSL, and keytool. Generate disposable certificates
in a fresh directory; nothing is installed into the machine's trust store:

```sh
bash src/protocol/http/src/test/resources/http3/create-fixture.sh /tmp/breaktest-h3-fixture
docker run --rm -d --name breaktest-h3-review \
  -p 127.0.0.1:19443-19446:19443-19446/tcp \
  -p 127.0.0.1:19443-19446:19443-19446/udp \
  -v /tmp/breaktest-h3-fixture:/fixture:ro \
  caddy:2@sha256:13ba145cba2f3e28fa801994876e4c086d1b95d5aa2a520a734765ffb6b12017 \
  caddy run --config /fixture/Caddyfile
export BREAKTEST_HTTP3_FIXTURE=/tmp/breaktest-h3-fixture
export BREAKTEST_HTTP3_SELF_SIGNED_URL=https://localhost:19443/
./gradlew :src:protocol:http:test --tests '*TestHTTPJavaHttp3Impl' \
  :src:core:test --tests '*UpdateService*Test' -PjdkTestVersion=27 \
  -Djmeter.properties.jdk.internal.httpclient.disableHostnameVerification=true
./gradlew :src:protocol:http:test --tests '*HTTP3MutualTlsTest' \
  -PjdkTestVersion=27 -Djmeter.properties.jdk.internal.httpclient.disableHostnameVerification=true \
  -Djmeter.properties.jdk.net.hosts.file="$BREAKTEST_HTTP3_FIXTURE/hosts"
for retry in false true; do
  ./gradlew :src:protocol:http:test --tests '*HTTP3ScopedClientCertificateTest' \
    -PjdkTestVersion=27 -Djmeter.properties.jdk.internal.httpclient.disableHostnameVerification=true \
    -Djmeter.properties.jdk.net.hosts.file="$BREAKTEST_HTTP3_FIXTURE/hosts" \
    -Djmeter.properties.httpsampler.http3.ignore_certificate_errors="$retry"
done
./gradlew :src:protocol:http:test \
  --tests '*TestHTTPJavaHttp3Impl.embeddedClientWithoutStartupSettingRejectsWrongHostname' \
  -PjdkTestVersion=27 -Djmeter.properties.jdk.internal.httpclient.disableHostnameVerification=false
docker stop breaktest-h3-review
```

The ports serve a self-signed certificate (19443), an expired certificate
(19444), a wrong-host certificate (19445), and a trusted server requiring a
client certificate (19446). The first three assert direct H3 and H2 → H3 → H3.
Run the mTLS test separately: it initializes TLS without default client keys,
then configures JMeter's key/trust stores and disables certificate retries
before the sampler class initializes. The updater test trusts its local server
certificate and proves an ordinary JDK client succeeds while the actual updater
client rejects the hostname, with the global hostname switch enabled.

The embedded regression deliberately omits the permissive startup setting and
expects a wrong-host failure. It documents the JDK initialization requirement,
not a different sampler setting. CI runs the regular tests on Java 21 and 26,
with these Docker integration tests on 26.

The scoped-certificate suite uses `/identity` on port 19446, which reports the
client certificate subject authenticated by Caddy. Disposable `alice.p12` and
`bob.p12` stores and a combined `users.p12` store verify concurrent thread groups,
profile defaults and overrides, CSV aliases per virtual user, and alias changes
on reused clients. Every successful request asserts its protocol: direct requests
must use HTTP/3, and Alt-Svc requests must progress HTTP/2 → HTTP/3 → HTTP/3 without
changing client identity. Sending no certificate must fail the mTLS handshake.

Run that suite separately in both modes. With retries disabled it trusts the
fixture CA; with retries enabled it deliberately leaves that CA untrusted, testing
that the TLS probe and certificate-retry client preserve the scoped identity.
Both modes also verify that global alias rotation keeps a bounded client cache,
selects the next alias only when creating a new client, and preserves that alias
through certificate retries. The Java 27 sampler TLS CI job runs both modes.

## QUIC session resumption

The local `resumption` fixture uses quic-go and reports the connection ID and
server-side `tls.ConnectionState.DidResume`. Requires Go 1.26+ and Java 27; no
Docker, external endpoint, or changes to the machine trust store are needed.

```sh
(cd src/protocol/http/src/test/resources/http3/resumption && go build -o /tmp/breaktest-h3-resumption-server .)
BREAKTEST_HTTP3_RESUMPTION_SERVER=/tmp/breaktest-h3-resumption-server \
  ./gradlew :src:protocol:http:test --tests '*HTTP3SessionResumptionTest' -PjdkTestVersion=27
```

Run separately because the test temporarily changes the JVM trust-store property.
The test launches and stops the fixture automatically, using disposable certificates
and loopback ports. It covers trusted certificates and the certificate-retry path,
a forced reconnect on the same client, five parallel clients with different timeout
settings per user, two isolated users, and cleanup without disrupting the other user.
A control endpoint closes individual QUIC connections; the test retries once if a
request races that deliberate close packet. Every successful request must be HTTP/3.
The assertion requires some resumption, not reuse of a single-use ticket by all five
parallel handshakes. This test also runs in the Java 27 Sampler TLS CI job.

## HTTP/2 session resumption

The same binary also serves HTTP/2 over TLS 1.3. Run its test separately (Java 21+
supported), using the fixture built above:

```sh
BREAKTEST_HTTP3_RESUMPTION_SERVER=/tmp/breaktest-h3-resumption-server \
  ./gradlew :src:protocol:http:test --tests '*HTTP2SessionResumptionTest'
```

This checks server-observed resumption after a forced reconnect and when parallel
requests switch from a forced HTTP/2 client to a negotiating client of the same
virtual user. HTTP/2 may multiplex those requests on one new connection. It also
checks that a second user starts with a full handshake, and that cleanup drops
sessions without closing the other user's connections. The fixture announces
HTTP/2 GOAWAY before closing TLS. The Java 27 Sampler TLS CI job runs this test too.

## Default-protocol discovery

With the same fixture binary and Java 27, run:

```sh
BREAKTEST_HTTP3_RESUMPTION_SERVER=/tmp/breaktest-h3-resumption-server \
  ./gradlew :src:protocol:http:test --tests '*HTTPDefaultProtocolUpgradeTest' -PjdkTestVersion=27
```

This exercises the actual sampler factory with a blank protocol and the default
configuration: HTTP/2 over TCP then HTTP/3, HTTP/1.1 over TCP then HTTP/3, and TCP
remaining in use without Alt-Svc. Separate sequential and parallel samplers share
discovery within one user's client, a second user starts on TCP, and explicitly
selected HTTP/1.1 and HTTP/2 never upgrade. The certificate-retry path is included
because the fixture uses a fresh self-signed certificate.

HTTP/2 coalescing uses the same fixture (`coalesce` mode), with certificates for
multiple local test names and server-observed connection IDs. It covers eligible
hosts, certificate and DNS mismatches, parallel requests, user isolation,
implicit and explicit enablement, explicit opt-out, and a 421 response followed by a dedicated-connection POST retry.
Run separately from other TLS fixtures because trust-store settings are global:

```sh
BREAKTEST_HTTP3_RESUMPTION_SERVER=/tmp/breaktest-h3-resumption-server \
  ./gradlew :src:protocol:http:test --tests '*HTTP2CoalescingTest' -PjdkTestVersion=27
```

To verify the public demo hosts with BreakTest's actual virtual-user engine and
Parallel Controller (36 GET requests total, explicitly opt-in):

```sh
BREAKTEST_HTTP2_LIVE=true ./gradlew :src:protocol:http:test \
  --tests '*HTTP2CoalescingLiveTest' -PjdkTestVersion=27
```

This checks both hostname orders, six overlapping requests per user, physical HC5
connection objects and socket endpoints, isolation between two users, and the
control case with coalescing disabled. It requires `benchmart.breaktest.io` and
`benchmark-rust.breaktest.io` to resolve to the same address and present a
certificate covering both names.

HTTP/3 cross-origin reuse can be inspected separately on Java 27:

```sh
BREAKTEST_HTTP3_COALESCING_LIVE=true ./gradlew :src:protocol:http:test \
  --tests '*HTTP3CoalescingLiveTest' -PjdkTestVersion=27
```

This opt-in diagnostic sends 18 GET requests through BreakTest's virtual-user
engine and Parallel Controller. It checks both hostname orders, sequential and
parallel reuse within each origin, six overlapping requests per user, and
isolation between users. It reports whether the two origins share a connection
instead of assuming HTTP/3 coalescing is implemented. The test inspects native
QUIC connection objects and the Java client pool; only this opt-in test opens
the internal HTTP, QUIC and QUIC TLS API packages for reflection. Production code is unchanged.

On the tested Java 27 runtime, each user shares one JDK HttpClient across both
hosts but uses two distinct QUIC connections. The Java HTTP/3 pool looks up
connections by scheme, hostname and port, so the HTTP/2 coalescing setting does
not enable HTTP/3 cross-origin reuse.

The same class also sends 12 GET requests to inspect TLS ticket scope. Two users
visit the hosts in opposite orders, then open four new connections concurrently
using different client timeout keys while retaining each user's TLS context.
First visits to each hostname require full handshakes; the new same-host
connections must adopt cached TLS sessions. Physical connection identities prove
these are new connections, not reuse of open connections. The diagnostic snapshots
cached SSLSession objects before the request and compares their identity with the
negotiated session: Java's TLS 1.3 implementation adopts the ticket session upon
successful resumption. `cachedSession=true` on an unchanged connection is only
connection reuse, not another handshake. No tickets, keys or session IDs are logged.

On the tested Java 27 runtime, new same-host connections resume successfully,
including parallel requests, but tickets do not cross hostnames or virtual users.
