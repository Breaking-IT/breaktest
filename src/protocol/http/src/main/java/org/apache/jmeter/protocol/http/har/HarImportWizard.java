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

package org.apache.jmeter.protocol.http.har;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A modal wizard that imports a HAR file: choose the file (which immediately
 * advances to hostname filtering), then pick conversion options. Mirrors the
 * flow of the BreakTest Python {@code HarConvertModal}. On success
 * {@link #getResult()} returns the user's selections; it is {@code null} when
 * the user cancels.
 */
public class HarImportWizard extends JDialog {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(HarImportWizard.class);

    /** Immutable outcome of a completed wizard. */
    public static final class Result {
        private final List<HarEntry> entries;
        private final Set<String> selectedHostnames;
        private final HarImportOptions options;
        private final String harName;
        private final String harMd5;
        private final byte[] harContent;

        Result(List<HarEntry> entries, Set<String> selectedHostnames, HarImportOptions options,
                String harName, String harMd5, byte[] harContent) {
            this.entries = entries;
            this.selectedHostnames = selectedHostnames;
            this.options = options;
            this.harName = harName;
            this.harMd5 = harMd5;
            this.harContent = harContent;
        }

        public List<HarEntry> getEntries() {
            return entries;
        }

        public Set<String> getSelectedHostnames() {
            return selectedHostnames;
        }

        public HarImportOptions getOptions() {
            return options;
        }

        public String getHarName() {
            return harName;
        }

        public String getHarMd5() {
            return harMd5;
        }

        public byte[] getHarContent() {
            return harContent;
        }
    }

    private static final String HOST_PROPERTY = "harHostname";

    private static final int STEP_FILE = 0;
    private static final int STEP_HOSTS = 1;
    private static final int STEP_OPTIONS = 2;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final MainFrame mainFrame;
    private int step = STEP_FILE;

    private final JButton backButton = new JButton(JMeterUtils.getResString("har_import_back"));
    private final JButton nextButton = new JButton(JMeterUtils.getResString("har_import_next"));
    private final JButton finishButton = new JButton(JMeterUtils.getResString("har_import_finish"));
    private final JButton cancelButton = new JButton(JMeterUtils.getResString("cancel"));
    private final JButton chooseButton = new JButton(JMeterUtils.getResString("har_import_choose_button"));

    // Step 1 state
    private final JLabel fileLabel = new JLabel(JMeterUtils.getResString("har_import_no_file"));
    private final JLabel analysisLabel = new JLabel(" ");
    private List<HarEntry> entries;
    private List<String> hostnames;
    private String harName;
    private String harMd5;
    private byte[] harContent;

    // Step 2 state
    private final JPanel hostsPanel = new JPanel();
    private final List<JCheckBox> hostCheckBoxes = new ArrayList<>();
    private final List<JCheckBox> groupCheckBoxes = new ArrayList<>();
    private final JButton toggleAllButton = new JButton(JMeterUtils.getResString("har_import_unselect_all"));
    private boolean allSelected = true;

    // Step 3 controls
    private final JCheckBox continueOnError = new JCheckBox(JMeterUtils.getResString("har_import_continue_on_error"));
    private final JCheckBox ignoreErrors = new JCheckBox(JMeterUtils.getResString("har_import_ignore_errors"), true);
    private final JCheckBox addIndex = new JCheckBox(JMeterUtils.getResString("har_import_add_index"));
    private final JCheckBox detectDynamicUrls =
            new JCheckBox(JMeterUtils.getResString("har_import_detect_dynamic_urls"), true);
    private final JSpinner idleTime = new JSpinner(new SpinnerNumberModel(4, 0, 3600, 1));
    private final JComboBox<String> recordingStorageMode = new JComboBox<>(new String[] {
            JMeterUtils.getResString("har_import_recording_storage_all"),
            JMeterUtils.getResString("har_import_recording_storage_without_static_bodies"),
            JMeterUtils.getResString("har_import_recording_storage_without_statics"),
            JMeterUtils.getResString("har_import_recording_storage_none")});

    private final JComboBox<String> delayMode = new JComboBox<>(new String[] {
            JMeterUtils.getResString("har_import_delay_as_recorded"),
            JMeterUtils.getResString("har_import_delay_fixed"),
            JMeterUtils.getResString("har_import_delay_random"),
            JMeterUtils.getResString("har_import_delay_gaussian"),
            JMeterUtils.getResString("har_import_delay_none")});
    private final JSpinner recordedRandom = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
    private final JTextField fixedDelay = new JTextField("1000", 12);
    private final JTextField delayMin = new JTextField("500", 12);
    private final JTextField delayMax = new JTextField("2000", 12);
    private final JLabel recordedRandomLabel = new JLabel(JMeterUtils.getResString("har_import_random_spread"));
    private final JLabel fixedDelayLabel = new JLabel(JMeterUtils.getResString("har_import_delay_fixed_ms"));
    private final JLabel delayMinLabel = new JLabel(JMeterUtils.getResString("har_import_delay_min_ms"));
    private final JLabel delayMaxLabel = new JLabel(JMeterUtils.getResString("har_import_delay_max_ms"));
    private final JCheckBox useDelayVariables =
            new JCheckBox(JMeterUtils.getResString("har_import_delay_variables"));

    private Result result;

    public HarImportWizard(Frame owner) {
        super(owner, JMeterUtils.getResString("har_import_title"), true);
        this.mainFrame = owner instanceof MainFrame frame ? frame : null;
        buildUi();
        setSize(640, 560);
        setLocationRelativeTo(owner);
    }

    /** @return the user's selections, or {@code null} if the wizard was cancelled */
    public Result getResult() {
        return result;
    }

    private void buildUi() {
        cards.add(buildFileCard(), "file");
        cards.add(buildHostsCard(), "hosts");
        cards.add(buildOptionsCard(), "options");

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        buttons.add(Box.createHorizontalGlue());
        backButton.addActionListener(e -> goBack());
        nextButton.addActionListener(e -> goNext());
        finishButton.addActionListener(e -> finish());
        cancelButton.addActionListener(e -> {
            result = null;
            dispose();
        });
        buttons.add(backButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(nextButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(finishButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(cancelButton);

        setLayout(new BorderLayout());
        add(cards, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        updateButtons();
    }

    // ---------------------------------------------------------------------
    // Step 1: choose file (auto-advances to hostnames on success)
    // ---------------------------------------------------------------------

    private JPanel buildFileCard() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel prompt = new JLabel(JMeterUtils.getResString("har_import_choose_prompt"));
        JLabel referenceWarning = new JLabel(JMeterUtils.getResString("har_import_reference_warning"));
        prompt.setAlignmentX(Component.LEFT_ALIGNMENT);
        referenceWarning.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(prompt);
        top.add(Box.createVerticalStrut(6));
        top.add(referenceWarning);
        panel.add(top, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        chooseButton.addActionListener(e -> chooseFile());
        chooseButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        fileLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        analysisLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(chooseButton);
        center.add(Box.createVerticalStrut(10));
        center.add(fileLabel);
        center.add(Box.createVerticalStrut(6));
        center.add(analysisLabel);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                JMeterUtils.getResString("har_import_file_filter"), "har"));
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".har")) {
            this.entries = null;
            analysisLabel.setText(JMeterUtils.getResString("har_import_raw_har_required"));
            updateButtons();
            return;
        }
        analyzeHarFile(file);
    }

    private void analyzeHarFile(File file) {
        this.entries = null;
        chooseButton.setEnabled(false);
        fileLabel.setText(file.getName());
        analysisLabel.setText(JMeterUtils.getResString("har_import_analyzing"));
        updateButtons();
        if (mainFrame != null) {
            mainFrame.showLoadingOverlay(JMeterUtils.getResString("har_import_analyze_progress"));
        }
        SwingWorker<HarAnalysis, Void> worker = new SwingWorker<>() {
            @Override
            protected HarAnalysis doInBackground() throws IOException {
                byte[] content = Files.readAllBytes(file.toPath());
                List<HarEntry> parsed = HarParser.parse(content);
                return new HarAnalysis(file, parsed, HarConverter.sortedHostnames(parsed), md5(content), content);
            }

            @Override
            protected void done() {
                try {
                    applyAnalysis(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    LOG.warn("HAR analysis interrupted", ex);
                    showAnalysisError(ex);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    LOG.warn("Failed to parse HAR file {}", file, cause);
                    showAnalysisError(cause);
                } catch (RuntimeException ex) {
                    LOG.warn("Failed to parse HAR file {}", file, ex);
                    showAnalysisError(ex);
                } finally {
                    chooseButton.setEnabled(true);
                    if (mainFrame != null) {
                        mainFrame.hideLoadingOverlay();
                    }
                }
            }
        };
        worker.execute();
    }

    private void applyAnalysis(HarAnalysis analysis) {
        this.entries = analysis.entries();
        this.hostnames = analysis.hostnames();
        this.harName = analysis.file().getName();
        this.harMd5 = analysis.md5();
        this.harContent = analysis.content();
        fileLabel.setText(analysis.file().getName());
        analysisLabel.setText(MessageFormat.format(
                JMeterUtils.getResString("har_import_analysis"), entries.size(), hostnames.size()));
        rebuildHostsPanel();
        // Immediately progress to host selection once the HAR parses.
        step = STEP_HOSTS;
        showStep();
    }

    private void showAnalysisError(Throwable ex) {
        this.entries = null;
        this.hostnames = null;
        this.harName = null;
        this.harMd5 = null;
        this.harContent = null;
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        analysisLabel.setText(MessageFormat.format(
                JMeterUtils.getResString("har_import_parse_error"), message));
        updateButtons();
    }

    // ---------------------------------------------------------------------
    // Step 2: hostname filter (with per-host request counts)
    // ---------------------------------------------------------------------

    private JPanel buildHostsCard() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel top = new JPanel(new BorderLayout());
        top.add(new JLabel(JMeterUtils.getResString("har_import_hosts_prompt")), BorderLayout.WEST);
        toggleAllButton.addActionListener(e -> toggleAll());
        top.add(toggleAllButton, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        hostsPanel.setLayout(new BoxLayout(hostsPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(hostsPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void toggleAll() {
        allSelected = !allSelected;
        for (JCheckBox box : hostCheckBoxes) {
            box.setSelected(allSelected);
        }
        for (JCheckBox box : groupCheckBoxes) {
            box.setSelected(allSelected);
        }
        toggleAllButton.setText(allSelected
                ? JMeterUtils.getResString("har_import_unselect_all")
                : JMeterUtils.getResString("har_import_select_all"));
        updateButtons();
    }

    private void rebuildHostsPanel() {
        hostsPanel.removeAll();
        hostCheckBoxes.clear();
        groupCheckBoxes.clear();
        allSelected = true;
        toggleAllButton.setText(JMeterUtils.getResString("har_import_unselect_all"));

        Map<String, Integer> counts = requestCounts();

        // Group hostnames by base domain, preserving the sorted order.
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String host : hostnames) {
            groups.computeIfAbsent(HarConverter.baseDomain(host), k -> new ArrayList<>()).add(host);
        }

        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            List<String> members = group.getValue();
            if (members.size() == 1) {
                String host = members.get(0);
                JCheckBox box = hostCheckBox(host, counts.getOrDefault(host, 0));
                hostsPanel.add(box);
            } else {
                int groupTotal = members.stream().mapToInt(h -> counts.getOrDefault(h, 0)).sum();
                JCheckBox parent = new JCheckBox(hostLabel("*." + group.getKey(), groupTotal), true);
                parent.setAlignmentX(Component.LEFT_ALIGNMENT);
                groupCheckBoxes.add(parent);
                List<JCheckBox> children = new ArrayList<>();
                for (String host : members) {
                    JCheckBox child = hostCheckBox(host, counts.getOrDefault(host, 0));
                    child.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
                    child.addActionListener(e ->
                            parent.setSelected(children.stream().allMatch(JCheckBox::isSelected)));
                    children.add(child);
                }
                parent.addActionListener(e -> {
                    for (JCheckBox child : children) {
                        child.setSelected(parent.isSelected());
                    }
                    updateButtons();
                });
                hostsPanel.add(parent);
                for (JCheckBox child : children) {
                    hostsPanel.add(child);
                }
            }
        }
        hostsPanel.revalidate();
        hostsPanel.repaint();
    }

    private JCheckBox hostCheckBox(String host, int count) {
        JCheckBox box = new JCheckBox(hostLabel(host, count), true);
        box.putClientProperty(HOST_PROPERTY, host);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.addActionListener(e -> updateButtons());
        hostCheckBoxes.add(box);
        return box;
    }

    private static String hostLabel(String host, int count) {
        return MessageFormat.format(JMeterUtils.getResString("har_import_host_requests"), host, count);
    }

    private Map<String, Integer> requestCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (HarEntry entry : entries) {
            String host = HarConverter.hostnameOf(entry.getUrl());
            if (host != null && !host.isEmpty()) {
                counts.merge(host, 1, Integer::sum);
            }
        }
        return counts;
    }

    private Set<String> selectedHostnames() {
        Set<String> selected = new LinkedHashSet<>();
        for (JCheckBox box : hostCheckBoxes) {
            if (box.isSelected()) {
                selected.add((String) box.getClientProperty(HOST_PROPERTY));
            }
        }
        return selected;
    }

    // ---------------------------------------------------------------------
    // Step 3: options
    // ---------------------------------------------------------------------

    private JPanel buildOptionsCard() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;

        panel.add(ignoreErrors, gbc);
        gbc.gridy++;
        panel.add(continueOnError, gbc);
        gbc.gridy++;
        panel.add(addIndex, gbc);
        gbc.gridy++;
        panel.add(detectDynamicUrls, gbc);

        gbc.gridwidth = 1;
        addLabeledRow(panel, gbc, "har_import_recording_storage", recordingStorageMode);
        addLabeledRow(panel, gbc, "har_import_idle_time", idleTime);
        addLabeledRow(panel, gbc, "har_import_delay", delayMode);

        addLabeledRow(panel, gbc, recordedRandomLabel, recordedRandom);
        addLabeledRow(panel, gbc, fixedDelayLabel, fixedDelay);
        addLabeledRow(panel, gbc, delayMinLabel, delayMin);
        addLabeledRow(panel, gbc, delayMaxLabel, delayMax);
        gbc.gridx = 1;
        gbc.gridy++;
        panel.add(useDelayVariables, gbc);

        delayMode.addActionListener(e -> updateDelayFields());
        updateDelayFields();
        return panel;
    }

    private void updateDelayFields() {
        int selected = delayMode.getSelectedIndex();
        setRowVisible(recordedRandomLabel, recordedRandom, selected == 0);
        setRowVisible(fixedDelayLabel, fixedDelay, selected == 1);
        setRowVisible(delayMinLabel, delayMin, selected == 2 || selected == 3);
        setRowVisible(delayMaxLabel, delayMax, selected == 2 || selected == 3);
        useDelayVariables.setVisible(selected >= 1 && selected <= 3);
    }

    private static void addLabeledRow(JPanel panel, GridBagConstraints gbc, String labelKey, Component field) {
        addLabeledRow(panel, gbc, new JLabel(JMeterUtils.getResString(labelKey)), field);
    }

    private static void addLabeledRow(JPanel panel, GridBagConstraints gbc, JLabel label, Component field) {
        gbc.gridx = 0;
        gbc.gridy++;
        panel.add(label, gbc);
        gbc.gridx = 1;
        panel.add(field, gbc);
    }

    private static void setRowVisible(Component label, Component field, boolean visible) {
        label.setVisible(visible);
        field.setVisible(visible);
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    private void goBack() {
        if (step > STEP_FILE) {
            step--;
            showStep();
        }
    }

    private void goNext() {
        if (step < STEP_OPTIONS) {
            step++;
            showStep();
        }
    }

    private void showStep() {
        switch (step) {
            case STEP_HOSTS -> cardLayout.show(cards, "hosts");
            case STEP_OPTIONS -> cardLayout.show(cards, "options");
            default -> cardLayout.show(cards, "file");
        }
        updateButtons();
    }

    private void updateButtons() {
        backButton.setEnabled(step > STEP_FILE);
        boolean canLeaveFile = entries != null && !entries.isEmpty();
        boolean canLeaveHosts = !selectedHostnames().isEmpty();
        if (step == STEP_FILE) {
            nextButton.setEnabled(canLeaveFile);
        } else if (step == STEP_HOSTS) {
            nextButton.setEnabled(canLeaveHosts);
        } else {
            nextButton.setEnabled(false);
        }
        finishButton.setEnabled(step == STEP_OPTIONS && canLeaveFile && canLeaveHosts);
    }

    private void finish() {
        HarImportOptions.DelayMode selectedDelayMode = selectedDelayMode();
        if (!validateDelayFields(selectedDelayMode)) {
            return;
        }
        HarImportOptions options = new HarImportOptions();
        options.setContinueOnError(continueOnError.isSelected());
        options.setIgnoreErrors(ignoreErrors.isSelected());
        options.setAddIndex(addIndex.isSelected());
        options.setDetectDynamicUrls(detectDynamicUrls.isSelected());
        options.setRecordingStorageMode(switch (recordingStorageMode.getSelectedIndex()) {
            case 1 -> RecordingStorageMode.OMIT_STATIC_BODIES;
            case 2 -> RecordingStorageMode.OMIT_STATICS;
            case 3 -> RecordingStorageMode.NONE;
            default -> RecordingStorageMode.ALL;
        });
        options.setIdleTimeSeconds((Integer) idleTime.getValue());
        options.setDelayMode(selectedDelayMode);
        options.setRecordedRandomPercent((Integer) recordedRandom.getValue());
        options.setFixedDelay(fixedDelay.getText());
        options.setDelayMin(delayMin.getText());
        options.setDelayMax(delayMax.getText());
        options.setUseDelayVariables(useDelayVariables.isSelected());

        result = new Result(entries, selectedHostnames(), options, harName, harMd5, harContent);
        dispose();
    }

    private HarImportOptions.DelayMode selectedDelayMode() {
        return switch (delayMode.getSelectedIndex()) {
            case 1 -> HarImportOptions.DelayMode.FIXED;
            case 2 -> HarImportOptions.DelayMode.RANDOM;
            case 3 -> HarImportOptions.DelayMode.GAUSSIAN;
            case 4 -> HarImportOptions.DelayMode.NONE;
            default -> HarImportOptions.DelayMode.AS_RECORDED;
        };
    }

    private boolean validateDelayFields(HarImportOptions.DelayMode mode) {
        if (mode == HarImportOptions.DelayMode.FIXED) {
            return validateDelayValue(fixedDelay);
        }
        if (mode != HarImportOptions.DelayMode.RANDOM && mode != HarImportOptions.DelayMode.GAUSSIAN) {
            return true;
        }
        if (!validateDelayValue(delayMin) || !validateDelayValue(delayMax)) {
            return false;
        }
        String min = delayMin.getText().trim();
        String max = delayMax.getText().trim();
        if (isInteger(min) && isInteger(max) && Long.parseLong(min) > Long.parseLong(max)) {
            showDelayError(JMeterUtils.getResString("har_import_delay_range_error"));
            return false;
        }
        return true;
    }

    private boolean validateDelayValue(JTextField field) {
        if (HarImportOptions.isValidDelay(field.getText())) {
            return true;
        }
        field.requestFocusInWindow();
        showDelayError(JMeterUtils.getResString("har_import_delay_value_error"));
        return false;
    }

    private void showDelayError(String message) {
        JOptionPane.showMessageDialog(this, message, JMeterUtils.getResString("har_import_title"),
                JOptionPane.ERROR_MESSAGE);
    }

    private static boolean isInteger(String value) {
        return !value.startsWith("${");
    }

    private static String md5(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static final class HarAnalysis {
        private final File file;
        private final List<HarEntry> entries;
        private final List<String> hostnames;
        private final String md5;
        private final byte[] content;

        private HarAnalysis(File file, List<HarEntry> entries, List<String> hostnames, String md5, byte[] content) {
            this.file = file;
            this.entries = entries;
            this.hostnames = hostnames;
            this.md5 = md5;
            this.content = content;
        }

        private File file() {
            return file;
        }

        private List<HarEntry> entries() {
            return entries;
        }

        private List<String> hostnames() {
            return hostnames;
        }

        private String md5() {
            return md5;
        }

        private byte[] content() {
            return content;
        }
    }
}
