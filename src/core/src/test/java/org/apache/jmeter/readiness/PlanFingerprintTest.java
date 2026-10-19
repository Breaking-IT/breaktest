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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

class PlanFingerprintTest extends JMeterTestCase {
    private final TestPlan plan = new TestPlan("plan");
    private final ThreadGroup group = new ThreadGroup();
    private final ConfigTestElement sampler = new ConfigTestElement();

    private ListedHashTree tree() {
        ListedHashTree tree = new ListedHashTree();
        tree.add(plan).add(group).add(sampler);
        return tree;
    }

    private String fingerprint() {
        return PlanFingerprint.compute(tree(), ValidationSettings.defaults(), List.of("tg"));
    }

    @Test
    void editorPropertyEditMakesEvidenceStale() {
        sampler.setProperty("HTTPSampler.path", "/login");
        String before = fingerprint();
        assertEquals(before, fingerprint());
        // An edit made only in the editor panel reaches the element when the panel is committed; no tree event fires
        sampler.setProperty("HTTPSampler.path", "/logout");
        assertNotEquals(before, fingerprint());
    }

    @Test
    void exceptionsCommentsAndGuiClassAreLeftOut() {
        String before = fingerprint();
        ReadinessExceptions.put(sampler, new AcceptedException(CheckId.THINK_TIME, "*", "API", "", ""));
        sampler.setProperty(TestElement.COMMENTS, "a comment");
        sampler.setProperty(TestElement.GUI_CLASS, "SomeGui");
        assertEquals(before, fingerprint());
    }

    @Test
    void propertyOrderDoesNotMatter() {
        sampler.setProperty("a", "1");
        sampler.setProperty("b", "2");
        String before = fingerprint();
        sampler.removeProperty("a");
        sampler.setProperty("a", "1");
        assertEquals(before, fingerprint());
    }

    @Test
    void settingsAndScopeAreIncluded() {
        String before = fingerprint();
        assertNotEquals(before, PlanFingerprint.compute(tree(), ValidationSettings.defaults().withIterations(5),
                List.of("tg")));
        assertNotEquals(before, PlanFingerprint.compute(tree(), ValidationSettings.defaults(), List.of("other")));
        assertEquals(PlanFingerprint.compute(tree(), null, List.of("a", "b")),
                PlanFingerprint.compute(tree(), null, List.of("b", "a")));
    }

    @Test
    void structureIsIncluded() {
        String before = fingerprint();
        ListedHashTree moved = new ListedHashTree();
        moved.add(plan).add(group);
        moved.getTree(plan).add(sampler);
        assertNotEquals(before, PlanFingerprint.compute(moved, ValidationSettings.defaults(), List.of("tg")));
    }
}
