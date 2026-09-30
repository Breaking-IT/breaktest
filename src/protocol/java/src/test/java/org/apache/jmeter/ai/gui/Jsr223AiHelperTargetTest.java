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

package org.apache.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.java.sampler.JSR223Sampler;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Jsr223AiHelperTargetTest extends JMeterTestCase {
    @AfterEach
    void resetGui() throws Exception {
        var field = GuiPackage.class.getDeclaredField("guiPack");
        field.setAccessible(true);
        field.set(null, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void resultUpdatesTheInitiatingScriptEvenWhenTheEditorIsReused(boolean switchSelection) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture fixture = new Fixture();
            fixture.select(fixture.original);
            Component originalGui = (Component) fixture.gui.getGui(fixture.original.getTestElement());
            JSyntaxTextArea editor = scriptEditor((Container) originalGui);
            editor.setText("original script edited before asking AI");
            JMeterTreeNode target = Jsr223AiHelper.captureTarget();
            assertSame(fixture.original, target);

            if (switchSelection) {
                fixture.select(fixture.other);
                assertSame(originalGui, fixture.gui.getGui(fixture.other.getTestElement()), "JSR223 editors are shared");
                editor.setText("unsaved edits to the other script");
            }
            assertTrue(Jsr223AiHelper.applyScript(target, "generated script"));
            assertEquals("generated script", fixture.original.getTestElement().getPropertyAsString("script"));
            assertEquals("generated script", ((JSR223Sampler) fixture.original.getTestElement()).getScript());
            assertTrue(fixture.gui.isDirty());
            if (switchSelection) {
                assertSame(fixture.other, fixture.gui.getCurrentNode());
                assertEquals("unsaved edits to the other script", editor.getText());
                assertEquals("unsaved edits to the other script", fixture.other.getTestElement().getPropertyAsString("script"));
                fixture.select(fixture.original);
            }
            assertEquals("generated script", editor.getText());
            fixture.gui.updateCurrentNode();
            assertEquals("generated script", fixture.original.getTestElement().getPropertyAsString("script"),
                    "Later editor write-back must preserve the generated script");
        });
    }

    @Test
    void removedOriginalDoesNotRedirectTheResultToTheSelectedScript() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture fixture = new Fixture();
            fixture.select(fixture.original);
            JMeterTreeNode target = Jsr223AiHelper.captureTarget();
            fixture.select(fixture.other);
            fixture.model.removeNodeFromParent(fixture.original);
            assertFalse(Jsr223AiHelper.applyScript(target, "generated script"));
            assertEquals("other script", fixture.other.getTestElement().getPropertyAsString("script"));
            assertEquals("other script", scriptEditor((Container) fixture.gui.getGui(fixture.other.getTestElement())).getText());
        });
    }

    private static JSyntaxTextArea scriptEditor(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JSyntaxTextArea editor) {
                return editor;
            }
            if (child instanceof Container container) {
                JSyntaxTextArea editor = scriptEditor(container);
                if (editor != null) {
                    return editor;
                }
            }
        }
        return null;
    }

    private static final class Fixture {
        private final JMeterTreeModel model = new JMeterTreeModel(new TestPlan("Root"));
        private final JMeterTreeListener listener = new JMeterTreeListener(model);
        private final GuiPackage gui;
        private final JMeterTreeNode original;
        private final JMeterTreeNode other;

        private Fixture() {
            listener.setJTree(new JTree(model));
            GuiPackage.initInstance(listener, model);
            gui = GuiPackage.getInstance();
            original = addScript("original script");
            other = addScript("other script");
        }

        private JMeterTreeNode addScript(String script) {
            JSR223Sampler sampler = new JSR223Sampler();
            sampler.setName(script);
            sampler.setProperty(TestElement.TEST_CLASS, JSR223Sampler.class.getName());
            sampler.setProperty(TestElement.GUI_CLASS, TestBeanGUI.class.getName());
            sampler.setProperty("script", script);
            sampler.setProperty("scriptLanguage", "groovy");
            JMeterTreeNode node = new JMeterTreeNode(sampler, model);
            JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
            model.insertNodeInto(node, plan, plan.getChildCount());
            return node;
        }

        private void select(JMeterTreeNode node) {
            listener.setSelectionPathWithoutEdit(new TreePath(node.getPath()));
            gui.updateCurrentGui();
        }
    }
}
