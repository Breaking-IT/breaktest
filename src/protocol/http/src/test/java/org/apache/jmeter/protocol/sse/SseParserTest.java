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

package org.apache.jmeter.protocol.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SseParserTest {
    @Test
    void parsesUtf8BomLineEndingsCommentsMultilineDataAndPersistentIds() throws Exception {
        assertEquals(List.of(new SseEvent("ready", "42", "héllo\nworld"),
                new SseEvent("message", "42", ""), new SseEvent("message", "", "end")),
                parse("\uFEFF: heartbeat\r\nevent: ready\rid: 42\rdata: héllo\rdata: world\r\r"
                        + "id: bad\0id\ndata:\n\nid:\ndata: end\n\ndata: incomplete"));
    }

    @Test
    void idOnlyAndRetryAndCommentsDoNotDispatch() throws Exception {
        assertEquals(List.of(new SseEvent("message", "7", "ok")),
                parse("retry: 2000\n\nid: 7\n\nevent: discarded\n\n: comment\n\ndata: ok\n\n"));
    }

    @Test
    void boundsUnterminatedLinesAndMultilineEventsButNotTheStream() throws Exception {
        assertThrows(IOException.class, () -> SseParser.read(bytes("data: " + "a".repeat(100)), 32, e -> { }));
        assertThrows(IOException.class, () -> SseParser.read(bytes("data: 1234567890\n".repeat(10)), 32, e -> { }));
        var result = new ArrayList<SseEvent>();
        SseParser.read(bytes(": heartbeat\n".repeat(100) + "data: hi\n\n".repeat(100)), 32, result::add);
        assertEquals(100, result.size());
    }

    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    private static List<SseEvent> parse(String value) throws IOException {
        var result = new ArrayList<SseEvent>();
        SseParser.read(bytes(value), 1024, result::add);
        return result;
    }
}
