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

package org.apache.jmeter.protocol.http.proxy.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;

import javax.swing.SwingUtilities;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.apache.jmeter.scenario.NonTestElementsSection;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

class WelcomeRecorderActionTest extends JMeterTestCase {
    @Test
    void recorderTargetsTheEmptyThreadGroupAfterGuiConfiguration() throws Exception {
        GuiPackage previous = GuiPackage.getInstance();
        Field guiField = GuiPackage.class.getDeclaredField("guiPack");
        guiField.setAccessible(true);
        try {
            guiField.set(null, null);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JMeterTreeModel model = new JMeterTreeModel();
                    GuiPackage.initInstance(new JMeterTreeListener(model), model);
                    JMeterTreeNode recorderNode = WelcomeRecorderAction.createRecordingPlan(GuiPackage.getInstance());
                    ProxyControl recorder = (ProxyControl) recorderNode.getTestElement();
                    assertEquals(1, model.getNodesOfType(ThreadGroup.class).size());
                    assertEquals(1, model.getNodesOfType(ProxyControl.class).size());
                    JMeterTreeNode group = model.getNodesOfType(ThreadGroup.class).get(0);
                    assertEquals(0, group.getChildCount());
                    assertSame(group, recorder.getTarget());
                    assertSame(model.getNodesOfType(ThreadGroupsSection.class).get(0), group.getParent());
                    assertSame(model.getNodesOfType(NonTestElementsSection.class).get(0), recorderNode.getParent());
                    ProxyControlGui editor = (ProxyControlGui) GuiPackage.getInstance().getGui(recorder);
                    editor.configure(recorder);
                    editor.modifyTestElement(recorder);
                    assertSame(group, recorder.getTarget(), "opening the recorder editor retains the target");
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            });
        } finally {
            guiField.set(null, previous);
        }
    }
}
