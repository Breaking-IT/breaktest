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

package org.apache.jmeter.gui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.beans.BeanInfo;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.jmeter.assertions.JSR223AssertionBeanInfo;
import org.apache.jmeter.extractor.JSR223PostProcessorBeanInfo;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.modifiers.JSR223PreProcessorBeanInfo;
import org.apache.jmeter.testbeans.gui.GenericTestBeanCustomizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Jsr223CompletionExclusionTest extends JMeterTestCase {
    @ParameterizedTest
    @ValueSource(strings = {"pre", "post", "assertion"})
    void excludesOnlyTheScriptBodyOfRealJsr223Editors(String type) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BeanInfo info = switch (type) {
            case "pre" -> new JSR223PreProcessorBeanInfo();
            case "post" -> new JSR223PostProcessorBeanInfo();
            default -> new JSR223AssertionBeanInfo();
            };
            GenericTestBeanCustomizer customizer = new GenericTestBeanCustomizer(info);
            List<Component> components = descendants(customizer);
            JSyntaxTextArea script = components.stream().filter(JSyntaxTextArea.class::isInstance)
                    .map(JSyntaxTextArea.class::cast).findFirst().orElseThrow();
            JTextField parameterField = components.stream().filter(JTextField.class::isInstance)
                    .map(JTextField.class::cast).findFirst().orElseThrow();
            int scriptListeners = script.getCaretListeners().length;
            int parameterListeners = parameterField.getCaretListeners().length;
            ParameterCompletion.install(customizer);
            assertEquals(scriptListeners, script.getCaretListeners().length);
            assertTrue(parameterField.getCaretListeners().length > parameterListeners);
            script.setLanguage("javascript");
            ParameterCompletion.install(customizer);
            ParameterCompletion.install(script.getParent().getParent());
            assertEquals(scriptListeners, script.getCaretListeners().length);
        });
    }

    private static List<Component> descendants(Container container) {
        List<Component> result = new ArrayList<>();
        for (Component component : container.getComponents()) {
            result.add(component);
            if (component instanceof Container child) {
                result.addAll(descendants(child));
            }
        }
        return result;
    }
}
