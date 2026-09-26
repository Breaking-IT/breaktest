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

package org.apache.jmeter.testbeans.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;

import org.apache.jmeter.gui.util.JTextScrollPane;
import org.apache.jmeter.junit.JMeterTestCase;
import org.junit.jupiter.api.Test;

public class TestTextAreaEditor extends JMeterTestCase {

    @Test
    public void plainEditorIsJustTheScriptArea() {
        TextAreaEditor editor = new TextAreaEditor();

        assertInstanceOf(JTextScrollPane.class, editor.getCustomEditor());
    }

    @Test
    public void jsr223EditorShowsAskAiButtonAboveTheScriptArea() {
        TextAreaEditor editor = new TextAreaEditor();
        Component scriptArea = editor.getCustomEditor();

        editor.installJsr223AiHelper("JSR223Sampler", () -> "groovy");

        List<Component> components = descendants(editor.getCustomEditor());
        assertTrue(components.contains(scriptArea), "The script area must stay in the editor");
        List<String> buttons = components.stream()
                .filter(JButton.class::isInstance)
                .map(button -> ((JButton) button).getText())
                .toList();
        assertEquals(List.of("Ask AI"), buttons);
    }

    private static List<Component> descendants(Component root) {
        List<Component> result = new ArrayList<>();
        result.add(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                result.addAll(descendants(child));
            }
        }
        return result;
    }
}
