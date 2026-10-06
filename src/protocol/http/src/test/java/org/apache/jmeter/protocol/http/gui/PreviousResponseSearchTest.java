/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jmeter.protocol.http.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.jmeter.control.ModuleController;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

class PreviousResponseSearchTest {
    @Test
    void includesOnlyEarlierHttpSamplersAndPreservesDetachedAncestorMetadata() {
        var root = new JMeterTreeNode(new TestPlan(), null);
        root.setName("root");
        var plan = new JMeterTreeNode(new TestPlan(), null);
        plan.setName("Test Plan");
        root.add(plan);
        var threadGroup = new JMeterTreeNode(new ThreadGroup(), null);
        threadGroup.setName("Users");
        plan.add(threadGroup);
        var transaction = new JMeterTreeNode(new TransactionController(), null);
        transaction.setName("Login");
        transaction.getTestElement().setProperty("recording", "source");
        threadGroup.add(transaction);
        var previous = new JMeterTreeNode(new HTTPSamplerProxy(), null);
        previous.setName("Token");
        transaction.add(previous);
        var current = new JMeterTreeNode(new HTTPSamplerProxy(), null);
        transaction.add(current);
        transaction.add(new JMeterTreeNode(new HTTPSamplerProxy(), null));

        var candidates = PreviousResponseSearch.previousSamplers(current);
        assertEquals(1, candidates.size());
        var candidate = candidates.get(0);
        assertSame(previous, candidate.target());
        assertEquals("Test Plan / Users / Login / Token", candidate.path());
        assertNotSame(previous.getTestElement(), candidate.snapshot().getTestElement());
        var parent = (JMeterTreeNode) candidate.snapshot().getParent();
        transaction.getTestElement().setProperty("recording", "changed");
        assertEquals("source", parent.getTestElement().getPropertyAsString("recording"));
        assertTrue(PreviousResponseSearch.previousSamplers(previous).isEmpty());
    }

    @Test
    void excludesOtherThreadGroupsButIncludesEarlierTransactionsInTheSameGroup() {
        var plan = new JMeterTreeNode(new TestPlan(), null);
        var otherGroup = new JMeterTreeNode(new ThreadGroup(), null);
        plan.add(otherGroup);
        otherGroup.add(new JMeterTreeNode(new HTTPSamplerProxy(), null));
        var threadGroup = new JMeterTreeNode(new ThreadGroup(), null);
        plan.add(threadGroup);
        var earlierTransaction = new JMeterTreeNode(new TransactionController(), null);
        threadGroup.add(earlierTransaction);
        var previous = new JMeterTreeNode(new HTTPSamplerProxy(), null);
        earlierTransaction.add(previous);
        var transaction = new JMeterTreeNode(new TransactionController(), null);
        threadGroup.add(transaction);
        var current = new JMeterTreeNode(new HTTPSamplerProxy(), null);
        transaction.add(current);
        transaction.add(new JMeterTreeNode(new HTTPSamplerProxy(), null));

        assertEquals(List.of(previous), PreviousResponseSearch.previousSamplers(current)
                .stream().map(PreviousResponseSearch.Candidate::target).toList());
        assertTrue(PreviousResponseSearch.previousSamplers(previous).isEmpty());
    }

    @Test
    void doesNotSearchThePlanWhenCurrentSamplerHasNoThreadGroup() {
        var plan = new JMeterTreeNode(new TestPlan(), null);
        var threadGroup = new JMeterTreeNode(new ThreadGroup(), null);
        plan.add(threadGroup);
        threadGroup.add(new JMeterTreeNode(new HTTPSamplerProxy(), null));
        var current = new JMeterTreeNode(new HTTPSamplerProxy(), null);
        plan.add(current);

        assertTrue(PreviousResponseSearch.previousSamplers(current).isEmpty());
    }

