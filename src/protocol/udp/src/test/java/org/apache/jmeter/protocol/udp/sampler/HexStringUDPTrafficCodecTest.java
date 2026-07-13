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

package org.apache.jmeter.protocol.udp.sampler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HexStringUDPTrafficCodecTest {

    private final HexStringUDPTrafficCodec codec = new HexStringUDPTrafficCodec();

    @Test
    void encodesHexadecimalRequestData() {
        assertArrayEquals("test".getBytes(StandardCharsets.US_ASCII), codec.encode("74657374"));
    }

    @Test
    void decodesResponseDataAsLowercaseHexadecimal() {
        assertEquals("00abcdef", new String(codec.decode(new byte[] {0, (byte) 0xab, (byte) 0xcd, (byte) 0xef}),
                StandardCharsets.US_ASCII));
    }

    @Test
    void rejectsInvalidHexadecimal() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode("123"));
        assertThrows(IllegalArgumentException.class, () -> codec.encode("zz"));
    }
}
