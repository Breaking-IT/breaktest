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

package org.apache.jmeter.protocol.http.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.stream.Stream;

import org.apache.jmeter.protocol.http.control.Header;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class HeaderTablePanelTest {
    @ParameterizedTest
    @MethodSource("clipboardHeaders")
    void preservesHeaderNameAndValue(String line, String name, String value) {
        Header header = HeaderTablePanel.parseClipboardLine(line);
        assertNotNull(header);
        assertEquals(name, header.getName());
        assertEquals(value, header.getValue());
    }

    static Stream<Arguments> clipboardHeaders() {
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) Gecko/20100101 Firefox/145.0";
        return Stream.of(
                Arguments.of("User-Agent\t" + userAgent, "User-Agent", userAgent),
                Arguments.of("User-Agent: " + userAgent, "User-Agent", userAgent),
                Arguments.of("Referer\thttps://example.test:8443/path", "Referer", "https://example.test:8443/path"),
                Arguments.of("X-Test: first\tsecond:third", "X-Test", "first\tsecond:third"),
                Arguments.of("Accept\tapplication/json\r", "Accept", "application/json"),
                Arguments.of("Accept: \tapplication/json", "Accept", "application/json"),
                Arguments.of("X-Empty\t", "X-Empty", ""),
                Arguments.of("X-Empty:", "X-Empty", "")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no separator", ":value", "\tvalue:with colon"})
    void ignoresLinesWithoutANameOrSeparator(String line) {
        assertNull(HeaderTablePanel.parseClipboardLine(line));
    }
}
