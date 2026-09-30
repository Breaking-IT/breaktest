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

package org.apache.jmeter.protocol.java.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;

class JSR223SamplerGuiTest extends JMeterTestCase {
    @Test
    void settingsAndActionsShareTheHeadersRightEdgeWhenResized() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TestBeanGUI gui = new TestBeanGUI(JSR223Sampler.class);
            JSR223Sampler sampler = new JSR223Sampler();
            sampler.setName("Sampler name");
            sampler.setProperty("parameters", "parameters");
            gui.configure(sampler);
            List<Component> components = descendants(gui);
            JButton included = button(components, JMeterUtils.getResString("sampler_ignore_never"));
            JButton askAi = button(components, "Ask AI");
            JButton browse = button(components, JMeterUtils.getResString("browse"));
            JTextField parameters = components.stream().filter(JTextField.class::isInstance)
                    .map(JTextField.class::cast).filter(field -> "parameters".equals(field.getText()))
                    .findFirst().orElseThrow();
            JComboBox<?> language = components.stream().filter(JComboBox.class::isInstance)
                    .map(JComboBox.class::cast).findFirst().orElseThrow();
            for (int width : new int[] { 800, 1100 }) {
                gui.setSize(width, 650);
                layout(gui);
                int expectedRight = rightEdge(included, gui);
                assertEquals(expectedRight, rightEdge(askAi, gui), "Ask AI aligns with Included");
                assertEquals(expectedRight, rightEdge(browse, gui), "Browse aligns with Included");
                assertEquals(expectedRight, rightEdge(parameters, gui), "Parameters reaches the same right edge");
                assertEquals(expectedRight, rightEdge(language, gui), "Language reaches the same right edge");
                assertTrue(parameters.getWidth() > 0);
                assertTrue(askAi.getWidth() > 0);
            }
        });
    }

    private static JButton button(List<Component> components, String text) {
        JButton result = components.stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElse(null);
        assertNotNull(result, text);
        return result;
    }

    private static int rightEdge(Component component, Container parent) {
        return SwingUtilities.convertPoint(component, component.getWidth(), 0, parent).x;
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layout(nested);
            }
        }
    }

    private static List<Component> descendants(Container container) {
        List<Component> result = new ArrayList<>();
        for (Component child : container.getComponents()) {
            result.add(child);
            if (child instanceof Container nested) {
                result.addAll(descendants(nested));
            }
        }
        return result;
    }
}
