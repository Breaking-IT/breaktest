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
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.nio.ManagedAsyncClientConnection;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in: small, read-only runs against the two public BreakTest demo hosts. */
@EnabledIfEnvironmentVariable(named = "BREAKTEST_HTTP2_LIVE", matches = "true")
class HTTP2CoalescingLiveTest {
    private static final List<String> HOSTS = List.of("benchmart.breaktest.io", "benchmark-rust.breaktest.io");

    @Test
    void realParallelControllerSharesOneConnectionPerUserAcrossDemoHosts() throws Exception {
        TestHTTPJavaHttp3Impl.setupJMeterProperties();
        verifyRun(true);
        verifyRun(false);
    }

    private static void verifyRun(boolean coalescing) throws Exception {
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
            root.add(sampler("initial", HOSTS.get(user), coalescing));
            root.add(sampler("sequential-other-host", HOSTS.get(1 - user), coalescing));
            ParallelController parallel = new ParallelController();
            parallel.setName("six parallel requests");
            parallel.setMaxParallel(6);
            parallel.setEnabled(true);
            var branches = root.add(parallel);
            for (int request = 0; request < 6; request++) {
                branches.add(sampler("parallel-" + request, HOSTS.get(request % 2), coalescing));
            }
            root.add(sampler("tail", HOSTS.get(user), coalescing));
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
                assertEquals(coalescing ? 1 : 2, listener.connections.size(),
                        "Inspect physical HC5 connections, not just reported connect times");
                assertEquals(coalescing ? 1 : 2, listener.samples.stream()
                        .map(SampleResult::getLocalEndpoint).distinct().count(), "Results must report the same socket reuse");
                assertTrue(listener.samples.stream().noneMatch(s -> s.getLocalEndpoint().isEmpty()),
                        "Results must contain socket endpoints");
                for (SampleResult result : listener.samples) {
                    assertEquals("200", result.getResponseCode(), result.getResponseMessage());
                    assertEquals("HTTP/2", result.getProtocolVersion());
                    if (coalescing && !result.getSampleLabel().equals("initial")) {
                        assertEquals(-1, result.getConnectTime(), "Shared connection must not establish another TLS session");
                    }
                    System.out.printf("coalescing=%s user=%s sample=%s host=%s local=%s remote=%s connect=%d start=%d end=%d%n",
                            coalescing, listener.owner.get().getThreadName(), result.getSampleLabel(), result.getURL().getHost(),
                            result.getLocalEndpoint(), result.getDestinationEndpoint(), result.getConnectTime(),
                            result.getStartTime(), result.getEndTime());
                }
                var parallel = listener.samples.stream().filter(s -> s.getSampleLabel().startsWith("parallel-")).toList();
                long overlap = parallel.stream().mapToLong(sample -> parallel.stream()
                        .filter(other -> other.getStartTime() <= sample.getStartTime()
                                && other.getEndTime() > sample.getStartTime()).count()).max().orElse(0);
                assertTrue(overlap >= 2, "Parallel requests must overlap in time");
                System.out.printf("SUMMARY coalescing=%s user=%s physicalConnections=%d overlappingRequests=%d endpoints=%s%n",
                        coalescing, listener.owner.get().getThreadName(), listener.connections.size(), overlap,
                        listener.sockets);
            }
            assertTrue(listeners.get(0).connections.stream().noneMatch(listeners.get(1).connections::contains),
                    "Virtual users must never share a physical connection");
        } finally {
            carriers.stream().filter(Thread::isAlive).forEach(Thread::interrupt);
        }
    }

    private static HTTPSamplerProxy sampler(String name, String host, boolean coalescing) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName(name);
        sampler.setEnabled(true);
        sampler.setProtocol("https");
        sampler.setDomain(host);
        sampler.setPort(443);
        sampler.setPath("/");
        sampler.setMethod("GET");
        sampler.setHttpProtocol("HTTP/2");
        // Exercise existing scripts with an absent property, and an explicit opt-out.
        if (!coalescing) {
            sampler.setProperty(HTTPSamplerBase.HTTP2_COALESCING, false);
        }
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
        private final Set<ManagedAsyncClientConnection> connections = ConcurrentHashMap.newKeySet();
        private final Set<String> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicReference<Exception> failure = new AtomicReference<>();

        @Override public void sampleOccurred(SampleEvent event) {
            if (!(event.getResult() instanceof HTTPSampleResult)) {
                return;
            }
            samples.add(event.getResult());
            try {
                Field cache = HTTPHC5H2Impl.class.getDeclaredField("HTTPCLIENTS_CACHE_PER_JMETER_THREAD");
                cache.setAccessible(true);
                Map<?, ?> clients = (Map<?, ?>) ((Map<?, ?>) cache.get(null)).get(owner.get());
                synchronized (clients) {
                    for (Object client : clients.values()) {
                        for (Object connection : (Set<?>) field(field(client, "connectionManager"), "connections")) {
                            var physical = (ManagedAsyncClientConnection) connection;
                            connections.add(physical);
                            sockets.add(physical.getLocalAddress() + " -> " + physical.getRemoteAddress());
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
