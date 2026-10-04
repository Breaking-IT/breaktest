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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.PreCompiler;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

@Timeout(20)
class SseSamplerTest extends JMeterTestCase {
    @AfterEach
    void cleanup() {
        if (JMeterContextService.getContext().getVariables() != null) {
            SseSessions.current().close();
        }
        JMeterContextService.getContext().clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HTTP/1.1", "cleanup", "uncounted", "prefix", "fixed"})
    void streamReturnsAtHeadersAndHandlerCanMakeHttpRequestWithCookies(String protocol) throws Exception {
        JMeterUtils.setLocale(java.util.Locale.ENGLISH);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        CountDownLatch mainContinued = new CountDownLatch(1);
        CountDownLatch acted = new CountDownLatch(1);
        CountDownLatch disconnect = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> cookie = new AtomicReference<>();
        CountDownLatch actionReported = new CountDownLatch(1);
        Results results = new Results() {
            @Override public void sampleOccurred(SampleEvent event) {
                super.sampleOccurred(event);
                if ("ok".equals(event.getResult().getResponseDataAsString())) {
                    actionReported.countDown();
                }
            }
        };
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/events", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().add("Set-Cookie", "token=received; Path=/");
            exchange.sendResponseHeaders(200, 0);
            try {
                assertTrue(mainContinued.await(5, TimeUnit.SECONDS), "Main flow must continue before any event arrives");
                var out = exchange.getResponseBody();
                for (String chunk : List.of(": keepalive\r\n\r\n", "event: ready\nid: evt-1\ndata: page/", "42\n\n")) {
                    out.write(chunk.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                assertTrue(acted.await(5, TimeUnit.SECONDS), "Matching child must act while the stream is open");
                while (true) {
                    out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(20);
                }
            } catch (java.io.IOException expectedClose) {
                disconnect.countDown();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                exchange.close();
            }
        });
        server.createContext("/page/42", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
            exchange.close();
            acted.countDown();
        });
        server.start();
        try {
            var group = new org.apache.jmeter.threads.ThreadGroup();
            LoopController loop = new LoopController();
            loop.setLoops(1);
            group.setSamplerController(loop);
            ListedHashTree tree = new ListedHashTree();
            var children = tree.add(group);
            children.add(results);
            CookieManager cookies = new CookieManager();
            cookies.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.gui.CookiePanel");
            cookies.testStarted();
            children.add(cookies);
            HTTPSamplerProxy open = request(server, "/events", true);
            open.setName("open events");
            open.setHttpProtocol("HTTP/1.1".equals(protocol) ? protocol : "");
            if ("prefix".equals(protocol) || "fixed".equals(protocol)) {
                open.setProperty(HTTPSamplerProxy.SSE_SAMPLE_NAME, "prefix".equals(protocol) ? "Notifications / " : "Notifications");
                open.setProperty(HTTPSamplerProxy.SSE_NAME_MODE,
                        "fixed".equals(protocol) ? SseSampler.FIXED_NAME : SseSampler.EVENT_NAME);
            }
            open.setProperty(HTTPSamplerProxy.SSE_COUNT, !"uncounted".equals(protocol));
            SseMatchController match = new SseMatchController();
            match.setEventName("ready");
            match.setMatchMode(SseMatchController.REGEX);
            match.setMatchValue("page/\\d+");
            match.setSaveMessageVariable("eventData");
            children.add(open).add(match).add(request(server, "/${eventData}"));
            children.add(new Probe(() -> {
                mainContinued.countDown();
                assertTrue(acted.await(5, TimeUnit.SECONDS));
                assertTrue(actionReported.await(5, TimeUnit.SECONDS), "Wait for the handler's sample before closing its flow");
            }, failure));
            SseCloseSampler close = new SseCloseSampler();
            close.setName("close events");
            if (!"cleanup".equals(protocol)) {
                children.add(close);
            }
            tree.traverse(new PreCompiler());
            JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier(), true);
            user.setThreadGroup(group);
            user.setThreadName("sse-user");
            Thread runner = Thread.ofVirtual().start(user);
            try {
                runner.join(10000);
                assertFalse(runner.isAlive(), "Virtual user and handlers should finish");
            } finally {
                user.stop();
                runner.interrupt();
                runner.join(2000);
            }
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals("text/event-stream", accept.get());
            assertEquals("token=received", cookie.get());
            assertTrue(disconnect.await(3, TimeUnit.SECONDS), "Close must release the live stream");
            assertTrue(results.samples.stream().allMatch(SampleResult::isSuccessful),
                    () -> results.samples.stream().map(r -> r.getSampleLabel() + ": " + r.getResponseMessage()).toList().toString());
            assertEquals(!"uncounted".equals(protocol), results.samples.stream().anyMatch(
                    r -> "page/42".equals(r.getResponseDataAsString()) && r.getResponseHeaders().contains("evt-1")));
            if (!"uncounted".equals(protocol)) {
                String expectedName = "fixed".equals(protocol) ? "Notifications"
                        : "prefix".equals(protocol) ? "Notifications / ready" : "open events / ready";
                assertTrue(results.samples.stream().anyMatch(r -> expectedName.equals(r.getSampleLabel())
                        && "ready".equals(r.getResponseMessage()) && "page/42".equals(r.getResponseDataAsString())));
            }
            assertTrue(results.samples.stream().anyMatch(r -> "ok".equals(r.getResponseDataAsString())),
                    "Message Match HTTP actions must still produce their own samples");
            assertTrue(results.samples.stream().anyMatch(r -> "open events".equals(r.getSampleLabel())
                    && r.getResponseHeaders().toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 401, 503})
    void preservesRejectedResponse(int status) throws Exception {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wrong", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            HTTPSamplerProxy sampler = request(server, "/wrong", true);
            SampleResult result = sampler.sample();
            assertFalse(result.isSuccessful());
            assertEquals(Integer.toString(status), result.getResponseCode());
            assertEquals("{}", result.getResponseDataAsString());
            if (status == 200) {
                assertTrue(result.getResponseMessage().contains("text/event-stream"), result::getResponseMessage);
            }
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HTTP/1.1"})
    void reusesExistingHttpConnectionAndLeavesOtherConnectionsAliveOnClose(String protocol) throws Exception {
        var context = JMeterContextService.getContext();
        context.setVariables(new JMeterVariables());
        Results results = new Results();
        var pack = new org.apache.jmeter.threads.SamplePackage(List.of(), List.of(results), List.of(),
                List.of(), List.of(), List.of(), List.of());
        context.getVariables().putObject(JMeterThread.PACKAGE_OBJECT, pack);
        var peers = new java.util.concurrent.ConcurrentHashMap<String, InetSocketAddress>();
        CountDownLatch disconnected = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            peers.put(path, exchange.getRemoteAddress());
            if ("/events".equals(path)) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try {
                    exchange.getResponseBody().write("data: hello\n\n".getBytes(StandardCharsets.UTF_8));
                    while (true) {
                        exchange.getResponseBody().write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                        Thread.sleep(20);
                    }
                } catch (java.io.IOException expectedClose) {
                    disconnected.countDown();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            } else {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
                exchange.close();
            }
        });
        server.start();
        HTTPSamplerProxy ordinary = request(server, "/warmup");
        ordinary.setHttpProtocol(protocol);
        ordinary.setUseKeepAlive(true);
        try {
            assertTrue(ordinary.sample().isSuccessful());
            HTTPSamplerProxy events = request(server, "/events", true);
            events.setHttpProtocol(protocol);
            events.setUseKeepAlive(true);
            assertTrue(events.sample().isSuccessful());
            assertTrue(results.received.await(3, TimeUnit.SECONDS));
            assertEquals(peers.get("/warmup"), peers.get("/events"), "SSE must reuse the existing idle HTTP connection");
            ordinary.setPath("/parallel");
            assertTrue(ordinary.sample().isSuccessful());
            new SseCloseSampler().sample(null);
            assertTrue(disconnected.await(3, TimeUnit.SECONDS));
            ordinary.setPath("/after-close");
            assertTrue(ordinary.sample().isSuccessful());
            assertEquals(peers.get("/parallel"), peers.get("/after-close"), "Closing SSE must leave the other pooled connection open");
        } finally {
            SseSessions.current().close();
            ordinary.threadFinished();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void stoppingPendingOpenUnblocksTheSampler() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/events", exchange -> {
            requested.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        HTTPSamplerProxy sampler = request(server, "/events", true);
        sampler.setResponseTimeout("0");
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> {
                JMeterContextService.getContext().setVariables(new JMeterVariables());
                try {
                    return sampler.sample();
                } finally {
                    SseSessions.current().close();
                    JMeterContextService.getContext().clear();
                }
            });
            assertTrue(requested.await(3, TimeUnit.SECONDS));
            assertTrue(sampler.interrupt());
            assertFalse(pending.get(3, TimeUnit.SECONDS).isSuccessful());
        } finally {
            release.countDown();
            sampler.threadFinished();
            server.stop(0);
        }
    }

    @Test
    void receivesEventsOverActualHttp2() throws Exception {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        Results results = new Results();
        var pack = new org.apache.jmeter.threads.SamplePackage(List.of(), List.of(results), List.of(),
                List.of(), List.of(), List.of(), List.of());
        JMeterContextService.getContext().getVariables().putObject(JMeterThread.PACKAGE_OBJECT, pack);
        try (var server = org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap.bootstrap()
                .setCanonicalHostName("127.0.0.1")
                .setVersionPolicy(org.apache.hc.core5.http2.HttpVersionPolicy.FORCE_HTTP_2)
                .register("*", new EventHandler()).create()) {
            server.start();
            var endpoint = server.listen(new InetSocketAddress("127.0.0.1", 0), org.apache.hc.core5.http.URIScheme.HTTP)
                    .get(3, TimeUnit.SECONDS);
            HTTPSamplerProxy sampler = new SseSampler();
            sampler.setName("h2 events");
            sampler.setDomain("127.0.0.1");
            sampler.setPort(((InetSocketAddress) endpoint.getAddress()).getPort());
            sampler.setPath("/events");
            sampler.setMethod("GET");
            sampler.setHttpProtocol("HTTP/2");
            var result = (org.apache.jmeter.protocol.http.sampler.HTTPSampleResult) sampler.sample();
            try {
                assertTrue(result.isSuccessful(), result::getResponseMessage);
                assertEquals("HTTP/2", result.getProtocolVersion());
                assertTrue(results.received.await(3, TimeUnit.SECONDS));
                assertEquals("hello", results.samples.get(0).getResponseDataAsString());
            } finally {
                sampler.threadFinished();
            }
        }
    }

    private static class EventHandler implements org.apache.hc.core5.http.nio.AsyncServerRequestHandler<
            org.apache.hc.core5.http.Message<org.apache.hc.core5.http.HttpRequest, Void>> {
        @Override
        public org.apache.hc.core5.http.nio.AsyncRequestConsumer<
                org.apache.hc.core5.http.Message<org.apache.hc.core5.http.HttpRequest, Void>> prepare(
                org.apache.hc.core5.http.HttpRequest request, org.apache.hc.core5.http.EntityDetails entity,
                org.apache.hc.core5.http.protocol.HttpContext context) {
            return new org.apache.hc.core5.http.nio.support.BasicRequestConsumer<>(
                    entity == null ? null : new org.apache.hc.core5.http.nio.entity.DiscardingEntityConsumer<>());
        }
        @Override
        public void handle(org.apache.hc.core5.http.Message<org.apache.hc.core5.http.HttpRequest, Void> request,
                ResponseTrigger trigger, org.apache.hc.core5.http.protocol.HttpContext context)
                throws java.io.IOException, org.apache.hc.core5.http.HttpException {
            trigger.submitResponse(org.apache.hc.core5.http.nio.support.AsyncResponseBuilder.create(200)
                    .setEntity("data: hello\n\n", org.apache.hc.core5.http.ContentType.create("text/event-stream", StandardCharsets.UTF_8))
                    .build(), context);
        }
    }

    private static HTTPSamplerProxy request(HttpServer server, String path) {
        return request(server, path, false);
    }

    private static HTTPSamplerProxy request(HttpServer server, String path, boolean sse) {
        HTTPSamplerProxy sampler = sse ? new SseSampler() : new HTTPSamplerProxy();
        sampler.setName(path);
        sampler.setDomain("127.0.0.1");
        sampler.setPort(server.getAddress().getPort());
        sampler.setPath(path);
        sampler.setMethod("GET");
        sampler.setConnectTimeout("3000");
        sampler.setResponseTimeout("3000");
        return sampler;
    }

    @FunctionalInterface
    interface Action { void run() throws Exception; }

    static class Probe extends AbstractSampler implements NoThreadClone {
        private final Action action;
        private final AtomicReference<Throwable> failure;
        Probe(Action action, AtomicReference<Throwable> failure) {
            this.action = action;
            this.failure = failure;
        }
        @Override public SampleResult sample(Entry entry) {
            SampleResult result = new SampleResult();
            result.sampleStart();
            try {
                action.run();
                result.setSuccessful(true);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                result.setSuccessful(false);
            }
            result.sampleEnd();
            return result;
        }
    }

    static class Results extends AbstractTestElement implements SampleListener, NoThreadClone {
        final List<SampleResult> samples = new CopyOnWriteArrayList<>();
        final CountDownLatch received = new CountDownLatch(1);
        @Override public void sampleOccurred(SampleEvent event) {
            samples.add(event.getResult());
            received.countDown();
        }
        @Override public void sampleStarted(SampleEvent event) { }
        @Override public void sampleStopped(SampleEvent event) { }
    }
}
