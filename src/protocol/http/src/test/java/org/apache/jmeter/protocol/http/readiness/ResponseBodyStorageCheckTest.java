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

package org.apache.jmeter.protocol.http.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.gui.action.Start;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.modifiers.JSR223PreProcessor;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase.ResponseProcessingMode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.CheckResult;
import org.apache.jmeter.readiness.CheckState;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.check.CheckContext;
import org.apache.jmeter.readiness.check.CheckOutcome;
import org.apache.jmeter.readiness.check.PlanModel;
import org.apache.jmeter.readiness.check.ReadinessAnalyzer;
import org.apache.jmeter.readiness.check.ReadinessOptions;
import org.apache.jmeter.reporters.ResultSaver;
import org.apache.jmeter.scenario.Profile;
import org.apache.jmeter.scenario.ProfilesSection;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResponseBodyStorageCheckTest extends JMeterTestCase {
    private final TestPlan plan = new TestPlan("plan");
    private final ThreadGroup group = new ThreadGroup();
    private final ConfigTestElement defaults = new ConfigTestElement();
    private final List<TestElement[]> flow = new ArrayList<>();

    ResponseBodyStorageCheckTest() {
        group.setName("users");
        group.setThreadGroupId("users");
        group.setSamplerController(new LoopController());
        defaults.setName("HTTP Request Defaults");
        defaults.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui");
    }

    @AfterEach
    void cleanup() {
        ValidationCheckpoints.clear();
    }

    private static HTTPSamplerProxy request(String name) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName(name);
        sampler.setDomain("localhost");
        sampler.setPath("/" + name);
        return sampler;
    }

    /** Adds a request to the thread group, with elements under it. */
    private HTTPSamplerProxy add(HTTPSamplerProxy sampler, TestElement... children) {
        TestElement[] entry = new TestElement[children.length + 1];
        entry[0] = sampler;
        System.arraycopy(children, 0, entry, 1, children.length);
        flow.add(entry);
        return sampler;
    }

    private PlanModel model(TestElement... groupLevel) {
        ListedHashTree tree = new ListedHashTree();
        HashTree planTree = tree.add(plan);
        HashTree groupTree = planTree.add(group);
        groupTree.add(defaults);
        for (TestElement element : groupLevel) {
            groupTree.add(element);
        }
        for (TestElement[] entry : flow) {
            HashTree samplerTree = groupTree.add(entry[0]);
            for (int i = 1; i < entry.length; i++) {
                samplerTree.add(entry[i]);
            }
        }
        return PlanModel.build(Start.validationTree(tree, new AbstractThreadGroup[]{group}));
    }

    private CheckOutcome run(TestElement... groupLevel) {
        return new ResponseBodyStorageCheck().run(new CheckContext(model(groupLevel), null,
                ReadinessOptions.defaults(), Map.of(), null, Map.of()));
    }

    private static Finding finding(CheckOutcome outcome, String key) {
        return outcome.findings().stream().filter(f -> f.findingKey().equals(key)).findFirst().orElseThrow(
                () -> new AssertionError(outcome.findings().toString()));
    }

    private static RegexExtractor extractor() {
        RegexExtractor extractor = new RegexExtractor();
        extractor.setRefName("token");
        extractor.setRegex("t=(\\w+)");
        return extractor;
    }

    private static JSR223PreProcessor preProcessor(String script) {
        JSR223PreProcessor pre = new JSR223PreProcessor();
        pre.setName("pre");
        pre.setProperty("script", script);
        return pre;
    }

    @Test
    void inheritedStoreResponseIsOneSuggestionOnTheDefaults() {
        add(request("a"));
        add(request("b"));
        add(request("c"), extractor());
        CheckOutcome outcome = run();
        Finding suggestion = finding(outcome, "defaults");
        assertEquals(Severity.SUGGESTION, suggestion.severity());
        assertEquals("HTTP Request Defaults", suggestion.element().name());
        assertTrue(suggestion.message().contains("2 of 3 requests would discard successful bodies"),
                suggestion.message());
        assertTrue(suggestion.evidence().contains(
                "1 request keeps its body automatically while assertions or extractors apply to them."),
                suggestion.evidence().toString());
        assertEquals(1, outcome.findings().size(), outcome.findings().toString());
    }

    @Test
    void defaultsAlreadyStoringOnErrorOnlyNeedNothing() {
        defaults.setProperty("HTTPSampler.responseProcessingMode",
                ResponseProcessingMode.STORE_ON_ERROR.getResourceKey());
        add(request("a"));
        add(request("b"));
        assertTrue(run().findings().isEmpty());
    }

    @Test
    void explicitStoreResponseWithoutReadersIsAPerRequestSuggestion() {
        defaults.setProperty("HTTPSampler.responseProcessingMode",
                ResponseProcessingMode.STORE_ON_ERROR.getResourceKey());
        HTTPSamplerProxy pinned = add(request("pinned"));
        pinned.setResponseProcessingMode(ResponseProcessingMode.STORE_COMPRESSED);
        HTTPSamplerProxy checked = add(request("checked"), extractor());
        checked.setResponseProcessingMode(ResponseProcessingMode.STORE_COMPRESSED);
        CheckOutcome outcome = run();
        assertEquals(List.of("pinned"), outcome.findings().stream().map(f -> f.element().name()).toList(),
                "A request whose extractor keeps the body automatically saves nothing by switching");
        assertEquals("store", outcome.findings().get(0).findingKey());
    }

    @Test
    void nextRequestReadingThePreviousBodyMustKeepStoreResponse() {
        add(request("search"));
        add(request("details"), preProcessor("def html = prev.getResponseDataAsString()"));
        add(request("other"));
        add(request("more"));
        Finding suggestion = finding(run(), "defaults");
        assertTrue(suggestion.evidence().stream().anyMatch(line -> line.startsWith("Set explicitly to Store response:")
                && line.contains("search (read by 'pre')")), suggestion.evidence().toString());
    }

    @Test
    void embeddedResourcesMustKeepStoreResponse() {
        HTTPSamplerProxy page = add(request("page"));
        page.setImageParser(true);
        add(request("api1"));
        add(request("api2"));
        Finding suggestion = finding(run(), "defaults");
        assertTrue(suggestion.evidence().stream().anyMatch(line -> line.contains(
                "page (retrieves embedded resources from its body)")), suggestion.evidence().toString());
    }

    @Test
    void savingSuccessfulResponsesKeepsTheirBodies() {
        add(request("a"));
        add(request("b"));
        ResultSaver saver = new ResultSaver();
        saver.setName("save responses");
        Finding suggestion;
        CheckOutcome outcome = run(saver);
        assertTrue(outcome.findings().isEmpty(), "Every request must keep its body: " + outcome.findings());
        saver.setProperty(ResultSaver.ERRORS_ONLY, true);
        suggestion = finding(run(saver), "defaults");
        assertTrue(suggestion.message().contains("2 of 2"), suggestion.message());
        // Disabled listeners do not run
        saver.setProperty(ResultSaver.ERRORS_ONLY, false);
        saver.setEnabled(false);
        assertTrue(finding(run(saver), "defaults").message().contains("2 of 2"));
    }

    @Test
    void scriptsUsingThePreviousResultAreReviewedNotCounted() {
        add(request("a"));
        add(request("b"), preProcessor("log.info(prev.getSampleLabel())"));
        add(request("c"));
        Finding suggestion = finding(run(), "defaults");
        assertTrue(suggestion.evidence().stream().anyMatch(line -> line.startsWith("Possibly reads the body")
                && line.contains("a (previous result used by 'pre')")), suggestion.evidence().toString());
        // Only "a" is in doubt: "b" and "c" still discard
        assertTrue(suggestion.message().contains("2 of 3"), suggestion.message());
    }

    @Test
    void discardingARequestWhoseBodyIsReadIsFlagged() {
        defaults.setProperty("HTTPSampler.responseProcessingMode",
                ResponseProcessingMode.FETCH_AND_DISCARD.getResourceKey());
        add(request("search"));
        add(request("details"), preProcessor("vars.put('html', prev.getResponseDataAsString())"));
        Finding keep = finding(run(), "keep");
        assertEquals("search", keep.element().name());
        assertEquals(Severity.SUGGESTION, keep.severity());
    }

    @Test
    void defaultsInAProfileAreReportedOnTheProfileElementNotOnItsCopy() {
        // With sections, the default profile's configuration is copied into each thread group when flattening
        ListedHashTree tree = new ListedHashTree();
        HashTree planTree = tree.add(plan);
        HashTree groups = planTree.add(new ThreadGroupsSection());
        HashTree groupTree = groups.add(group);
        groupTree.add(request("a"));
        groupTree.add(request("b"));
        Profile profile = new Profile("acceptance");
        profile.setDefault(true);
        planTree.add(new ProfilesSection()).add(profile).add(defaults);
        AbstractThreadGroup[] scope = {group};
        CheckOutcome outcome = ValidationCheckpoints.withSourceIdentity(() -> {
            PlanModel model = PlanModel.build(Start.validationTree(tree, scope));
            assertTrue(model.nodes().stream().noneMatch(node -> node.element() == defaults),
                    "The flattened tree holds a copy of the defaults");
            return new ResponseBodyStorageCheck().run(new CheckContext(model, null, ReadinessOptions.defaults(),
                    Map.of(), null, Map.of()));
        });
        Finding suggestion = finding(outcome, "defaults");
        assertEquals(ValidationCheckpoints.sourceIdOfSource(defaults), suggestion.element().sourceId(),
                "The finding must point at the defaults in the profile, so the wizard can select it");
    }

    @Test
    void suggestionsNeverChangeTheVerdict() {
        add(request("a"));
        add(request("b"));
        var report = ReadinessAnalyzer.analyze(new ReadinessAnalyzer.Input(model(), Start.readinessScope(
                new AbstractThreadGroup[]{group}), List.of("users"), Set.of(CheckId.RESPONSE_BODY_STORAGE), null,
                ValidationCheckpoints.generation(), ReadinessOptions.defaults(), Map.of(), null, Map.of()));
        CheckResult result = report.result(CheckId.RESPONSE_BODY_STORAGE).orElseThrow();
        assertEquals(CheckState.SUGGESTION, result.state(), "The check is provided by the HTTP module");
        assertTrue(result.isVerified());
        assertEquals(0, report.summary().blockers() + report.summary().warnings() + report.summary().reviews());
        assertEquals(1, report.summary().suggestions());
        assertEquals(1, report.summary().passed());
    }
}
