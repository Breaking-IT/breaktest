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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.gui.action.Start;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.evidence.ReadinessEvidenceStore;
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
 * Builds in-process test plans the way the GUI holds them.
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
        return new CheckContext(model(groups), null, ReadinessOptions.defaults(), Map.of(), Map.of());
    }

    ReadinessReport analyze(Set<CheckId> checks, ReadinessOptions options, AbstractThreadGroup... groups) {
        return analyze(checks, options, Map.of(), groups);
    }

    ReadinessReport analyze(Set<CheckId> checks, ReadinessOptions options, Map<String, List<Long>> offline,
            AbstractThreadGroup... groups) {
        PlanModel model = model(groups);
        return ReadinessAnalyzer.analyze(new ReadinessAnalyzer.Input(model, Start.readinessScope(groups),
                List.of(), checks, ReadinessEvidenceStore.latest(), ValidationCheckpoints.generation(), options,
                offline, Map.of()));
    }

    static Set<CheckId> all() {
        return EnumSet.allOf(CheckId.class);
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
            String code = getPropertyAsString("code." + iteration, getPropertyAsString("code", "200"));
            result.setResponseCode(code);
            result.setSuccessful(!getPropertyAsBoolean("fail") && code.compareTo("400") < 0);
            result.sampleEnd();
            return result;
        }
    }
}
