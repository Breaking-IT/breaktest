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

import javax.swing.Box;
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

    private final JComboBox<TimingModeOption> timingMode = new JComboBox<>(TimingModeOption.values());

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
            timingMode.setSelectedItem(TimingModeOption.fromMode(transactionController.getTimingMode()));
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
            transactionController.setTimingMode(((TimingModeOption) timingMode.getSelectedItem()).mode);
        }
    }

    @Override
    public void assignDefaultValues(TestElement element) {
        super.assignDefaultValues(element);
        // See https://github.com/apache/jmeter/issues/3282
        ((TransactionController) element).setTimingMode(TransactionController.TIMING_MODE_SUM_CHILD_SAMPLES);
    }

    @Override
    public void clearGui() {
        super.clearGui();
        delayMode.setSelectedItem(TransactionController.DELAY_DISABLED);
        fixedDelay.setText("0"); // $NON-NLS-1$
        delayMin.setText("0"); // $NON-NLS-1$
        delayMax.setText("0"); // $NON-NLS-1$
        timingMode.setSelectedItem(TimingModeOption.fromMode(TransactionController.TIMING_MODE_SUM_CHILD_SAMPLES));
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
        add(createTimingModePanel());
        add(createDelayPanel());
        add(createPacingPanel());
    }

    private JPanel createTimingModePanel() {
        JPanel timingPanel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 2, 2, 2);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 0;
        timingPanel.add(new JLabel(JMeterUtils.getResString("transaction_controller_timing_mode")), constraints);
        constraints.gridx = 1;
        constraints.fill = GridBagConstraints.NONE;
        constraints.weightx = 0;
        timingPanel.add(timingMode, constraints);
        addHorizontalSpacer(timingPanel, constraints);
        timingMode.setToolTipText(JMeterUtils.getResString("transaction_controller_timing_mode_tooltip"));
        return timingPanel;
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

        addControlRow(delayPanel, constraints, 0,
                new JLabel(JMeterUtils.getResString("transaction_controller_delay_option")), delayMode);
        addControlRow(delayPanel, constraints, 1, fixedDelayLabel, fixedDelay);
        addControlRow(delayPanel, constraints, 2, delayMinLabel, delayMin);
        addControlRow(delayPanel, constraints, 3, delayMaxLabel, delayMax);
        addHorizontalSpacer(delayPanel, constraints);

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

        addControlRow(pacingPanel, constraints, 0,
                new JLabel(JMeterUtils.getResString("transaction_controller_pacing_option")), pacingMode);
        addControlRow(pacingPanel, constraints, 1, fixedPacingLabel, fixedPacing);
        addControlRow(pacingPanel, constraints, 2, pacingMinLabel, pacingMin);
        addControlRow(pacingPanel, constraints, 3, pacingMaxLabel, pacingMax);
        addHorizontalSpacer(pacingPanel, constraints);

        pacingMode.addActionListener(e -> updatePacingFields());
        updatePacingFields();
        return pacingPanel;
    }

    private static void addControlRow(
            JPanel panel, GridBagConstraints constraints, int row, JLabel label, java.awt.Component control) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0;
        panel.add(label, constraints);
        constraints.gridx = 1;
        panel.add(control, constraints);
    }

    private static void addHorizontalSpacer(JPanel panel, GridBagConstraints constraints) {
        constraints.gridx = 2;
        constraints.gridy = 0;
        constraints.weightx = 1.0d;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(Box.createHorizontalGlue(), constraints);
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

    private static class TimingModeOption {
        private static final TimingModeOption[] VALUES = new TimingModeOption[] {
                new TimingModeOption(
                        TransactionController.TIMING_MODE_SUM_CHILD_SAMPLES,
                        "transaction_controller_timing_mode_sum_child_samples"),
                new TimingModeOption(
                        TransactionController.TIMING_MODE_TOTAL_INCLUDE_TIMERS,
                        "transaction_controller_timing_mode_total_include_timers"),
                new TimingModeOption(
                        TransactionController.TIMING_MODE_TOTAL_EXCLUDE_TIMERS,
                        "transaction_controller_timing_mode_total_exclude_timers"),
        };

        private final String mode;

        private final String label;

        private TimingModeOption(String mode, String labelResource) {
            this.mode = mode;
            this.label = JMeterUtils.getResString(labelResource);
        }

        private static TimingModeOption[] values() {
            return VALUES.clone();
        }

        private static TimingModeOption fromMode(String mode) {
            for (TimingModeOption option : VALUES) {
                if (option.mode.equals(mode)) {
                    return option;
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
