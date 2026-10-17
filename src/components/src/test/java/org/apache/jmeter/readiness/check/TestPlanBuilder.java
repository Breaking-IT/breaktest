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

package org.apache.jmeter.readiness.check;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.gui.action.Start;
import org.apache.jmeter.gui.action.validation.TreeClonerForValidation;
import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.PlanFingerprint;
import org.apache.jmeter.readiness.evidence.ReadinessEvidenceStore;
import org.apache.jmeter.readiness.evidence.RunKind;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

/**
 * Builds in-process test plans the way the GUI holds them, and runs readiness validations of them through the
 * validation cloner and the engine, as {@link Start} does.
 */
final class TestPlanBuilder {
    final TestPlan plan = new TestPlan("plan");
    final Node root = new Node(plan);

    static final class Node {
        final TestElement element;
        final List<Node> children = new ArrayList<>();

        Node(TestElement element) {
            this.element = element;
        }

        Node add(TestElement child) {
            Node node = new Node(child);
            children.add(node);
            return node;
        }

        <T extends TestElement> T with(T child) {
            add(child);
            return child;
        }

        void fill(HashTree tree) {
            HashTree sub = tree.add(element);
            for (Node child : children) {
                child.fill(sub);
            }
        }
    }

    /**
     * @return a fresh tree over the same elements, like {@code JMeterTreeModel.getTestPlan()}
     */
    ListedHashTree tree() {
        ListedHashTree tree = new ListedHashTree();
        root.fill(tree);
        return tree;
    }

    static ThreadGroup group(String id) {
        ThreadGroup group = new ThreadGroup();
        group.setName(id);
        group.setThreadGroupId(id);
        group.setNumThreads(1);
        group.setSamplerController(new LoopController());
        return group;
    }

    static EchoSampler sampler(String name, String path) {
        EchoSampler sampler = new EchoSampler();
        sampler.setName(name);
        sampler.setProperty("HTTPSampler.path", path);
        sampler.setProperty("url", "http://localhost" + path);
        return sampler;
    }

    PlanModel model(AbstractThreadGroup... groups) {
        return PlanModel.build(Start.validationTree(tree(), groups));
    }

    /**
     * @return the context a check runs with, without runtime evidence
     */
    CheckContext context(AbstractThreadGroup... groups) {
        return new CheckContext(model(groups), null, ReadinessOptions.defaults(), Map.of(), null, Map.of());
    }

    ReadinessReport analyze(Set<CheckId> checks, ReadinessOptions options, AbstractThreadGroup... groups) {
        return analyze(checks, options, Map.of(), groups);
    }

    ReadinessReport analyze(Set<CheckId> checks, ReadinessOptions options, Map<String, List<Long>> offline,
            AbstractThreadGroup... groups) {
        PlanModel model = model(groups);
        return ReadinessAnalyzer.analyze(new ReadinessAnalyzer.Input(model, Start.readinessScope(groups),
                List.of(), checks, ReadinessEvidenceStore.latest(), ValidationCheckpoints.generation(), options,
                offline, null, Map.of()));
    }

    static Set<CheckId> all() {
        return EnumSet.allOf(CheckId.class);
    }

    /**
     * Runs a readiness validation of the groups, collecting evidence.
     */
    RunSegment run(RunKind kind, ValidationSettings settings, AbstractThreadGroup... groups) throws Exception {
        if (kind == RunKind.MAIN) {
            ValidationCheckpoints.clear();
        }
        HashTree scope = Start.validationTree(tree(), groups);
        String fingerprint = PlanFingerprint.compute(scope, settings, Start.readinessScope(groups));
        TreeClonerForValidation cloner = new TreeClonerForValidation(false);
        cloner.setSettings(settings);
        scope.traverse(cloner);
        ListedHashTree cloned = cloner.getClonedTree();
        ValidationCheckpoints.rememberStructure(cloned);
        List<String> scopeIds = new ArrayList<>();
        for (AbstractThreadGroup group : groups) {
            scopeIds.add(ValidationCheckpoints.sourceIdOfSource(group));
        }
        RunSegment segment = new RunSegment(kind, fingerprint, settings, scopeIds,
                ValidationCheckpoints.generation(), Set.of());
        JMeterContextService.setValidationRun(true);
        ReadinessEvidenceStore.begin(segment);
        try {
            StandardJMeterEngine engine = new StandardJMeterEngine();
            engine.configure(cloned);
            engine.runTest();
            engine.awaitTermination(Duration.ofSeconds(20));
        } finally {
            ReadinessEvidenceStore.end(segment);
            JMeterContextService.setValidationRun(false);
        }
        return segment;
    }

    /**
     * A sampler that "sends" its url, headers and body properties and returns its response property; the response
     * of iteration N can be overridden with a {@code response.N} property.
     */
    public static class EchoSampler extends AbstractSampler {
        private static final long serialVersionUID = 1L;

        @Override
        @SuppressWarnings("deprecation") // the URL may hold an unresolved "${...}", which URI rejects
        public SampleResult sample(Entry entry) {
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            try {
                result.setURL(new java.net.URL(getPropertyAsString("url", "http://localhost/")));
            } catch (java.net.MalformedURLException e) {
                throw new IllegalStateException(e);
            }
            result.setRequestHeaders(getPropertyAsString("headers"));
            result.setSamplerData(getPropertyAsString("body"));
            int iteration = JMeterContextService.getContext().getVariables().getIteration();
            String response = getPropertyAsString("response." + iteration, getPropertyAsString("response"));
            result.setResponseData(response, "UTF-8");
            if (getPropertyAsBoolean("cancel")) {
                // Stands for the user pressing Cancel in the wizard while the run executes
                org.apache.jmeter.readiness.evidence.ReadinessRecorder.active().markStatus(
                        org.apache.jmeter.readiness.evidence.RunStatus.CANCELLED, "Cancelled by the user");
            }
            String code = getPropertyAsString("code." + iteration, getPropertyAsString("code", "200"));
            result.setResponseCode(code);
            result.setSuccessful(!getPropertyAsBoolean("fail") && code.compareTo("400") < 0);
            result.sampleEnd();
            return result;
        }
    }
}
