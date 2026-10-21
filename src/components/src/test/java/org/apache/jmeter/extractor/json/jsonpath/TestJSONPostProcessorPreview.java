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

package org.apache.jmeter.extractor.json.jsonpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TestJSONPostProcessorPreview implements JMeterSerialTest {
    private static JSONPostProcessor extractor(String path, String match) {
        JSONPostProcessor extractor = new JSONPostProcessor();
        extractor.setRefNames("value");
        extractor.setJsonPathExpressions(path);
        extractor.setMatchNumbers(match);
        extractor.setDefaultValues("missing");
        extractor.setComputeConcatenation(true);
        return extractor;
    }

    private static SampleResult sample(String body) {
        SampleResult sample = new SampleResult();
        sample.setResponseData(body, "UTF-8");
        sample.setSuccessful(true);
        return sample;
    }

    @ParameterizedTest
    @CsvSource({"$[*], -1", "$[*], 1", "$[*], 2", "$[*], 9", "$.absent, -1", "$.absent, 1"})
    void matchesRuntimeVariablesWithoutMutatingSample(String path, String match) {
        JSONPostProcessor extractor = extractor(path, match);
        SampleResult sample = sample("[1,null,{\"id\":3}]");
        var preview = extractor.extractForTesting(sample);
        assertTrue(sample.isSuccessful());
        assertEquals(0, sample.getAssertionResults().length);
        var context = JMeterContextService.getContext();
        try {
            JMeterVariables variables = new JMeterVariables();
            context.setVariables(variables);
            context.setPreviousResult(sample);
            extractor.setThreadContext(context);
            extractor.process();
            Map<String, String> actual = new TreeMap<>();
            variables.getIterator().forEachRemaining(entry -> actual.put(entry.getKey(), (String) entry.getValue()));
            assertEquals(actual, preview.variables());
        } finally {
            context.clear();
        }
    }

    @Test
    void excludesPreloadedVariablesButAllowsExplicitlyExtractingTheSameName() throws Exception {
        var propertiesField = JMeterUtils.class.getDeclaredField("appProperties");
        propertiesField.setAccessible(true);
        Object previous = propertiesField.get(null);
        try {
            java.util.Properties properties = new java.util.Properties();
            for (String name : new String[] {"START.MS", "START.YMD", "START.HMS", "TESTSTART.MS"}) {
                properties.setProperty(name, "preloaded");
            }
            propertiesField.set(null, properties);
            JSONPostProcessor extractor = extractor("$.id", "1");
            SampleResult sample = sample("{\"id\":42}");
            assertEquals(Map.of("value", "42", "value_matchNr", "1"),
                    extractor.extractForTesting(sample).variables());
            extractor.setRefNames("START.MS");
            assertEquals(Map.of("START.MS", "42", "START.MS_matchNr", "1"),
                    extractor.extractForTesting(sample).variables());
        } finally {
            propertiesField.set(null, previous);
        }
    }

    @Test
    void handlesMultipleExpressionsAndCounts() {
        JSONPostProcessor extractor = extractor("$.ids[*];$.name", "-1;1");
        extractor.setRefNames("ids;name");
        extractor.setDefaultValues(";unknown");
        var result = extractor.extractForTesting(sample("{\"ids\":[1,2],\"name\":\"demo\"}"));
        assertEquals(3, result.matchCount());
        assertEquals(Map.of("ids_1", "1", "ids_2", "2", "ids_ALL", "1,2", "ids_matchNr", "2",
                "name", "demo", "name_matchNr", "1"), result.variables());
    }

    @Test
    void honorsSampleScope() {
        JSONPostProcessor extractor = extractor("$.id", "-1");
        SampleResult parent = sample("{\"id\":1}");
        parent.addSubResult(sample("{\"id\":2}"));
        extractor.setScopeChildren();
        assertEquals("2", extractor.extractForTesting(parent).variables().get("value_1"));
        extractor.setScopeAll();
        assertEquals(2, extractor.extractForTesting(parent).matchCount());
        extractor.setScopeParent();
        assertEquals("1", extractor.extractForTesting(parent).variables().get("value_1"));
        extractor.setScopeVariable("runtime");
        assertThrows(IllegalArgumentException.class, () -> extractor.extractForTesting(parent));
    }

    @Test
    void randomMatchIsOneOfTheValues() {
        var result = extractor("$[*]", "0").extractForTesting(sample("[1,2,3]"));
        assertTrue(java.util.List.of("1", "2", "3").contains(result.variables().get("value")));
        assertEquals(3, result.matchCount());
    }

    @Test
    void distinguishesErrorsFromNoMatches() {
        assertThrows(RuntimeException.class,
                () -> extractor("$[", "1").extractForTesting(sample("{}")));
        assertThrows(RuntimeException.class,
                () -> extractor("$", "1").extractForTesting(sample("{")));
        assertThrows(RuntimeException.class,
                () -> extractor("$", "invalid").extractForTesting(sample("{}")));
        assertThrows(IllegalArgumentException.class,
                () -> extractor("$.a;$.b", "1").extractForTesting(sample("{}")));
        assertEquals("missing", extractor("$.absent", "1").extractForTesting(sample("{}")).variables().get("value"));
    }
}
