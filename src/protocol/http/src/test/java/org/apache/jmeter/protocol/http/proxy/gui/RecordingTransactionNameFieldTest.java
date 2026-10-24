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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

class RecordingTransactionNameFieldTest extends JMeterTestCase {
    @Test
    void publishesTypingPastingDeletingAndReplacementWithoutTransientEmptyNames() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> updates = new ArrayList<>();
            var field = new RecordingTransactionNameField(20, updates::add);
            field.setText("Login");
            assertEquals(List.of("Login"), updates);
            updates.clear();
            field.selectAll();
            field.replaceSelection("Search");
            assertEquals(List.of("Search"), updates, "Replacing selected text must not publish an intermediate blank name");
            updates.clear();
            field.setText("Checkout");
            assertEquals(List.of("Checkout"), updates);
            try {
                field.getDocument().insertString(field.getDocument().getLength(), " cart", null);
                assertEquals("Checkout cart", updates.get(updates.size() - 1));
                field.getDocument().remove(8, 5);
                assertEquals("Checkout", updates.get(updates.size() - 1));
            } catch (javax.swing.text.BadLocationException e) {
                throw new AssertionError(e);
            }
            field.setText("");
            assertEquals("", updates.get(updates.size() - 1), "Deliberately clearing the name still works");
        });
    }

    @Test
    void bothRecorderFieldsNameTransactionsWithoutEnterOrFocusLoss() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        GuiPackage previous = GuiPackage.getInstance();
        var guiField = GuiPackage.class.getDeclaredField("guiPack");
        guiField.setAccessible(true);
        try {
            guiField.set(null, null);
            SwingUtilities.invokeAndWait(() -> {
                var tree = new JMeterTreeModel();
                GuiPackage.initInstance(new JMeterTreeListener(tree), tree);
                var gui = new ProxyControlGui();
                var dialog = field(gui, "recorderDialog", RecorderDialog.class);
                try {
                    var recorder = new ProxyControl();
                    recorder.setNonGuiTreeModel(tree);
                    recorder.setStoreRecordedExchanges(false);
                    gui.configure(recorder);
                    var parent = tree.getNodesOfType(ThreadGroupsSection.class).get(0);
                    var target = new JMeterTreeNode(new ThreadGroup(), tree);
                    tree.insertNodeInto(target, parent, 0);
                    recorder.setTarget(target);
                    JTextField mainName = field(gui, "prefixHTTPSampleName", JTextField.class);
                    JTextField floatingName = field(dialog, "prefixHTTPSampleName", JTextField.class);
                    String[] names = {"Login", "Search", "Checkout", "Payment"};
                    for (int i = 0; i < names.length; i++) {
                        JTextField input = i == 0 || i == 3 ? mainName : floatingName;
                        input.selectAll();
                        input.replaceSelection(names[i]);
                        assertEquals(names[i], recorder.getPrefixHTTPSampleName());
                        assertEquals(names[i], gui.getPrefixHTTPSampleName());
                        var sampler = new HTTPSamplerProxy();
                        sampler.setDomain("example.test");
                        sampler.setPath("/action-" + i);
                        sampler.setName("Action " + i);
                        sampler.setProperty(TestElement.GUI_CLASS, org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui.class.getName());
                        var result = new HTTPSampleResult();
                        result.setURL(sampler.getUrl());
                        result.setResponseCode("200");
                        result.setStampAndTime(1000 + i * 1000, 100);
                        recorder.deliverSampler(sampler, new TestElement[0], result);
                    }
                    mainName.setText("Edited after the last request");
                    recorder.stopProxy();
                    assertEquals(List.of(names), tree.getNodesOfType(TransactionController.class).stream()
                            .map(JMeterTreeNode::getName).toList());
                } catch (Exception e) {
                    throw new AssertionError(e);
                } finally {
                    dialog.dispose();
                }
            });
        } finally {
            guiField.set(null, previous);
        }
    }

    private static <T> T field(Object object, String name, Class<T> type) {
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(object));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
