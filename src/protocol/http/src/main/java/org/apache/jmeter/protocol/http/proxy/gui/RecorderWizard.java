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

package org.apache.jmeter.protocol.http.proxy.gui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;

import org.apache.jmeter.protocol.http.har.HarConverter;
import org.apache.jmeter.protocol.http.har.HarImportOptions;
import org.apache.jmeter.protocol.http.har.RecordingHostsPanel;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.apache.jmeter.protocol.http.proxy.RecordedSampler;
import org.apache.jmeter.protocol.http.proxy.RecorderSettings;

import net.miginfocom.swing.MigLayout;

/** Reviews a stopped recording before any samplers are inserted into the plan. */
public final class RecorderWizard extends JDialog {
    public record Result(Set<String> hosts, Set<RecordedSampler> failed, HarImportOptions options, int grouping, boolean processCorrelations) { }

    private final List<RecordedSampler> samples;
    private final ProxyControl recorder;
    private final CardLayout layout = new CardLayout();
    private final JPanel cards = new JPanel(layout);
    private final JLabel title = new JLabel();
    private final JButton back = new JButton("Back");
    private final JButton next = new JButton("Next");
    private final JButton finish = new JButton("Add to test plan");
    private final RecordingHostsPanel hosts = new RecordingHostsPanel(this::updateButtons);
    private final FailureTable failures = new FailureTable();
    private final JLabel failureCount = new JLabel();
    private final JCheckBox transactions = new JCheckBox("Put each group in a new transaction controller");
    private final JComboBox<String> delay = new JComboBox<>(new String[]{
        "As recorded", "Fixed", "Uniform random", "Gaussian random", "None"
    });
    private final JSpinner spread = new JSpinner(new SpinnerNumberModel(0, 0, 100, 5));
    private final JTextField fixed = new JTextField(12);
    private final JTextField minimum = new JTextField(12);
    private final JTextField maximum = new JTextField(12);
    private final JCheckBox remember = new JCheckBox("Remember recorder settings for other scripts", true);
    private final JCheckBox processCorrelations = new JCheckBox("Process correlation rules", true);
    private final JLabel correlationHint = new JLabel();
    private int step;
    private Result result;

