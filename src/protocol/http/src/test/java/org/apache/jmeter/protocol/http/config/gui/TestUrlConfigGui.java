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

package org.apache.jmeter.protocol.http.config.gui;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;

import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.jmeter.config.gui.ArgumentsPanel;
import org.apache.jmeter.protocol.http.gui.HTTPArgumentsPanel;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jorphan.gui.JLabeledTextField;
import org.apache.jorphan.gui.ObjectTableModel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestUrlConfigGui {

    @Test
    public void testTabCountUpdateDoesNotReenterWhileParameterCellIsEditing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UrlConfigGui gui = new UrlConfigGui();
                JTable table = argumentTable(gui);
                ObjectTableModel model = (ObjectTableModel) table.getModel();
                model.addRow(new HTTPArgument("name", "value"));

                Assertions.assertTrue(table.editCellAt(0, 1));
                ((JTextField) table.getEditorComponent()).setText("edited");

                model.setValueAt("external update", 0, 1);

                Assertions.assertFalse(table.isEditing());
                Assertions.assertEquals("edited", model.getValueAt(0, 1));
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        });
    }

    @Test
    public void testModernUrlBarGivesHostMoreSpaceThanPort() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UrlConfigGui gui = new UrlConfigGui(true, true, true, true);
                gui.setSize(1400, 700);
                layoutRecursively(gui);

                JLabeledTextField host = labeledField(gui, "domain");
                JLabeledTextField port = labeledField(gui, "port");
                Assertions.assertTrue(host.getWidth() >= 260);
                Assertions.assertEquals(100, port.getWidth());
                Assertions.assertTrue(host.getWidth() > port.getWidth() * 2);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        });
    }

    private static JTable argumentTable(UrlConfigGui gui) throws ReflectiveOperationException {
        Field argsPanelField = UrlConfigGui.class.getDeclaredField("argsPanel");
        argsPanelField.setAccessible(true);
        HTTPArgumentsPanel argsPanel = (HTTPArgumentsPanel) argsPanelField.get(gui);

        Field tableField = ArgumentsPanel.class.getDeclaredField("table");
        tableField.setAccessible(true);
        return (JTable) tableField.get(argsPanel);
    }

    private static JLabeledTextField labeledField(UrlConfigGui gui, String name)
            throws ReflectiveOperationException {
        Field field = UrlConfigGui.class.getDeclaredField(name);
        field.setAccessible(true);
        return (JLabeledTextField) field.get(gui);
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container childContainer) {
                layoutRecursively(childContainer);
            }
        }
    }
}
