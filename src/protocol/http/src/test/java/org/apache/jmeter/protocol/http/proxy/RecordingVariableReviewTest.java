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

package org.apache.jmeter.protocol.http.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.junit.jupiter.api.Test;

class RecordingVariableReviewTest extends JMeterTestCase {
    private static RecordedSampler sample(String path) throws Exception {
        var sampler = new HTTPSamplerProxy();
        sampler.setDomain("example.test");
        sampler.setMethod("POST");
        sampler.setPath(path);
        sampler.setName("Request 10");
        sampler.setProperty("BreakTest.recording.exchangeId", "capture-10");
        var result = new HTTPSampleResult();
        result.setURL(sampler.getUrl());
        result.setResponseCode("200");
        return new RecordedSampler(sampler, new TestElement[0], null, "", 0, result, false, null);
    }

    private static Arguments variables(String... pairs) {
        var variables = new Arguments();
        for (int i = 0; i < pairs.length; i += 2) {
            variables.addArgument(pairs[i], pairs[i + 1]);
        }
        return variables;
    }

    @Test
    void previewsAllAffectedFieldsWithoutMutatingAndAppliesOnlyAcceptedPairs() throws Exception {
        var sample = sample("/items/10?limit=100&token=secret");
        sample.sampler.addArgument("limit", "10");
        sample.sampler.setNativeHeaders(List.of(new Header("X-Limit", "10")));
        var candidates = RecordingVariableReview.find(List.of(sample), ignored ->
                List.of(variables("limit", "10", "token", "secret", "empty", "", "missing", "absent")));
        assertEquals(2, candidates.size());
        var limit = candidates.stream().filter(candidate -> candidate.pair().name().equals("limit")).findFirst().orElseThrow();
        assertEquals(3, limit.matches().size());
        assertTrue(limit.matches().stream().anyMatch(match -> match.after().contains("limit=${limit}0")));
        assertEquals("/items/10?limit=100&token=secret", sample.sampler.getPath());
        RecordingVariableReview.apply(List.of());
        assertEquals("10", sample.sampler.getArguments().getArgument(0).getValue());
        RecordingVariableReview.apply(List.of(limit));
        assertEquals("/items/${limit}?limit=${limit}0&token=secret", sample.sampler.getPath());
        assertEquals("${limit}", sample.sampler.getArguments().getArgument(0).getValue());
        assertEquals("${limit}", sample.sampler.getNativeHeaderList().get(0).getValue());
        assertEquals("Request 10", sample.sampler.getName());
        assertEquals("capture-10", sample.sampler.getPropertyAsString("BreakTest.recording.exchangeId"));
    }

    /** Counts copies so tests can tell whether the search skipped the expensive comparison. */
    public static class CountingSampler extends HTTPSamplerProxy {
        private static final long serialVersionUID = 1L;
        static final AtomicInteger CLONES = new AtomicInteger();

        @Override
        public Object clone() {
            CLONES.incrementAndGet();
            return super.clone();
        }
    }

    @Test
    void skipsCopyingRequestsThatCannotContainTheValue() throws Exception {
        var sampler = new CountingSampler();
        sampler.setDomain("example.test");
        sampler.setMethod("POST");
        sampler.setPath("/items/abc%2b123%2f%3d?limit=100");
        sampler.addArgument("q", "plain value");
        var result = new HTTPSampleResult();
        result.setURL(sampler.getUrl());
        var sample = new RecordedSampler(sampler, new TestElement[0], null, "", 0, result, false, null);

        CountingSampler.CLONES.set(0);
        assertTrue(RecordingVariableReview.find(List.of(sample), ignored ->
                List.of(variables("absent", "zzz", "longer", "value that is longer than any field"))).isEmpty());
        assertEquals(0, CountingSampler.CLONES.get(), "Values absent from every field must not copy the request");

        // Encoded (with lowercase escapes) and plain occurrences still reach the full comparison.
        var candidates = RecordingVariableReview.find(List.of(sample), ignored ->
                List.of(variables("token", "abc+123/=", "limit", "100", "query", "plain value")));
        assertTrue(CountingSampler.CLONES.get() > 0);
        assertEquals(List.of("query", "token", "limit"),
                candidates.stream().map(candidate -> candidate.pair().name()).toList());
    }

    @Test
    void mentionsMatchesEveryFormTheReplacerChanges() {
        assertTrue(RecordingVariableReview.mentions(List.of(Map.of("path", "/a/b%2Fc")), "b/c"));
        assertTrue(RecordingVariableReview.mentions(List.of(Map.of("path", "/a/b%2fc")), "b/c"));
        assertTrue(RecordingVariableReview.mentions(List.of(Map.of("body", "q=a+b"), Map.of("x", "y")), "a b"));
        assertTrue(RecordingVariableReview.mentions(List.of(Map.of("header", "a b")), "a%20b"));
        assertFalse(RecordingVariableReview.mentions(List.of(Map.of("path", "/a/b/c"), Map.of()), "zzz"));
        assertFalse(RecordingVariableReview.mentions(List.of(), "b/c"));
    }

    @Test
    void groupsPairsAcrossRequestsAndHonorsVariableOverridesPerTarget() throws Exception {
        var first = sample("/10");
        var second = sample("/20");
        var third = sample("/10");
        var candidates = RecordingVariableReview.find(List.of(first, second, third), sample ->
                sample == second ? List.of(variables("limit", "10"), variables("limit", "20"))
                        : List.of(variables("limit", "10")));
        assertEquals(2, candidates.size());
        assertEquals(2, candidates.get(0).samples().size());
        RecordingVariableReview.apply(List.of(candidates.get(0)));
        assertEquals("/${limit}", first.sampler.getPath());
        assertEquals("/20", second.sampler.getPath());
        assertEquals("/${limit}", third.sampler.getPath());
    }

    @Test
    void preservesEncodingAndExistingReferencesWithOverlappingAcceptedValues() throws Exception {
        var sample = sample("/abc%2B123%2F%3D/100/10/${existing10}");
        sample.sampler.setDomain("host100.test");
        var candidates = RecordingVariableReview.find(List.of(sample), ignored ->
                List.of(variables("token", "abc+123/=", "large10", "100", "small", "10")));
        RecordingVariableReview.apply(candidates);
        assertEquals("/${__urlencode(${token})}/${large10}/${small}/${existing10}", sample.sampler.getPath());
        assertEquals("host${large10}.test", sample.sampler.getDomain());
        assertFalse(candidates.isEmpty());
    }
}
