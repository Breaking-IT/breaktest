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
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.ListSelectionModel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableColumn;

import org.apache.jmeter.control.IfController;
import org.apache.jmeter.control.IfControllerCondition;
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
import org.apache.jorphan.gui.JFactory;
import org.apache.jorphan.gui.JMeterUIDefaults;
import org.apache.jorphan.gui.GuiUtils;

import net.miginfocom.swing.MigLayout;

/**
 * The user interface for a controller which specifies that its subcomponents
 * should be executed while a condition holds. This component can be used
 * standalone or embedded into some other component.
 *
 */
@GUIMenuSortOrder(1)
@TestElementMetadata(labelResource = "if_controller_title")
public class IfControllerPanel extends AbstractControllerGui
        implements ChangeListener, ActionListener, DocumentListener, TableModelListener {

    private static final long serialVersionUID = 240L;

    private static final String USE_LAST_SAMPLE_OK = "use_last_sample_ok";

    private static final String ADD_CONDITION = "add_condition";

    private static final String DELETE_CONDITION = "delete_condition";

    private static final String OPERAND1_COLUMN = "if_controller_operand1"; // $NON-NLS-1$

    private static final String OPERATOR_COLUMN = "if_controller_operator"; // $NON-NLS-1$

    private static final String OPERAND2_COLUMN = "if_controller_operand2"; // $NON-NLS-1$

    private static final int OPERAND1_INDEX = 0;

    private static final int OPERATOR_INDEX = 1;

    private static final int OPERAND2_INDEX = 2;

    private static final int OPERAND_MIN_WIDTH = 80;

    private static final int OPERATOR_COLUMN_PADDING = 40;

    /**
     * Used to warn about performance penalty
     */
    private JLabel warningLabel;

    private JLabel conditionLabel;
    /**
     * A field allowing the user to specify the number of times the controller
     * should loop.
     */
    private JSyntaxTextArea theCondition;

    private JCheckBox useExpression;

    private JCheckBox evaluateAll;

    private PowerTableModel conditionTableModel;

    private JTable conditionTable;

    private JButton addConditionButton;

    private JButton deleteConditionButton;

    private JRadioButton matchAll;

    private JRadioButton matchAny;

    private JLabel structuredConditionLabel;

    /**
     * Boolean indicating whether or not this component should display its name.
     * If true, this is a standalone component. If false, this component is
     * intended to be used as a subpanel for another component.
     */
    private boolean displayName = true;

    private JButton useLastSampleStatusButton;

    /**
     * Create a new LoopControlPanel as a standalone component.
     */
    public IfControllerPanel() {
        this(true);
    }

    /**
     * Create a new IfControllerPanel as either a standalone or an embedded
     * component.
     *
     * @param displayName
     *            indicates whether or not this component should display its
     *            name. If true, this is a standalone component. If false, this
     *            component is intended to be used as a subpanel for another
     *            component.
     */
    public IfControllerPanel(boolean displayName) {
        this.displayName = displayName;
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
        if (element instanceof IfController ifController) {
            theCondition.setText(ifController.getCondition());
            evaluateAll.setSelected(ifController.isEvaluateAll());
            useExpression.setSelected(ifController.isUseExpression());
            matchAny.setSelected(IfController.MATCH_ANY.equals(ifController.getConditionMatch()));
            matchAll.setSelected(!matchAny.isSelected());
            conditionTableModel.clearData();
            PropertyIterator iterator = ifController.getConditions().iterator();
            while (iterator.hasNext()) {
                JMeterProperty property = iterator.next();
                Object value = property.getObjectValue();
                if (value instanceof IfControllerCondition condition) {
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
        IfController controller = new IfController();
        modifyTestElement(controller);
        return controller;
    }

    /**
     * Implements JMeterGUIComponent.modifyTestElement(TestElement)
     */
    @Override
    public void modifyTestElement(TestElement controller) {
        configureTestElement(controller);
        if (controller instanceof IfController ifController) {
            GuiUtils.stopTableEditing(conditionTable);
            ifController.setCondition(theCondition.getText());
            ifController.setEvaluateAll(evaluateAll.isSelected());
            ifController.setUseExpression(useExpression.isSelected());
            ifController.setConditionMatch(matchAny.isSelected() ? IfController.MATCH_ANY : IfController.MATCH_ALL);
            ifController.setConditions(getConditionRows());
        }
    }

    /**
     * Implements JMeterGUIComponent.clearGui
     */
    @Override
    public void clearGui() {
        super.clearGui();
        useExpression.setSelected(true);
        theCondition.setText(""); // $NON-NLS-1$
        evaluateAll.setSelected(false);
        conditionTableModel.clearData();
        matchAll.setSelected(true);
        updateModeControls();
    }

    @Override
    public String getLabelResource() {
        return "if_controller_title"; // $NON-NLS-1$
    }

    /**
     * Initialize the GUI components and layout for this component.
     */
    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        // Standalone
        if (displayName) {
            setLayout(new BorderLayout(0, 5));
            setBorder(makeBorder());
            add(makeTitlePanel(), BorderLayout.NORTH);
            add(createConditionPanel(), BorderLayout.CENTER);
        } else {
            // Embedded
            setLayout(new BorderLayout());
            add(createConditionPanel(), BorderLayout.NORTH);
        }
    }

    /**
     * Create a GUI panel containing the condition.
     *
     * @return a GUI panel containing the condition components
     */
    private JPanel createConditionPanel() {
        JPanel conditionPanel = new JPanel(new MigLayout("fill, wrap 1, insets 0", "[fill,grow]"));

        structuredConditionLabel = new JLabel(JMeterUtils.getResString("if_controller_conditions")); // $NON-NLS-1$
        conditionPanel.add(structuredConditionLabel);
        conditionPanel.add(createStructuredConditionPanel(), "pushx, growx, wmin 0"); // $NON-NLS-1$

        ImageIcon image = JMeterUtils.getImage("warning.png"); // $NON-NLS-1$
        warningLabel = new JLabel(JMeterUtils.getResString("if_controller_warning"), image, SwingConstants.LEFT); // $NON-NLS-1$
        JFactory.warning(warningLabel);
        conditionPanel.add(warningLabel);

        // Condition LABEL
        conditionLabel = new JLabel(JMeterUtils.getResString("if_controller_label")); // $NON-NLS-1$
        conditionPanel.add(conditionLabel);
        conditionLabel.setName("if_controller_label"); // $NON-NLS-1$

        // Condition
        theCondition = JSyntaxTextArea.getInstance(5, 50);
        theCondition.getDocument().addDocumentListener(this);
        conditionLabel.setLabelFor(theCondition);
        conditionPanel.add(JTextScrollPane.getInstance(theCondition), "push, grow, wmin 0"); // $NON-NLS-1$

        JLabel ifControllerTipLabel = new JLabel(JMeterUtils.getResString("if_controller_tip")); // $NON-NLS-1$
        useLastSampleStatusButton = new JButton(JMeterUtils.getResString("if_controller_use_last_sample_ok")); // $NON-NLS-1$
        useLastSampleStatusButton.setActionCommand(USE_LAST_SAMPLE_OK);
        useLastSampleStatusButton.addActionListener(this);
        JPanel lastSamplePanel = new JPanel(new MigLayout("insets 0, fillx", "[][fill,grow]")); // $NON-NLS-1$ // $NON-NLS-2$
        lastSamplePanel.add(useLastSampleStatusButton);
        lastSamplePanel.add(ifControllerTipLabel);
        conditionPanel.add(lastSamplePanel, "growx, wmin 0"); // $NON-NLS-1$

        // Use expression instead of Groovy
        useExpression = new JCheckBox(JMeterUtils.getResString("if_controller_expression")); // $NON-NLS-1$
        useExpression.addChangeListener(this);
        conditionPanel.add(useExpression);

        // Evaluate All checkbox
        evaluateAll = new JCheckBox(JMeterUtils.getResString("if_controller_evaluate_all")); // $NON-NLS-1$
        conditionPanel.add(evaluateAll);

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

        matchAll = new JRadioButton(JMeterUtils.getResString("if_controller_match_all")); // $NON-NLS-1$
        matchAny = new JRadioButton(JMeterUtils.getResString("if_controller_match_any")); // $NON-NLS-1$
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
    public void stateChanged(ChangeEvent e) {
        if(e.getSource() == useExpression) {
            String colorId;
            if(useExpression.isSelected()) {
                colorId = JMeterUIDefaults.LABEL_WARNING_FOREGROUND;
                conditionLabel.setText(JMeterUtils.getResString("if_controller_expression_label")); // $NON-NLS-1$
                useLastSampleStatusButton.setEnabled(true);
            } else {
                colorId = JMeterUIDefaults.LABEL_ERROR_FOREGROUND;
                conditionLabel.setText(JMeterUtils.getResString("if_controller_label")); // $NON-NLS-1$
                useLastSampleStatusButton.setEnabled(false);
            }
            warningLabel.setForeground(UIManager.getColor(colorId));
            updateModeControls();
        }
    }

    /**
     * Fill theCondition
     * @param e {@link ActionEvent}
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        String action = e.getActionCommand();
        if (action.equals(USE_LAST_SAMPLE_OK)) {
            theCondition.setText(theCondition.getText()+"${JMeterThread.last_sample_ok}"); // $NON-NLS-1$
        } else if (action.equals(ADD_CONDITION)) {
            GuiUtils.stopTableEditing(conditionTable);
            conditionTableModel.addRow(new Object[] { "", OperatorSelection.fromId(IfController.Operator.EQUALS.getId()), "" }); // $NON-NLS-1$ // $NON-NLS-2$
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

    private List<IfControllerCondition> getConditionRows() {
        List<IfControllerCondition> conditions = new ArrayList<>();
        for (int row = 0; row < conditionTableModel.getRowCount(); row++) {
            String operand1 = String.valueOf(conditionTableModel.getValueAt(row, OPERAND1_INDEX));
            Object operatorValue = conditionTableModel.getValueAt(row, OPERATOR_INDEX);
            String operator = operatorValue instanceof OperatorSelection selection
                    ? selection.id
                    : String.valueOf(operatorValue);
            String operand2 = String.valueOf(conditionTableModel.getValueAt(row, OPERAND2_INDEX));
            IfControllerCondition condition = new IfControllerCondition(operand1, operator, operand2);
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
                || conditionLabel == null
                || useExpression == null
                || useLastSampleStatusButton == null
                || warningLabel == null) {
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
        useExpression.setEnabled(legacyEnabled);
        useLastSampleStatusButton.setEnabled(legacyEnabled && useExpression.isSelected());
        warningLabel.setEnabled(legacyEnabled);

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
                new OperatorSelection(IfController.Operator.EQUALS.getId(), "if_controller_operator_equals"),
                new OperatorSelection(IfController.Operator.NOT_EQUALS.getId(), "if_controller_operator_not_equals"),
                new OperatorSelection(IfController.Operator.CONTAINS.getId(), "if_controller_operator_contains"),
                new OperatorSelection(IfController.Operator.NOT_CONTAINS.getId(), "if_controller_operator_not_contains"),
                new OperatorSelection(IfController.Operator.STARTS_WITH.getId(), "if_controller_operator_starts_with"),
                new OperatorSelection(IfController.Operator.NOT_STARTS_WITH.getId(), "if_controller_operator_not_starts_with"),
                new OperatorSelection(IfController.Operator.ENDS_WITH.getId(), "if_controller_operator_ends_with"),
                new OperatorSelection(IfController.Operator.NOT_ENDS_WITH.getId(), "if_controller_operator_not_ends_with"),
                new OperatorSelection(IfController.Operator.MATCHES_REGEX.getId(), "if_controller_operator_matches_regex"),
                new OperatorSelection(IfController.Operator.NOT_MATCHES_REGEX.getId(), "if_controller_operator_not_matches_regex"),
                new OperatorSelection(IfController.Operator.GREATER_THAN.getId(), "if_controller_operator_greater_than"),
                new OperatorSelection(IfController.Operator.GREATER_THAN_OR_EQUAL.getId(), "if_controller_operator_greater_than_or_equal"),
                new OperatorSelection(IfController.Operator.LESS_THAN.getId(), "if_controller_operator_less_than"),
                new OperatorSelection(IfController.Operator.LESS_THAN_OR_EQUAL.getId(), "if_controller_operator_less_than_or_equal"),
                new OperatorSelection(IfController.Operator.EXISTS.getId(), "if_controller_operator_exists"),
                new OperatorSelection(IfController.Operator.NOT_EXISTS.getId(), "if_controller_operator_not_exists"),
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
