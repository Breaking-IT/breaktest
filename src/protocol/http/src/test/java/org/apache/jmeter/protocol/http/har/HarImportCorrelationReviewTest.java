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

package org.apache.jmeter.protocol.http.har;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.SearchByClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HarImportCorrelationReviewTest extends JMeterTestCase {
    private static final String TOKEN = "recorded-token";
    private static final byte[] HAR = """
            {"log":{"entries":[
              {"startedDateTime":"2026-01-01T00:00:00Z","time":10,"serverIPAddress":"127.0.0.1",
               "request":{"method":"GET","url":"https://excluded.test/","headers":[]},
               "response":{"status":200,"content":{"text":"excluded"}}},
              {"startedDateTime":"2026-01-01T00:00:01Z","time":10,"serverIPAddress":"127.0.0.1",
               "request":{"method":"GET","url":"https://example.test/start",
                 "headers":[{"name":"X-Token","value":"recorded-token"}]},
               "response":{"status":200,"content":{"mimeType":"text/plain","text":"token=recorded-token;"}}},
              {"startedDateTime":"2026-01-01T00:00:02Z","time":10,"serverIPAddress":"127.0.0.1",
               "request":{"method":"POST","url":"https://example.test/submit",
                 "headers":[{"name":"X-Token","value":"recorded-token"}],
                 "postData":{"mimeType":"application/x-www-form-urlencoded",
                   "params":[{"name":"token","value":"recorded-token:recorded-token"}]}},
               "response":{"status":200,"content":{"text":"OK"}}}
            ]}}
            """.getBytes(StandardCharsets.UTF_8);

    private static List<HarPredefinedCorrelation> correlations(List<HarEntry> entries) {
        var rule = new HarPredefinedCorrelation.Rule("test", "test", "Test", "token",
                HarPredefinedCorrelation.ExtractorType.REGEX, HarPredefinedCorrelation.ResponseField.BODY,
                "token=([^;]+);", "$1$", "", false, false, false);
        var correlations = HarPredefinedCorrelation.find(entries, Set.of("example.test"), List.of(rule));
        assertEquals(1, correlations.size());
        return correlations;
    }

    @ParameterizedTest
    @EnumSource(RecordingStorageMode.class)
    void importsUnchangedMatchesAndKeepsOriginalIdentityAcrossArchiveFiltering(
            RecordingStorageMode storage) throws Exception {
        var entries = HarParser.parse(HAR);
        var correlations = correlations(entries);
        HarImportOptions options = new HarImportOptions();
        options.setRecordingStorageMode(storage);
        HarImportWizard.configureCorrelationReview(options, correlations, true);
        assertTrue(options.getPredefinedCorrelations().isEmpty());
        assertEquals(correlations, options.getStepByStepCorrelations());
        var result = new HarImportWizard.Result(entries, Set.of("example.test"), options, "review.har", "md5", HAR);
        var converted = HarImportAction.convertAndRegister(result, options,
                new HarConverter(entries, options, "review.har", "md5"));
        assertEquals(Set.of(1, 2), converted.requests().keySet());
        for (var request : converted.requests().values()) {
            assertTrue(request.getPropertyAsString(RecordedHarExchangeResolver.HAR_ENTRY_INDEX).isEmpty());
        }
        SwingUtilities.invokeAndWait(() -> {
            JMeterTreeModel model = new JMeterTreeModel(new TestPlan("Existing plan"));
            assertNotNull(HarImportAction.insertUnderTestPlan(model, converted.tree()));
            var nodes = HarImportAction.importedCorrelationNodes(model, converted.requests());
            assertSame(converted.requests().get(1), nodes.get(1).getTestElement());
            assertSame(converted.requests().get(2), nodes.get(2).getTestElement());
            assertTrue(model.getNodesOfType(RegexExtractor.class).isEmpty());
            var target = (HTTPSamplerProxy) nodes.get(2).getTestElement();
            assertEquals(TOKEN + ":" + TOKEN, target.getArguments().getArgument(0).getValue());
            // Even a header shared by all imported requests stays native for live review.
            assertEquals(TOKEN, target.getNativeHeaderList().get(0).getValue());
            var steps = CorrelationReviewStep.create(options.getStepByStepCorrelations(), nodes);
            assertEquals(3, steps.size());
            assertTrue(steps.get(0).accept(steps));
            steps.get(1).decision = CorrelationReviewStep.Decision.REJECTED;
            assertFalse(steps.get(1).accept(steps));
            assertEquals("${token}:" + TOKEN, target.getArguments().getArgument(0).getValue());
            assertEquals(TOKEN, target.getNativeHeaderList().get(0).getValue());
        });
    }

    @Test
    void normalImportStillAppliesSelectedCorrelationsDuringConversion() throws Exception {
        var entries = HarParser.parse(HAR);
        var correlations = correlations(entries);
        HarImportOptions options = new HarImportOptions();
        HarImportWizard.configureCorrelationReview(options, correlations, true);
        HarImportWizard.configureCorrelationReview(options, correlations, false);
        assertTrue(options.getStepByStepCorrelations().isEmpty());
        assertEquals(correlations, options.getPredefinedCorrelations());
        var tree = new HarConverter(entries, options, "review.har", "md5").convert(Set.of("example.test"));
        var samplers = new SearchByClass<>(HTTPSamplerProxy.class);
        tree.traverse(samplers);
        var target = samplers.getSearchResults().stream().filter(sampler -> sampler.getMethod().equals("POST"))
                .findFirst().orElseThrow();
        assertEquals("${token}:${token}", target.getArguments().getArgument(0).getValue());
        var extractors = new SearchByClass<>(RegexExtractor.class);
        tree.traverse(extractors);
        assertEquals(1, extractors.getSearchResults().size());
    }
}
