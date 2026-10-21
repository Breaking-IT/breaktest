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
import java.util.List;

import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.readiness.ElementRef;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;

/**
 * One occurrence of an element in the in-scope tree. An element reached through several Module Controllers occurs
 * several times, with the same {@link #ref() source identity}.
 */
public final class PlanNode {
    private final TestElement element;
    private final PlanNode parent;
    private final List<PlanNode> children = new ArrayList<>();
    private final int order;
    private final ElementRef ref;

    PlanNode(TestElement element, PlanNode parent, int order, ElementRef ref) {
        this.element = element;
        this.parent = parent;
        this.order = order;
        this.ref = ref;
    }

    void addChild(PlanNode child) {
        children.add(child);
    }

    public TestElement element() {
        return element;
    }

    public PlanNode parent() {
        return parent;
    }

    public List<PlanNode> children() {
        return Collections.unmodifiableList(children);
    }

    /**
     * @return position in execution (depth-first) order
     */
    public int order() {
        return order;
    }

    public ElementRef ref() {
        return ref;
    }

    public String sourceId() {
        return ref.sourceId();
    }

    public String name() {
        return element.getName();
    }

    public boolean isSampler() {
        return element instanceof Sampler;
    }

    public boolean isController() {
        return element instanceof Controller;
    }

    /**
     * @return whether the element is a sampler that sends a request (not a Debug Sampler or Flow Control Action)
     */
    public boolean isRequestSampler() {
        return element instanceof Sampler && !PlanModel.sendsNoRequest(element);
    }

    /**
     * @return enclosing elements, nearest first
     */
    public List<PlanNode> ancestors() {
        List<PlanNode> result = new ArrayList<>();
        for (PlanNode node = parent; node != null; node = node.parent) {
            result.add(node);
        }
        return result;
    }

    /**
     * @param other a node
     * @return whether this node is the other node or below it
     */
    public boolean isWithin(PlanNode other) {
        for (PlanNode node = this; node != null; node = node.parent) {
            if (node == other) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the enclosing thread group, or {@code null} at test plan level
     */
    public PlanNode threadGroup() {
        for (PlanNode node = this; node != null; node = node.parent) {
            if (node.element instanceof AbstractThreadGroup) {
                return node;
            }
        }
        return null;
    }

    /**
     * @return the nearest enclosing Transaction Controller (excluding this node), or {@code null}
     */
    public PlanNode enclosingTransaction() {
        for (PlanNode node = parent; node != null; node = node.parent) {
            if (node.element instanceof TransactionController) {
                return node;
            }
        }
        return null;
    }

    /**
     * @return this node and every node below it, in execution order
     */
    public List<PlanNode> subtree() {
        List<PlanNode> result = new ArrayList<>();
        collect(this, result);
        return result;
    }

    private static void collect(PlanNode node, List<PlanNode> result) {
        result.add(node);
        for (PlanNode child : node.children) {
            collect(child, result);
        }
    }

    /**
     * @return request samplers at or below this node
     */
    public List<PlanNode> requestSamplers() {
        return subtree().stream().filter(PlanNode::isRequestSampler).toList();
    }

    /**
     * Elements of the given type that JMeter applies to this sampler: children of the sampler itself and children of
     * each enclosing controller, thread group and test plan.
     *
     * @param type type of the scoped elements (assertions, post-processors, timers, config elements, ...)
     * @return applicable nodes, the sampler's own children first
     */
    public List<PlanNode> applicable(Class<?> type) {
        List<PlanNode> result = new ArrayList<>();
        for (PlanNode scope = this; scope != null; scope = scope.parent) {
            if (scope != this && !(scope.isController() || scope.parent == null
                    || scope.element instanceof AbstractThreadGroup)) {
                continue;
            }
            for (PlanNode child : scope.children) {
                if (type.isInstance(child.element) && !(child.element instanceof Sampler)
                        && !(child.element instanceof Controller)) {
                    result.add(child);
                }
            }
        }
        return result;
    }

    /**
     * @return the samplers this scoped element (assertion, timer, config, ...) applies to
     */
    public List<PlanNode> scopeSamplers() {
        if (parent == null) {
            return List.of();
        }
        if (parent.isSampler()) {
            return List.of(parent);
        }
        return parent.subtree().stream().filter(PlanNode::isSampler).toList();
    }

    @Override
    public String toString() {
        return element.getName();
    }
}
