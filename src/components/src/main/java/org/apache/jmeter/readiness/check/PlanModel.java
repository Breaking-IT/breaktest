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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.jmeter.readiness.ElementRef;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jorphan.collections.HashTree;

/**
 * The in-scope part of a test plan, as validation runs it: enabled elements only, replaceable controllers resolved
 * and only the thread groups in scope (see {@link org.apache.jmeter.gui.action.Start#validationTree}). Elements
 * outside the scope and disabled elements are not in the model, so they never produce findings.
 */
public final class PlanModel {
    private static final Set<String> NO_REQUEST_SAMPLERS = Set.of(
            "org.apache.jmeter.sampler.DebugSampler", // $NON-NLS-1$
            "org.apache.jmeter.sampler.TestAction"); // $NON-NLS-1$

    private final HashTree tree;
    private final List<PlanNode> nodes = new ArrayList<>();
    private final Map<String, List<PlanNode>> bySourceId = new LinkedHashMap<>();
    private final List<PlanNode> roots = new ArrayList<>();
    private VariableSources sources;

    private PlanModel(HashTree tree) {
        this.tree = tree;
    }

    /**
     * @param tree in-scope tree
     * @param refs describes elements; in the GUI it also records their tree path
     * @return the model
     */
    public static PlanModel build(HashTree tree, Function<TestElement, ElementRef> refs) {
        PlanModel model = new PlanModel(tree);
        for (Object key : tree.list()) {
            if (key instanceof TestElement element) {
                model.roots.add(model.add(element, null, tree.getTree(key), refs));
            }
        }
        return model;
    }

    /**
     * @param tree in-scope tree
     * @return a model whose element references carry no tree path, as used outside the GUI
     */
    public static PlanModel build(HashTree tree) {
        return build(tree, PlanModel::defaultRef);
    }

    /**
     * @param element test element
     * @return a reference without tree path
     */
    public static ElementRef defaultRef(TestElement element) {
        return new ElementRef(ValidationCheckpoints.sourceIdOfSource(element), element.getName(),
                element.getClass().getSimpleName(), List.of());
    }

    private PlanNode add(TestElement element, PlanNode parent, HashTree subTree, Function<TestElement, ElementRef> refs) {
        PlanNode node = new PlanNode(element, parent, nodes.size(), refs.apply(element));
        nodes.add(node);
        bySourceId.computeIfAbsent(node.sourceId(), ignored -> new ArrayList<>()).add(node);
        if (parent != null) {
            parent.addChild(node);
        }
        for (Object key : subTree.list()) {
            if (key instanceof TestElement child) {
                add(child, node, subTree.getTree(key), refs);
            }
        }
        return node;
    }

    static boolean sendsNoRequest(TestElement sampler) {
        return NO_REQUEST_SAMPLERS.contains(sampler.getClass().getName());
    }

    /**
     * @return the in-scope tree the model was built from
     */
    public HashTree tree() {
        return tree;
    }

    /**
     * @return every node in execution order
     */
    public List<PlanNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * @param type element type
     * @return nodes whose element has that type, in execution order
     */
    public List<PlanNode> nodesOf(Class<?> type) {
        return nodes.stream().filter(n -> type.isInstance(n.element())).toList();
    }

    /**
     * @return thread group nodes in scope
     */
    public List<PlanNode> threadGroups() {
        return nodesOf(AbstractThreadGroup.class);
    }

    /**
     * @param sourceId source id
     * @return the occurrences of that element, empty when it is not in scope
     */
    public List<PlanNode> nodesWithId(String sourceId) {
        return bySourceId.getOrDefault(sourceId, List.of());
    }

    /**
     * @param sourceId source id
     * @return the element, or {@code null} when it is not in scope
     */
    public TestElement element(String sourceId) {
        List<PlanNode> found = bySourceId.get(sourceId);
        return found == null || found.isEmpty() ? null : found.get(0).element();
    }

    /**
     * @return the root node (normally the test plan), or {@code null} for an empty tree
     */
    public PlanNode root() {
        return roots.isEmpty() ? null : roots.get(0);
    }

    /**
     * @return the variable sources in scope
     */
    public synchronized VariableSources sources() {
        if (sources == null) {
            sources = VariableSources.collect(this);
        }
        return sources;
    }

    /**
     * @return each element once, in execution order of its first occurrence
     */
    public List<PlanNode> distinctNodes() {
        return bySourceId.values().stream().map(list -> list.get(0)).toList();
    }
}
