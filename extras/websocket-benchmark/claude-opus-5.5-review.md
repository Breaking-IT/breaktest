I found 2 blocking defects, 3 high-priority concurrency defects and several medium/low issues. The control-character persistence, the Send+Wait core semantics and the TLS trust/alias binding look correct. The benchmark supports the platform-thread reduction but not the CPU or latency improvement claims.

Everything here comes from reading the code; I did not compile or run anything. Where I say "won't compile", that comes from comparing method signatures.

## Blocking

**1. Test sources won't compile (confirmed).** `WebSocketTlsTest.java:155,156,160` and `WebSocketSamplerTest.java:284` call `sessions.client(String, WebSocketSession, URI)`. The only method that exists is `client(String, URI)` at `WebSocketSessions.java:53`.
- **Consequence:** the `:src:protocol:http` test source set fails to compile, so no WebSocket test runs. The README's claim that tests cover mTLS, shutdown and cleanup (`README.md:74-76`) can't be true for this code.
- **Also:** test line 284 expects `client()` to reject a stale session with `IllegalStateException`. That guard does not exist, which points to finding 2.

**2. The transport lease is tracked by session name, not by session object (confirmed).**
- **Where:** `WebSocketSessions.java:77-84`. `remove(name, session)` only removes the map entry if it is that exact session. But `clients.remove(name)` removes and closes whatever lease is stored under the name, every time.
- **Trigger:** exactly what `WebSocketSamplerTest:283` does. A stale `remove("same", old)` runs after a replacement session was connected under the same name. In real use this happens with concurrent flows, e.g. a failed Connect's `catch` (`WebSocketConnectSampler.java:81-83`) running after another flow closed and reconnected the name.
- **Consequence:** the replacement's lease is closed. If it was the last reference, `client.shutdownNow()` (`WebSocketTransportPool.java:102`) kills the live replacement connection.
- **Fix:** keep the lease inside `WebSocketSession` (or in a map keyed by session object), release it in `remove` only for that session, and have `client()` require that the session is still registered.

## High

**3. Two flows can each create their own session registry (confirmed).**
- **Where:** `WebSocketSessions.current()` (`:37-38`) locks on `context.getVariables()`. In fork/parallel workers that is a separate `ParallelWorkerVariables` wrapper per worker (`JMeterThread.java:1327`). The main flow locks the parent variables. So the check-then-create isn't atomic across flows.
- **Trigger:** the first WebSocket Connect in a virtual user happens at the same time in two parallel branches, e.g. two hubs connected in parallel.
- **Consequence:** two registries are created and one is overwritten. Its sessions stay connected until the thread ends but are unreachable ("Unknown WebSocket session").
- **Fix:** lock on something stable, such as `context.getThread()`, a static lock, or a `computeIfAbsent` keyed by `JMeterThread`.

