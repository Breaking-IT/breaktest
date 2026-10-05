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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Incremental EventSource framing. EOF never dispatches an incomplete event. */
public final class SseParser {
    private SseParser() { }

    public static void read(InputStream input, int maxCharacters, Consumer<SseEvent> events) throws IOException {
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("SSE maximum event size must be positive");
        }
        var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder line = new StringBuilder();
        StringBuilder data = new StringBuilder();
        String name = "";
        String id = "";
        boolean first = true;
        boolean cr = false;
        int size = 0;
        for (int ch; (ch = reader.read()) != -1;) {
            if (first) {
                first = false;
                if (ch == '\uFEFF') {
                    continue;
                }
            }
            if (ch == '\n' && cr) {
                cr = false;
                continue;
            }
            cr = ch == '\r';
            if (ch != '\r' && ch != '\n') {
                if (++size > maxCharacters) {
                    throw new IOException("SSE event exceeds " + maxCharacters + " characters");
                }
                line.append((char) ch);
                continue;
            }
            String value = line.toString();
            line.setLength(0);
            if (value.isEmpty()) {
                if (!data.isEmpty()) {
                    events.accept(new SseEvent(name.isEmpty() ? "message" : name, id,
                            data.substring(0, data.length() - 1)));
                }
                data.setLength(0);
                name = "";
                size = 0;
                continue;
            }
            int colon = value.indexOf(':');
            String field = colon < 0 ? value : value.substring(0, colon);
            value = colon < 0 ? "" : value.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "data" -> data.append(value).append('\n');
                case "event" -> name = value;
                case "id" -> {
                    if (value.indexOf('\0') < 0) {
                        id = value;
                    }
                }
                default -> { /* Comments, retry and extension fields do not dispatch events. */ }
            }
            // Heartbeats and ignored fields must not accumulate indefinitely.
            size = data.length() + name.length() + id.length();
        }
    }
}