    @Test
    void searchesEarlierRequestsWithinTheCurrentFragment() {
        var plan = new JMeterTreeNode(new TestPlan(), null);
        var unrelated = add(plan, new TestFragmentController());
        add(unrelated, new HTTPSamplerProxy());
        var fragment = add(plan, new TestFragmentController());
        fragment.setName("Login");
        var previous = add(fragment, new HTTPSamplerProxy());
        previous.setName("Token");
        var transaction = add(fragment, new TransactionController());
        var current = add(transaction, new HTTPSamplerProxy());
        add(transaction, new HTTPSamplerProxy());

        var candidates = PreviousResponseSearch.previousSamplers(current);
        assertEquals(List.of(previous), candidates.stream().map(PreviousResponseSearch.Candidate::target).toList());
        assertEquals("Login / Token", candidates.get(0).path());
        assertTrue(PreviousResponseSearch.previousSamplers(previous).isEmpty());
    }

    @Test
    void followsEarlierModulesIncludingNestedReferencesWithoutDuplicatesOrCycles() {
        var plan = new JMeterTreeNode(new TestPlan(), null);
        var login = add(plan, new TestFragmentController());
        var token = add(login, new HTTPSamplerProxy());
        var wrapper = add(plan, new TestFragmentController());
        addModule(wrapper, login);
        addModule(login, wrapper);
        var laterFragment = add(plan, new TestFragmentController());
        add(laterFragment, new HTTPSamplerProxy());
        var unusedFragment = add(plan, new TestFragmentController());
        add(unusedFragment, new HTTPSamplerProxy());
        var otherGroup = add(plan, new ThreadGroup());
        var otherController = add(otherGroup, new TransactionController());
        var referencedToken = add(otherController, new HTTPSamplerProxy());
        add(otherGroup, new HTTPSamplerProxy());
        var group = add(plan, new ThreadGroup());
        addModule(group, wrapper);
        addModule(group, login);
        addModule(group, otherController);
        add(group, new ModuleController());
        var previous = add(group, new HTTPSamplerProxy());
        var current = add(group, new HTTPSamplerProxy());
        addModule(group, laterFragment);

        var candidates = PreviousResponseSearch.previousSamplers(current);
        assertEquals(List.of(token, referencedToken, previous),
                candidates.stream().map(PreviousResponseSearch.Candidate::target).toList());
        assertNotSame(token.getTestElement(), candidates.get(0).snapshot().getTestElement());
    }

    @Test
    void ignoresModuleTargetsFromAnotherPlan() {
        var otherPlan = new JMeterTreeNode(new TestPlan(), null);
        var target = add(otherPlan, new TransactionController());
        add(target, new HTTPSamplerProxy());
        var plan = new JMeterTreeNode(new TestPlan(), null);
        var group = add(plan, new ThreadGroup());
        addModule(group, target);
        var current = add(group, new HTTPSamplerProxy());

        assertTrue(PreviousResponseSearch.previousSamplers(current).isEmpty());
    }

    private static JMeterTreeNode add(JMeterTreeNode parent, TestElement element) {
        var node = new JMeterTreeNode(element, null);
        parent.add(node);
        return node;
    }

    private static void addModule(JMeterTreeNode parent, JMeterTreeNode target) {
        var module = new ModuleController();
        add(parent, module);
        module.setSelectedNode(target);
    }

    @Test
    void findsEveryLiteralOccurrenceIncludingOverlaps() {
        assertEquals(List.of(0, 5), PreviousResponseSearch.findHits(null, "a.b\n a.b", "a.b")
                .stream().map(PreviousResponseSearch.Hit::offset).toList());
        assertEquals(List.of(0, 1), PreviousResponseSearch.findHits(null, "aaa", "aa")
                .stream().map(PreviousResponseSearch.Hit::offset).toList());
        assertTrue(PreviousResponseSearch.findHits(null, "axb", "a.b").isEmpty());
        assertTrue(PreviousResponseSearch.findHits(null, "abc", "").isEmpty());
    }
}
