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

package org.apache.jmeter.protocol.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.PreCompiler;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sun.net.httpserver.HttpServer;

@Timeout(20)
class SseSessionPolicyTest extends JMeterTestCase {
    @AfterEach
    void cleanup() {
        if (JMeterContextService.getContext().getVariables() != null) {
            SseSessions.current().close();
        }
        JMeterContextService.getContext().clear();
    }

    @ParameterizedTest
    @CsvSource({"Close and reconnect,true,2,0", "Reuse if connected,true,1,0", "Fail if exists,true,1,1",
            "Close and reconnect,false,2,0", "Reuse if connected,false,2,0", "Fail if exists,false,2,0"})
    void policyAppliesAcrossIterationsAndNewUsersAlwaysStartFresh(String action, boolean sameUser,
            int expectedRequests, int expectedFailures) throws Exception {
        try (var fixture = new Server()) {
            var results = new SseSamplerTest.Results();
            var failure = new AtomicReference<Throwable>();
            var group = new org.apache.jmeter.threads.ThreadGroup();
            var loop = new LoopController();
            loop.setLoops(2);
            group.setSamplerController(loop);
            var tree = new ListedHashTree();
            var children = tree.add(group);
            children.add(results);
            var request = fixture.request("/events");
            request.setName("open events");
            request.setSseExistingSessionAction(action);
            children.add(request);
            children.add(new SseSamplerTest.Probe(() ->
                    assertTrue(results.received.await(3, TimeUnit.SECONDS)), failure));
            tree.traverse(new PreCompiler());
            var user = new JMeterThread(tree, group, new ListenerNotifier(), sameUser);
            user.setThreadGroup(group);
            user.setThreadName("sse-iteration-policy");
            Thread runner = Thread.ofVirtual().start(user);
            try {
                runner.join(10000);
                assertFalse(runner.isAlive());
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertEquals(expectedRequests, fixture.requests.get());
                assertEquals(expectedFailures, results.samples.stream().filter(r -> !r.isSuccessful()).count(),
                        () -> results.samples.stream().map(SampleResult::getResponseMessage).toList().toString());
                assertEquals(2, results.samples.stream().filter(r -> "open events".equals(r.getSampleLabel())).count());
            } finally {
                user.stop();
                runner.interrupt();
                runner.join(2000);
            }
        }
    }

    @Test
    void reuseKeepsOriginalRequestAndFailLeavesItOpenUntilExplicitClose() throws Exception {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        try (var fixture = new Server()) {
            var request = fixture.request("/events");
            assertTrue(request.sample().isSuccessful());
            request.setPath("/different-url");
            request.setSseExistingSessionAction(SseSampler.REUSE);
            assertTrue(request.sample().getResponseMessage().contains("reused"));
            assertEquals(1, fixture.requests.get(), "Reuse must not send the changed request");
            request.setSseExistingSessionAction(SseSampler.FAIL);
            assertFalse(request.sample().isSuccessful());
            request.setSseExistingSessionAction(SseSampler.REUSE);
            assertTrue(request.sample().getResponseMessage().contains("reused"));
            assertEquals(1, fixture.requests.get(), "Fail must leave the existing stream untouched");
            new SseCloseSampler().sample(null);
            request.setSseExistingSessionAction(SseSampler.FAIL);
            assertTrue(request.sample().isSuccessful());
            assertEquals(2, fixture.requests.get(), "Explicit close removes the named session");
        }
    }

    @Test
    void reuseReconnectsAfterEofButFailStillRejectsTheRegisteredSession() throws Exception {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        try (var fixture = new Server()) {
            var request = fixture.request("/finite");
            var ended = new SseSession(request, List.of());
            SseSessions.current().replace("sse", ended);
            assertTrue(ended.open().isSuccessful());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ended.isOpen() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(ended.isOpen());
            request.setSseExistingSessionAction(SseSampler.FAIL);
            assertFalse(request.sample().isSuccessful());
            request.setSseExistingSessionAction(SseSampler.REUSE);
            request.setPath("/events");
            assertTrue(request.sample().isSuccessful());
            assertEquals(2, fixture.requests.get());
        }
    }

    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger requests = new AtomicInteger();

        Server() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try {
                    exchange.getResponseBody().write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    while (!"/finite".equals(exchange.getRequestURI().getPath())) {
                        exchange.getResponseBody().write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                        Thread.sleep(20);
                    }
                } catch (java.io.IOException expectedClose) {
                    // Closing or replacing a stream releases only that HTTP request.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        SseSampler request(String path) {
            var request = new SseSampler();
            request.setDomain("127.0.0.1");
            request.setPort(server.getAddress().getPort());
            request.setProtocol("http");
            request.setMethod("GET");
            request.setPath(path);
            request.setHttpProtocol("HTTP/1.1");
            request.setResponseTimeout("3000");
            return request;
        }

        @Override public void close() {
            if (JMeterContextService.getContext().getVariables() != null) {
                SseSessions.current().close();
            }
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
