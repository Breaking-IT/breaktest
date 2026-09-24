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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.TransactionRef;

/**
 * Arranges the results of the View Results Tree by transaction. A transaction shows up as soon as
 * it starts, the samples that run in it are placed below it as they complete, and the finished
 * transaction sample takes the place of the running one.
 * <p>
 * Samples are attached through {@link SampleResult#getParentTransaction()}, so the tree is also
 * built when a start event was missed: the running transaction is then created from the first
 * sample that points to it.
 * <p>
 * Not thread-safe: the visualizer guards it with the lock of its result buffer.
 */
final class TransactionResultTree {

    private final List<SampleResult> roots;

    private final int maxRoots;

    private final Map<Long, Node> nodesById = new HashMap<>();

    private final IdentityHashMap<SampleResult, Node> nodesByResult = new IdentityHashMap<>();

    /** Running samplers mapped to the list showing them: the roots or the samples of a transaction */
    private final IdentityHashMap<SampleResult, List<SampleResult>> runningSamples = new IdentityHashMap<>();

    /** Running transaction and sampler placeholders replaced by their finished sample since the last refresh */
    private final IdentityHashMap<SampleResult, SampleResult> replacements = new IdentityHashMap<>();

    private static final class Node {
        private final long id;
        private SampleResult result;
        private Node parent;
        private final List<SampleResult> children = new ArrayList<>();
        private boolean running;

        private Node(long id, SampleResult result, boolean running) {
            this.id = id;
            this.result = result;
            this.running = running;
        }
    }

    /**
     * @param roots    the top level results, owned by the caller
     * @param maxRoots maximum number of top level results to keep, or 0 for no limit
     */
    TransactionResultTree(List<SampleResult> roots, int maxRoots) {
        this.roots = roots;
        this.maxRoots = maxRoots;
    }

    /**
     * Adds a transaction that has started.
     *
     * @param started the unfinished transaction sample
     * @return the top level results removed to stay within the limit
     */
    List<SampleResult> addStarted(SampleResult started) {
        TransactionRef transaction = started.getTransaction();
        if (transaction == null || nodesById.containsKey(transaction.getId())) {
            return List.of();
        }
        List<SampleResult> evicted = new ArrayList<>();
        Node node = createNode(transaction, started, true);
        place(node, transaction.getParent(), started, evicted);
        return evicted;
    }

    /**
     * Adds a sampler that has started sending its request.
     *
     * @param started the unfinished sample
     * @return the top level results removed to stay within the limit
     */
    List<SampleResult> addStartedSample(SampleResult started) {
        List<SampleResult> evicted = new ArrayList<>();
        TransactionRef parent = started.getParentTransaction();
        if (parent == null) {
            addRoot(started, evicted);
            if (!evicted.contains(started)) {
                runningSamples.put(started, roots);
            }
        } else {
            Node node = ensureNode(parent, started, evicted);
            node.children.add(started);
            runningSamples.put(started, node.children);
        }
        return evicted;
    }

    /**
     * Puts the finished sample in the place of the running one.
     *
     * @param started the placeholder given to {@link #addStartedSample(SampleResult)}
     * @param result  the finished sample
     * @return the top level results removed to stay within the limit
     */
    List<SampleResult> finishSample(SampleResult started, SampleResult result) {
        List<SampleResult> container = runningSamples.remove(started);
        if (container != null && replaceIdentity(container, started, result)) {
            recordReplacement(started, result);
            return List.of();
        }
        return add(result);
    }

    /**
     * Removes a started sampler that did not produce a sample.
     *
     * @param started the placeholder given to {@link #addStartedSample(SampleResult)}
     * @return {@code true} if it was still shown
     */
    boolean removeStartedSample(SampleResult started) {
        List<SampleResult> container = runningSamples.remove(started);
        return container != null && removeIdentity(container, started);
    }

    /**
     * Adds a completed sample or a finished transaction.
     *
     * @param result the sample
     * @return the top level results removed to stay within the limit
     */
    List<SampleResult> add(SampleResult result) {
        List<SampleResult> evicted = new ArrayList<>();
        TransactionRef transaction = result.getTransaction();
        if (transaction == null) {
            TransactionRef parent = result.getParentTransaction();
            if (parent == null) {
                addRoot(result, evicted);
            } else {
                ensureNode(parent, result, evicted).children.add(result);
            }
            return evicted;
        }
        Node node = nodesById.get(transaction.getId());
        if (node == null) {
            place(createNode(transaction, result, false), transaction.getParent(), result, evicted);
            return evicted;
        }
        finish(node, result);
        if (node.parent == null && transaction.getParent() != null) {
            // Created before its enclosing transaction was known: move it below it
            removeIdentity(roots, node.result);
            place(node, transaction.getParent(), result, evicted);
        }
        return evicted;
    }

