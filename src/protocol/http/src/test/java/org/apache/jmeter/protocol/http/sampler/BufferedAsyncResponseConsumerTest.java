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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.impl.BasicEntityDetails;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class BufferedAsyncResponseConsumerTest {

    private static final int LIMIT = BufferedAsyncResponseConsumer.BUFFER_LIMIT;

    /** Grants recorded as the peer's sending allowance. */
    private static final class RecordingChannel implements CapacityChannel {
        long granted;

        @Override
        public void update(int increment) {
            granted += increment;
        }
    }

    private static final class RecordingCallback implements FutureCallback<ClassicHttpResponse> {
        final List<ClassicHttpResponse> completed = new ArrayList<>();

        @Override
        public void completed(ClassicHttpResponse result) {
            completed.add(result);
        }

        @Override
        public void failed(Exception ex) {
            throw new AssertionError(ex);
        }

        @Override
        public void cancelled() {
            throw new AssertionError("cancelled");
        }
    }

    private static byte[] body(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    @Test
    void smallBodyIsHandedOverWhole() throws Exception {
        BufferedAsyncResponseConsumer consumer = new BufferedAsyncResponseConsumer(new SampleResult(), 0, "");
        RecordingCallback callback = new RecordingCallback();
        byte[] data = body(20_000);
        consumer.consumeResponse(new BasicHttpResponse(200), new BasicEntityDetails(-1, ContentType.TEXT_PLAIN),
                null, callback);
        consumer.updateCapacity(new RecordingChannel());
        consumer.consume(ByteBuffer.wrap(data));
        assertFalse(consumer.ready().isDone(), "a small body is handed over only when it is complete");
        consumer.streamEnd(null);

        assertEquals(1, callback.completed.size());
        assertArrayEquals(data, callback.completed.get(0).getEntity().getContent().readAllBytes());
    }

    @Test
    void largeBodyIsStreamedWithBoundedBuffering() throws Exception {
        BufferedAsyncResponseConsumer consumer = new BufferedAsyncResponseConsumer(new SampleResult(), 0, "");
        RecordingCallback callback = new RecordingCallback();
        RecordingChannel channel = new RecordingChannel();
        byte[] data = body(8 * LIMIT + 12_345);
        consumer.consumeResponse(new BasicHttpResponse(200), new BasicEntityDetails(-1, ContentType.TEXT_PLAIN),
                null, callback);
        consumer.updateCapacity(channel);

        // A peer that sends only what the consumer granted; the user reads a little at a time.
        int sent = 0;
        long read = 0;
        long maxHeld = 0;
        InputStream in = null;
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] chunk = new byte[10_000];
        while (sent < data.length) {
            long allowance = channel.granted - sent;
            if (allowance > 0) {
                int n = (int) Math.min(Math.min(allowance, 16_384), data.length - sent);
                consumer.consume(ByteBuffer.wrap(data, sent, n));
                sent += n;
            } else {
                assertTrue(consumer.ready().isDone(), "the user gets the response before the peer stalls");
                if (in == null) {
                    in = consumer.ready().get().getEntity().getContent();
                }
                int n = in.read(chunk, 0, (int) Math.min(chunk.length, sent - read));
                received.write(chunk, 0, n);
                read += n;
            }
            maxHeld = Math.max(maxHeld, sent - read);
        }
        consumer.streamEnd(null);
        if (in == null) {
            in = consumer.ready().get().getEntity().getContent();
        }
        in.transferTo(received);

        assertTrue(maxHeld <= 2L * LIMIT, "bytes held before the user read them: " + maxHeld);
        assertArrayEquals(data, received.toByteArray());
        assertEquals(1, callback.completed.size(), "the exchange completes when the stream ends");
    }
}
