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

package org.apache.jmeter.control.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.table.AbstractTableModel;

import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.WeightedSwitchController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

@TestElementMetadata(labelResource = "weighted_switch_controller_title")
public class WeightedSwitchControllerGui extends AbstractControllerGui implements TreeModelListener {
    private static final long serialVersionUID = 1L;

    private final WeightsModel model = new WeightsModel();
    private final JTable table = new JTable(model);
    private transient JMeterTreeModel treeModel;
    private transient TestElement configuredElement;

    public WeightedSwitchControllerGui() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);
        table.setRowHeight(Math.max(table.getRowHeight(), 24));
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.getColumnModel().getColumn(1).setCellEditor(new WeightEditor());
        JPanel content = new JPanel(new BorderLayout(0, 5));
        content.add(new JLabel(JMeterUtils.getResString("weighted_switch_controller_help")), BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);
    }

    @Override
    public String getLabelResource() {
        return "weighted_switch_controller_title";
    }

    @Override
    public TestElement createTestElement() {
        WeightedSwitchController controller = new WeightedSwitchController();
        configureTestElement(controller);
        return controller;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        finishEditing();
        configureTestElement(element);
        if (element == configuredElement) {
            boolean changed = false;
            for (TestElement child : model.children) {
                String weight = model.pendingWeights.remove(child);
                if (weight != null && !weight.equals(WeightedSwitchController.getWeight(child))) {
                    WeightedSwitchController.setWeight(child, weight);
                    changed = true;
                }
            }
            GuiPackage gui = GuiPackage.getInstance();
            if (changed && gui != null) {
                JMeterTreeNode node = gui.getNodeOf(element);
                if (node != null) {
                    // The parent's own properties did not change, so explicitly capture child edits for undo/save.
                    gui.setDirty(true);
                    gui.getTreeModel().nodeChanged(node);
                }
            }
        }
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        detachTreeListener();
        configuredElement = element;
        model.pendingWeights.clear();
        attachTreeListener();
        refreshChildren();
    }

    @Override
    public void clearGui() {
        super.clearGui();
        finishEditing();
        detachTreeListener();
        configuredElement = null;
        model.children.clear();
        model.pendingWeights.clear();
        model.fireTableDataChanged();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        attachTreeListener();
        refreshChildren();
    }

    @Override
    public void removeNotify() {
        detachTreeListener();
        super.removeNotify();
    }

    private void attachTreeListener() {
        if (treeModel == null && GuiPackage.getInstance() != null) {
            treeModel = GuiPackage.getInstance().getTreeModel();
            treeModel.addTreeModelListener(this);
        }
    }

    private void detachTreeListener() {
        if (treeModel != null) {
            treeModel.removeTreeModelListener(this);
            treeModel = null;
        }
    }

    private void finishEditing() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
    }

    private void refreshChildren() {
        JMeterTreeNode node = treeModel == null || configuredElement == null
                ? null : treeModel.getNodeOf(configuredElement);
        refreshChildren(node);
    }

    private void refreshChildren(JMeterTreeNode node) {
        List<TestElement> children = new ArrayList<>();
        if (node != null) {
            for (int i = 0; i < node.getChildCount(); i++) {
                TestElement child = ((JMeterTreeNode) node.getChildAt(i)).getTestElement();
                if (child instanceof Sampler || child instanceof Controller) {
                    children.add(child);
                }
            }
        }
        boolean sameRows = children.size() == model.children.size();
        for (int i = 0; sameRows && i < children.size(); i++) {
            sameRows = children.get(i) == model.children.get(i);
        }
        if (sameRows) {
            // Renames, enabled-state and external weight updates must not interrupt cell editing.
            if (!children.isEmpty()) {
                model.fireTableRowsUpdated(0, children.size() - 1);
            }
            return;
        }
        finishEditing();
        model.children.clear();
        model.children.addAll(children);
        model.fireTableDataChanged();
    }

    private void refreshAffectedChildren(TreeModelEvent event) {
        Object parent = event.getTreePath().getLastPathComponent();
        if (parent instanceof JMeterTreeNode node && node.getTestElement() == configuredElement) {
            refreshChildren(node);
        } else if (event.getChildren() != null) {
            for (Object child : event.getChildren()) {
                if (child instanceof JMeterTreeNode node && node.getTestElement() == configuredElement) {
                    refreshChildren(node);
                    break;
                }
            }
        }
    }

    @Override
    public void treeNodesChanged(TreeModelEvent event) {
        refreshAffectedChildren(event);
    }

    @Override
    public void treeNodesInserted(TreeModelEvent event) {
        refreshAffectedChildren(event);
    }

    @Override
    public void treeNodesRemoved(TreeModelEvent event) {
        refreshAffectedChildren(event);
    }

    @Override
    public void treeStructureChanged(TreeModelEvent event) {
        refreshChildren();
    }

    private static class WeightsModel extends AbstractTableModel {
        private final List<TestElement> children = new ArrayList<>();
        private final IdentityHashMap<TestElement, String> pendingWeights = new IdentityHashMap<>();

        @Override
        public int getRowCount() {
            return children.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return JMeterUtils.getResString(column == 0 ? "weighted_switch_controller_name"
                    : column == 1 ? "weighted_switch_controller_weight" : "settings_enabled");
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 ? Boolean.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            TestElement child = children.get(row);
            return column == 0 ? child.getName()
                    : column == 1 ? pendingWeights.getOrDefault(child, WeightedSwitchController.getWeight(child)) : child.isEnabled();
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 1) {
                String weight = value.toString().trim();
                WeightedSwitchController.parseWeight(weight);
                pendingWeights.put(children.get(row), weight);
                fireTableCellUpdated(row, column);
            }
        }
    }

    private static class WeightEditor extends DefaultCellEditor {
        WeightEditor() {
            super(new JTextField());
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
            JTextField field = (JTextField) super.getTableCellEditorComponent(table, value, selected, row, column);
            field.setBorder(new JTextField().getBorder());
            field.setToolTipText(null);
            return field;
        }

        @Override
        public boolean stopCellEditing() {
            try {
                WeightedSwitchController.parseWeight(getCellEditorValue().toString());
                return super.stopCellEditing();
            } catch (IllegalArgumentException e) {
                ((JTextField) getComponent()).setBorder(BorderFactory.createLineBorder(Color.RED));
                ((JTextField) getComponent()).setToolTipText(
                        JMeterUtils.getResString("weighted_switch_controller_invalid"));
                return false;
            }
        }
    }
}
