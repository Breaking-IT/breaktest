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

package org.apache.jmeter.protocol.http.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.junit.jupiter.api.Test;

class RecordedValueReplacerTest extends JMeterTestCase {
    @Test
    void decodedFieldsPreservePlusSignsEscapeCaseAndEncodingDepth() {
        String decoded = "${__urldecode(${__strReplace(${v},+,%2B)})}";
        assertEquals(decoded, RecordedValueReplacer.replace("/", "%2F", "${v}", true));
        assertEquals("%2f", RecordedValueReplacer.replace("%2f", "%2F", "${v}", true));
        assertEquals(decoded + "|${v}",
                RecordedValueReplacer.replace("%2F|%252F", "%252F", "${v}", true));
        assertEquals(decoded, RecordedValueReplacer.replace("a+b/c", "a+b%2Fc", "${v}", true));
        assertEquals("a b/c", RecordedValueReplacer.replace("a b/c", "a+b%2Fc", "${v}", true));
        assertEquals("${v}", RecordedValueReplacer.replace("bad%zz", "bad%zz", "${v}", true));
    }

    @Test
    void largeScriptValuesDoNotRequireRegexCompilation() {
        String value = "this.match(e.id, a, null);cancelMatch(){this.subscription.unsubscribe();}\n".repeat(2000);
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        assertTimeout(Duration.ofSeconds(10), () -> {
            assertNull(RecordedValueReplacer.matchedLiteral("short request", value));
            assertEquals(encoded, RecordedValueReplacer.matchedLiteral("before" + encoded + "after", value));
            assertEquals("before${__strReplace(${__urlencode(${script})},+,%20)}after",
                    RecordedValueReplacer.replace("before" + encoded + "after", value, "${script}", false));
            assertEquals("before${script}after",
                    RecordedValueReplacer.replace("before" + value + "after", value, "${script}", true));
            assertNull(RecordedValueReplacer.matchedLiteral(value.substring(1), value));
        });
    }

    @Test
    void percentEscapeBoundariesKeepLiteralCase() {
        assertEquals("%2${v}", RecordedValueReplacer.replace("%2f", "f", "${v}", false));
        assertEquals("%2f", RecordedValueReplacer.replace("%2f", "F", "${v}", false));
        assertEquals("%2${v}", RecordedValueReplacer.replace("%2f%2a", "f%2A", "${v}", false));
        assertEquals("%2f%2a", RecordedValueReplacer.replace("%2f%2a", "F%2A", "${v}", false));
        assertEquals("${v}f", RecordedValueReplacer.replace("%2a%af", "%2A%a", "${v}", false));
        assertEquals("%2a%af", RecordedValueReplacer.replace("%2a%af", "%2A%A", "${v}", false));
        assertEquals("${v}", RecordedValueReplacer.replace("%2a%zz%2f", "%2A%zz%2F", "${v}", false));
        assertEquals("%2a%ZZ%2f", RecordedValueReplacer.replace("%2a%ZZ%2f", "%2A%zz%2F", "${v}", false));
    }

    @Test
    void preservesReferencesAndNeverRescansReplacementText() {
        assertEquals("${outer(${inner(token)},token)}-${token}", RecordedValueReplacer.replace(
                "${outer(${inner(token)},token)}-token", "token", "${token}", false));
        assertEquals("${unterminated token", RecordedValueReplacer.replace(
                "${unterminated token", "token", "${v}", false));
        assertEquals("${v}${v}${v}", RecordedValueReplacer.replace("aaaaaa", "aa", "${v}", false));
        assertEquals("aaa", RecordedValueReplacer.replace("a", "a", "aaa", false));
        assertEquals("${v}tail", RecordedValueReplacer.replace("x${name}tail", "x${name}", "${v}", false));
        assertEquals("${name}", RecordedValueReplacer.replace("${name}", "${name}", "${v}", false));
    }

    @Test
    void handlesEmptyUnicodeAndRegexMetacharactersLiterally() {
        assertNull(RecordedValueReplacer.matchedLiteral(null, "value"));
        assertNull(RecordedValueReplacer.matchedLiteral("text", ""));
        assertNull(RecordedValueReplacer.replace(null, "value", "${v}", false));
        assertEquals("text", RecordedValueReplacer.replace("text", "", "${v}", false));
        assertEquals("", RecordedValueReplacer.replace("", "value", "${v}", false));
        for (String value : List.of("[a-z]+(.*)?^$|\\Q\\E", "é😀漢字", "%2Fé%a😀%af", "%", "%a", "%zz", "a+b c")) {
            assertEquals("${v}", RecordedValueReplacer.replace(value, value, "${v}", false));
            String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
            assertEquals("${__urlencode(${v})}",
                    RecordedValueReplacer.replace(encoded, value, "${v}", false));
        }
    }

