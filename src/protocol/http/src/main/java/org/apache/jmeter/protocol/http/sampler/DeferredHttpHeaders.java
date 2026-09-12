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

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.jmeter.util.JMeterUtils;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BufferedHeader;

/** Owned diagnostic snapshot: no references to mutable HTTP messages or parser buffers. */
final class DeferredHttpHeaders {
    static final boolean ENABLED = JMeterUtils.getPropDefault("httpclient5.defer_diagnostic_headers", true);

    private String[] fields;
    private final String prefix;
    private final int count;
    private final int length;
    private volatile String text;

    DeferredHttpHeaders(String prefix, Header[] headers, Predicate<String> include) {
        this.prefix = prefix;
        fields = new String[headers.length * 2];
        int accepted = 0;
        int chars = prefix.length();
        for (Header header : headers) {
            if (!include.test(header.getName())) {
                continue;
            }
            int index = accepted * 2;
            if (header instanceof BufferedHeader buffered) {
                // Preserve original whitespace/casing, but detach from its mutable char buffer.
                fields[index] = buffered.getBuffer().toString();
                chars += fields[index].length() + 1;
            } else {
                fields[index] = header.getName();
                fields[index + 1] = String.valueOf(header.getValue());
                chars += fields[index].length() + 2 + fields[index + 1].length() + 1;
            }
            accepted++;
        }
        count = accepted;
        length = chars;
    }

    DeferredHttpHeaders(String prefix, Map<String, List<String>> headers, Predicate<String> include) {
        this.prefix = prefix;
        int capacity = 0;
        for (var entry : headers.entrySet()) {
            if (include.test(entry.getKey())) {
                capacity += entry.getValue().size();
            }
        }
        fields = new String[capacity * 2];
        int accepted = 0;
        int chars = prefix.length();
        for (var entry : headers.entrySet()) {
            String name = entry.getKey();
            if (!include.test(name)) {
                continue;
            }
            for (String value : entry.getValue()) {
                fields[accepted * 2] = name;
                fields[accepted * 2 + 1] = value;
                chars += name.length() + 2 + value.length() + 1;
                accepted++;
            }
        }
        count = accepted;
        length = chars;
    }

    int length() {
        return length;
    }

    int count() {
        return count;
    }

    boolean isMaterialized() {
        return text != null;
    }

    String text() {
        String value = text;
        if (value == null) {
            synchronized (this) {
                value = text;
                if (value == null) {
                    StringBuilder builder = new StringBuilder(length);
                    builder.append(prefix);
                    for (int i = 0; i < count * 2; i += 2) {
                        builder.append(fields[i]);
                        if (fields[i + 1] != null) {
                            builder.append(": ").append(fields[i + 1]);
                        }
                        builder.append('\n');
                    }
                    value = builder.toString();
                    fields = null; // Release the component strings after materialization.
                    text = value;
                }
            }
        }
        return value;
    }
}
