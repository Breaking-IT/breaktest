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

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.control.TransactionControllerSchema;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.JBooleanPropertyEditor;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.layout.VerticalLayout;

/**
 * A Transaction controller component.
 */
@GUIMenuSortOrder(2)
@TestElementMetadata(labelResource = "transaction_controller_title")
public class TransactionControllerGui extends AbstractControllerGui {

    private static final long serialVersionUID = 240L;

    /** If selected, then generate parent sample, otherwise as per original controller */
    private final JBooleanPropertyEditor generateParentSample =
            new JBooleanPropertyEditor(
                    TransactionControllerSchema.INSTANCE.getGenearteParentSample(),
                    "transaction_controller_parent",
                    JMeterUtils::getResString);

    /** if selected, add duration of timers to total runtime */
    private final JBooleanPropertyEditor includeTimers =
            new JBooleanPropertyEditor(
                    TransactionControllerSchema.INSTANCE.getIncludeTimers(),
                    "transaction_controller_include_timers",
                    JMeterUtils::getResString);

    private final JComboBox<String> delayMode = new JComboBox<>(new String[] {
            TransactionController.DELAY_DISABLED,
            TransactionController.DELAY_FIXED,
            TransactionController.DELAY_RANDOM,
            TransactionController.DELAY_GAUSSIAN_RANDOM,
    });

    private final JTextField fixedDelay = new JTextField(12);

    private final JTextField delayMin = new JTextField(12);

    private final JTextField delayMax = new JTextField(12);

    private final JLabel fixedDelayLabel =
            new JLabel(JMeterUtils.getResString("transaction_controller_delay_fixed"));

    private final JLabel delayMinLabel =
            new JLabel(JMeterUtils.getResString("transaction_controller_delay_min"));

    private final JLabel delayMaxLabel =
            new JLabel(JMeterUtils.getResString("transaction_controller_delay_max"));

    private final JComboBox<String> pacingMode = new JComboBox<>(new String[] {
            TransactionController.DELAY_DISABLED,
            TransactionController.DELAY_FIXED,
            TransactionController.DELAY_RANDOM,
            TransactionController.DELAY_GAUSSIAN_RANDOM,
    });

    private final JTextField fixedPacing = new JTextField(12);

    private final JTextField pacingMin = new JTextField(12);

    private final JTextField pacingMax = new JTextField(12);

    private final JLabel fixedPacingLabel =
            new JLabel(JMeterUtils.getResString("transaction_controller_pacing_fixed"));

    private final JLabel pacingMinLabel =
            new JLabel(JMeterUtils.getResString("transaction_controller_pacing_min"));

    private final JLabel pacingMaxLabel =
            new JLabel(JMeterUtils.getResString("transaction_controller_pacing_max"));

    /**
     * Create a new TransactionControllerGui instance.
     */
    public TransactionControllerGui() {
        init();
        bindingGroup.add(generateParentSample);
        bindingGroup.add(includeTimers);
    }

    @Override
    public TestElement makeTestElement() {
        return new TransactionController();
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        if (element instanceof TransactionController transactionController) {
            delayMode.setSelectedItem(transactionController.getDelayMode());
            fixedDelay.setText(transactionController.getFixedDelay());
            delayMin.setText(transactionController.getDelayMin());
            delayMax.setText(transactionController.getDelayMax());
            pacingMode.setSelectedItem(transactionController.getPacingMode());
            fixedPacing.setText(transactionController.getFixedPacing());
            pacingMin.setText(transactionController.getPacingMin());
            pacingMax.setText(transactionController.getPacingMax());
            updateDelayFields();
            updatePacingFields();
        }
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.modifyTestElement(element);
        if (element instanceof TransactionController transactionController) {
            transactionController.setDelayMode((String) delayMode.getSelectedItem());
            transactionController.setFixedDelay(fixedDelay.getText());
            transactionController.setDelayMin(delayMin.getText());
            transactionController.setDelayMax(delayMax.getText());
            transactionController.setPacingMode((String) pacingMode.getSelectedItem());
            transactionController.setFixedPacing(fixedPacing.getText());
            transactionController.setPacingMin(pacingMin.getText());
            transactionController.setPacingMax(pacingMax.getText());
        }
    }

    @Override
    public void assignDefaultValues(TestElement element) {
        super.assignDefaultValues(element);
        // See https://github.com/apache/jmeter/issues/3282
        ((TransactionController) element).setIncludeTimers(false);
    }

