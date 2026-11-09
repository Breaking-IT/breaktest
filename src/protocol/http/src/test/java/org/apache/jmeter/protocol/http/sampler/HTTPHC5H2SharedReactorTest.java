/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.protocol.http.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.entity.DiscardingEntityConsumer;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.nio.support.BasicRequestConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Users share an async client and its I/O reactor, but never a connection: each keeps its
 * own pool, and the shared client closes when its last user finishes.
 */
@Timeout(30)
@Execution(ExecutionMode.SAME_THREAD)
class HTTPHC5H2SharedReactorTest extends JMeterTestCase {

    @AfterEach
    void restoreProperty() {
        JMeterUtils.getJMeterProperties().remove(HTTPHC5H2Impl.SHARED_REACTOR);
        JMeterUtils.getJMeterProperties().remove(SharedAsyncClients.IDLE_CLOSE_MS);
    }

    @Test
    void usersShareOneClientButKeepTheirOwnConnections() throws Exception {
        JMeterUtils.setProperty(HTTPHC5H2Impl.SHARED_REACTOR, "true");
        JMeterUtils.setProperty(SharedAsyncClients.IDLE_CLOSE_MS, "0");
        Peers peers = runTwoUsers(port -> assertEquals(1,
                HTTPHC5H2Impl.sharedClientCount(port), "users with the same client settings share one client"));

        assertEquals(1, peers.get("/user-a").size(), "a user keeps reusing its own HTTP/2 connection");
        assertEquals(1, peers.get("/user-b").size(), "a user keeps reusing its own HTTP/2 connection");
        assertNotEquals(peers.get("/user-a"), peers.get("/user-b"), "users must not share a connection");
        assertEquals(0, HTTPHC5H2Impl.sharedClientCount(peers.port),
                "the shared client closes when its last user finishes");
    }

    @Test
    void idleSharedClientOutlivesItsLastUserForTheGracePeriod() throws Exception {
        JMeterUtils.setProperty(HTTPHC5H2Impl.SHARED_REACTOR, "true");
        JMeterUtils.setProperty(SharedAsyncClients.IDLE_CLOSE_MS, "1500");
        int port = runTwoUsers(ignored -> { }).port;

        assertEquals(1, HTTPHC5H2Impl.sharedClientCount(port),
                "a client briefly without users stays open for the next arrival");
        long deadline = System.currentTimeMillis() + 10_000;
        while (HTTPHC5H2Impl.sharedClientCount(port) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(0, HTTPHC5H2Impl.sharedClientCount(port), "the idle client closes after the grace period");
    }

    @Test
    void disabledSharingKeepsAClientPerUser() throws Exception {
        JMeterUtils.setProperty(HTTPHC5H2Impl.SHARED_REACTOR, "false");
        Peers peers = runTwoUsers(port -> assertEquals(0,
                HTTPHC5H2Impl.sharedClientCount(port), "no shared client when sharing is disabled"));

        assertNotEquals(peers.get("/user-a"), peers.get("/user-b"), "users must not share a connection");
    }

    /**
     * Two users each send two HTTP/2 requests from their own thread; {@code whileBothActive}
     * gets the server port once both have sampled and before either finishes.
     */
    /** Requests seen per path, and the port of the server that saw them. */
    private static final class Peers extends ConcurrentHashMap<String, Set<Object>> {
        private static final long serialVersionUID = 1L;
        private int port;
    }

    private static Peers runTwoUsers(IntConsumer whileBothActive) throws Exception {
        PeerRecordingHandler handler = new PeerRecordingHandler();
        try (var server = H2ServerBootstrap.bootstrap().setCanonicalHostName("127.0.0.1")
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2).register("*", handler).create()) {
            server.start();
            int port = ((InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0), URIScheme.HTTP)
                    .get(3, TimeUnit.SECONDS).getAddress()).getPort();
            CyclicBarrier sampled = new CyclicBarrier(2, () -> whileBothActive.accept(port));
            handler.peers.port = port;
            ExecutorService users = Executors.newFixedThreadPool(2);
            try {
                Future<?> a = users.submit(() -> runUser(port, "/user-a", sampled));
                Future<?> b = users.submit(() -> runUser(port, "/user-b", sampled));
                a.get(20, TimeUnit.SECONDS);
                b.get(20, TimeUnit.SECONDS);
            } finally {
                users.shutdownNow();
            }
        }
        return handler.peers;
    }

    private static Void runUser(int port, String path, CyclicBarrier sampled) throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setDomain("127.0.0.1");
        sampler.setPort(port);
        sampler.setPath(path);
        sampler.setMethod("GET");
        sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        sampler.setResponseTimeout("5000");
        try {
            for (int i = 0; i < 2; i++) {
                HTTPSampleResult result = (HTTPSampleResult) sampler.sample();
                assertTrue(result.isSuccessful(), result::getResponseMessage);
                assertEquals("ok", result.getResponseDataAsString());
            }
            sampled.await(10, TimeUnit.SECONDS);
        } finally {
            sampler.threadFinished();
            JMeterContextService.getContext().clear();
        }
        return null;
    }

    private static final class PeerRecordingHandler implements AsyncServerRequestHandler<Message<HttpRequest, Void>> {
        final Peers peers = new Peers();

        @Override
        public AsyncRequestConsumer<Message<HttpRequest, Void>> prepare(
                HttpRequest request, EntityDetails entity, HttpContext context) {
            return new BasicRequestConsumer<>(entity == null ? null : new DiscardingEntityConsumer<>());
        }

        @Override
        public void handle(Message<HttpRequest, Void> request, ResponseTrigger trigger, HttpContext context)
                throws IOException, HttpException {
            peers.computeIfAbsent(request.getHead().getPath(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(HttpCoreContext.cast(context).getEndpointDetails().getRemoteAddress());
            trigger.submitResponse(AsyncResponseBuilder.create(200)
                    .setEntity("ok", ContentType.TEXT_PLAIN).build(), context);
        }
    }
}
