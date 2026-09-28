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


package org.apache.jmeter.scenario.gui;

import java.util.Arrays;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.scenario.NonTestElementsSection;
import org.apache.jmeter.scenario.SharedProfile;
import org.apache.jmeter.scenario.TestPlanSection;

/**
 * The sections of a test plan and its Shared profile are a fixed part of every test plan: they cannot be copied,
 * duplicated, cut or removed. Only the Non-Test Elements section, which is created on demand, can be removed.
 */
public final class FixedNodes {

    private FixedNodes() {
    }

    /**
     * @param node a tree node
     * @return whether copying, duplicating or cloning the node is allowed
     */
    public static boolean isCopyable(JMeterTreeNode node) {
        Object element = node.getUserObject();
        return !(element instanceof TestPlanSection || element instanceof SharedProfile);
    }

    /**
     * @param node a tree node
     * @return whether removing the node is allowed. A second copy of a fixed node, as created before copies were
     *     refused, can be removed so plans can be cleaned up.
     */
    public static boolean isRemovable(JMeterTreeNode node) {
        Object element = node.getUserObject();
        if (element instanceof NonTestElementsSection || isCopyable(node)) {
            return true;
        }
        return node.getParent() instanceof JMeterTreeNode parent && hasOtherOfSameClass(parent, node);
    }

    /**
     * @param nodes selected tree nodes
     * @return the nodes that can be copied
     */
    public static JMeterTreeNode[] copyable(JMeterTreeNode[] nodes) {
        return Arrays.stream(nodes).filter(FixedNodes::isCopyable).toArray(JMeterTreeNode[]::new);
    }

    private static boolean hasOtherOfSameClass(JMeterTreeNode parent, JMeterTreeNode node) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof JMeterTreeNode sibling && sibling != node
                    && sibling.getUserObject().getClass() == node.getUserObject().getClass()) {
                return true;
            }
        }
        return false;
    }
}