    @Override
    public void clearGui() {
        super.clearGui();
        delayMode.setSelectedItem(TransactionController.DELAY_DISABLED);
        fixedDelay.setText("0"); // $NON-NLS-1$
        delayMin.setText("0"); // $NON-NLS-1$
        delayMax.setText("0"); // $NON-NLS-1$
        pacingMode.setSelectedItem(TransactionController.DELAY_DISABLED);
        fixedPacing.setText("0"); // $NON-NLS-1$
        pacingMin.setText("0"); // $NON-NLS-1$
        pacingMax.setText("0"); // $NON-NLS-1$
        updateDelayFields();
        updatePacingFields();
    }

    @Override
    public String getLabelResource() {
        return "transaction_controller_title"; // $NON-NLS-1$
    }

    /**
     * Initialize the GUI components and layout for this component.
     */
    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        setLayout(new VerticalLayout(5, VerticalLayout.BOTH, VerticalLayout.TOP));
        setBorder(makeBorder());
        add(makeTitlePanel());
        add(generateParentSample);
        add(includeTimers);
        add(createDelayPanel());
        add(createPacingPanel());
    }

    private JPanel createDelayPanel() {
        JPanel delayPanel = new JPanel(new GridBagLayout());
        delayPanel.setBorder(
                javax.swing.BorderFactory.createTitledBorder(
                        JMeterUtils.getResString("transaction_controller_delay")));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 2, 2, 2);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        constraints.gridx = 0;
        constraints.gridy = 0;
        delayPanel.add(new JLabel(JMeterUtils.getResString("transaction_controller_delay_option")), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        delayPanel.add(delayMode, constraints);

        constraints.gridy++;
        constraints.gridx = 0;
        constraints.weightx = 0;
        delayPanel.add(fixedDelayLabel, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        delayPanel.add(fixedDelay, constraints);

        constraints.gridy++;
        constraints.gridx = 0;
        constraints.weightx = 0;
        delayPanel.add(delayMinLabel, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        delayPanel.add(delayMin, constraints);

        constraints.gridy++;
        constraints.gridx = 0;
        constraints.weightx = 0;
        delayPanel.add(delayMaxLabel, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        delayPanel.add(delayMax, constraints);

        delayMode.addActionListener(e -> updateDelayFields());
        updateDelayFields();
        return delayPanel;
    }

    private void updateDelayFields() {
        String mode = (String) delayMode.getSelectedItem();
        boolean fixed = TransactionController.DELAY_FIXED.equals(mode);
        boolean random = TransactionController.DELAY_RANDOM.equals(mode)
                || TransactionController.DELAY_GAUSSIAN_RANDOM.equals(mode);

        fixedDelayLabel.setVisible(fixed);
        fixedDelay.setVisible(fixed);
        delayMinLabel.setVisible(random);
        delayMin.setVisible(random);
        delayMaxLabel.setVisible(random);
        delayMax.setVisible(random);
        revalidate();
        repaint();
    }

    private JPanel createPacingPanel() {
        JPanel pacingPanel = new JPanel(new GridBagLayout());
        pacingPanel.setBorder(
                javax.swing.BorderFactory.createTitledBorder(
                        JMeterUtils.getResString("transaction_controller_pacing")));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 2, 2, 2);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        constraints.gridx = 0;
        constraints.gridy = 0;
        pacingPanel.add(new JLabel(JMeterUtils.getResString("transaction_controller_pacing_option")), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        pacingPanel.add(pacingMode, constraints);

        constraints.gridy++;
        constraints.gridx = 0;
        constraints.weightx = 0;
        pacingPanel.add(fixedPacingLabel, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        pacingPanel.add(fixedPacing, constraints);

        constraints.gridy++;
        constraints.gridx = 0;
        constraints.weightx = 0;
        pacingPanel.add(pacingMinLabel, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        pacingPanel.add(pacingMin, constraints);

        constraints.gridy++;
        constraints.gridx = 0;
        constraints.weightx = 0;
        pacingPanel.add(pacingMaxLabel, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        pacingPanel.add(pacingMax, constraints);

        pacingMode.addActionListener(e -> updatePacingFields());
        updatePacingFields();
        return pacingPanel;
    }

    private void updatePacingFields() {
        String mode = (String) pacingMode.getSelectedItem();
        boolean fixed = TransactionController.DELAY_FIXED.equals(mode);
        boolean random = TransactionController.DELAY_RANDOM.equals(mode)
                || TransactionController.DELAY_GAUSSIAN_RANDOM.equals(mode);

        fixedPacingLabel.setVisible(fixed);
        fixedPacing.setVisible(fixed);
        pacingMinLabel.setVisible(random);
        pacingMin.setVisible(random);
        pacingMaxLabel.setVisible(random);
        pacingMax.setVisible(random);
        revalidate();
        repaint();
    }
}