    /**
     * @param result a result shown in the tree
     * @return the samples of a transaction followed by the sub-results of the result
     */
    List<SampleResult> childrenOf(SampleResult result) {
        Node node = nodesByResult.get(result);
        SampleResult[] subResults = result.getSubResults();
        if (node == null || node.children.isEmpty()) {
            return Arrays.asList(subResults);
        }
        if (subResults.length == 0) {
            return node.children;
        }
        List<SampleResult> children = new ArrayList<>(node.children);
        children.addAll(Arrays.asList(subResults));
        return children;
    }

    /**
     * @param result a result shown in the tree
     * @return the result and everything shown below it
     */
    Stream<SampleResult> resultAndChildren(SampleResult result) {
        return Stream.concat(Stream.of(result), childrenOf(result).stream().flatMap(this::resultAndChildren));
    }

    /**
     * @return the transactions and samplers that have started but not finished yet
     */
    Set<SampleResult> runningResults() {
        Set<SampleResult> running = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node node : nodesByResult.values()) {
            if (node.running) {
                running.add(node.result);
            }
        }
        running.addAll(runningSamples.keySet());
        return running;
    }

    /**
     * A finished transaction sample takes the place of the running one: moves the expansion and
     * selection of running samples replaced since the previous call to their finished sample.
     *
     * @param expanded the expanded tree objects, updated in place
     * @param selected the selected tree object
     * @return the object to select
     */
    Object carryOverViewState(Set<Object> expanded, Object selected) {
        Object result = selected;
        for (Map.Entry<SampleResult, SampleResult> finished : replacements.entrySet()) {
            if (expanded.remove(finished.getKey())) {
                expanded.add(finished.getValue());
            }
            if (result == finished.getKey()) {
                result = finished.getValue();
            }
        }
        replacements.clear();
        return result;
    }

    void clear() {
        nodesById.clear();
        nodesByResult.clear();
        runningSamples.clear();
        replacements.clear();
    }

    private Node createNode(TransactionRef transaction, SampleResult result, boolean running) {
        Node node = new Node(transaction.getId(), result, running);
        nodesById.put(transaction.getId(), node);
        nodesByResult.put(result, node);
        return node;
    }

    private void place(Node node, TransactionRef parentRef, SampleResult timeSource, List<SampleResult> evicted) {
        if (parentRef == null) {
            addRoot(node.result, evicted);
            return;
        }
        Node parent = ensureNode(parentRef, timeSource, evicted);
        parent.children.add(node.result);
        node.parent = parent;
    }

    /**
     * Finds the node of a transaction, creating a running one from a sample that points to it when
     * its start was not seen.
     */
    private Node ensureNode(TransactionRef transaction, SampleResult timeSource, List<SampleResult> evicted) {
        Node node = nodesById.get(transaction.getId());
        if (node != null) {
            return node;
        }
        SampleResult started = new SampleResult();
        started.setSampleLabel(transaction.getName());
        started.setThreadName(timeSource.getThreadName());
        started.setStampAndTime(timeSource.getStartTime(), 0);
        started.setSuccessful(true);
        started.setTransaction(transaction);
        node = createNode(transaction, started, true);
        place(node, transaction.getParent(), timeSource, evicted);
        return node;
    }

    private void finish(Node node, SampleResult result) {
        SampleResult previous = node.result;
        node.result = result;
        node.running = false;
        nodesByResult.remove(previous);
        nodesByResult.put(result, node);
        replaceIdentity(node.parent == null ? roots : node.parent.children, previous, result);
        recordReplacement(previous, result);
    }

    private void recordReplacement(SampleResult previous, SampleResult result) {
        for (Map.Entry<SampleResult, SampleResult> replacement : replacements.entrySet()) {
            if (replacement.getValue() == previous) {
                replacement.setValue(result);
            }
        }
        replacements.put(previous, result);
    }

    private void addRoot(SampleResult result, List<SampleResult> evicted) {
        roots.add(result);
        while (maxRoots > 0 && roots.size() > maxRoots) {
            SampleResult removed = roots.remove(0);
            evicted.add(removed);
            forget(removed, evicted);
        }
    }

    /** Drops the transaction nodes of a removed result and collects the samples shown below it */
    private void forget(SampleResult result, List<SampleResult> removed) {
        runningSamples.remove(result);
        Node node = nodesByResult.remove(result);
        if (node == null) {
            return;
        }
        nodesById.remove(node.id);
        replacements.values().remove(result);
        for (SampleResult child : node.children) {
            removed.add(child);
            forget(child, removed);
        }
    }

    private static boolean replaceIdentity(List<SampleResult> list, SampleResult previous, SampleResult replacement) {
        for (ListIterator<SampleResult> iterator = list.listIterator(); iterator.hasNext();) {
            if (iterator.next() == previous) {
                iterator.set(replacement);
                return true;
            }
        }
        return false;
    }

    private static boolean removeIdentity(List<SampleResult> list, SampleResult result) {
        for (ListIterator<SampleResult> iterator = list.listIterator(); iterator.hasNext();) {
            if (iterator.next() == result) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }
}
