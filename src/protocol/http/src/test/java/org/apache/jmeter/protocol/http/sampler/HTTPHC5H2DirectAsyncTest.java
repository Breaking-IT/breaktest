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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncServer;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.AsyncRequestConsumer;
import org.apache.hc.core5.http.nio.AsyncServerRequestHandler;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.entity.AsyncEntityProducers;
import org.apache.hc.core5.http.nio.entity.DiscardingEntityConsumer;
import org.apache.hc.core5.http.nio.support.AsyncResponseBuilder;
import org.apache.hc.core5.http.nio.support.BasicRequestConsumer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2ServerBootstrap;
import org.apache.hc.core5.reactor.ListenerEndpoint;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Requests without a body take the direct async path ({@link BufferedAsyncResponseConsumer}).
 * Its response timeout must stay a read timeout, and large bodies must be streamed.
 */
@Timeout(60)
class HTTPHC5H2DirectAsyncTest extends JMeterTestCase {

    private static final int LARGE = 6 * BufferedAsyncResponseConsumer.BUFFER_LIMIT + 1234;
    private static final byte[] LARGE_BODY = new byte[LARGE];

    private static ScheduledExecutorService timer;
    private static HttpAsyncServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        for (int i = 0; i < LARGE; i++) {
            LARGE_BODY[i] = (byte) (i * 17 + 3);
        }
        timer = Executors.newSingleThreadScheduledExecutor();
        server = H2ServerBootstrap.bootstrap().setCanonicalHostName("127.0.0.1")
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2).register("*", new Handler()).create();
        server.start();
        ListenerEndpoint endpoint = server.listen(new InetSocketAddress("127.0.0.1", 0), URIScheme.HTTP)
                .get(3, TimeUnit.SECONDS);
        port = ((InetSocketAddress) endpoint.getAddress()).getPort();
    }

    @AfterAll
    static void stopServer() throws IOException {
        server.close();
        timer.shutdownNow();
    }

    private static HTTPSampleResult sample(String path, int responseTimeoutMillis,
            HTTPSamplerBase.ResponseProcessingMode mode) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setDomain("127.0.0.1");
        sampler.setPort(port);
        sampler.setPath(path);
        sampler.setMethod("GET");
        sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        sampler.setResponseTimeout(String.valueOf(responseTimeoutMillis));
        sampler.setResponseProcessingMode(mode);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        try {
            return (HTTPSampleResult) sampler.sample();
        } finally {
            sampler.threadFinished();
            JMeterContextService.getContext().clear();
        }
    }

    @Test
    void slowlyArrivingBodyIsNotATimeout() {
        // 20 bytes, one every 100 ms: about 2 s in total, never 1 s without data.
        HTTPSampleResult result = sample("/trickle", 1000, HTTPSamplerBase.ResponseProcessingMode.STORE_COMPRESSED);
        assertTrue(result.isSuccessful(), result::getResponseMessage);
        assertEquals("x".repeat(20), result.getResponseDataAsString());
    }

    @Test
    void silentServerTimesOut() {
        HTTPSampleResult result = sample("/silent", 1000, HTTPSamplerBase.ResponseProcessingMode.STORE_COMPRESSED);
        assertFalse(result.isSuccessful());
        assertTrue(result.getResponseMessage().contains("Response timeout"), result::getResponseMessage);
    }

    @Test
    void largeBodyIsStoredIntact() {
        HTTPSampleResult result = sample("/large", 5000, HTTPSamplerBase.ResponseProcessingMode.STORE_COMPRESSED);
        assertTrue(result.isSuccessful(), result::getResponseMessage);
        assertArrayEquals(LARGE_BODY, result.getResponseData());
    }

    @Test
    void largeBodyIsCountedWhenDiscarded() {
        HTTPSampleResult result = sample("/large", 5000, HTTPSamplerBase.ResponseProcessingMode.FETCH_AND_DISCARD);
        assertTrue(result.isSuccessful(), result::getResponseMessage);
        assertEquals(0, result.getResponseData().length);
        assertEquals(LARGE, result.getBodySizeAsLong());
    }

    private static final class Handler implements AsyncServerRequestHandler<Message<HttpRequest, Void>> {
        @Override
        public AsyncRequestConsumer<Message<HttpRequest, Void>> prepare(
                HttpRequest request, EntityDetails entity, HttpContext context) {
            return new BasicRequestConsumer<>(entity == null ? null : new DiscardingEntityConsumer<>());
        }

        @Override
        public void handle(Message<HttpRequest, Void> request, ResponseTrigger trigger, HttpContext context)
                throws IOException, HttpException {
            switch (request.getHead().getPath()) {
                case "/trickle" -> trigger.submitResponse(AsyncResponseBuilder.create(200)
                        .setEntity(new TrickleProducer(20)).build(), context);
                case "/silent" -> timer.schedule(() -> {
                    try {
                        trigger.submitResponse(AsyncResponseBuilder.create(200)
                                .setEntity("late", ContentType.TEXT_PLAIN).build(), context);
                    } catch (IOException | HttpException ignored) {
                        // the client gave up
                    }
                }, 3, TimeUnit.SECONDS);
                default -> trigger.submitResponse(AsyncResponseBuilder.create(200)
                        .setEntity(AsyncEntityProducers.create(LARGE_BODY, ContentType.APPLICATION_OCTET_STREAM))
                        .build(), context);
            }
        }
    }

    /** Sends one byte every 100 ms. */
    private static final class TrickleProducer implements AsyncEntityProducer {
        private int remaining;
        private volatile boolean due = true;

        TrickleProducer(int bytes) {
            this.remaining = bytes;
        }

        @Override
        public void produce(DataStreamChannel channel) throws IOException {
            if (!due) {
                return;
            }
            due = false;
            channel.write(ByteBuffer.wrap(new byte[]{'x'}));
            if (--remaining == 0) {
                channel.endStream();
                return;
            }
            timer.schedule(() -> {
                due = true;
                channel.requestOutput();
            }, 100, TimeUnit.MILLISECONDS);
        }

        @Override
        public int available() {
            return due ? 1 : 0;
        }

        @Override
        public boolean isRepeatable() {
            return false;
        }

        @Override
        public void failed(Exception cause) {
        }

        @Override
        public long getContentLength() {
            return -1;
        }

        @Override
        public String getContentType() {
            return ContentType.TEXT_PLAIN.toString();
        }

        @Override
        public String getContentEncoding() {
            return null;
        }

        @Override
        public boolean isChunked() {
            return true;
        }

        @Override
        public Set<String> getTrailerNames() {
            return null;
        }

        @Override
        public void releaseResources() {
        }
    }
}
