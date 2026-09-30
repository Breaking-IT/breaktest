# Local Kerberos integration tests

From the repository root, run:

```sh
src/protocol/http/src/test/resources/kerberos/run-live-test.sh
# Also exercise the HTTP/3-capable runtime when JDK 27 is installed:
src/protocol/http/src/test/resources/kerberos/run-live-test.sh -PjdkTestVersion=27
```

Requires Docker, curl, openssl, and the project's normal Java/Gradle environment.
The first run downloads Debian packages for the KDC image and the `caddy:2` image.
No corporate account, DNS change, host Kerberos configuration, or existing ticket
cache is used. All usernames, passwords, certificates, and tickets are disposable.

The runner starts an MIT KDC and a Python GSSAPI HTTP acceptor, plus a Caddy HTTPS
frontend supporting HTTP/1.1 and HTTP/2. All published ports bind to loopback only.
The acceptor verifies real Kerberos tickets, returns the authenticated principal,
and reports the protocol received by the frontend. Thus successful authentication
cannot accidentally be confused with successful HTTP/2 negotiation.

Containers and temporary configuration are removed on exit; images remain cached.
Without `BREAKTEST_KERBEROS_FIXTURE`, Gradle skips the live cases. The runner supplies
that variable and tracks its path as a Gradle test input. Ordinary unit/regression
tests still run without Docker.

## Coverage

`KerberosProtocolMatrixTest` saves and reloads actual JMX archives, then runs them
through `StandardJMeterEngine` with two concurrent users and two iterations. It
crosses HTTP/HTTPS with these script configurations:

- Protocol property absent, or explicitly blank.
- Fixed `HTTP/1.1` and `HTTP/2`.
- Legacy `HTTP/2.0`, `HTTP 2.0`, and `HTTP/2 preferred` values.
- Protocol inherited from HTTP Request Defaults, including blank sampler fields.
- Explicit sampler protocol overriding conflicting defaults.
- Protocol supplied by a variable on the sampler or in defaults.

Authenticated requests verify the principal and HTTP/1.1 fallback. Unauthenticated
HTTPS control requests verify real HTTP/2 negotiation (or HTTP/1.1 when selected),
so the tests also check that defaults and variables actually take effect.

Additional live cases cover:

- Changing protocols on one sampler, including variable changes between iterations.
- Switching users and auth scopes without reusing another identity or sending auth
  outside the matching scope.
- Repeated requests, keep-alive, concurrent users, and auth clearing between loops.
- Negotiate-only, mixed Negotiate/Basic, Basic-only, missing auth, wrong passwords,
  recovery after a failed login, and HTTP/1.1-only server rejection of forced h2.
- POST/PUT body replay, HEAD, automatic redirects, and followed redirects.
- Response timeouts, interruption after ticket verification, and recovery.
- HTTP/3-only rejection and the runtime-dependent sampler-factory fallback.

`TestKerberosAuthentication` retains the earlier credential-scope, login-failure,
no-Basic-fallback, and compatible HTTP/3 fallback tests. The ordinary
`TestHTTPHC5Impl` suite also verifies that one child user's teardown cannot close
another user's in-flight connection.

These tests exercise MIT Kerberos/SPNEGO. They do not emulate Active Directory,
cross-realm trust, delegation, enterprise proxies, or Kerberos over native HTTP/2.

## Application configuration

On the matching HTTP Authorization Manager row, select `KERBEROS`. The default
remains `BASIC`. Earlier matching URL rows take precedence. Configure the JVM
properties `java.security.krb5.conf` and `java.security.auth.login.config` for the
actual realm and JAAS login module, then restart. The default JAAS entry name is
`JMeter` (`kerberos_jaas_application`). The bundled `bin` files are examples.

Kerberos requests use the synchronous HTTP/1.1 transport so ticket acquisition and
HTTP authentication execute under the same JAAS Subject. This fallback also applies
when the sampler selects HTTP/2. Other requests retain normal protocol selection.
Failed JAAS login produces an explicit sample error; Kerberos credentials cannot
answer Basic, Digest, or NTLM challenges. On an HTTP/3-capable JDK, HTTP/3-only mode
retains its explicit error when a fallback is needed.
