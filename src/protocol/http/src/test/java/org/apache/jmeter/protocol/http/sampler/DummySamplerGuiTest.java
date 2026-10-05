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

package org.apache.jmeter.protocol.http.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.stream.Stream;

import javax.swing.JComboBox;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.gui.DummySamplerGui;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;

class DummySamplerGuiTest extends JMeterTestCase {
    @Test
    void typeSwitchingHidesIrrelevantFieldsAndPreservesEdits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DummySamplerGui gui = new DummySamplerGui();
            JComboBox<?> type = descendants(gui).filter(JComboBox.class::isInstance)
                    .map(JComboBox.class::cast).findFirst().orElseThrow();
            JTabbedPane tabs = descendants(gui).filter(JTabbedPane.class::isInstance)
                    .map(JTabbedPane.class::cast).findFirst().orElseThrow();
            assertTrue(tabs.indexOfTab(JMeterUtils.getResString("dummy_sampler_group_http")) >= 0);
            assertEquals(-1, tabs.indexOfTab(JMeterUtils.getResString("dummy_sampler_group_statistics")));
            JTextComponent method = descendants(gui).filter(JTextComponent.class::isInstance)
                    .map(JTextComponent.class::cast)
                    .filter(field -> DummySamplerField.HTTP_METHOD.propertyName().equals(field.getName()))
                    .findFirst().orElseThrow();
            method.setText("PATCH");
            type.setSelectedItem(DummySampler.ResultType.STATISTICAL);
            assertEquals(-1, tabs.indexOfTab(JMeterUtils.getResString("dummy_sampler_group_http")));
            assertTrue(tabs.indexOfTab(JMeterUtils.getResString("dummy_sampler_group_statistics")) >= 0);
            DummySampler saved = (DummySampler) gui.createTestElement();
            assertEquals("PATCH", saved.value(DummySamplerField.HTTP_METHOD));
            assertEquals(DummySamplerGui.class.getName(), saved.getPropertyAsString(TestElement.GUI_CLASS));
            assertEquals(DummySampler.class.getName(), saved.getPropertyAsString(TestElement.TEST_CLASS));
            type.setSelectedItem(DummySampler.ResultType.STANDARD);
            assertEquals(-1, tabs.indexOfTab(JMeterUtils.getResString("dummy_sampler_group_http")));
            assertEquals(-1, tabs.indexOfTab(JMeterUtils.getResString("dummy_sampler_group_statistics")));
            gui.configure(saved);
            assertEquals(DummySampler.ResultType.STATISTICAL, type.getSelectedItem());
            type.setSelectedItem(DummySampler.ResultType.HTTP);
            assertEquals("PATCH", method.getText());
            gui.clearGui();
            assertEquals(DummySampler.ResultType.HTTP, type.getSelectedItem());
            assertEquals("GET", method.getText());
            assertFalse(((DummySampler) gui.createTestElement()).value(DummySamplerField.SIMULATE_TIME)
                    .equals("true"));
        });
    }

    @Test
    void successExpressionSurvivesSavingAndReopening() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DummySamplerGui gui = new DummySamplerGui();
            JTextComponent success = descendants(gui).filter(JTextComponent.class::isInstance)
                    .map(JTextComponent.class::cast)
                    .filter(field -> DummySamplerField.SUCCESSFUL.propertyName().equals(field.getName()))
                    .findFirst().orElseThrow();
            JComboBox<?> selector = descendants(gui).filter(JComboBox.class::isInstance)
                    .map(JComboBox.class::cast)
                    .filter(combo -> combo.isEditable() && combo.getEditor().getEditorComponent() == success)
                    .findFirst().orElseThrow();
            assertEquals("true", selector.getItemAt(0));
            assertEquals("false", selector.getItemAt(1));
            assertEquals("${variable_name}", selector.getItemAt(2));
            selector.setSelectedItem("false");
            assertEquals("false", ((DummySampler) gui.createTestElement()).value(DummySamplerField.SUCCESSFUL));
            assertTrue(success.isEditable());
            success.setText("${sampleSuccessful}");
            DummySampler saved = (DummySampler) gui.createTestElement();
            assertEquals("${sampleSuccessful}", saved.value(DummySamplerField.SUCCESSFUL));
            gui.clearGui();
            assertEquals("true", success.getText());
            gui.configure(saved);
            assertEquals("${sampleSuccessful}", success.getText());
        });
    }

    @Test
    void unknownResultTypeCanBeOpenedPreservedAndRepaired() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DummySamplerGui gui = new DummySamplerGui();
            JComboBox<?> type = descendants(gui).filter(JComboBox.class::isInstance)
                    .map(JComboBox.class::cast).findFirst().orElseThrow();
            DummySampler sampler = new DummySampler();
            sampler.setProperty(DummySampler.RESULT_TYPE, "future-type");
            gui.configure(sampler);
            assertEquals("future-type", type.getSelectedItem());
            assertEquals("future-type", gui.createTestElement().getPropertyAsString(DummySampler.RESULT_TYPE));
            type.setSelectedItem(DummySampler.ResultType.HTTP);
            assertEquals(DummySampler.ResultType.HTTP, ((DummySampler) gui.createTestElement()).getResultType());
            sampler.setProperty(DummySampler.RESULT_TYPE, "Statistical");
            gui.configure(sampler);
            assertEquals(DummySampler.ResultType.STATISTICAL, type.getSelectedItem());
            assertEquals(3, type.getItemCount());
            gui.clearGui();
            assertEquals(DummySampler.ResultType.HTTP, type.getSelectedItem());
        });
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(DummySamplerGuiTest::descendants)
                : Stream.empty());
    }
}
