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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.apache.jmeter.recording.RecordedWebSocketMessage;

/** Observes copies of forwarded bytes; framing and decompression never run on the relay threads. */
final class WebSocketProxyRecorder implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final List<RecordedWebSocketMessage> messages = new ArrayList<>();
    private final double startedAt;
    private final Direction outgoing;
    private final Direction incoming;
    private String failure = "";

    WebSocketProxyRecorder(double startedAt, String extensions) {
        this.startedAt = startedAt;
        String negotiated = extensions.toLowerCase(Locale.ROOT);
        outgoing = new Direction("send", negotiated.contains("permessage-deflate"), negotiated.contains("client_no_context_takeover"));
        incoming = new Direction("receive", negotiated.contains("permessage-deflate"), negotiated.contains("server_no_context_takeover"));
    }

    void accept(boolean request, byte[] bytes, double receivedAt) {
        worker.execute(() -> {
            Direction direction = request ? outgoing : incoming;
            direction.wire.writeBytes(bytes);
            if (!direction.failed) {
                try {
                    direction.accept(bytes, receivedAt);
                } catch (IOException | RuntimeException e) {
                    direction.failed = true;
                    failure = "WebSocket message inspection failed: " + e.getMessage();
                }
            }
        });
    }

    List<RecordedWebSocketMessage> messages() {
        return messages.stream().sorted(Comparator.comparing(RecordedWebSocketMessage::relativeTimeMs)).toList();
    }

    BigDecimal clientCloseOffset() {
        return messages().stream().filter(message -> message.opcode() == 8).findFirst()
                .filter(message -> "send".equals(message.direction())).map(RecordedWebSocketMessage::relativeTimeMs).orElse(null);
    }

    byte[] wire(boolean request) { return (request ? outgoing : incoming).wire.toByteArray(); }
    String failure() { return failure; }

    @Override
    public void close() {
        worker.close();
        for (Direction direction : List.of(outgoing, incoming)) {
            if (!direction.failed && (direction.headerSize != 0 || direction.messageOpcode != 0)) {
                failure = "WebSocket connection ended during an incomplete frame or message";
            }
            direction.inflater.end();
        }
    }

    private final class Direction {
        private final String name;
        private final boolean deflate;
        private final boolean noContextTakeover;
        private final Inflater inflater = new Inflater(true);
        private final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        private final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        private final ByteArrayOutputStream message = new ByteArrayOutputStream();
        private final byte[] header = new byte[14];
        private int headerSize;
        private int headerLength = 2;
        private long length;
        private long consumed;
        private double frameTime;
        private double messageTime;
        private int messageOpcode;
        private boolean compressed;
        private boolean failed;

        Direction(String name, boolean deflate, boolean noContextTakeover) {
            this.name = name;
            this.deflate = deflate;
            this.noContextTakeover = noContextTakeover;
        }

        void accept(byte[] bytes, double time) throws IOException {
            int position = 0;
            while (position < bytes.length) {
                if (headerSize < headerLength) {
                    if (headerSize == 0) {
                        frameTime = time;
                    }
                    header[headerSize++] = bytes[position++];
                    if (headerSize == 2) {
                        int shortLength = header[1] & 127;
                        headerLength = 2 + (shortLength == 126 ? 2 : shortLength == 127 ? 8 : 0)
                                + ((header[1] & 128) != 0 ? 4 : 0);
                    }
                    if (headerSize < headerLength) {
                        continue;
                    }
                    length = header[1] & 127;
                    if (length >= 126) {
                        int count = length == 126 ? 2 : 8;
                        length = 0;
                        for (int i = 0; i < count; i++) {
                            length = (length << 8) | (header[2 + i] & 255);
                        }
                    }
                    if (length < 0 || length > Integer.MAX_VALUE) {
                        throw new IOException("WebSocket frame exceeds supported payload size");
                    }
                }
                int count = (int) Math.min((long) bytes.length - position, length - consumed);
                for (int i = 0; i < count; i++) {
                    int value = bytes[position + i] & 255;
                    if ((header[1] & 128) != 0) {
                        value ^= header[headerLength - 4 + (int) ((consumed + i) % 4)] & 255;
                    }
                    payload.write(value);
                }
                position += count;
                consumed += count;
                if (consumed == length) {
                    completeFrame();
                    headerSize = 0;
                    headerLength = 2;
                    consumed = 0;
                    payload.reset();
                }
            }
        }

        private void completeFrame() throws IOException {
            int opcode = header[0] & 15;
            boolean fin = (header[0] & 128) != 0;
            boolean rsv1 = (header[0] & 64) != 0;
            if ((header[0] & 48) != 0 || (rsv1 && (!deflate || opcode == 0 || opcode >= 8))) {
                throw new IOException("Unsupported WebSocket extension bits");
            }
            if (opcode >= 8) {
                if (!fin || length > 125 || opcode > 10) {
                    throw new IOException("Invalid WebSocket control frame");
                }
                record(opcode, payload.toByteArray(), frameTime);
                return;
            }
            if (opcode == 1 || opcode == 2) {
                if (messageOpcode != 0) {
                    throw new IOException("New WebSocket message before final continuation");
                }
                messageOpcode = opcode;
                messageTime = frameTime;
                compressed = rsv1;
            } else if (opcode != 0 || messageOpcode == 0) {
                throw new IOException("Unexpected WebSocket continuation/opcode");
            }
            message.writeBytes(payload.toByteArray());
            if (fin) {
                byte[] data = message.toByteArray();
                record(messageOpcode, compressed ? inflate(data) : data, messageTime);
                message.reset();
                messageOpcode = 0;
            }
        }

        private byte[] inflate(byte[] data) throws IOException {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            ByteArrayOutputStream compressedData = new ByteArrayOutputStream();
            compressedData.writeBytes(data);
            compressedData.writeBytes(new byte[]{0, 0, (byte) 255, (byte) 255});
            inflater.setInput(compressedData.toByteArray());
            byte[] buffer = new byte[8192];
            try {
                while (!inflater.needsInput()) {
                    int count = inflater.inflate(buffer);
                    if (count == 0 && !inflater.needsInput()) {
                        throw new IOException("Invalid permessage-deflate payload");
                    }
                    result.write(buffer, 0, count);
                }
            } catch (DataFormatException e) {
                throw new IOException("Invalid permessage-deflate payload", e);
            }
            if (noContextTakeover) {
                inflater.reset();
            }
            return result.toByteArray();
        }

        private void record(int opcode, byte[] data, double time) {
            messages.add(new RecordedWebSocketMessage(BigDecimal.valueOf(Math.max(0, time - startedAt)),
                    name, opcode, Base64.getEncoder().encodeToString(data)));
        }
    }
}
