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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.entity.DiscardingEntityConsumer;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.nio.support.BasicRequestConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.PreCompiler;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class SseHttp2Test extends JMeterTestCase {
    @Test
    void closeResetsOnlyItsStreamAndKeepsSharedConnectionUsable() throws Exception {
        StreamingHandler handler = new StreamingHandler();
        CountDownLatch otherEvent = new CountDownLatch(1);
        SseSamplerTest.Results results = new SseSamplerTest.Results() {
            @Override public void sampleOccurred(SampleEvent event) {
                super.sampleOccurred(event);
                if ("still open".equals(event.getResult().getResponseDataAsString())) {
                    otherEvent.countDown();
                }
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var server = H2ServerBootstrap.bootstrap().setCanonicalHostName("127.0.0.1")
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2).register("*", handler).create()) {
            server.start();
            int port = ((InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0), URIScheme.HTTP)
                    .get(3, TimeUnit.SECONDS).getAddress()).getPort();
            var group = new org.apache.jmeter.threads.ThreadGroup();
            LoopController loop = new LoopController();
            loop.setLoops(1);
            group.setSamplerController(loop);
            ListedHashTree tree = new ListedHashTree();
            var children = tree.add(group);
            children.add(results);
            children.add(request(port, "/warmup", false));
            children.add(request(port, "/first", true));
            children.add(request(port, "/second", true));
            children.add(new SseSamplerTest.Probe(() -> {
                SseCloseSampler close = new SseCloseSampler();
                close.setSessionName("/first");
                assertTrue(close.sample(null).isSuccessful());
                // HttpCore applies cancellation when the peer next sends on a receive-only stream.
                handler.first.send(": heartbeat after close\n\n");
                assertTrue(handler.first.released.await(3, TimeUnit.SECONDS), "Server must receive stream cancellation");
                assertEquals(1, handler.second.released.getCount(), "The other stream must remain open");
                handler.second.send("data: still open\n\n");
                assertTrue(otherEvent.await(3, TimeUnit.SECONDS), () -> "Second stream must still deliver: "
                        + results.samples.stream().map(r -> r.getSampleLabel() + ": " + r.getResponseMessage()).toList());
                close.setSessionName("/second");
                assertTrue(close.sample(null).isSuccessful());
                handler.second.send(": heartbeat after close\n\n");
                assertTrue(handler.second.released.await(3, TimeUnit.SECONDS));
            }, failure));
            children.add(request(port, "/ordinary", false));
            tree.traverse(new PreCompiler());
            JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier(), true);
            user.setThreadGroup(group);
            user.setThreadName("sse-h2-user");
            Thread runner = Thread.ofVirtual().start(user);
            try {
                runner.join(10000);
                assertFalse(runner.isAlive(), () -> "requests=" + handler.requests + " samples="
                        + results.samples.stream().map(r -> r.getSampleLabel() + ":" + r.getResponseMessage()).toList());
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertEquals(4, handler.requests.get());
                assertEquals(1, handler.peers.size(), "Both SSE streams and the later HTTP request must use one TCP connection");
                assertTrue(results.samples.stream().allMatch(SampleResult::isSuccessful),
                        () -> results.samples.stream().map(SampleResult::getResponseMessage).toList().toString());
                assertTrue(results.samples.stream().anyMatch(r -> "ordinary".equals(r.getResponseDataAsString())));
                assertTrue(handler.second.released.await(3, TimeUnit.SECONDS));
            } finally {
                user.stop();
                runner.interrupt();
                runner.join(2000);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void iterationResetRespectsReturningVersusNewVisitors(boolean sameUser) throws Exception {
        StreamingHandler handler = new StreamingHandler();
        CountDownLatch streamFailure = new CountDownLatch(1);
        CountDownLatch nextEvent = new CountDownLatch(1);
        SseSamplerTest.Results results = new SseSamplerTest.Results() {
            @Override public void sampleOccurred(SampleEvent event) {
                super.sampleOccurred(event);
                if (!event.getResult().isSuccessful()) {
                    streamFailure.countDown();
                }
                if ("next iteration".equals(event.getResult().getResponseDataAsString())) {
                    nextEvent.countDown();
                }
            }
        };
        var variables = new JMeterVariables() {
            @Override public boolean isSameUserOnNextIteration() { return sameUser; }
        };
        variables.putObject(JMeterThread.PACKAGE_OBJECT, new org.apache.jmeter.threads.SamplePackage(
                List.of(), List.of(results), List.of(), List.of(), List.of(), List.of(), List.of()));
        JMeterContextService.getContext().setVariables(variables);
        HTTPSamplerProxy ordinary = null;
        try (var server = H2ServerBootstrap.bootstrap().setCanonicalHostName("127.0.0.1")
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2).register("*", handler).create()) {
            server.start();
            int port = ((InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0), URIScheme.HTTP)
                    .get(3, TimeUnit.SECONDS).getAddress()).getPort();
            ordinary = request(port, "/ordinary", false);
            assertTrue(ordinary.sample().isSuccessful());
            assertTrue(request(port, "/first", true).sample().isSuccessful());
            assertTrue(results.received.await(3, TimeUnit.SECONDS));
            ordinary.testIterationStart(null);
            if (sameUser) {
                handler.first.send("data: next iteration\n\n");
                assertTrue(nextEvent.await(3, TimeUnit.SECONDS));
                assertEquals(1, streamFailure.getCount());
            } else {
                assertTrue(handler.first.released.await(3, TimeUnit.SECONDS));
                assertTrue(streamFailure.await(3, TimeUnit.SECONDS));
            }
            assertTrue(ordinary.sample().isSuccessful());
            assertEquals(sameUser ? 1 : 2, handler.peers.size(),
                    "A new visitor gets a fresh connection; a returning visitor keeps its stream and connection");
        } finally {
            SseSessions.current().close();
            if (ordinary != null) {
                ordinary.threadFinished();
            }
            JMeterContextService.getContext().clear();
        }
    }

    @Test
    void droppedHttp2ConnectionDoesNotReconnectSse() throws Exception {
        StreamingHandler handler = new StreamingHandler();
        AtomicReference<IOSession> connection = new AtomicReference<>();
        CountDownLatch failure = new CountDownLatch(1);
        SseSamplerTest.Results results = new SseSamplerTest.Results() {
            @Override public void sampleOccurred(SampleEvent event) {
                super.sampleOccurred(event);
                if (!event.getResult().isSuccessful()) {
                    failure.countDown();
                }
            }
        };
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        var pack = new org.apache.jmeter.threads.SamplePackage(List.of(), List.of(results), List.of(),
                List.of(), List.of(), List.of(), List.of());
        JMeterContextService.getContext().getVariables().putObject(JMeterThread.PACKAGE_OBJECT, pack);
        try (var server = H2ServerBootstrap.bootstrap().setCanonicalHostName("127.0.0.1")
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                .setIOSessionDecorator(session -> {
                    connection.set(session);
                    return session;
                })
                .register("*", handler).create()) {
            server.start();
            int port = ((InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0), URIScheme.HTTP)
                    .get(3, TimeUnit.SECONDS).getAddress()).getPort();
            HTTPSamplerProxy sampler = request(port, "/first", true);
            assertTrue(sampler.sample().isSuccessful());
            assertTrue(results.received.await(3, TimeUnit.SECONDS));
            connection.get().close(CloseMode.IMMEDIATE);
            assertTrue(failure.await(3, TimeUnit.SECONDS), () -> results.samples.stream()
                    .map(r -> r.getResponseCode() + ":" + r.getResponseMessage()).toList().toString());
            assertEquals(1, handler.requests.get(), "Failed SSE requests must never be replayed");
        } finally {
            SseSessions.current().close();
            JMeterContextService.getContext().clear();
        }
    }

    private static HTTPSamplerProxy request(int port, String path, boolean sse) {
        HTTPSamplerProxy sampler = sse ? new SseSampler() : new HTTPSamplerProxy();
        sampler.setName(path);
        sampler.setDomain("127.0.0.1");
        sampler.setPort(port);
        sampler.setPath(path);
        sampler.setMethod("GET");
        sampler.setHttpProtocol("HTTP/2");
        sampler.setSseSessionName(path);
        sampler.setResponseTimeout("5000");
        return sampler;
    }

    private static final class StreamingHandler implements AsyncServerRequestHandler<Message<HttpRequest, Void>> {
        final OpenBody first = new OpenBody();
        final OpenBody second = new OpenBody();
        final Set<Object> peers = ConcurrentHashMap.newKeySet();
        final AtomicInteger requests = new AtomicInteger();
        @Override public AsyncRequestConsumer<Message<HttpRequest, Void>> prepare(
                HttpRequest request, EntityDetails entity, HttpContext context) {
            return new BasicRequestConsumer<>(entity == null ? null : new DiscardingEntityConsumer<>());
        }
        @Override public void handle(Message<HttpRequest, Void> request, ResponseTrigger trigger, HttpContext context)
                throws IOException, org.apache.hc.core5.http.HttpException {
            requests.incrementAndGet();
            peers.add(HttpCoreContext.cast(context).getEndpointDetails().getRemoteAddress());
            var response = AsyncResponseBuilder.create(200);
            switch (request.getHead().getPath()) {
                case "/first" -> response.setEntity(first);
                case "/second" -> response.setEntity(second);
                default -> response.setEntity("ordinary", ContentType.TEXT_PLAIN);
            }
            trigger.submitResponse(response.build(), context);
        }
    }

    private static final class OpenBody implements AsyncEntityProducer {
        final CountDownLatch released = new CountDownLatch(1);
        final ConcurrentLinkedQueue<ByteBuffer> pending = new ConcurrentLinkedQueue<>();
        volatile DataStreamChannel channel;
        OpenBody() { send("data: hello\n\n"); }
        void send(String data) {
            pending.add(ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)));
            DataStreamChannel active = channel;
            if (active != null) {
                active.requestOutput();
            }
        }
        @Override public void produce(DataStreamChannel stream) throws IOException {
            channel = stream;
            ByteBuffer data = pending.peek();
            if (data != null) {
                stream.write(data);
                if (!data.hasRemaining()) {
                    pending.poll();
                }
            }
        }
        @Override public int available() { return pending.isEmpty() ? 0 : 1; }
        @Override public boolean isRepeatable() { return false; }
        @Override public long getContentLength() { return -1; }
        @Override public String getContentType() { return "text/event-stream"; }
        @Override public String getContentEncoding() { return null; }
        @Override public boolean isChunked() { return false; }
        @Override public Set<String> getTrailerNames() { return Set.of(); }
        @Override public void failed(Exception failure) { releaseResources(); }
        @Override public void releaseResources() { released.countDown(); }
    }
}
