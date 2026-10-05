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

package org.apache.jmeter.protocol.http.proxy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.jmeter.protocol.sse.SseParser;
import org.apache.jmeter.recording.RecordedSseEvent;
import org.apache.jmeter.samplers.ResponseDecoderRegistry;

/** Decodes copies of de-framed response bytes on a dedicated worker, without delaying forwarding. */
final class SseProxyRecorder {
    private static final class Chunk {
        private final byte[] bytes;
        private final double time;
        private final RecordingRequestSettings settings;
        private Chunk(byte[] bytes, double time, RecordingRequestSettings settings) {
            this.bytes = bytes;
            this.time = time;
            this.settings = settings;
        }
    }
    private static final Chunk END = new Chunk(new byte[0], 0, null);
    private final LinkedBlockingQueue<Chunk> chunks = new LinkedBlockingQueue<>();
    private final List<RecordedSseEvent> events = new ArrayList<>();
    private final Thread worker;
    private final double start;
    private final String transactionPrefix = "proxy-sse-" + java.util.UUID.randomUUID() + "-";
    private int transaction;
    private String previousName;
    private double previousTime;
    private volatile boolean expectedStop;
    private volatile boolean finished;
    private String failure = "";

    SseProxyRecorder(double start, String encoding) {
        this.start = start;
        worker = Thread.ofVirtual().name("proxy-sse-observer").start(() -> read(encoding));
    }

    static boolean isEventStream(String contentType) {
        return "text/event-stream".equalsIgnoreCase(contentType.split(";", 2)[0].trim());
    }

    void accept(byte[] bytes, int offset, int length, double time, RecordingRequestSettings settings) {
        if (!finished) {
            chunks.add(new Chunk(Arrays.copyOfRange(bytes, offset, offset + length), time, settings));
        }
    }

    void finish(boolean expectedStop) {
        this.expectedStop = expectedStop;
        chunks.add(END);
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        chunks.clear();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    List<RecordedSseEvent> events() { return List.copyOf(events); }
    String failure() { return failure; }

    private void read(String encoding) {
        var source = new QueuedInput();
        try {
            InputStream decoded = source;
            String[] encodings = encoding.toLowerCase(Locale.ROOT).split(",");
            for (int i = encodings.length - 1; i >= 0; i--) {
                String name = encodings[i].trim();
                if (!name.isEmpty() && !"identity".equals(name)) {
                    if (!ResponseDecoderRegistry.hasDecoder(name)) {
                        throw new IOException("Unsupported SSE content encoding: " + name);
                    }
                    decoded = ResponseDecoderRegistry.decodeStream(name, decoded);
                }
            }
            // InflaterInputStream.available() reports 1 even when another network chunk is required.
            // Prevent the character decoder from reading ahead past a complete event into that chunk.
            try (InputStream input = new java.io.FilterInputStream(decoded) {
                @Override
                public int available() {
                    return 0;
                }
            }) {
                SseParser.read(input, Integer.MAX_VALUE, event -> {
                    RecordingRequestSettings settings = source.current.settings;
                    String name = settings == null ? "" : settings.prefix();
                    long gap = settings == null ? 5000 : settings.transactionGapMillis();
                    double time = source.current.time;
                    if (!Objects.equals(previousName, name) || time - previousTime > (double) gap) {
                        transaction++;
                    }
                    previousName = name;
                    previousTime = time;
                    events.add(new RecordedSseEvent(BigDecimal.valueOf(Math.max(0, time - start)),
                            event.eventName(), event.eventId(), event.data(), transactionPrefix + transaction, name));
                });
            }
        } catch (IOException | RuntimeException e) {
            if (!(expectedStop && e instanceof EOFException)) {
                failure = "SSE event inspection failed: " + e.getMessage();
            }
        } finally {
            finished = true;
            chunks.clear();
        }
    }

    private final class QueuedInput extends InputStream {
        private Chunk current = END;
        private int position;
        private boolean ended;

        @Override
        public int read() throws IOException {
            return next() ? current.bytes[position++] & 255 : -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (!next()) {
                return -1;
            }
            int count = Math.min(length, current.bytes.length - position);
            System.arraycopy(current.bytes, position, bytes, offset, count);
            position += count;
            return count;
        }

        private boolean next() throws IOException {
            while (!ended && position == current.bytes.length) {
                try {
                    Chunk next = chunks.take();
                    if (next == END) {
                        ended = true;
                    } else {
                        current = next;
                        position = 0;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("SSE inspection interrupted", e);
                }
            }
            return !ended;
        }
    }
}
