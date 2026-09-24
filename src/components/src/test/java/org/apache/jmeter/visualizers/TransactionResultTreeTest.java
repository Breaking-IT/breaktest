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

package org.apache.jmeter.visualizers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.TransactionRef;
import org.junit.jupiter.api.Test;

class TransactionResultTreeTest extends JMeterTestCase {
    @Test
    void longTransactionStaysBoundedAndFinishesInPlace() {
        List<SampleResult> roots = new ArrayList<>();
        TransactionResultTree tree = new TransactionResultTree(roots, 500);
        TransactionRef ref = TransactionRef.start("transaction", null);
        SampleResult started = transaction(ref);
        tree.addStarted(started);
        SampleResult last = null;
        for (int i = 0; i < 10000; i++) {
            last = child(ref, "sample " + i);
            tree.add(last);
        }
        assertEquals(1, roots.size());
        assertSame(started, roots.get(0));
        assertEquals(499, tree.childrenOf(started).size());
        assertEquals("sample 9501", tree.childrenOf(started).get(0).getSampleLabel());
        assertTrue(tree.runningResults().contains(started));
        SampleResult finished = transaction(ref);
        tree.add(finished);
        assertSame(finished, roots.get(0));
        assertSame(last, tree.childrenOf(finished).get(498));
        assertTrue(tree.runningResults().isEmpty());
        assertTrue(tree.unmeasuredResults().isEmpty());
    }

    @Test
    void nestedAncestorsStayAttachedDuringEviction() {
        List<SampleResult> roots = new ArrayList<>();
        TransactionResultTree tree = new TransactionResultTree(roots, 3);
        TransactionRef outer = TransactionRef.start("outer", null);
        TransactionRef inner = TransactionRef.start("inner", outer);
        tree.add(child(inner, "old"));
        SampleResult latest = child(inner, "latest");
        assertEquals(1, tree.add(latest).size());
        SampleResult innerFinished = transaction(inner);
        SampleResult outerFinished = transaction(outer);
        tree.add(innerFinished);
        tree.add(outerFinished);
        assertEquals(List.of(outerFinished), roots);
        assertEquals(List.of(innerFinished), tree.childrenOf(outerFinished));
        assertEquals(List.of(latest), tree.childrenOf(innerFinished));
        tree.add(child(null, "outside"));
        assertEquals(3, roots.stream().flatMap(tree::resultAndChildren).count());
    }

    @Test
    void evictedRunningSamplerCanStillComplete() {
        List<SampleResult> roots = new ArrayList<>();
        TransactionResultTree tree = new TransactionResultTree(roots, 2);
        TransactionRef ref = TransactionRef.start("transaction", null);
        SampleResult started = child(ref, "running");
        tree.addStartedSample(started);
        assertEquals(List.of(started), tree.add(child(ref, "other")));
        assertFalse(tree.runningResults().contains(started));
        SampleResult finished = child(ref, "finished");
        tree.finishSample(started, finished);
        assertEquals(List.of(finished), tree.childrenOf(roots.get(0)));
        assertFalse(tree.removeStartedSample(started));
        assertEquals(2, roots.stream().flatMap(tree::resultAndChildren).count());
    }

    @Test
    void inferredAncestorIsUnmeasuredUntilARealLifecycleEventArrives() {
        List<SampleResult> roots = new ArrayList<>();
        TransactionResultTree tree = new TransactionResultTree(roots, 3);
        TransactionRef ref = TransactionRef.start("transaction", null);
        tree.add(child(ref, "sample"));
        assertTrue(tree.runningResults().isEmpty());
        assertTrue(tree.unmeasuredResults().contains(roots.get(0)));
        SampleResult started = transaction(ref);
        tree.addStarted(started);
        assertSame(started, roots.get(0));
        assertTrue(tree.runningResults().contains(started));
        tree.add(transaction(ref));
        assertTrue(tree.runningResults().isEmpty());
        assertTrue(tree.unmeasuredResults().isEmpty());
        tree.addStarted(started);
        assertTrue(tree.runningResults().isEmpty(), "A late start cannot resurrect a finished transaction");
    }

