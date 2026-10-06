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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.junit.jupiter.api.Test;

class RecordedValueReplacerTest extends JMeterTestCase {
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
