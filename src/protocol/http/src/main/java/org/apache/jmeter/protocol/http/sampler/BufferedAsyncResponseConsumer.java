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
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CancellationException;
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
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.support.BasicRequestProducer;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.ByteArrayBuffer;
import org.apache.jmeter.samplers.SampleResult;

/**
 * Sends a request without a body straight through the async client: the response is collected
 * on the I/O thread and handed over as an ordinary classic response, so the sampler's response
 * handling stays unchanged without the classic-to-async adaptor's stream pipe and its extra
 * I/O cycles per request. It records when the first body byte arrived (or the head, for a
 * response without a body) so latency is measured on arrival, not when the waiting virtual
 * user resumes.
 */
final class BufferedAsyncResponseConsumer implements AsyncResponseConsumer<ClassicHttpResponse> {

    private static final int MAX_INITIAL_CAPACITY = 64 * 1024;

    private final SampleResult result;
    private volatile long firstByteMillis;
    private HttpResponse head;
    private EntityDetails entityDetails;
    private ByteArrayBuffer body;
    private FutureCallback<ClassicHttpResponse> resultCallback;
    private ClassicHttpResponse response;

    private BufferedAsyncResponseConsumer(SampleResult result) {
        this.result = result;
    }

    /**
     * Executes {@code request} and waits for the buffered response.
     *
     * @param responseTimeoutMillis the wait limit, or 0 for none; a timed wait schedules a timer
     *                              per request, so none is used unless one is configured
     * @param timeoutText           how the timeout is written in the exception, matching the
     *                              classic adaptor so timeout results format the same way
     */
    static BufferedAsyncResponseConsumer execute(CloseableHttpAsyncClient client, HttpUriRequestBase request,
            HttpClientContext context, SampleResult result, int responseTimeoutMillis, Object timeoutText)
            throws IOException {
        BufferedAsyncResponseConsumer consumer = new BufferedAsyncResponseConsumer(result);
        Future<ClassicHttpResponse> future =
                client.execute(new BasicRequestProducer(request, null), consumer, null, context, null);
        // Aborting the request (interrupt, test stop) cancels the exchange.
        request.setDependency(() -> future.cancel(true));
        try {
            consumer.response = responseTimeoutMillis > 0
                    ? future.get(responseTimeoutMillis, TimeUnit.MILLISECONDS) : future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted waiting for response");
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new InterruptedIOException("Timeout blocked waiting for input (" + timeoutText + ")");
        } catch (CancellationException e) {
            throw new InterruptedIOException("Request aborted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ClientProtocolException(cause);
        }
        return consumer;
    }

    ClassicHttpResponse response() {
        return response;
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
        // The whole body is wanted; do not throttle the peer.
        capacityChannel.update(Integer.MAX_VALUE);
    }

    @Override
    public void consume(ByteBuffer src) {
        if (firstByteMillis == 0 && src.hasRemaining()) {
            firstByteMillis = result.currentTimeInMillis();
        }
        body.append(src);
    }

    @Override
    public void streamEnd(List<? extends Header> trailers) {
        if (firstByteMillis == 0) {
            firstByteMillis = result.currentTimeInMillis();
        }
        resultCallback.completed(build());
    }

    @Override
    public void failed(Exception cause) {
        releaseResources();
    }

    @Override
    public void releaseResources() {
        body = null;
    }

    private ClassicHttpResponse build() {
        BasicClassicHttpResponse classic = new BasicClassicHttpResponse(head.getCode(), head.getReasonPhrase());
        classic.setVersion(head.getVersion());
        classic.setHeaders(head.getHeaders());
        if (entityDetails != null) {
            // The sampler reads the content type from the response header, so the entity
            // carries none and nothing is parsed here on the I/O thread.
            classic.setEntity(new ByteArrayEntity(body.array(), 0, body.length(), null,
                    entityDetails.getContentEncoding(), entityDetails.isChunked()));
        }
        return classic;
    }
}