    @Test
    void longestVariantWinsAtSamePositionButEarliestPositionWinsAcrossVariants() {
        assertEquals("${__strReplace(${__urlencode(${v})},+,%20)}${__urlencode(${v})}${v}",
                RecordedValueReplacer.replace("%20+ ", " ", "${v}", false));
        assertEquals("${v}|${__urlencode(${v})}",
                RecordedValueReplacer.replace("/|%2f", "/", "${v}", false));
        // Discovery historically prefers the longest representation anywhere in the field.
        assertEquals("%2f", RecordedValueReplacer.matchedLiteral("/|%2f", "/"));
        assertEquals("${v}", RecordedValueReplacer.replace("plain", "plain", "${v}", false));
    }

    @Test
    void repeatedNearMatchesAndManyReplacementsRemainBounded() {
        assertTimeout(Duration.ofSeconds(10), () -> {
            String value = "a".repeat(2000) + "%2F" + "b".repeat(2000);
            String text = ("a".repeat(2000) + "%2f" + "b".repeat(1999) + "c").repeat(100);
            assertNull(RecordedValueReplacer.matchedLiteral(text, value));
            assertEquals(text, RecordedValueReplacer.replace(text, value, "${v}", false));
            assertEquals("${v}".repeat(20000),
                    RecordedValueReplacer.replace("a".repeat(20000), "a", "${v}", false));
        });
    }

    @Test
    void agreesWithPreviousRegexSemanticsForSmallRandomInputs() {
        // The old per-char quoting mishandles some surrogate pairs; Unicode correctness
        // is asserted separately rather than preserving that bug in this compatibility test.
        Random random = new Random(314159);
        List<String> pieces = List.of("a", "A", "f", "F", "%2f", "%2F", "%af", "%", "%a", "%zz",
                "+", " ", "/", "é", "[x]", "\\E", "${x}", "}");
        for (int trial = 0; trial < 2000; trial++) {
            StringBuilder value = new StringBuilder();
            for (int i = 0, n = 1 + random.nextInt(5); i < n; i++) {
                value.append(pieces.get(random.nextInt(pieces.size())));
            }
            String literal = value.toString();
            String encoded = URLEncoder.encode(literal, StandardCharsets.UTF_8);
            String text = pieces.get(random.nextInt(pieces.size())) + literal + "|"
                    + encoded.replace("%2F", "%2f") + "|${outer(" + literal + ")}|"
                    + encoded.replace("+", "%20") + pieces.get(random.nextInt(pieces.size()));
            var variants = new LinkedHashMap<String, String>();
            variants.put(encoded.replace("+", "%20"), "${__strReplace(${__urlencode(${v})},+,%20)}");
            variants.put(encoded, "${__urlencode(${v})}");
            variants.put(literal, "${v}");
            var ordered = new ArrayList<>(variants.keySet());
            ordered.sort(Comparator.comparingInt(String::length).reversed());
            String expectedMatch = null;
            for (String variant : ordered) {
                var matcher = legacyPattern(variant).matcher(text);
                if (matcher.find()) {
                    expectedMatch = matcher.group();
                    break;
                }
            }
            assertEquals(expectedMatch, RecordedValueReplacer.matchedLiteral(text, literal), literal);
            String regex = "(\\$\\{)";
            for (String variant : ordered) {
                regex += "|(" + legacyPattern(variant).pattern() + ")";
            }
            var matcher = Pattern.compile(regex).matcher(text);
            StringBuilder expected = new StringBuilder();
            int offset = 0;
            while (matcher.find(offset)) {
                expected.append(text, offset, matcher.start());
                if (matcher.group(1) != null) {
                    int end = matcher.end();
                    int depth = 1;
                    while (end < text.length() && depth > 0) {
                        if (text.startsWith("${", end)) {
                            depth++;
                            end += 2;
                        } else if (text.charAt(end++) == '}') {
                            depth--;
                        }
                    }
                    expected.append(text, matcher.start(), end);
                    offset = end;
                } else {
                    for (int i = 0; i < ordered.size(); i++) {
                        if (matcher.group(i + 2) != null) {
                            expected.append(variants.get(ordered.get(i)));
                            break;
                        }
                    }
                    offset = matcher.end();
                }
            }
            expected.append(text, offset, text.length());
            assertEquals(expected.toString(), RecordedValueReplacer.replace(text, literal, "${v}", false), literal);
        }
    }