    public RecorderWizard(Frame owner, ProxyControl recorder, List<RecordedSampler> samples) {
        super(owner, "Finish proxy recording", true);
        this.recorder = recorder;
        this.samples = List.copyOf(samples);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        title.setBorder(BorderFactory.createEmptyBorder(12, 16, 0, 16));
        JPanel heading = new JPanel(new BorderLayout());
        heading.add(title, BorderLayout.NORTH);
        heading.add(new RecordingStatusPanel(recorder::getRecordingDiagnostics), BorderLayout.CENTER);
        add(heading, BorderLayout.NORTH);
        hosts.setEntries(samples.stream().map(RecordedSampler::entry).toList());
        cards.add(hosts, "0");
        cards.add(failurePanel(), "1");
        cards.add(optionsPanel(), "2");
        add(cards, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton later = new JButton("Review later");
        later.addActionListener(e -> dispose());
        back.addActionListener(e -> {
            step = step == 2 && !hasFailedCaptures() ? 0 : Math.max(0, step - 1);
            showStep();
        });
        next.addActionListener(e -> {
            step = step == 0 && !hasFailedCaptures() ? 2 : Math.min(2, step + 1);
            showStep();
        });
        finish.addActionListener(e -> finish());
        buttons.add(later);
        buttons.add(back);
        buttons.add(next);
        buttons.add(finish);
        add(buttons, BorderLayout.SOUTH);
        setMinimumSize(new Dimension(780, 520));
        setSize(980, 620);
        setLocationRelativeTo(owner);
        showStep();
    }

    public Result getResult() {
        return result;
    }

    private JPanel failurePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        panel.add(new JLabel("<html>All recording failures are shown. Select a host first to include its captured requests.<br>"
                + "Failures before capture cannot be replayed.</html>"), BorderLayout.NORTH);
        JTable table = new JTable(failures);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(Math.max(24, table.getRowHeight()));
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(400);
        table.getColumnModel().getColumn(4).setPreferredWidth(300);
        JTextArea details = new JTextArea(5, 60);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setText("Select a failure to read its full destination and error details.");
        table.getSelectionModel().addListSelectionListener(event -> {
            int selected = table.getSelectedRow();
            if (selected >= 0) {
                int row = table.convertRowIndexToModel(selected);
                details.setText(failures.getValueAt(row, 2) + " " + failures.getValueAt(row, 3)
                        + "\n" + failures.getValueAt(row, 5) + "\n" + failures.getValueAt(row, 4));
                details.setCaretPosition(0);
            }
        });
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(new JScrollPane(details), BorderLayout.SOUTH);
        panel.add(content, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton all = new JButton("Select all");
        JButton none = new JButton("Select none");
        all.addActionListener(e -> {
            failures.visible.stream().filter(failures::hostSelected).forEach(failures.selected::add);
            failures.changed();
        });
        none.addActionListener(e -> {
            failures.selected.removeAll(failures.visible);
            failures.changed();
        });
        actions.add(all);
        actions.add(none);
        actions.add(failureCount);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel optionsPanel() {
        HarImportOptions options = RecorderSettings.options(recorder);
        transactions.setSelected(recorder.getGroupingMode() == 4);
        delay.setSelectedIndex(switch (options.getDelayMode()) {
            case AS_RECORDED -> 0;
            case FIXED -> 1;
            case RANDOM -> 2;
            case GAUSSIAN -> 3;
            case NONE -> 4;
        });
        spread.setValue(Math.min(100, Math.max(0, options.getRecordedRandomPercent())));
        fixed.setText(options.getFixedDelay());
        minimum.setText(options.getDelayMin());
        maximum.setText(options.getDelayMax());
        JPanel panel = new JPanel(new MigLayout("wrap 2, insets 16, fillx", "[][grow]"));
        panel.add(transactions, "span 2");
        panel.add(new JLabel("Transactions follow the names set during recording."), "span 2");
        panel.add(new JLabel("Transaction think time"));
        panel.add(delay);
        panel.add(new JLabel("Recorded delay random spread (%)"));
        panel.add(spread);
        panel.add(new JLabel("Fixed delay (ms)"));
        panel.add(fixed);
        panel.add(new JLabel("Minimum delay (ms)"));
        panel.add(minimum);
        panel.add(new JLabel("Maximum delay (ms)"));
        panel.add(maximum);
        panel.add(new JLabel("Requests are ordered by start time; overlapping requests use Parallel Controllers."), "span 2");
        processCorrelations.setSelected(org.apache.jmeter.util.JMeterUtils.getPropDefault("proxy.recorder.process_correlations", true));
        panel.add(processCorrelations, "span 2, gaptop 16");
        panel.add(correlationHint, "span 2");
        panel.add(remember, "span 2, gaptop 16");
        delay.addActionListener(e -> updateDelayFields());
        transactions.addActionListener(e -> updateDelayFields());
        updateDelayFields();
        return panel;
    }

    private void updateDelayFields() {
        boolean enabled = transactions.isSelected();
        delay.setEnabled(enabled);
        spread.setEnabled(enabled && delay.getSelectedIndex() == 0);
        fixed.setEnabled(enabled && delay.getSelectedIndex() == 1);
        minimum.setEnabled(enabled && (delay.getSelectedIndex() == 2 || delay.getSelectedIndex() == 3));
        maximum.setEnabled(minimum.isEnabled());
    }

    private void showStep() {
        if (step == 1) {
            failures.visible = samples.stream().filter(RecordedSampler::failed).toList();
            failures.changed();
        }
        layout.show(cards, Integer.toString(step));
        updateButtons();
    }

    private boolean hasFailedCaptures() {
        return samples != null && (samples.stream().anyMatch(RecordedSampler::failed)
                || !recorder.getRecordingDiagnostics().uncapturedFailures().isEmpty());
    }

    private void updateButtons() {
        boolean reviewFailures = hasFailedCaptures();
        int total = reviewFailures ? 3 : 2;
        title.setText(switch (step) {
            case 0 -> "1 of " + total + " — Select hosts (" + (samples == null ? 0 : samples.size()) + " captured requests)";
            case 1 -> "2 of 3 — Review failed captures";
            default -> total + " of " + total + " — Transactions and think times";
        });
        back.setEnabled(step > 0);
        next.setVisible(step < 2);
        next.setEnabled(true);
        finish.setVisible(step == 2);
        long included = samples == null ? 0 : samples.stream().filter(sample ->
                hosts.selectedHostnames().contains(HarConverter.hostnameOf(sample.entry().getUrl()))
                        && (!sample.failed() || failures.selected.contains(sample))).count();
        boolean storedResponses = samples != null && samples.stream().anyMatch(sample -> sample.hasStoredExchange() && !sample.entry().isWebSocket()
                && hosts.selectedHostnames().contains(HarConverter.hostnameOf(sample.entry().getUrl()))
                && (!sample.failed() || failures.selected.contains(sample)));
        processCorrelations.setEnabled(storedResponses);
        correlationHint.setText(storedResponses ? "Review matches after adding these requests; only selected matches are applied."
                : "Requires stored HTTP requests/responses in the selected recording.");
        finish.setEnabled(true);
        finish.setText(included == 0 ? "Finish without adding requests" : "Add " + included + " requests to test plan");
    }

    private void finish() {
        try {
            spread.commitEdit();
        } catch (java.text.ParseException e) {
            JOptionPane.showMessageDialog(this, "Enter a valid number for the random spread.");
            return;
        }
        HarImportOptions options = RecorderSettings.options(recorder);
        options.setDelayMode(switch (delay.getSelectedIndex()) {
            case 1 -> HarImportOptions.DelayMode.FIXED;
            case 2 -> HarImportOptions.DelayMode.RANDOM;
            case 3 -> HarImportOptions.DelayMode.GAUSSIAN;
            case 4 -> HarImportOptions.DelayMode.NONE;
            default -> HarImportOptions.DelayMode.AS_RECORDED;
        });
        options.setRecordedRandomPercent((Integer) spread.getValue());
        options.setFixedDelay(fixed.getText());
        options.setDelayMin(minimum.getText());
        options.setDelayMax(maximum.getText());
        if ((fixed.isEnabled() && !HarImportOptions.isValidDelay(fixed.getText()))
                || (minimum.isEnabled() && (!HarImportOptions.isValidDelay(minimum.getText())
                || !HarImportOptions.isValidDelay(maximum.getText())))) {
            JOptionPane.showMessageDialog(this, "Enter non-negative milliseconds or a variable reference for the delay.");
            return;
        }
        if (minimum.isEnabled() && !minimum.getText().trim().startsWith("${") && !maximum.getText().trim().startsWith("${")
                && Long.parseLong(maximum.getText().trim()) < Long.parseLong(minimum.getText().trim())) {
            JOptionPane.showMessageDialog(this, "Maximum delay must be greater than or equal to minimum delay.");
            return;
        }
        int grouping = transactions.isSelected() ? 4 : recorder.getGroupingMode() == 4 ? 0 : recorder.getGroupingMode();
        recorder.setGroupingMode(grouping);
        if (remember.isSelected()) {
            try {
                RecorderSettings.save(recorder, options, processCorrelations.isSelected());
            } catch (java.io.IOException e) {
                JOptionPane.showMessageDialog(this, "Unable to save recorder preferences: " + e.getMessage());
                return;
            }
        }
        result = new Result(hosts.selectedHostnames(), Set.copyOf(failures.selected), options, grouping,
                processCorrelations.isEnabled() && processCorrelations.isSelected());
        dispose();
    }

    private final class FailureTable extends AbstractTableModel {
        private final Set<RecordedSampler> selected = new HashSet<>();
        private List<RecordedSampler> visible = new ArrayList<>();
        private final String[] columns = {"Include", "Host", "Method", "URL", "Failure", "Replay availability"};

        void changed() {
            fireTableDataChanged();
            failureCount.setText(visible.stream().filter(this::hostSelected).filter(selected::contains).count() + " selected; " + getRowCount() + " failures");
            updateButtons();
        }

        private boolean hostSelected(RecordedSampler sample) {
            return hosts.selectedHostnames().contains(HarConverter.hostnameOf(sample.entry().getUrl()));
        }

        @Override public int getRowCount() { return visible.size() + recorder.getRecordingDiagnostics().uncapturedFailures().size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0 && row < visible.size() && hostSelected(visible.get(row)); }
        @Override public Object getValueAt(int row, int column) {
            if (row >= visible.size()) {
                var failure = recorder.getRecordingDiagnostics().uncapturedFailures().get(row - visible.size());
                return switch (column) {
                    case 0 -> false;
                    case 1 -> HarConverter.hostnameOf(failure.url());
                    case 2 -> failure.method();
                    case 3 -> failure.url();
                    case 4 -> failure.reason();
                    default -> "Unavailable — no HTTP request captured";
                };
            }
            RecordedSampler sample = visible.get(row);
            return switch (column) {
                case 0 -> hostSelected(sample) && selected.contains(sample);
                case 1 -> HarConverter.hostnameOf(sample.entry().getUrl());
                case 2 -> sample.entry().getMethod();
                case 3 -> sample.entry().getUrl();
                case 4 -> sample.diagnostic();
                default -> hostSelected(sample) ? "Available" : "Host excluded — select it in the previous step";
            };
        }
        @Override public void setValueAt(Object value, int row, int column) {
            if (!isCellEditable(row, column)) {
                return;
            }
            if (Boolean.TRUE.equals(value)) {
                selected.add(visible.get(row));
            } else {
                selected.remove(visible.get(row));
            }
            changed();
        }
    }
}
