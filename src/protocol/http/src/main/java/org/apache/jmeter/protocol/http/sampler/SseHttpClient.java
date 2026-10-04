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

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.concurrent.CancellableDependency;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.support.ClassicResponseBuilder;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.support.classic.ClassicToAsyncRequestProducer;
import org.apache.hc.core5.http.nio.support.classic.SharedInputBuffer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/** Streaming facade over the shared async client. Unlike the general classic facade,
 * closing a response never drains its unbounded body, and failures wake a waiting reader. */
final class SseHttpClient extends CloseableHttpClient {
    static final String CANCELLATION = SseHttpClient.class.getName() + ".cancellation";
    private final CloseableHttpAsyncClient client;
    private final Timeout timeout;

    SseHttpClient(CloseableHttpAsyncClient client, Timeout timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    protected CloseableHttpResponse doExecute(HttpHost host, ClassicHttpRequest request, HttpContext context)
            throws IOException {
        var producer = new ClassicToAsyncRequestProducer(request, timeout);
        var consumer = new StreamConsumer(timeout, context);
        var future = client.execute(host, producer, consumer, null, context, null);
        if (request instanceof CancellableDependency dependency) {
            dependency.setDependency(() -> future.cancel(true));
        }
        try {
            producer.blockWaiting().execute();
            ClassicHttpResponse response = consumer.headers.get(timeout.toMilliseconds(), TimeUnit.MILLISECONDS);
            return CloseableHttpResponse.create(response, (closeable, mode) -> {
                try {
                    closeable.close();
                } finally {
                    if (context.getAttribute(CANCELLATION) instanceof org.apache.hc.core5.concurrent.Cancellable stream) {
                        stream.cancel();
                    }
                    future.cancel(true);
                }
            });
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("SSE request interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            future.cancel(true);
            throw new IOException("SSE response failed", failure.getCause() == null ? failure : failure.getCause());
        } catch (IOException failure) {
            future.cancel(true);
            throw failure;
        }
    }

    // The virtual user's normal client facade owns this shared client's lifecycle.
    @Override public void close() { }
    @Override public void close(CloseMode mode) { }

    private static final class StreamConsumer implements AsyncResponseConsumer<Void> {
        private final CompletableFuture<ClassicHttpResponse> headers = new CompletableFuture<>();
        private final SharedInputBuffer buffer = new SharedInputBuffer(16384);
        private final Timeout timeout;
        private volatile IOException failure;
        private volatile boolean ended;
        private FutureCallback<Void> callback;
        private final HttpContext context;

        StreamConsumer(Timeout timeout, HttpContext context) {
            this.timeout = timeout;
            this.context = context;
        }

        @Override
        public void consumeResponse(HttpResponse response, EntityDetails entity, HttpContext context,
                FutureCallback<Void> callback) {
            this.callback = callback;
            var builder = ClassicResponseBuilder.copy(response);
            if (entity != null) {
                InputStream input = new InputStream() {
                    @Override public int read() throws IOException {
                        byte[] single = new byte[1];
                        return read(single, 0, 1) == -1 ? -1 : single[0] & 0xff;
                    }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        try {
                            if (failure != null) {
                                throw failure;
                            }
                            return buffer.read(bytes, offset, length, timeout);
                        } catch (IOException error) {
                            throw failure == null ? error : failure;
                        }
                    }
                    @Override public void close() { buffer.abort(); }
                };
                builder.setEntity(new InputStreamEntity(input, entity.getContentLength(),
                        ContentType.parse(entity.getContentType()), entity.getContentEncoding()));
            }
            headers.complete(builder.build());
            if (entity == null) {
                streamEnd(List.of());
            }
        }

        @Override public void informationResponse(HttpResponse response, HttpContext context) { }
        @Override public void updateCapacity(CapacityChannel channel) throws IOException { buffer.updateCapacity(channel); }
        @Override public void consume(ByteBuffer data) { buffer.fill(data); }
        @Override public void streamEnd(List<? extends Header> trailers) {
            ended = true;
            // A fully consumed response can return its connection to the pool. Closing
            // its facade afterwards must not cancel another request using that connection.
            if (context.getAttribute(CANCELLATION) instanceof org.apache.hc.core5.concurrent.ComplexFuture<?> cancellation) {
                cancellation.completed(null);
            }
            buffer.markEndStream();
            callback.completed(null);
        }
        @Override public void failed(Exception error) {
            failure = error instanceof IOException io ? io : new IOException(error);
            headers.completeExceptionally(error);
            buffer.abort();
        }
        @Override public void releaseResources() {
            if (!ended && failure == null) {
                failed(new IOException("SSE request cancelled"));
            }
        }
    }
}