    // Kept only as a compatibility oracle for bounded inputs, never for the large-value regression.
    private static Pattern legacyPattern(String literal) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < literal.length(); i++) {
            if (literal.charAt(i) == '%' && i + 2 < literal.length()
                    && Character.digit(literal.charAt(i + 1), 16) >= 0
                    && Character.digit(literal.charAt(i + 2), 16) >= 0) {
                regex.append("(?i:").append(Pattern.quote(literal.substring(i, i + 3))).append(')');
                i += 2;
            } else {
                regex.append(Pattern.quote(literal.substring(i, i + 1)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    @Test
    void decodedParametersRetainTheEncodingLevelOfTheExtractedValue() {
        String decode = "${__urldecode(${__strReplace(${state},+,%2B)})}";
        assertEquals(decode, RecordedValueReplacer.replace("abc/def", "abc%2Fdef", "${state}", true));
        assertEquals("${state}", RecordedValueReplacer.replace("abc%2Fdef", "abc%2Fdef", "${state}", true));
        assertEquals("${state}", RecordedValueReplacer.replace("abc/def", "abc/def", "${state}", true));
        assertEquals(decode + "|${state}", RecordedValueReplacer.replace(
                "abc/def|abc%2Fdef", "abc%2Fdef", "${state}", true));
        assertEquals("${state}", RecordedValueReplacer.replace("abc%ZZdef", "abc%ZZdef", "${state}", true));
        assertEquals(decode, RecordedValueReplacer.replace("abc+def/ghi", "abc+def%2Fghi", "${state}", true));
    }

    @Test
    void retainsEncodingInRawFieldsAndLeavesExistingReferencesAlone() {
        String value = "token +/é=%";
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        assertEquals("prefix=${__urlencode(${token})}&original=${token}",
                RecordedValueReplacer.replace("prefix=" + encoded + "&original=${token}",
                        value, "${token}", false));
        assertEquals("${__strReplace(${__urlencode(${token})},+,%20)}",
                RecordedValueReplacer.replace(encoded.replace("+", "%20"), value, "${token}", false));
        assertEquals("${token}-${__urlencode(${token})}", RecordedValueReplacer.replace(
                value + "-" + encoded, value, "${token}", false));
    }

    @Test
    void handlesEscapeCaseWithoutIgnoringTokenCase() {
        assertEquals("Token%2f123", RecordedValueReplacer.matchedLiteral("Token%2f123", "Token/123"));
        assertEquals("${__urlencode(${token})}",
                RecordedValueReplacer.replace("Token%2f123", "Token/123", "${token}", false));
        assertEquals("token%2f123", RecordedValueReplacer.replace("token%2f123", "Token/123", "${token}", false));
    }

    @Test
    void parametersEncodeExactlyOnceOnReplay() throws Exception {
        for (String value : List.of("abc+123/=", "a b+é%", "percent%2Fvalue")) {
            HTTPArgument argument = new HTTPArgument("token", value);
            argument.setAlwaysEncoded(true);
            RecordedValueReplacer.replaceArgument(argument, value, "${token}", false);
            assertEquals("${token}", argument.getValue());
            assertTrue(argument.isAlwaysEncoded());
            // Substitute a new runtime value, then use the real sampler serialization.
            String runtimeValue = value + "/new";
            argument.setValue(runtimeValue);
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.getArguments().addArgument(argument);
            assertEquals("token=" + URLEncoder.encode(runtimeValue, StandardCharsets.UTF_8),
                    sampler.getQueryString("UTF-8"));
        }
    }

    @Test
    void enablesParameterEncodingForAnEncodedLiteral() {
        HTTPArgument argument = new HTTPArgument("token", "abc%2B123%2F%3D");
        argument.setAlwaysEncoded(false);
        RecordedValueReplacer.replaceArgument(argument, "abc+123/=", "${token}", false);
        assertEquals("${token}", argument.getValue());
        assertTrue(argument.isAlwaysEncoded());
    }

    @Test
    void rawBodyNeverUsesParameterEncoding() {
        HTTPArgument argument = new HTTPArgument("", "token=abc%2B123%2F%3D");
        argument.setAlwaysEncoded(false);
        RecordedValueReplacer.replaceArgument(argument, "abc+123/=", "${token}", true);
        assertEquals("token=${__urlencode(${token})}", argument.getValue());
        assertTrue(!argument.isAlwaysEncoded());
    }
}
