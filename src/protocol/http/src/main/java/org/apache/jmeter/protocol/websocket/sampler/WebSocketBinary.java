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

package org.apache.jmeter.protocol.websocket.sampler;

import java.util.HexFormat;
import java.util.function.Predicate;

import org.apache.jmeter.samplers.SampleResult;

final class WebSocketBinary {
    private WebSocketBinary() {
    }

    static byte[] parse(String hex) {
        return HexFormat.of().parseHex(hex.replaceAll("(?U)\\s+", ""));
    }

    static Predicate<SampleResult> matcher(byte[] pattern) {
        if (pattern.length == 0) {
            throw new IllegalArgumentException("A non-empty binary response sequence is required");
        }
        byte[] needle = pattern.clone();
        // Prefix table keeps searching linear even for long, repetitive payloads.
        int[] prefixes = new int[needle.length];
        for (int i = 1, matched = 0; i < needle.length; i++) {
            while (matched > 0 && needle[i] != needle[matched]) {
                matched = prefixes[matched - 1];
            }
            if (needle[i] == needle[matched]) {
                matched++;
            }
            prefixes[i] = matched;
        }
        return result -> {
            if (!SampleResult.BINARY.equals(result.getDataType())) {
                return false;
            }
            int matched = 0;
            for (byte value : result.getResponseData()) {
                while (matched > 0 && value != needle[matched]) {
                    matched = prefixes[matched - 1];
                }
                if (value == needle[matched] && ++matched == needle.length) {
                    return true;
                }
            }
            return false;
        };
    }
}
