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

import java.util.Locale;

/** Shared field definitions keep the dummy editor and runtime defaults aligned. */
public enum DummySamplerField {
    SUCCESSFUL("true", "status", null, false),
    RESPONSE_CODE("200", "status", null, false),
    RESPONSE_MESSAGE("OK", "status", null, false),
    TIMESTAMP("", "timing", null, false),
    RESPONSE_TIME("0", "timing", null, false),
    LATENCY("0", "timing", null, false),
    CONNECT_TIME("0", "timing", null, false),
    IDLE_TIME("0", "timing", null, false),
    SIMULATE_TIME("false", "timing", null, false),
    URL("", "request", null, false),
    REQUEST_HEADERS("", "request", null, true),
    REQUEST_DATA("", "request", null, true),
    RESPONSE_HEADERS("", "response", null, true),
    RESPONSE_DATA("", "response", null, true),
    CONTENT_TYPE("text/plain", "response", null, false),
    ENCODING("UTF-8", "response", null, false),
    DATA_TYPE("text", "response", null, false),
    BASE64("false", "response", null, false),
    SENT_BYTES("0", "sizes", null, false),
    BODY_SIZE("0", "sizes", null, false),
    HEADERS_SIZE("0", "sizes", null, false),
    LOCAL_ENDPOINT("", "connection", null, false),
    DESTINATION_ENDPOINT("", "connection", null, false),
    PROTOCOL_VERSION("", "connection", null, false),
    TLS_VERSION("", "connection", null, false),
    HTTP_METHOD("GET", "http", DummySampler.ResultType.HTTP, false),
    QUERY_STRING("", "http", DummySampler.ResultType.HTTP, true),
    COOKIES("", "http", DummySampler.ResultType.HTTP, true),
    REDIRECT_LOCATION("", "http", DummySampler.ResultType.HTTP, false),
    SAMPLE_COUNT("1", "statistics", DummySampler.ResultType.STATISTICAL, false),
    ERROR_COUNT("0", "statistics", DummySampler.ResultType.STATISTICAL, false);

    private final String defaultValue;
    private final String group;
    private final DummySampler.ResultType type;
    private final boolean multiline;

    DummySamplerField(String defaultValue, String group, DummySampler.ResultType type, boolean multiline) {
        this.defaultValue = defaultValue;
        this.group = group;
        this.type = type;
        this.multiline = multiline;
    }

    public String propertyName() {
        return "DummySampler." + name().toLowerCase(Locale.ROOT);
    }

    public String resourceKey() {
        return "dummy_sampler_" + name().toLowerCase(Locale.ROOT);
    }

    public String defaultValue() {
        return defaultValue;
    }

    public String group() {
        return group;
    }

    public boolean multiline() {
        return multiline;
    }

    public boolean appliesTo(DummySampler.ResultType selected) {
        return type == null || type == selected;
    }

}
