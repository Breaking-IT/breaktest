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

package org.apache.jmeter.protocol.websocket.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.testbeans.gui.GenericTestBeanCustomizer;
import org.junit.jupiter.api.Test;

class WebSocketWaitEditorTest extends JMeterTestCase {
    @Test
    void waitModeEnablesOnlyRelevantMatcherAndPreservesValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GenericTestBeanCustomizer gui = new GenericTestBeanCustomizer(new WebSocketSendWaitSamplerBeanInfo());
            Map<String, Object> values = new HashMap<>();
            gui.setObject(values);
            JLabel regex = label(gui, "Response regular expression");
            JLabel binary = label(gui, "Response binary sequence");
            JComboBox<?> mode = descendants(label(gui, "Wait for").getLabelFor())
                    .filter(JComboBox.class::isInstance).map(JComboBox.class::cast).findFirst().orElseThrow();
            assertEnabled(regex, false);
            assertEnabled(binary, false);
            mode.setSelectedItem(WebSocketSendWaitSampler.MATCHING_MESSAGE);
            assertEnabled(regex, true);
            assertEnabled(binary, false);
            text(regex).setText("hello.*");
            mode.setSelectedItem(WebSocketSendWaitSampler.BINARY_MESSAGE);
            assertEnabled(regex, false);
            assertEnabled(binary, true);
            text(binary).setText("07 95");
            mode.setSelectedItem(WebSocketSendWaitSampler.NEXT_MESSAGE);
            assertEnabled(regex, false);
            assertEnabled(binary, false);
            assertEquals("hello.*", text(regex).getText());
            assertEquals("07 95", text(binary).getText());
            values.put("waitMode", WebSocketSendWaitSampler.BINARY_MESSAGE);
            values.put("responsePattern", "saved regex");
            values.put("responseBinary", "AA BB");
            gui.setObject(values);
            assertEnabled(regex, false);
            assertEnabled(binary, true);
            assertEquals("AA BB", text(binary).getText());
            values.put("waitMode", WebSocketSendWaitSampler.MATCHING_MESSAGE);
            gui.setObject(values);
            assertEnabled(regex, true);
            assertEnabled(binary, false);
            assertEquals("saved regex", text(regex).getText());
        });
    }

    private static JLabel label(Component root, String prefix) {
        return descendants(root).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                .filter(label -> label.getText().startsWith(prefix)).findFirst().orElseThrow();
    }

    private static JTextComponent text(JLabel label) {
        return descendants(label.getLabelFor()).filter(JTextComponent.class::isInstance)
                .map(JTextComponent.class::cast).findFirst().orElseThrow();
    }

    private static void assertEnabled(JLabel label, boolean enabled) {
        assertEquals(enabled, label.isEnabled());
        if (enabled) {
            assertTrue(text(label).isEnabled());
        } else {
            assertFalse(text(label).isEnabled());
        }
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root), root instanceof Container container
                ? Stream.of(container.getComponents()).flatMap(WebSocketWaitEditorTest::descendants) : Stream.empty());
    }
}