**4. Two concurrent sends on the same session abort it for everyone (confirmed).**
- **Where:** the JDK fails a second `sendText`/`sendBinary` with `IllegalStateException` while a previous send is still pending. `failure()` (`AbstractWebSocketSampler.java:83-87`) then calls `dispose()` on the active session, which aborts it (`WebSocketSendSampler.java:35-40`).
- **Trigger:** two fork/parallel flows send on the same named session at the same time.
- **Consequence:** one sample fails and the session is aborted for every flow using it.
- **Same pattern:** a malformed UTF-16 payload produces an `IllegalArgumentException` and also kills the session.
- **Fix:** serialise sends per session (e.g. chain each send on the previous send's future, or hold a per-session lock until it completes), and only dispose on timeout or I/O failure.

**5. Incoming samples go to listeners on the JDK callback thread, outside the virtual user's context (confirmed path; impact depends on which listeners are in the plan).**
- **Where:** `WebSocketConnectSampler.java:179-185` passes them to `ListenerNotifier`, which calls `TestBeanHelper.prepare` (`ListenerNotifier.java:58`) on the user's per-thread listener clones, concurrently with the user's own thread.
- **Consequence:** on the callback thread, `JMeterContextService.getContext()` is an empty context. A JSR223 Listener binds `vars`/`ctx` from it (`JSR223TestElement.java:151-153`), so `vars.put(...)` in scripts throws a NullPointerException for every incoming message. The docs (`breaktest-features.xml:112-117`) cover custom listeners, but this is a built-in one.
- **Lock held during listener calls:** results are published while holding the session lock (`WebSocketSession.java:163-165, 207, 243`). Slow listeners therefore delay the user's `socket()`, `waitForMessage()` and `cancelWait()`, which inflates measured Send times.
- **JDK 21 (uncertain magnitude):** JDK 21 is both the target and the benchmark JDK. There, virtual threads waiting on that lock pin their carrier threads; the benchmark disabled all incoming samples, so this wasn't exercised.
- **Fix:**
  - Collect results under the lock and publish after releasing it.
  - Notify with a worker context that mirrors the user (like `createParallelContext`).
  - Serialise notifications per virtual user.

## Medium

**6. TLS sessions are reused across virtual users with the same identity (confirmed; a realism issue, not an identity leak).**
- **Where:** the pooled `SSLContext` (`WebSocketTransportPool.java:52-55`) holds one session cache shared by every user with the same identity.
- **HTTP behaves differently:** it uses per-thread contexts by default (`JsseSSLManager.java:63-65, 164-184`) and resets them per iteration (`HTTPHC5Impl.java:1517`).
- **Consequences:** WebSocket handshakes resume each other's TLS sessions, which reduces server handshake load. Resumption also depends on whether leases happen to overlap: overlapping users share sessions, a lone user gets a fresh client and a full handshake.
- **Docs:** `breaktest-features.xml:141-142` only rules out reuse *across* identities.
- **Decision for you:** honour `https.sessioncontext.shared`, which would need per-user clients when false, or document the deviation.

**7. Restricted headers from scoped Header Managers fail Connect (confirmed).**
- **Where:** `WebSocketConnectSampler.java:135-137` throws for any transport-controlled header, including ones inherited from an in-scope HTTP Header Manager.
- **Trigger:** a thread-group-level manager containing `Connection: keep-alive` or `Host`, which is common in recorded or HAR-imported plans.
- **Consequence:** every Connect fails.
- **Fix:** silently drop (or debug-log) these headers when they come from scoped managers; keep the strict rejection for Connect's own header table.

**8. A dead session blocks reconnecting under the same name (confirmed; partly by design).**
- **Where:** `add()` (`WebSocketSessions.java:62-67`) rejects the name even when the existing session is already terminated.
- **Trigger:** the server disconnects, a sampler then fails, and "Start next thread loop on error" skips Close.
- **Consequence:** every later Connect fails with "already exists".
- **Iterations:** sessions also survive iterations when "Same user on each iteration" is off, while HTTP resets cookies and TLS state. The docs say persistence is intended (`:71-72`), but replacing terminated sessions automatically seems safe.

**9. The transport pool does expensive work under one global lock (confirmed code; impact unmeasured).**
- **Under the lock:** `acquire` builds the `SSLContext` and `HttpClient` inside `synchronized (CLIENTS)` (`WebSocketTransportPool.java:46-65`). This includes `TrustManagerFactory.init(null)`, which loads cacerts.
- **Many identities:** if each user has its own certificate alias, creation is serialised during ramp-up, and on JDK 21 the virtual threads waiting on that monitor pin their carriers.
- **Churn:** the last lease shuts the client down immediately (`:100-104`). A Connect/Close-per-iteration plan at low concurrency therefore rebuilds the client, selector thread and `SSLContext` (and does a full TLS handshake) every iteration.
- **Fix:** create per key outside the lock (a per-key future), and add an idle grace period or cache the `SSLContext` per identity.

**10. The binary viewer can freeze the GUI on large responses (confirmed).**
- **Where:** `RenderAsBinary.java:37` caps at `view.results.tree.max_size` *bytes* (10 MB). The hex dump is about 4.9 characters per byte, so that's roughly 49 MB of text.
- **Why it isn't caught:** body withholding only triggers on long single lines (`SamplerResultTab.java:1352-1354`), and hex lines are short.
- **Text view too:** with binary decoding enabled, the Text view decodes the entire body before truncating it (`ViewResultsFullVisualizer.java:1776-1795`), e.g. a 100 MB download.
- **Fix:** use a separate, smaller byte cap for hex, and truncate the bytes before decoding.

## Low

- **TLS protocol/cipher settings ignored (confirmed):** `https.socket.protocols` and `https.cipherSuites` are not applied to WebSocket; HTTP applies them at `LazyLayeredConnectionSocketFactoryHC5.java:43-47`. Pass them via `HttpClient.Builder.sslParameters`.
- **Round-robin certificate aliases (confirmed):** without an alias variable, every wss Connect advances the global rotation that HTTP handshakes also use (`JsseSSLManager.java:254`). A user may log in over HTTP with one certificate and connect the WebSocket with another. Recommend documenting the alias variable for mutual TLS.
- **Session looked up twice (confirmed, needs concurrent Close+Connect):** Send+Wait looks the session up twice (`WebSocketSendWaitSampler.java:49` and `WebSocketSendSampler.java:34`). If the name is replaced in between, the wait sits on the old session and the send goes to the new one. Pass the session object through.
- **Thread-end window (confirmed):** sessions are disposed after listeners' `threadFinished` and after thread counts are decremented (`JMeterThread.java:533-537`), so incoming samples can still be published in that window.
- **Executor shutdown timing (uncertain):** `executor.shutdown()` runs right after the asynchronous `client.shutdownNow()` (`WebSocketTransportPool.java:102-103`). JDK completion tasks submitted afterwards may be rejected. Waiting for `client.awaitTermination` first is safer.
- **Older readers (uncertain whether it matters):** `StringPropertyConverter` round-trips 0x1E, NUL, lone surrogates and U+FFFF correctly, and the trigger condition is right. But upstream JMeter or older BreakTest will silently load the base64 text as the literal value, since there's no version gate.
- **Dead code:** `createContextForAsyncClient()` and the 3-argument `createContext(…, true)` path (`JsseSSLManager.java:242, 273-278`) are unused.
- **Handshake cookies:** Set-Cookie from the handshake is not imported. This is documented, but it affects sticky-session load balancers.

## Benchmark claims

- **Justified:**
  - Platform threads 633 → 39 at 300 users; this is structural.
  - 2000/2000 connections succeeded and closed cleanly.
- **Weak:**
  - **CPU −29%:** one run per configuration, and per-second CPU ranges from 2.8% to 20.5% within a run.
  - **RSS −25%:** a single sample, with `-Xms256m`.
- **Not comparable:**
  - **Peak heap column:** steady heap is a sawtooth (after-2000 grows about 4.5 MiB/s, then GCs at second 17). It measures GC timing.
  - **Latency maxima:**
    - before-300's 14.56 ms is a single steady-state outlier; other seconds are about 0.5–2 ms.
    - after-300's 5.52 ms is the ramp second.
    - after-2000-repeat's 19.23 ms is the teardown second (`active: 0`).
    - Steady-state p99 is about 1 ms in both, so there's no evidence of a latency improvement.
- **Not reproducible:** the baseline code has no commit reference, and the reproduce steps only run the current code.
- **Not covered by the harness:**
  - It uses raw virtual threads, not `JMeterThread`, so the engine cleanup path is untested.
  - There are no listeners, no incoming samples and no data throughput (just 8-byte pings at about 1000/s), so findings 5 and 9 weren't exercised.
  - There is one selector thread per identity for all 2000 connections. Whether that becomes a client-side bottleneck under message load, inflating measured latency, is untested.

## Checked and fine

- Send+Wait registers the wait before sending, and the wait timeout is separate from the send timeout and isn't reset by unrelated messages.
- The expected close is flagged before `sendClose`, so Close doesn't produce an unexpected-disconnect sample.
- `onOpen` aborts if the session was already disposed.
- Cleanup survives `JMeterContext.clear` via `registerThreadCleanup`.
- Mutual-TLS alias is bound per context; the trust manager skips hostname checks, matching HTTP.
- No new HttpComponents dependency; HTTP stays on HC 5.6.1.
- Cookie and header precedence match the HTTP sampler.
- The hex/ASCII formatter itself is correct.

Not reviewed: the `StandardCookieHandler` changes (outside the scope you gave).
