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

package org.apache.jmeter.samplers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

import org.apache.jmeter.control.IfController;
import org.apache.jmeter.control.IfControllerCondition;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.gui.WaitForValueGui;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;

class WaitForValueGuiTest extends JMeterTestCase {
    private static class PolicyGui extends WaitForValueGui {
        void selectFromTitleMenu(SampleIgnorePolicy policy) {
            setSampleIgnorePolicy(policy);
        }
    }

    @Test
    void resultOptionsSharePolicyAndDefaultOnlyForNewActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PolicyGui gui = new PolicyGui();
            assertEquals(SampleIgnorePolicy.ON_SUCCESS, SampleIgnorePolicy.from((WaitForValue) gui.createTestElement()));
            for (SampleIgnorePolicy policy : SampleIgnorePolicy.values()) {
                String label = JMeterUtils.getResString(policy.getLabelResource());
                JRadioButton option = findButton(gui, JRadioButton.class, label);
                option.doClick();
                assertEquals(policy, SampleIgnorePolicy.from((WaitForValue) gui.createTestElement()));
                assertEquals(label, findButton(gui, JButton.class, label).getText());
                gui.selectFromTitleMenu(SampleIgnorePolicy.ON_SUCCESS);
                gui.selectFromTitleMenu(policy);
                assertTrue(option.isSelected());
                WaitForValue saved = (WaitForValue) gui.createTestElement();
                gui.clearGui();
                assertEquals(SampleIgnorePolicy.ON_SUCCESS, SampleIgnorePolicy.from((WaitForValue) gui.createTestElement()));
                gui.configure(saved);
                assertTrue(option.isSelected());
                assertEquals(policy, SampleIgnorePolicy.from((WaitForValue) gui.createTestElement()));
            }
            // Existing actions with no policy property retain the standard Included behavior.
            gui.configure(new WaitForValue());
            assertTrue(findButton(gui, JRadioButton.class, JMeterUtils.getResString("sampler_ignore_never")).isSelected());
            assertEquals(SampleIgnorePolicy.NEVER, SampleIgnorePolicy.from((WaitForValue) gui.createTestElement()));
        });
    }

    private static <T extends AbstractButton> T findButton(Container parent, Class<T> type, String label) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child) && label.equals(((AbstractButton) child).getText())) {
                return type.cast(child);
            }
            if (child instanceof Container nested) {
                T found = findButton(nested, type, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void editorRoundTripsWithoutScriptEditor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WaitForValueGui gui = new WaitForValueGui();
            WaitForValue source = new WaitForValue();
            source.setTimeout("${timeout}");
            source.getConditions().setConditionMatch(IfController.MATCH_ANY);
            source.getConditions().setConditions(List.of(new IfControllerCondition("${ready}", "equals", "yes")));
            gui.configure(source);
            WaitForValue saved = (WaitForValue) gui.createTestElement();
            assertEquals("${timeout}", saved.getTimeout());
            assertEquals(IfController.MATCH_ANY, saved.getConditions().getConditionMatch());
            assertEquals(1, saved.getConditions().getConditions().size());
            assertFalse(hasScriptEditor(gui));
            gui.clearGui();
            gui.modifyTestElement(saved);
            assertEquals("30000", saved.getTimeout());
            assertEquals(0, saved.getConditions().getConditions().size());
        });
    }

    @Test
    void conditionsSurviveSaveAndClone() throws Exception {
        WaitForValue source = new WaitForValue();
        source.setProperty(org.apache.jmeter.testelement.TestElement.GUI_CLASS, WaitForValueGui.class.getName());
        source.setProperty(org.apache.jmeter.testelement.TestElement.TEST_CLASS, WaitForValue.class.getName());
        source.setTimeout("1234");
        source.getConditions().setConditions(List.of(new IfControllerCondition("${ready}", "equals", "yes")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(source, output);
        WaitForValue restored = (WaitForValue) SaveService.loadElement(new ByteArrayInputStream(output.toByteArray()));
        assertEquals("1234", restored.getTimeout());
        IfControllerCondition row = (IfControllerCondition) restored.getConditions().getConditions().get(0).getObjectValue();
        assertEquals("${ready}", row.getOperand1());
        WaitForValue clone = (WaitForValue) source.clone();
        clone.getConditions().setConditions(List.of());
        assertEquals(1, source.getConditions().getConditions().size());
    }

    private boolean hasScriptEditor(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JSyntaxTextArea || child instanceof Container nested && hasScriptEditor(nested)) {
                return true;
            }
        }
        return false;
    }
}
