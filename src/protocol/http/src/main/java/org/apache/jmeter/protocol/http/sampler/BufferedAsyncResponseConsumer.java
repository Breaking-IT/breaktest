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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.support.BasicRequestProducer;
import org.apache.hc.core5.http.nio.support.classic.SharedInputBuffer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.ByteArrayBuffer;
import org.apache.hc.core5.util.Timeout;
import org.apache.jmeter.samplers.SampleResult;

/**
 * Sends a request without a body straight through the async client: a response of up to
 * {@link #BUFFER_LIMIT} bytes is collected on the I/O thread and handed over as an ordinary
 * classic response, so the sampler's response handling stays unchanged without the
 * classic-to-async adaptor's stream pipe and its extra I/O cycles per request.
 * <p>
 * A larger body is streamed instead: once the buffered part reaches the limit, the virtual user
 * gets the response and reads the rest through a flow-controlled pipe, so memory stays bounded
 * whatever the sampler does with the body (discard, checksum, truncate).
 * <p>
 * The response timeout is a read (inactivity) timeout, as on the adaptor: it fails a sample
 * only when no data arrived for that long, not when a slow response takes longer in total.
 * <p>
 * The consumer records when the first body byte arrived (or the head, for a response without a
 * body) so latency is measured on arrival, not when the waiting virtual user resumes.
 */
final class BufferedAsyncResponseConsumer implements AsyncResponseConsumer<ClassicHttpResponse> {

    /** Bodies shorter than this are collected before the response is handed over; others are streamed. */
    static final int BUFFER_LIMIT = 256 * 1024;

    private static final int MAX_INITIAL_CAPACITY = 64 * 1024;

    private final SampleResult result;
    private final Timeout readTimeout;
    private final Object timeoutText;
    /** Completes when the virtual user can take over: whole body buffered, or streaming started. */
    private final CompletableFuture<ClassicHttpResponse> ready = new CompletableFuture<>();
    private volatile long firstByteMillis;
    /** Last time data or the head arrived; the read timeout counts from here. */
    private volatile long lastActivityNanos;
    private HttpResponse head;
    private EntityDetails entityDetails;
    private ByteArrayBuffer body;
    private CapacityChannel capacityChannel;
    private SharedInputBuffer pipe;
    private ClassicHttpResponse streamed;
    private StreamingBody streamingBody;
    private volatile Exception failure;
    private Future<ClassicHttpResponse> exchange;
    private FutureCallback<ClassicHttpResponse> resultCallback;
    private ClassicHttpResponse response;

    // VisibleForTesting
    BufferedAsyncResponseConsumer(SampleResult result, int responseTimeoutMillis, Object timeoutText) {
        this.result = result;
        this.timeoutText = timeoutText;
        this.readTimeout = responseTimeoutMillis > 0 ? Timeout.ofMilliseconds(responseTimeoutMillis) : null;
        this.lastActivityNanos = System.nanoTime();
    }

    /**
     * Executes {@code request} and waits until the response can be read.
     *
     * @param responseTimeoutMillis the read timeout, or 0 for none; a timed wait schedules a
     *                              timer per request, so none is used unless one is configured
     * @param timeoutText           how the timeout is written in the exception, matching the
     *                              classic adaptor so timeout results format the same way
     */
    static BufferedAsyncResponseConsumer execute(CloseableHttpAsyncClient client, HttpUriRequestBase request,
            HttpClientContext context, SampleResult result, int responseTimeoutMillis, Object timeoutText)
            throws IOException {
        BufferedAsyncResponseConsumer consumer = new BufferedAsyncResponseConsumer(result, responseTimeoutMillis, timeoutText);
        Future<ClassicHttpResponse> future = client.execute(new BasicRequestProducer(request, null), consumer,
                null, context, new FutureCallback<>() {
                    @Override
                    public void completed(ClassicHttpResponse response) {
                        consumer.ready.complete(response);
                    }

                    @Override
                    public void failed(Exception ex) {
                        consumer.ready.completeExceptionally(ex);
                    }

                    @Override
                    public void cancelled() {
                        consumer.ready.cancel(false);
                    }
                });
        consumer.exchange = future;
        // Aborting the request (interrupt, test stop) cancels the exchange, also while streaming.
        request.setDependency(() -> future.cancel(true));
        consumer.response = consumer.awaitResponse(responseTimeoutMillis);
        return consumer;
    }

