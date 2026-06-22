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
import java.awt.FontMetrics;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ButtonGroup;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.ListSelectionModel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTable;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableColumn;

import org.apache.jmeter.control.WhileController;
import org.apache.jmeter.control.WhileControllerCondition;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.util.HeaderAsPropertyRenderer;
import org.apache.jmeter.gui.util.PowerTableModel;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.gui.util.JTextScrollPane;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.GuiUtils;

import net.miginfocom.swing.MigLayout;

@GUIMenuSortOrder(4)
@TestElementMetadata(labelResource = "while_controller_title")
public class WhileControllerGui extends AbstractControllerGui
        implements ActionListener, DocumentListener, TableModelListener {

    private static final long serialVersionUID = 240L;

    private static final String ADD_CONDITION = "add_condition";

    private static final String DELETE_CONDITION = "delete_condition";

    private static final String OPERAND1_COLUMN = "while_controller_operand1"; // $NON-NLS-1$

    private static final String OPERATOR_COLUMN = "while_controller_operator"; // $NON-NLS-1$

    private static final String OPERAND2_COLUMN = "while_controller_operand2"; // $NON-NLS-1$

    private static final int OPERAND1_INDEX = 0;

    private static final int OPERATOR_INDEX = 1;

    private static final int OPERAND2_INDEX = 2;

    private static final int OPERAND_MIN_WIDTH = 80;

    private static final int OPERATOR_COLUMN_PADDING = 40;

    /**
     * A field allowing the user to specify the condition (not yet used).
     */
    private JSyntaxTextArea theCondition;

    private PowerTableModel conditionTableModel;

    private JTable conditionTable;

    private JButton addConditionButton;

    private JButton deleteConditionButton;

    private JRadioButton matchAll;

    private JRadioButton matchAny;

    private JLabel structuredConditionLabel;

    private JLabel conditionLabel;

    /** The name of the condition field component. */
    private static final String CONDITION = "While_Condition"; // $NON-NLS-1$

    /**
     * Create a new LoopControlPanel as a standalone component.
     */
    public WhileControllerGui() {
        init();
    }

    /**
     * A newly created component can be initialized with the contents of a Test
     * Element object by calling this method. The component is responsible for
     * querying the Test Element object for the relevant information to display
     * in its GUI.
     *
     * @param element
     *            the TestElement to configure
     */
    @Override
    public void configure(TestElement element) {
        super.configure(element);
        if (element instanceof WhileController whileController) {
            theCondition.setText(whileController.getCondition());
            matchAny.setSelected(WhileController.MATCH_ANY.equals(whileController.getConditionMatch()));
            matchAll.setSelected(!matchAny.isSelected());
            conditionTableModel.clearData();
            PropertyIterator iterator = whileController.getConditions().iterator();
            while (iterator.hasNext()) {
                JMeterProperty property = iterator.next();
                Object value = property.getObjectValue();
                if (value instanceof WhileControllerCondition condition) {
                    conditionTableModel.addRow(new Object[] {
                            condition.getOperand1(),
                            OperatorSelection.fromId(condition.getOperator()),
                            condition.getOperand2()
                    });
                }
            }
            conditionTableModel.fireTableDataChanged();
            updateModeControls();
        }

    }

    /**
     * Implements JMeterGUIComponent.createTestElement()
     */
    @Override
    public TestElement createTestElement() {
        WhileController controller = new WhileController();
        modifyTestElement(controller);
        return controller;
    }

    /**
     * Implements JMeterGUIComponent.modifyTestElement(TestElement)
     */
    @Override
    public void modifyTestElement(TestElement controller) {
        configureTestElement(controller);
        if (controller instanceof WhileController whileController) {
            GuiUtils.stopTableEditing(conditionTable);
            if (!theCondition.getText().isEmpty()) {
                whileController.setCondition(theCondition.getText());
            } else {
                whileController.setCondition(""); // $NON-NLS-1$
            }
            whileController.setConditionMatch(matchAny.isSelected() ? WhileController.MATCH_ANY : WhileController.MATCH_ALL);
            whileController.setConditions(getConditionRows());
        }
    }

    /**
     * Implements JMeterGUIComponent.clearGui
     */
    @Override
    public void clearGui() {
        super.clearGui();
        theCondition.setText(""); // $NON-NLS-1$
        conditionTableModel.clearData();
        matchAll.setSelected(true);
        updateModeControls();
    }

    @Override
    public String getLabelResource() {
        return "while_controller_title"; // $NON-NLS-1$
    }

    /**
     * Initialize the GUI components and layout for this component.
     */
    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);
        add(createConditionPanel(), BorderLayout.CENTER);
    }

    /**
     * Create a GUI panel containing the condition. TODO make use of the field
     *
     * @return a GUI panel containing the condition components
     */
    private JPanel createConditionPanel() {
        JPanel conditionPanel = new JPanel(new MigLayout("fill, wrap 1, insets 0", "[fill,grow]"));

        structuredConditionLabel = new JLabel(JMeterUtils.getResString("while_controller_conditions")); // $NON-NLS-1$
        conditionPanel.add(structuredConditionLabel);
        conditionPanel.add(createStructuredConditionPanel(), "pushx, growx, wmin 0"); // $NON-NLS-1$

        // Condition
        // This means exit if last sample failed
        theCondition = JSyntaxTextArea.getInstance(5, 50);
        theCondition.getDocument().addDocumentListener(this);
        JTextScrollPane theConditionJSP = JTextScrollPane.getInstance(theCondition);
        conditionLabel = JMeterUtils.labelFor(theConditionJSP, "while_controller_label");
        conditionPanel.add(conditionLabel);
        theCondition.setName(CONDITION);
        conditionPanel.add(theConditionJSP, "push, grow, wmin 0"); // $NON-NLS-1$

        return conditionPanel;
    }

    private JPanel createStructuredConditionPanel() {
        JPanel panel = new JPanel(new MigLayout("fillx, wrap 1, insets 0", "[fill,grow]")); // $NON-NLS-1$ // $NON-NLS-2$
        conditionTableModel = new PowerTableModel(
                new String[] { OPERAND1_COLUMN, OPERATOR_COLUMN, OPERAND2_COLUMN },
                new Class[] { String.class, Object.class, String.class });
        conditionTableModel.addTableModelListener(this);
        conditionTable = new JTable(conditionTableModel);
        conditionTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        JMeterUtils.applyHiDPI(conditionTable);
        conditionTable.getTableHeader().setDefaultRenderer(new HeaderAsPropertyRenderer());
        conditionTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        conditionTable.getColumnModel().getColumn(OPERATOR_INDEX)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(OperatorSelection.values())));
        configureConditionTableColumns();
        conditionTable.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeConditionTableColumns();
            }
        });
        panel.add(makeScrollPane(conditionTable), "height 100:120:, pushx, growx, wmin 0"); // $NON-NLS-1$

        JPanel controls = new JPanel(new MigLayout("insets 0, fillx, wrap 2", "[][]")); // $NON-NLS-1$ // $NON-NLS-2$
        addConditionButton = new JButton(JMeterUtils.getResString("add")); // $NON-NLS-1$
        addConditionButton.setActionCommand(ADD_CONDITION);
        addConditionButton.addActionListener(this);
        controls.add(addConditionButton);

        deleteConditionButton = new JButton(JMeterUtils.getResString("delete")); // $NON-NLS-1$
        deleteConditionButton.setActionCommand(DELETE_CONDITION);
        deleteConditionButton.addActionListener(this);
        controls.add(deleteConditionButton);

        matchAll = new JRadioButton(JMeterUtils.getResString("while_controller_match_all")); // $NON-NLS-1$
        matchAny = new JRadioButton(JMeterUtils.getResString("while_controller_match_any")); // $NON-NLS-1$
        ButtonGroup matchGroup = new ButtonGroup();
        matchGroup.add(matchAll);
        matchGroup.add(matchAny);
        matchAll.setSelected(true);
        controls.add(matchAll, "newline, span 2"); // $NON-NLS-1$
        controls.add(matchAny, "span 2"); // $NON-NLS-1$
        panel.add(controls, "growx, wmin 0"); // $NON-NLS-1$
        checkDeleteStatus();
        return panel;
    }

    private void configureConditionTableColumns() {
        TableColumn operand1Column = conditionTable.getColumnModel().getColumn(OPERAND1_INDEX);
        TableColumn operatorColumn = conditionTable.getColumnModel().getColumn(OPERATOR_INDEX);
        TableColumn operand2Column = conditionTable.getColumnModel().getColumn(OPERAND2_INDEX);
        int operatorWidth = getOperatorColumnWidth();

        operand1Column.setMinWidth(OPERAND_MIN_WIDTH);
        operand1Column.setPreferredWidth(300);
        operatorColumn.setMinWidth(operatorWidth);
        operatorColumn.setPreferredWidth(operatorWidth);
        operatorColumn.setMaxWidth(operatorWidth);
        operatorColumn.setResizable(false);
        operand2Column.setMinWidth(OPERAND_MIN_WIDTH);
        operand2Column.setPreferredWidth(300);
        resizeConditionTableColumns();
    }

    private void resizeConditionTableColumns() {
        int tableWidth = conditionTable.getWidth();
        if (conditionTable.getParent() != null) {
            tableWidth = conditionTable.getParent().getWidth();
        }
        if (tableWidth <= 0) {
            return;
        }

        TableColumn operand1Column = conditionTable.getColumnModel().getColumn(OPERAND1_INDEX);
        TableColumn operatorColumn = conditionTable.getColumnModel().getColumn(OPERATOR_INDEX);
        TableColumn operand2Column = conditionTable.getColumnModel().getColumn(OPERAND2_INDEX);
        int operatorWidth = operatorColumn.getPreferredWidth();
        int remainingWidth = Math.max(OPERAND_MIN_WIDTH * 2, tableWidth - operatorWidth);
        int operand1Width = remainingWidth / 2;
        int operand2Width = remainingWidth - operand1Width;

        operand1Column.setPreferredWidth(operand1Width);
        operand1Column.setWidth(operand1Width);
        operatorColumn.setWidth(operatorWidth);
        operand2Column.setPreferredWidth(operand2Width);
        operand2Column.setWidth(operand2Width);
    }

    private int getOperatorColumnWidth() {
        FontMetrics metrics = conditionTable.getFontMetrics(conditionTable.getFont());
        int width = metrics.stringWidth(JMeterUtils.getResString(OPERATOR_COLUMN));
        for (OperatorSelection selection : OperatorSelection.values()) {
            width = Math.max(width, metrics.stringWidth(selection.toString()));
        }
        return width + OPERATOR_COLUMN_PADDING;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String action = e.getActionCommand();
        if (action.equals(ADD_CONDITION)) {
            GuiUtils.stopTableEditing(conditionTable);
            conditionTableModel.addRow(new Object[] {
                    "",
                    OperatorSelection.fromId(WhileController.Operator.EQUALS.getId()),
                    ""
            });
            conditionTableModel.fireTableDataChanged();
            updateModeControls();
        } else if (action.equals(DELETE_CONDITION)) {
            GuiUtils.stopTableEditing(conditionTable);
            int[] selectedRows = conditionTable.getSelectedRows();
            for (int i = selectedRows.length - 1; i >= 0; i--) {
                conditionTableModel.removeRow(selectedRows[i]);
            }
            conditionTableModel.fireTableDataChanged();
            updateModeControls();
        }
    }

    private List<WhileControllerCondition> getConditionRows() {
        List<WhileControllerCondition> conditions = new ArrayList<>();
        for (int row = 0; row < conditionTableModel.getRowCount(); row++) {
            String operand1 = String.valueOf(conditionTableModel.getValueAt(row, OPERAND1_INDEX));
            Object operatorValue = conditionTableModel.getValueAt(row, OPERATOR_INDEX);
            String operator = operatorValue instanceof OperatorSelection selection
                    ? selection.id
                    : String.valueOf(operatorValue);
            String operand2 = String.valueOf(conditionTableModel.getValueAt(row, OPERAND2_INDEX));
            WhileControllerCondition condition = new WhileControllerCondition(operand1, operator, operand2);
            if (!condition.isBlank()) {
                conditions.add(condition);
            }
        }
        return conditions;
    }

    private void checkDeleteStatus() {
        if (deleteConditionButton != null) {
            deleteConditionButton.setEnabled(conditionTable != null
                    && conditionTable.isEnabled()
                    && conditionTableModel != null
                    && conditionTableModel.getRowCount() > 0);
        }
    }

    private void updateModeControls() {
        if (conditionTable == null
                || theCondition == null
                || structuredConditionLabel == null
                || addConditionButton == null
                || matchAll == null
                || matchAny == null
                || conditionLabel == null) {
            return;
        }
        boolean hasLegacyCondition = !theCondition.getText().trim().isEmpty();
        boolean hasStructuredConditions = hasStructuredConditionRows();
        boolean structuredEnabled = !hasLegacyCondition;
        boolean legacyEnabled = hasLegacyCondition || !hasStructuredConditions;

        structuredConditionLabel.setEnabled(structuredEnabled);
        conditionTable.setEnabled(structuredEnabled);
        conditionTable.getTableHeader().setEnabled(structuredEnabled);
        addConditionButton.setEnabled(structuredEnabled);
        matchAll.setEnabled(structuredEnabled);
        matchAny.setEnabled(structuredEnabled);

        conditionLabel.setEnabled(legacyEnabled);
        theCondition.setEnabled(legacyEnabled);
        theCondition.setEditable(legacyEnabled);

        checkDeleteStatus();
    }

    private boolean hasStructuredConditionRows() {
        if (conditionTableModel == null) {
            return false;
        }
        for (int row = 0; row < conditionTableModel.getRowCount(); row++) {
            String operand1 = String.valueOf(conditionTableModel.getValueAt(row, OPERAND1_INDEX));
            String operand2 = String.valueOf(conditionTableModel.getValueAt(row, OPERAND2_INDEX));
            if (!operand1.isBlank() || !operand2.isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void insertUpdate(DocumentEvent e) {
        updateModeControls();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        updateModeControls();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        updateModeControls();
    }

    @Override
    public void tableChanged(TableModelEvent e) {
        updateModeControls();
    }

    private static class OperatorSelection {
        private static final OperatorSelection[] VALUES = new OperatorSelection[] {
                new OperatorSelection(WhileController.Operator.EQUALS.getId(), "while_controller_operator_equals"),
                new OperatorSelection(WhileController.Operator.NOT_EQUALS.getId(), "while_controller_operator_not_equals"),
                new OperatorSelection(WhileController.Operator.CONTAINS.getId(), "while_controller_operator_contains"),
                new OperatorSelection(WhileController.Operator.NOT_CONTAINS.getId(), "while_controller_operator_not_contains"),
                new OperatorSelection(WhileController.Operator.STARTS_WITH.getId(), "while_controller_operator_starts_with"),
                new OperatorSelection(WhileController.Operator.NOT_STARTS_WITH.getId(), "while_controller_operator_not_starts_with"),
                new OperatorSelection(WhileController.Operator.ENDS_WITH.getId(), "while_controller_operator_ends_with"),
                new OperatorSelection(WhileController.Operator.NOT_ENDS_WITH.getId(), "while_controller_operator_not_ends_with"),
                new OperatorSelection(WhileController.Operator.MATCHES_REGEX.getId(), "while_controller_operator_matches_regex"),
                new OperatorSelection(WhileController.Operator.NOT_MATCHES_REGEX.getId(), "while_controller_operator_not_matches_regex"),
                new OperatorSelection(WhileController.Operator.GREATER_THAN.getId(), "while_controller_operator_greater_than"),
                new OperatorSelection(WhileController.Operator.GREATER_THAN_OR_EQUAL.getId(), "while_controller_operator_greater_than_or_equal"),
                new OperatorSelection(WhileController.Operator.LESS_THAN.getId(), "while_controller_operator_less_than"),
                new OperatorSelection(WhileController.Operator.LESS_THAN_OR_EQUAL.getId(), "while_controller_operator_less_than_or_equal"),
                new OperatorSelection(WhileController.Operator.EXISTS.getId(), "while_controller_operator_exists"),
                new OperatorSelection(WhileController.Operator.NOT_EXISTS.getId(), "while_controller_operator_not_exists"),
        };

        private final String id;

        private final String label;

        private OperatorSelection(String id, String labelResource) {
            this.id = id;
            this.label = JMeterUtils.getResString(labelResource);
        }

        private static OperatorSelection[] values() {
            return VALUES.clone();
        }

        private static OperatorSelection fromId(String id) {
            for (OperatorSelection value : VALUES) {
                if (value.id.equals(id)) {
                    return value;
                }
            }
            return VALUES[0];
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