    @Test
    void evictionDropsPendingReplacements() {
        List<SampleResult> roots = new ArrayList<>();
        TransactionResultTree tree = new TransactionResultTree(roots, 1);
        SampleResult started = child(null, "started");
        tree.addStartedSample(started);
        SampleResult finished = child(null, "finished");
        tree.finishSample(started, finished);
        tree.add(child(null, "next"));
        Set<Object> expanded = new HashSet<>();
        expanded.add(started);
        tree.carryOverViewState(expanded, null);
        assertFalse(expanded.contains(finished), "Evicted results must not be retained by replacement bookkeeping");
    }

    @Test
    void removalsAndClearReleaseTheirRetentionBudget() {
        List<SampleResult> roots = new ArrayList<>();
        TransactionResultTree tree = new TransactionResultTree(roots, 1);
        SampleResult started = child(null, "started");
        tree.addStartedSample(started);
        assertTrue(tree.removeStartedSample(started));
        SampleResult next = child(null, "next");
        assertTrue(tree.add(next).isEmpty());
        roots.clear();
        tree.clear();
        assertTrue(tree.add(next).isEmpty());
        assertEquals(List.of(next), roots);
    }

    @Test
    void zeroLimitRetainsAllChildren() {
        List<SampleResult> roots = new ArrayList<>();
        TransactionResultTree tree = new TransactionResultTree(roots, 0);
        TransactionRef ref = TransactionRef.start("transaction", null);
        for (int i = 0; i < 600; i++) {
            assertTrue(tree.add(child(ref, "sample")).isEmpty());
        }
        assertEquals(600, tree.childrenOf(roots.get(0)).size());
    }

    @Test
    void loadedNestedTransactionCanBeReparentedWithinLimit() {
        List<SampleResult> roots = new ArrayList<>();
        TransactionResultTree tree = new TransactionResultTree(roots, 3);
        TransactionRef unknownParent = new TransactionRef(2, "", null);
        SampleResult sample = child(unknownParent, "sample");
        tree.add(sample);
        TransactionRef outer = new TransactionRef(1, "outer", null);
        SampleResult innerFinished = transaction(new TransactionRef(2, "inner", outer));
        tree.add(innerFinished);
        SampleResult outerFinished = transaction(outer);
        tree.add(outerFinished);
        assertEquals(List.of(outerFinished), roots);
        assertEquals(List.of(innerFinished), tree.childrenOf(outerFinished));
        assertEquals(List.of(sample), tree.childrenOf(innerFinished));
        tree.add(child(null, "outside"));
        assertEquals(3, roots.stream().flatMap(tree::resultAndChildren).count());
    }

    @Test
    void limitSmallerThanAncestorChainStillAllowsCompletion() {
        List<SampleResult> roots = new ArrayList<>();
        TransactionResultTree tree = new TransactionResultTree(roots, 1);
        TransactionRef outer = TransactionRef.start("outer", null);
        TransactionRef inner = TransactionRef.start("inner", outer);
        tree.addStarted(transaction(outer));
        tree.addStarted(transaction(inner));
        SampleResult started = child(inner, "sample");
        tree.addStartedSample(started);
        tree.finishSample(started, child(inner, "finished"));
        tree.add(transaction(inner));
        SampleResult finished = transaction(outer);
        tree.add(finished);
        assertEquals(List.of(finished), roots);
        assertTrue(tree.childrenOf(finished).isEmpty());
        assertTrue(tree.runningResults().isEmpty());
    }

    private static SampleResult transaction(TransactionRef ref) {
        SampleResult result = new SampleResult();
        result.setTransaction(ref);
        result.setSampleLabel(ref.getName());
        return result;
    }

    private static SampleResult child(TransactionRef parent, String name) {
        SampleResult result = new SampleResult();
        result.setParentTransaction(parent);
        result.setSampleLabel(name);
        return result;
    }
}
