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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;

import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

class StartTest {
    private final JMeterTreeModel model = new JMeterTreeModel(new TestPlan());
    private final AbstractThreadGroup first = addThreadGroup();
    private final AbstractThreadGroup second = addThreadGroup();

    @Test
    void selectedThreadGroupOverridesPreviousValidation() {
        assertArrayEquals(new AbstractThreadGroup[] {second}, Start.resolveValidationThreadGroups(
                new AbstractThreadGroup[] {second}, new AbstractThreadGroup[] {first}, model));
    }

    @Test
    void nonThreadGroupSelectionReusesPreviousValidation() {
        assertArrayEquals(new AbstractThreadGroup[] {first}, Start.resolveValidationThreadGroups(
                new AbstractThreadGroup[0], new AbstractThreadGroup[] {first}, model));
    }

    @Test
    void preservesMultiplePreviouslyValidatedGroups() {
        assertArrayEquals(new AbstractThreadGroup[] {first, second}, Start.resolveValidationThreadGroups(
                new AbstractThreadGroup[0], new AbstractThreadGroup[] {first, second}, model));
    }

    @Test
    void noPreviousValidationRequiresSelection() {
        assertArrayEquals(new AbstractThreadGroup[0], Start.resolveValidationThreadGroups(
                new AbstractThreadGroup[0], new AbstractThreadGroup[0], model));
    }

    @Test
    void deletedThreadGroupIsNotReused() {
        model.removeNodeFromParent(model.getNodeOf(first));
        assertArrayEquals(new AbstractThreadGroup[0], Start.resolveValidationThreadGroups(
                new AbstractThreadGroup[0], new AbstractThreadGroup[] {first}, model));
    }

    @Test
    void previousPlanThreadGroupsAreNotReused() {
        assertArrayEquals(new AbstractThreadGroup[0], Start.resolveValidationThreadGroups(
                new AbstractThreadGroup[0], new AbstractThreadGroup[] {new ThreadGroup()}, model));
    }

    @Test
    void childSelectionValidatesContainingGroupWithoutPreviousValidation() {
        JMeterTreeNode controller = addChild(new GenericController(), model.getNodeOf(second));
        assertArrayEquals(new AbstractThreadGroup[] {second}, Start.resolveValidationThreadGroups(
                Start.findValidationThreadGroups(new JMeterTreeNode[] {controller}),
                new AbstractThreadGroup[0], model));
    }

    @Test
    void nestedChildSelectionOverridesPreviousValidation() {
        JMeterTreeNode controller = addChild(new GenericController(), model.getNodeOf(second));
        JMeterTreeNode nested = addChild(new GenericController(), controller);
        assertArrayEquals(new AbstractThreadGroup[] {second}, Start.resolveValidationThreadGroups(
                Start.findValidationThreadGroups(new JMeterTreeNode[] {nested}),
                new AbstractThreadGroup[] {first}, model));
    }

    @Test
    void selectionsInSameGroupAreDeduplicatedButDistinctGroupsArePreserved() {
        JMeterTreeNode child = addChild(new GenericController(), model.getNodeOf(first));
        JMeterTreeNode otherChild = addChild(new GenericController(), model.getNodeOf(second));
        assertArrayEquals(new AbstractThreadGroup[] {first, second}, Start.findValidationThreadGroups(
                new JMeterTreeNode[] {model.getNodeOf(first), child, otherChild}));
    }

    @Test
    void selectionOutsideThreadGroupsReusesPreviousValidation() {
        JMeterTreeNode plan = (JMeterTreeNode) model.getNodeOf(first).getParent();
        JMeterTreeNode outside = addChild(new GenericController(), plan);
        assertArrayEquals(new AbstractThreadGroup[] {first}, Start.resolveValidationThreadGroups(
                Start.findValidationThreadGroups(new JMeterTreeNode[] {outside}),
                new AbstractThreadGroup[] {first}, model));
    }

    @Test
    void replacingPlanReleasesValidationTargets() throws Exception {
        Field lastTargets = Start.class.getDeclaredField("lastValidationThreadGroups");
        lastTargets.setAccessible(true);
        Field activeTargets = Start.class.getDeclaredField("activeValidationThreadGroups");
        activeTargets.setAccessible(true);
        Field guiInstance = GuiPackage.class.getDeclaredField("guiPack");
        guiInstance.setAccessible(true);
        Object previousGui = guiInstance.get(null);
        try {
            lastTargets.set(null, new AbstractThreadGroup[] {first});
            activeTargets.set(null, new AbstractThreadGroup[] {first});
            guiInstance.set(null, null);
            GuiPackage.initInstance(new JMeterTreeListener(model), model);

            GuiPackage.getInstance().clearTestPlan(new TestPlan());

            assertArrayEquals(new AbstractThreadGroup[0], (AbstractThreadGroup[]) lastTargets.get(null));
            assertNull(Start.getActiveValidationThreadGroups());
        } finally {
            Start.clearValidationThreadGroups();
            guiInstance.set(null, previousGui);
        }
    }

    private JMeterTreeNode addChild(TestElement element, JMeterTreeNode parent) {
        JMeterTreeNode child = new JMeterTreeNode(element, model);
        model.insertNodeInto(child, parent, parent.getChildCount());
        return child;
    }

    private AbstractThreadGroup addThreadGroup() {
        AbstractThreadGroup group = new ThreadGroup();
        JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
        model.insertNodeInto(new JMeterTreeNode(group, model), plan, plan.getChildCount());
        return group;
    }
}
