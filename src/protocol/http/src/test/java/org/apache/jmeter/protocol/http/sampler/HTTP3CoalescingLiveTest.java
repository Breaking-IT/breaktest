/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.protocol.http.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSession;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in Java 27 diagnostic. Inspects native QUIC identities, without changing the runtime pool. */
@EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP3_COALESCING_LIVE", matches = "true")
class HTTP3CoalescingLiveTest {
    private static final List<String> HOSTS = List.of("benchmart.breaktest.io", "benchmark-rust.breaktest.io");

    @Test
    void inspectQuicReuseAcrossDemoHostsWithParallelRequestsAndIsolatedUsers() throws Exception {
        TestHTTPJavaHttp3Impl.setupJMeterProperties();
        assertTrue(Http3RuntimeSupport.isHttp3Supported(), "Run with -PjdkTestVersion=27");
        verifyRun();
    }

    private record Handshake(Object connection, boolean cachedSession) { }

    @Test
    void inspectTicketsAcrossHostsAndNewParallelConnections() throws Exception {
        TestHTTPJavaHttp3Impl.setupJMeterProperties();
        assertTrue(Http3RuntimeSupport.isHttp3Supported(), "Run with -PjdkTestVersion=27");
        var owners = new ArrayList<JMeterThread>();
        var connections = new ArrayList<Set<Object>>();
        var workers = Executors.newFixedThreadPool(4);
        try {
            for (int user = 0; user < 2; user++) {
                LoopController loop = new LoopController();
                loop.setLoops(0);
                loop.setContinueForever(false);
                ListedHashTree tree = new ListedHashTree();
                tree.add(loop);
                ThreadGroup group = new ThreadGroup();
                JMeterThread owner = new JMeterThread(tree, group, new ListenerNotifier());
                owner.setThreadGroup(group);
                owner.setThreadName("ticket-user-" + user);
                owners.add(owner);
                Set<Object> seen = ConcurrentHashMap.newKeySet();
                connections.add(seen);
                // Opposite orders test cross-host ticket selection in both directions.
                for (String host : List.of(HOSTS.get(user), HOSTS.get(1 - user))) {
                    Handshake first = handshake(owner, host, 10000);
                    assertFalse(first.cachedSession(), "First connection to each hostname uses a full handshake");
                    assertTrue(seen.add(first.connection()));
                    Handshake reused = handshake(owner, host, 10000);
                    assertEquals(first.connection(), reused.connection(), "Control must reuse the open connection");
                }
            }
            var futures = new ArrayList<java.util.concurrent.Future<Handshake>>();
            var start = new java.util.concurrent.CyclicBarrier(4);
            for (JMeterThread owner : owners) {
                for (String host : HOSTS) {
                    // A different client key forces a new connection but retains the user's TLS context.
                    futures.add(workers.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return handshake(owner, host, 11000);
                    }));
                }
            }
            for (int index = 0; index < futures.size(); index++) {
                Handshake fresh = futures.get(index).get(30, TimeUnit.SECONDS);
                assertTrue(connections.get(index / 2).add(fresh.connection()), "Must be a new QUIC connection");
                assertTrue(fresh.cachedSession(), "Same-host positive control must resume TLS on the new connection");
            }
            assertTrue(connections.get(0).stream().noneMatch(connections.get(1)::contains),
                    "Users must have separate QUIC connections");
        } finally {
            workers.shutdownNow();
            owners.forEach(JMeterThread::run);
            JMeterContextService.getContext().clear();
        }
    }

    private static Handshake handshake(JMeterThread owner, String host, int timeout) throws Exception {
        JMeterContextService.getContext().setThread(owner);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        try {
            HTTPSamplerProxy sampler = sampler("ticket-probe", host);
            sampler.setConnectTimeout(Integer.toString(timeout));
            Field cache = HTTPJavaHttp3Impl.class.getDeclaredField("HTTPCLIENTS_CACHE_PER_JMETER_THREAD");
            cache.setAccessible(true);
            Map<?, ?> existing = (Map<?, ?>) ((Map<?, ?>) cache.get(null)).get(owner);
            Set<SSLSession> tickets = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            if (existing != null) {
                synchronized (existing) {
                    for (Object state : existing.values()) {
                        var context = ((java.net.http.HttpClient) field(state, "client")).sslContext().getClientSessionContext();
                        var ids = context.getIds();
                        while (ids.hasMoreElements()) {
                            SSLSession ticket = context.getSession(ids.nextElement());
                            if (ticket != null) {
                                tickets.add(ticket);
                            }
                        }
                    }
                }
            }
            SampleResult result = sampler.sample();
            assertEquals("200", result.getResponseCode(), result.getResponseMessage());
            assertEquals("HTTP/3", result.getProtocolVersion());
            Map<?, ?> clients = (Map<?, ?>) ((Map<?, ?>) cache.get(null)).get(owner);
            Object quic = null;
            synchronized (clients) {
                for (Object state : clients.values()) {
                    java.net.http.HttpClient client = (java.net.http.HttpClient) field(state, "client");
                    if (client.connectTimeout().orElseThrow().toMillis() != timeout) {
                        continue;
                    }
                    Object pool = field(field(field(client, "impl"), "client3"), "connections");
                    for (String poolName : List.of("advertised", "unadvertised")) {
                        Object http3 = ((Map<?, ?>) field(pool, poolName)).get("https:" + host + ":443");
                        if (http3 != null) {
                            quic = field(http3, "quicConnection");
                        }
                    }
                }
            }
            assertTrue(quic != null, "The request must have an inspectable QUIC connection");
            Object engine = Class.forName("jdk.internal.net.http.quic.QuicConnection")
                    .getMethod("getTLSEngine").invoke(quic);
            SSLSession session = (SSLSession) Class.forName("jdk.internal.net.quic.QuicTLSEngine")
                    .getMethod("getSession").invoke(engine);
            // TLS 1.3 adopts the cached ticket session object when the peer accepts its PSK.
            // SSLSessionImpl.isSessionResumption is only set by the TLS 1.2 path.
            boolean cachedSession = tickets.contains(session);
            System.out.printf("TICKET user=%s host=%s timeout=%d connection=%x cachedSession=%s%n",
                    owner.getThreadName(), host, timeout, System.identityHashCode(quic), cachedSession);
            return new Handshake(quic, cachedSession);
        } finally {
            JMeterContextService.getContext().clear();
        }
    }

    private static void verifyRun() throws Exception {
        var listeners = new ArrayList<ConnectionListener>();
        var carriers = new ArrayList<Thread>();
        for (int user = 0; user < 2; user++) {
            LoopController loop = new LoopController();
            loop.setLoops(1);
            loop.setContinueForever(false);
            loop.setEnabled(true);
            ListedHashTree tree = new ListedHashTree();
            var root = tree.add(loop);
            ConnectionListener listener = new ConnectionListener();
            listeners.add(listener);
            root.add(listener);
            root.add(sampler("initial", HOSTS.get(user)));
            root.add(sampler("sequential-other-host", HOSTS.get(1 - user)));
            ParallelController parallel = new ParallelController();
            parallel.setName("six parallel requests");
            parallel.setMaxParallel(6);
            parallel.setEnabled(true);
            var branches = root.add(parallel);
            for (int request = 0; request < 6; request++) {
                branches.add(sampler("parallel-" + request, HOSTS.get(request % 2)));
            }
            root.add(sampler("tail", HOSTS.get(user)));
            ThreadGroup group = new ThreadGroup();
            group.setName("live-coalescing");
            group.setNumThreads(2);
            JMeterThread owner = new JMeterThread(tree, group, new ListenerNotifier());
            owner.setThreadGroup(group);
            owner.setThreadName("live-user-" + user);
            owner.setThreadNum(user);
            listener.owner.set(owner);
            carriers.add(new Thread(owner, owner.getThreadName()));
        }
        carriers.forEach(Thread::start);
        try {
            for (Thread carrier : carriers) {
                carrier.join(60_000);
                assertFalse(carrier.isAlive(), "The live run must finish");
            }
            for (ConnectionListener listener : listeners) {
                assertNull(listener.failure.get(), "Connection inspection must succeed");
                assertEquals(9, listener.samples.size(), "All sequential and parallel samplers must run");
                assertEquals(1, listener.clients.size(), "Both origins must use the same JDK client per user");
                assertTrue(listener.connections.size() >= 1 && listener.connections.size() <= 2,
                        "At most one QUIC connection per origin after sequential warmup");
                for (String host : HOSTS) {
                    assertEquals(1, listener.byHost.get(host).size(), "Sequential and parallel requests reuse each origin's connection");
                }
                for (SampleResult result : listener.samples) {
                    assertEquals("200", result.getResponseCode(), result.getResponseMessage());
                    assertEquals("HTTP/3", result.getProtocolVersion());
                    System.out.printf("user=%s sample=%s host=%s protocol=%s status=%s start=%d end=%d%n",
                            listener.owner.get().getThreadName(), result.getSampleLabel(), result.getURL().getHost(),
                            result.getProtocolVersion(), result.getResponseCode(), result.getStartTime(), result.getEndTime());
                }
                var parallel = listener.samples.stream().filter(s -> s.getSampleLabel().startsWith("parallel-")).toList();
                long overlap = parallel.stream().mapToLong(sample -> parallel.stream()
                        .filter(other -> other.getStartTime() <= sample.getStartTime()
                                && other.getEndTime() > sample.getStartTime()).count()).max().orElse(0);
                assertTrue(overlap >= 2, "Parallel requests must overlap in time");
                System.out.printf("SUMMARY user=%s jdkClients=%d quicConnections=%d overlappingRequests=%d origins=%s%n",
                        listener.owner.get().getThreadName(), listener.clients.size(), listener.connections.size(), overlap,
                        listener.labels);
            }
            assertTrue(listeners.get(0).clients.stream().noneMatch(listeners.get(1).clients::contains),
                    "Virtual users must never share the JDK client pool");
            assertTrue(listeners.get(0).connections.stream().noneMatch(listeners.get(1).connections::contains),
                    "Virtual users must never share a physical connection");
        } finally {
            carriers.stream().filter(Thread::isAlive).forEach(Thread::interrupt);
        }
    }

    private static HTTPSamplerProxy sampler(String name, String host) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName(name);
        sampler.setEnabled(true);
        sampler.setProtocol("https");
        sampler.setDomain(host);
        sampler.setPort(443);
        sampler.setPath("/");
        sampler.setMethod("GET");
        sampler.setHttpProtocol("HTTP/3");
        sampler.setProperty(HTTPSamplerBase.HTTP3_UPGRADE, true);
        sampler.setConnectTimeout("10000");
        sampler.setResponseTimeout("15000");
        return sampler;
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static final class ConnectionListener extends AbstractTestElement implements SampleListener {
        private static final long serialVersionUID = 1L;
        private final AtomicReference<JMeterThread> owner = new AtomicReference<>();
        private final List<SampleResult> samples = new CopyOnWriteArrayList<>();
        private final Set<Object> connections = ConcurrentHashMap.newKeySet();
        private final Set<Object> clients = ConcurrentHashMap.newKeySet();
        private final Map<String, Set<Object>> byHost = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> labels = new ConcurrentHashMap<>();
        private final AtomicReference<Exception> failure = new AtomicReference<>();

        @Override public void sampleOccurred(SampleEvent event) {
            if (!(event.getResult() instanceof HTTPSampleResult)) {
                return;
            }
            samples.add(event.getResult());
            try {
                Field cache = HTTPJavaHttp3Impl.class.getDeclaredField("HTTPCLIENTS_CACHE_PER_JMETER_THREAD");
                cache.setAccessible(true);
                Map<?, ?> clients = (Map<?, ?>) ((Map<?, ?>) cache.get(null)).get(owner.get());
                synchronized (clients) {
                    for (Object state : clients.values()) {
                        Object client = field(state, "client");
                        this.clients.add(client);
                        Object pool = field(field(field(client, "impl"), "client3"), "connections");
                        String host = event.getResult().getURL().getHost();
                        String key = "https:" + host + ":443";
                        for (String poolName : List.of("advertised", "unadvertised")) {
                            Object http3 = ((Map<?, ?>) field(pool, poolName)).get(key);
                            if (http3 != null) {
                                Object quic = field(http3, "quicConnection");
                                connections.add(quic);
                                byHost.computeIfAbsent(host, ignored -> ConcurrentHashMap.newKeySet()).add(quic);
                                labels.computeIfAbsent(host, ignored -> ConcurrentHashMap.newKeySet())
                                        .add(field(http3, "dbgTag") + " QUIC@" + Integer.toHexString(System.identityHashCode(quic)));
                            }
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                failure.compareAndSet(null, e);
            }
        }
        @Override public void sampleStarted(SampleEvent event) { }
        @Override public void sampleStopped(SampleEvent event) { }
    }
}