    private ClassicHttpResponse awaitResponse(int responseTimeoutMillis) throws IOException {
        try {
            if (responseTimeoutMillis <= 0) {
                return ready.get();
            }
            long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
            long waitNanos = timeoutNanos;
            while (true) {
                try {
                    return ready.get(waitNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    long idleNanos = System.nanoTime() - lastActivityNanos;
                    if (idleNanos >= timeoutNanos) {
                        cancelExchange();
                        throw readTimeout(null);
                    }
                    // Data is still arriving: only a silence as long as the timeout fails the sample.
                    waitNanos = timeoutNanos - idleNanos;
                }
            }
        } catch (InterruptedException e) {
            cancelExchange();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted waiting for response");
        } catch (CancellationException e) {
            throw new InterruptedIOException("Request aborted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CancellationException) {
                throw new InterruptedIOException("Request aborted");
            }
            if (cause instanceof IOException io) {
                throw asReadTimeout(io);
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ClientProtocolException(cause);
        }
    }

    private InterruptedIOException readTimeout(Throwable cause) {
        InterruptedIOException timeout =
                new InterruptedIOException("Timeout blocked waiting for input (" + timeoutText + ")");
        if (cause != null) {
            timeout.initCause(cause);
        }
        return timeout;
    }

    /**
     * The I/O reactor enforces the same inactivity limit as the connection's socket timeout; on
     * HTTP/2 it resets the streams with a plain stream reset. Report it as the response timeout
     * it is, the same as when the virtual user's own wait runs out first.
     */
    private IOException asReadTimeout(IOException failure) {
        String message = failure.getMessage();
        return message != null && message.startsWith("Timeout due to inactivity") ? readTimeout(failure) : failure;
    }

    private void cancelExchange() {
        Future<ClassicHttpResponse> current = exchange;
        if (current != null) {
            current.cancel(true);
        }
    }

    ClassicHttpResponse response() {
        return response;
    }

    /**
     * Stops the exchange if a streamed body was not read to its end, so the connection does
     * not stay leased behind a stalled stream. Call after the response was handled.
     */
    void finish() {
        // ready happened-before the user resumed, so this sees the I/O thread's write.
        StreamingBody rest = streamingBody;
        if (rest != null) {
            rest.close();
        }
    }

    // VisibleForTesting
    CompletableFuture<ClassicHttpResponse> ready() {
        return ready;
    }

    /** The body was buffered before the virtual user resumed; latency is its arrival time. */
    void applyLatency(SampleResult sample) {
        long firstByte = firstByteMillis;
        if (firstByte > 0) {
            sample.setLatency(Math.max(0, firstByte - sample.getStartTime() - sample.getIdleTime()));
        }
    }

    @Override
    public void consumeResponse(HttpResponse response, EntityDetails entityDetails, HttpContext context,
            FutureCallback<ClassicHttpResponse> callback) {
        lastActivityNanos = System.nanoTime();
        this.head = response;
        this.entityDetails = entityDetails;
        if (entityDetails == null) {
            firstByteMillis = result.currentTimeInMillis();
            callback.completed(build());
            return;
        }
        long length = entityDetails.getContentLength();
        body = new ByteArrayBuffer(length > 0 ? (int) Math.min(length, MAX_INITIAL_CAPACITY) : 1024);
        resultCallback = callback;
    }

    @Override
    public void informationResponse(HttpResponse response, HttpContext context) {
        // 1xx responses carry nothing the sampler records.
    }

    @Override
    public void updateCapacity(CapacityChannel capacityChannel) throws IOException {
        SharedInputBuffer streaming = pipe;
        if (streaming != null) {
            streaming.updateCapacity(capacityChannel);
            return;
        }
        if (this.capacityChannel == null) {
            // Enough for a buffered body; beyond it the pipe grants more as the user reads.
            capacityChannel.update(BUFFER_LIMIT);
        }
        this.capacityChannel = capacityChannel;
    }

    @Override
    public void consume(ByteBuffer src) throws IOException {
        if (!src.hasRemaining()) {
            return;
        }
        lastActivityNanos = System.nanoTime();
        if (firstByteMillis == 0) {
            firstByteMillis = result.currentTimeInMillis();
        }
        SharedInputBuffer streaming = pipe;
        if (streaming == null) {
            // Switch before the buffer holds all granted capacity, or a peer that sends exactly
            // the grant would stall with nobody reading.
            if (body.length() + src.remaining() < BUFFER_LIMIT) {
                body.append(src);
                return;
            }
            streaming = startStreaming();
        }
        streaming.fill(src);
    }

    /**
     * Hands the response to the virtual user with the buffered part in front of a pipe for the
     * rest, which grants the peer more data only as the user reads it.
     */
    private SharedInputBuffer startStreaming() throws IOException {
        SharedInputBuffer streaming = new SharedInputBuffer(MAX_INITIAL_CAPACITY);
        pipe = streaming;
        BasicClassicHttpResponse classic = head();
        StreamingBody rest = new StreamingBody(body.array(), body.length(), streaming);
        streamingBody = rest;
        classic.setEntity(new InputStreamEntity(rest, entityDetails.getContentLength(), null,
                entityDetails.getContentEncoding()));
        body = null;
        if (capacityChannel != null) {
            streaming.updateCapacity(capacityChannel);
        }
        streamed = classic;
        ready.complete(classic);
        return streaming;
    }

    @Override
    public void streamEnd(List<? extends Header> trailers) {
        lastActivityNanos = System.nanoTime();
        if (firstByteMillis == 0) {
            firstByteMillis = result.currentTimeInMillis();
        }
        SharedInputBuffer streaming = pipe;
        if (streaming != null) {
            streaming.markEndStream();
            resultCallback.completed(streamed);
        } else {
            resultCallback.completed(build());
        }
    }

    @Override
    public void failed(Exception cause) {
        failure = cause;
        SharedInputBuffer streaming = pipe;
        if (streaming != null) {
            streaming.abort();
        }
        body = null;
    }

    @Override
    public void releaseResources() {
        body = null;
    }

    private BasicClassicHttpResponse head() {
        BasicClassicHttpResponse classic = new BasicClassicHttpResponse(head.getCode(), head.getReasonPhrase());
        classic.setVersion(head.getVersion());
        classic.setHeaders(head.getHeaders());
        return classic;
    }

    private ClassicHttpResponse build() {
        BasicClassicHttpResponse classic = head();
        if (entityDetails != null) {
            // The sampler reads the content type from the response header, so the entity
            // carries none and nothing is parsed here on the I/O thread.
            classic.setEntity(new ByteArrayEntity(body.array(), 0, body.length(), null,
                    entityDetails.getContentEncoding(), entityDetails.isChunked()));
        }
        return classic;
    }

    /** The buffered start of a large body, then the rest as it arrives. */
    private final class StreamingBody extends InputStream {
        private final byte[] start;
        private final int startLength;
        private final SharedInputBuffer rest;
        private int position;
        private volatile boolean ended;

        StreamingBody(byte[] start, int startLength, SharedInputBuffer rest) {
            this.start = start;
            this.startLength = startLength;
            this.rest = rest;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (position < startLength) {
                int n = Math.min(len, startLength - position);
                System.arraycopy(start, position, b, off, n);
                position += n;
                return n;
            }
            int n;
            try {
                n = readTimeout == null ? rest.read(b, off, len) : rest.read(b, off, len, readTimeout);
            } catch (IOException e) {
                cancelExchange();
                throw failureOr(e);
            }
            if (n < 0) {
                ended = true;
            }
            return n;
        }

        private IOException failureOr(IOException fallback) {
            Exception cause = failure;
            if (cause instanceof IOException io) {
                return asReadTimeout(io);
            }
            return cause == null ? fallback : new IOException(cause);
        }

        @Override
        public void close() {
            if (!ended) {
                // The body was not read to its end: stop the exchange instead of leaving it stalled.
                cancelExchange();
            }
        }
    }
}
