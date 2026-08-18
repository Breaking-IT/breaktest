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

package org.apache.jmeter.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;

import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTree;

import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.PowerTableModel;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GuiPackageTest {

    @AfterEach
    void resetGuiPackage() throws Exception {
        setField("guiPack", null, null);
    }

    @Test
    void cleanPlanHasNoUnsavedChanges() {
        GuiPackage guiPackage = newGuiPackage();

        assertFalse(guiPackage.hasUnsavedChanges());
    }

    @Test
    void dirtyTreeHasUnsavedChanges() {
        GuiPackage guiPackage = newGuiPackage();

        guiPackage.setDirty(true);

        assertTrue(guiPackage.hasUnsavedChanges());
    }

    @Test
    void pendingCurrentEditorHasUnsavedChanges() throws Exception {
        GuiPackage guiPackage = newGuiPackage();
        JMeterTreeNode testPlanNode = (JMeterTreeNode)
                ((JMeterTreeNode) guiPackage.getTreeModel().getRoot()).getChildAt(0);
        setField("currentNode", guiPackage, testPlanNode);
        setField("currentNodeEdited", guiPackage, true);

        assertTrue(guiPackage.hasUnsavedChanges());
    }

    @Test
    void displayingAnUneditedElementIsNotAnUnsavedChange() throws Exception {
        GuiPackage guiPackage = newGuiPackage();
        JMeterTreeNode testPlanNode = (JMeterTreeNode)
                ((JMeterTreeNode) guiPackage.getTreeModel().getRoot()).getChildAt(0);
        setField("currentNode", guiPackage, testPlanNode);
        setField("currentNodeUpdated", guiPackage, false);

        assertFalse(guiPackage.hasUnsavedChanges());
    }

    @Test
    void navigatingAwayWritesTheEditorBackWithoutTrackedInteraction() throws Exception {
        GuiPackage guiPackage = newGuiPackage();
        TestElement element = new GenericController();
        element.setProperty(TestElement.GUI_CLASS, RecordingGui.class.getName());
        element.setProperty(TestElement.TEST_CLASS, GenericController.class.getName());
        JMeterTreeNode node = new JMeterTreeNode(element, guiPackage.getTreeModel());
        setField("currentNode", guiPackage, node);
        configureCurrentGui(guiPackage, guiPackage.getGui(element), element);

        guiPackage.updateCurrentNode();

        assertEquals("written", element.getPropertyAsString("edited"));
    }

    @Test
    void treeSelectionMarksCurrentEditorEdited() throws Exception {
        GuiPackage guiPackage = newGuiPackage();
        JPanel panel = new JPanel();
        JTree tree = new JTree();
        panel.add(tree);
        installDirtyTrackers(guiPackage, panel);

        setField("currentNodeEdited", guiPackage, false);
        tree.setSelectionRow(0);

        assertTrue(getBooleanField("currentNodeEdited", guiPackage));
    }

    @Test
    void listSelectionMarksCurrentEditorEdited() throws Exception {
        GuiPackage guiPackage = newGuiPackage();
        JPanel panel = new JPanel();
        JList<String> list = new JList<>(new String[] { "a", "b" });
        panel.add(list);
        installDirtyTrackers(guiPackage, panel);

        setField("currentNodeEdited", guiPackage, false);
        list.setSelectedIndex(1);

        assertTrue(getBooleanField("currentNodeEdited", guiPackage));
    }

    @Test
    void committingTableCellMarksCurrentEditorEdited() throws Exception {
        GuiPackage guiPackage = newGuiPackage();
        PowerTableModel model = new PowerTableModel(new String[] { "value" }, new Class[] { String.class });
        model.addNewRow();
        JTable table = new JTable(model);
        installDirtyTrackers(guiPackage, table);

        setField("currentNodeEdited", guiPackage, false);
        model.setValueAt("changed", 0, 0);

        assertTrue(getBooleanField("currentNodeEdited", guiPackage));
    }

    @Test
    void changingTableStructureMarksCurrentEditorEdited() throws Exception {
        GuiPackage guiPackage = newGuiPackage();
        PowerTableModel model = new PowerTableModel(new String[] { "value" }, new Class[] { String.class });
        JTable table = new JTable(model);
        installDirtyTrackers(guiPackage, table);

        setField("currentNodeEdited", guiPackage, false);
        model.addNewColumn("second", String.class);

        assertTrue(getBooleanField("currentNodeEdited", guiPackage));
    }

    @Test
    void writtenBackNodeIsNotWrittenBackTwice() throws Exception {
        GuiPackage guiPackage = newGuiPackage();
        TestElement element = new GenericController();
        element.setProperty(TestElement.GUI_CLASS, RecordingGui.class.getName());
        element.setProperty(TestElement.TEST_CLASS, GenericController.class.getName());
        JMeterTreeNode node = new JMeterTreeNode(element, guiPackage.getTreeModel());
        setField("currentNode", guiPackage, node);
        setField("currentNodeUpdated", guiPackage, false);
        guiPackage.updateCurrentNode();
        element.removeProperty("edited");

        // A shared GUI can be configured for another element between these calls.
        // The old node must not be written back from that unrelated GUI state.
        setField("currentNode", guiPackage, node);
        guiPackage.updateCurrentNode();

        assertEquals("", element.getPropertyAsString("edited"));
    }

    private static void configureCurrentGui(GuiPackage guiPackage, JMeterGUIComponent gui, TestElement element)
            throws Exception {
        Method method = GuiPackage.class.getDeclaredMethod(
                "configureCurrentGui", JMeterGUIComponent.class, TestElement.class);
        method.setAccessible(true);
        method.invoke(guiPackage, gui, element);
    }

    private static void installDirtyTrackers(GuiPackage guiPackage, Component component) throws Exception {
        Method method = GuiPackage.class.getDeclaredMethod("installDirtyTrackers", Component.class);
        method.setAccessible(true);
        method.invoke(guiPackage, component);
    }

    public static class RecordingGui implements JMeterGUIComponent {
        private String name = "recording";

        @Override
        public void modifyTestElement(TestElement element) {
            element.setProperty("edited", "written");
        }

        @Override
        public void configure(TestElement element) {
            // No state to restore.
        }

        @Override
        public void clearGui() {
            // No state to clear.
        }

        @Override
        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getStaticLabel() {
            return "Recording";
        }

        @Override
        public String getLabelResource() {
            return "recording";
        }

        @Override
        public String getDocAnchor() {
            return "";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
            // Always enabled.
        }

        @Override
        public JPopupMenu createPopupMenu() {
            return new JPopupMenu();
        }

        @Override
        public Collection<String> getMenuCategories() {
            return Collections.emptyList();
        }
    }

    private static GuiPackage newGuiPackage() {
        JMeterTreeModel model = new JMeterTreeModel(new TestPlan("Root"));
        GuiPackage.initInstance(new JMeterTreeListener(model), model);
        return GuiPackage.getInstance();
    }

    private static void setField(String name, Object target, Object value) throws Exception {
        Field field = GuiPackage.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean getBooleanField(String name, Object target) throws Exception {
        Field field = GuiPackage.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }
}
