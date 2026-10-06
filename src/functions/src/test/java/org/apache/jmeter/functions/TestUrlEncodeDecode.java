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

package org.apache.jmeter.functions;

import java.util.ArrayList;
import java.util.Collection;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestUrlEncodeDecode extends JMeterTestCase {

    private SampleResult result;
    private Collection<CompoundVariable> params;
    private JMeterVariables vars;
    private JMeterContext jmctx;

    @BeforeEach
    void setUp() {
        result = new SampleResult();
        jmctx = JMeterContextService.getContext();
        String data = "The quick brown fox";
        result.setResponseData(data, null);
        vars = new JMeterVariables();
        jmctx.setVariables(vars);
        jmctx.setPreviousResult(result);
        params = new ArrayList<>();
    }

    @Test
    void testParameterCount() throws Exception {
        AbstractFunction function = new UrlEncode();
        checkInvalidParameterCounts(function, 1, 1);

        function = new UrlDecode();
        checkInvalidParameterCounts(function, 1, 1);
    }

    @Test
    void testUrlEncode() throws Exception {
        AbstractFunction function = new UrlEncode();
        params.add(new CompoundVariable("Veni, vidi, vici ?"));
        function.setParameters(params);
        String returnValue = function.execute(result, null);
        Assertions.assertEquals("Veni%2C+vidi%2C+vici+%3F", returnValue);
    }

    @Test
    void evaluatesRecordedEncodingExpressionsWithChangingRuntimeValues() throws Exception {
        CompoundVariable form = new CompoundVariable("${__urlencode(${token})}");
        CompoundVariable percent = new CompoundVariable("${__strReplace(${__urlencode(${token})},+,%20)}");
        for (String value : java.util.List.of("csrf +/é=123", "another ,+/% value")) {
            vars.put("token", value);
            String encoded = java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
            Assertions.assertEquals(encoded, form.execute());
            Assertions.assertEquals(encoded.replace("+", "%20"), percent.execute());
        }
    }

    @Test
    void decodesEncodedExtractionBeforeParameterEncodingAtReplay() throws Exception {
        CompoundVariable decoded = new CompoundVariable("${__urldecode(${__strReplace(${state},+,%2B)})}");
        for (String value : java.util.List.of("abc%2Fdef", "next+token%2F%C3%A9", "literal%252Ftoken")) {
            vars.put("state", value);
            String decodedValue = java.net.URLDecoder.decode(value.replace("+", "%2B"),
                    java.nio.charset.StandardCharsets.UTF_8);
            Assertions.assertEquals(decodedValue, decoded.execute());
            String expected = value.replace("+", "%2B");
            Assertions.assertEquals(expected, java.net.URLEncoder.encode(decoded.execute(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void testUrlDecode() throws Exception {
        AbstractFunction function = new UrlDecode();
        params.add(new CompoundVariable("Veni%2C+vidi%2C+vici+%3F"));
        function.setParameters(params);
        String returnValue = function.execute(result, null);
        Assertions.assertEquals("Veni, vidi, vici ?", returnValue);
    }
}
