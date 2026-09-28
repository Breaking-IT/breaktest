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

import java.util.HashSet;
import java.util.Set;

import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.scenario.ProfilesSection;
import org.apache.jmeter.scenario.ScenariosSection;
import org.apache.jmeter.scenario.TestFragmentsSection;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;

/**
 * Thread groups, profiles, scenarios and test fragments are referenced by name: by scenario rows, the command line
 * and Module Controllers. Within their section, names are therefore unique, and thread groups have a unique id.
 */
public final class UniqueNames {

    private UniqueNames() {
    }

    /**
     * Makes the name of a renamed node unique among the other elements of its section, see
     * {@link #apply(JMeterTreeModel, JMeterTreeNode, boolean)}.
     * @param model the tree the node belongs to
     * @param node a node that may have been renamed
     */
    public static void apply(JMeterTreeModel model, JMeterTreeNode node) {
        apply(model, node, true);
    }

    /**
     * Makes the name of a node unique among the other elements of its section, adding " (2)", " (3)", ... when
     * needed, and gives a thread group a unique id when it has none or shares one with another thread group.
     * @param model the tree the node belongs to
     * @param node a node just added to the tree or renamed
     * @param notify whether to tell the tree the node changed. Must be {@code false} while the node is being inserted:
     *     the tree does not know the node until the insertion is announced, and a change event for an unknown row
     *     corrupts its layout
     */
    public static void apply(JMeterTreeModel model, JMeterTreeNode node, boolean notify) {
        if (!(node.getParent() instanceof JMeterTreeNode parent) || !isNamedSection(parent.getUserObject())) {
            return;
        }
        TestElement element = node.getTestElement();
        String name = element.getName() == null ? "" : element.getName();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof JMeterTreeNode sibling && sibling != node) {
                used.add(sibling.getName());
            }
        }
        if (used.contains(name)) {
            String candidate = name;
            for (int i = 2; used.contains(candidate); i++) {
                candidate = name + " (" + i + ")";
            }
            element.setName(candidate);
            if (notify) {
                model.nodeChanged(node);
            }
        }
        if (element instanceof AbstractThreadGroup threadGroup) {
            makeIdUnique(model, node, threadGroup);
        }
    }

    private static void makeIdUnique(JMeterTreeModel model, JMeterTreeNode node, AbstractThreadGroup threadGroup) {
        Set<String> usedIds = new HashSet<>();
        for (JMeterTreeNode other : model.getNodesOfType(AbstractThreadGroup.class)) {
            if (other != node) {
                usedIds.add(((AbstractThreadGroup) other.getTestElement()).getThreadGroupId());
            }
        }
        String id = threadGroup.getThreadGroupId();
        if (id.isEmpty() || usedIds.contains(id)) {
            threadGroup.setThreadGroupId(AbstractThreadGroup.uniqueReadableId(threadGroup.getName(), usedIds));
        }
    }

    private static boolean isNamedSection(Object element) {
        return element instanceof ThreadGroupsSection || element instanceof ProfilesSection
                || element instanceof ScenariosSection || element instanceof TestFragmentsSection;
    }
}
