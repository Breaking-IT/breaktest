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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

class WebSocketProxyRecorderTest {
    static byte[] frame(int flags, boolean masked, byte[] data) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(flags);
        int mask = masked ? 128 : 0;
        if (data.length < 126) {
            result.write(mask | data.length);
        } else if (data.length <= 65535) {
            result.write(mask | 126);
            result.write(data.length >>> 8);
            result.write(data.length);
        } else {
            result.write(mask | 127);
            result.writeBytes(java.nio.ByteBuffer.allocate(8).putLong(data.length).array());
        }
        byte[] key = {11, 22, 33, 44};
        if (masked) {
            result.writeBytes(key);
        }
        for (int i = 0; i < data.length; i++) {
            result.write(masked ? data[i] ^ key[i % 4] : data[i]);
        }
        return result.toByteArray();
    }

    @Test
    void observesFragmentedMaskedMessagesAndControlsAcrossArbitraryReads() {
        var recorder = new WebSocketProxyRecorder(1000, "");
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.writeBytes(frame(1, true, "hel".getBytes(StandardCharsets.UTF_8)));
        wire.writeBytes(frame(137, true, new byte[]{7}));
        wire.writeBytes(frame(128, true, "lo".getBytes(StandardCharsets.UTF_8)));
        wire.writeBytes(frame(136, true, new byte[]{3, (byte) 232}));
        byte[] bytes = wire.toByteArray();
        for (byte value : bytes) {
            recorder.accept(true, new byte[]{value}, 1010);
        }
        recorder.close();
        assertEquals("", recorder.failure());
        assertEquals(3, recorder.messages().size());
        assertTrue(recorder.messages().stream().anyMatch(message -> message.opcode() == 1 && message.text().equals("hello")));
        assertEquals(10, recorder.clientCloseOffset().intValueExact());
        assertArrayEquals(bytes, recorder.wire(true));
    }

    @Test
    void handlesBinaryExtendedLengthsAndEmptyMessages() {
        var recorder = new WebSocketProxyRecorder(0, "");
        for (int size : new int[]{0, 126, 65536}) {
            byte[] payload = new byte[size];
            Arrays.fill(payload, (byte) 255);
            recorder.accept(false, frame(130, false, payload), size);
        }
        recorder.close();
        assertEquals("", recorder.failure());
        assertEquals(3, recorder.messages().size());
        assertEquals(65536, java.util.Base64.getDecoder().decode(recorder.messages().get(2).data()).length);
    }

    @Test
    void decodesDeflateWithContextTakeoverWithoutChangingWireBytes() {
        var recorder = new WebSocketProxyRecorder(0, "permessage-deflate");
        Deflater deflater = new Deflater(6, true);
        String text = "repeated content repeated content repeated content";
        try {
            for (int i = 0; i < 2; i++) {
                deflater.setInput(text.getBytes(StandardCharsets.UTF_8));
                byte[] compressed = new byte[1024];
                int count = deflater.deflate(compressed, 0, compressed.length, Deflater.SYNC_FLUSH);
                byte[] wire = frame(193, false, Arrays.copyOf(compressed, count - 4));
                recorder.accept(false, wire, i);
            }
        } finally {
            deflater.end();
            recorder.close();
        }
        assertEquals("", recorder.failure());
        assertEquals(2, recorder.messages().size());
        assertEquals(text, recorder.messages().get(1).text());
    }

    @Test
    void incompleteFrameRetainsAvailableWireAndReportsFailure() {
        var recorder = new WebSocketProxyRecorder(0, "");
        byte[] truncated = { (byte) 129, 4, 1 };
        recorder.accept(false, truncated, 1);
        recorder.close();
        assertTrue(recorder.failure().contains("incomplete"));
        assertArrayEquals(truncated, recorder.wire(false));
    }
}
