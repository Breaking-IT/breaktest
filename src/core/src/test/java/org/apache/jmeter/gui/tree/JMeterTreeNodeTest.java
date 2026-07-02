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

package org.apache.jmeter.gui.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.BeanDescriptor;
import java.beans.Introspector;

import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.config.gui.AbstractConfigGui;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.junit.jupiter.api.Test;

class JMeterTreeNodeTest {

    @Test
    void clearingSearchMarkDoesNotMarkParentAsHavingMatchedChild() throws IllegalUserActionException {
        JMeterTreeModel treeModel = new JMeterTreeModel(new TestPlan("root"));
        JMeterTreeNode testPlan = (JMeterTreeNode) ((JMeterTreeNode) treeModel.getRoot()).getChildAt(0);
        JMeterTreeNode parent = treeModel.addComponent(new TestPlan("parent"), testPlan);
        JMeterTreeNode child = treeModel.addComponent(new TestPlan("child"), parent);

        child.setMarkedBySearch(true);

        assertTrue(child.isMarkedBySearch());
        assertTrue(parent.isChildrenMarkedBySearch());

        parent.setChildrenNodesHaveMatched(false);
        child.setMarkedBySearch(false);

        assertFalse(child.isMarkedBySearch());
        assertFalse(parent.isChildrenMarkedBySearch());
    }

    @Test
    void testBeanIconGuiClassIsInferredForConfigElement() throws Exception {
        BeanDescriptor beanDescriptor = Introspector.getBeanInfo(FastLoadedConfigBean.class).getBeanDescriptor();

        assertNull(beanDescriptor.getValue(TestElement.GUI_CLASS));

        String guiClassName = JMeterTreeNode.getTestBeanGuiClassName(beanDescriptor, FastLoadedConfigBean.class);

        assertEquals(AbstractConfigGui.class.getName(), guiClassName);
        assertEquals(AbstractConfigGui.class.getName(), beanDescriptor.getValue(TestElement.GUI_CLASS));
    }

    public static class FastLoadedConfigBean extends AbstractTestElement implements ConfigElement, TestBean {
        private static final long serialVersionUID = 1L;

        @Override
        public void addConfigElement(ConfigElement config) {
        }

        @Override
        public boolean expectsModification() {
            return false;
        }
    }
}
