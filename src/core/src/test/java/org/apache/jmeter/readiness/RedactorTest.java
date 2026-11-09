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

package org.apache.jmeter.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    void masksSensitiveHeaders() {
        String redacted = Redactor.redact("Host: x\nAuthorization: Bearer abc\nCookie: s=1\nSet-Cookie: t=2\n"
                + "Proxy-Authorization: Basic zz\nAccept: */*");
        assertFalse(redacted.contains("abc") || redacted.contains("s=1") || redacted.contains("t=2")
                || redacted.contains("zz"), redacted);
        assertTrue(redacted.contains("Host: x") && redacted.contains("Accept: */*"), redacted);
    }

    @Test
    void masksSensitiveParametersAndJsonKeys() {
        assertEquals("user=jo&password=***&csrf_token=***&page=2",
                Redactor.redact("user=jo&password=s3cret&csrf_token=abc&page=2"));
        assertEquals("{\"apiKey\": \"***\", \"name\": \"jo\", \"sessionId\": ***}",
                Redactor.redact("{\"apiKey\": \"k-1\", \"name\": \"jo\", \"sessionId\": 42}"));
    }

    @Test
    void boundsExcerpts() {
        String text = "x".repeat(1000) + "${missing}" + "y".repeat(1000);
        String excerpt = Redactor.excerpt(text, 1000, 10);
        assertTrue(excerpt.contains("${missing}"), excerpt);
        assertTrue(excerpt.length() <= 10 + 2 * Redactor.EXCERPT_RADIUS + 2, excerpt);
        assertEquals(Redactor.MAX_EVIDENCE, Redactor.bound("z".repeat(2000)).length());
    }

    @Test
    void excerptsAreRedacted() {
        String text = "a=1&token=abcdef&b=${x}";
        assertFalse(Redactor.excerpt(text, text.indexOf("${x}"), 4).contains("abcdef"));
    }
}
