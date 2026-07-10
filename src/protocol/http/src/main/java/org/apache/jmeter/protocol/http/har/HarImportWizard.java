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
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.apache.jmeter.gui.MainFrame;
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

        Result(List<HarEntry> entries, Set<String> selectedHostnames, HarImportOptions options,
                String harName, String harMd5) {
            this.entries = entries;
            this.selectedHostnames = selectedHostnames;
            this.options = options;
            this.harName = harName;
            this.harMd5 = harMd5;
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

    private final JComboBox<String> delayMode = new JComboBox<>(new String[] {
            JMeterUtils.getResString("har_import_delay_as_recorded"),
            JMeterUtils.getResString("har_import_delay_fixed"),
            JMeterUtils.getResString("har_import_delay_random"),
            JMeterUtils.getResString("har_import_delay_gaussian"),
            JMeterUtils.getResString("har_import_delay_none")});
    private final CardLayout delayCardLayout = new CardLayout();
    private final JPanel delayCards = new JPanel(delayCardLayout);
    private final JSpinner recordedRandom = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
    private final JSpinner fixedDelay = new JSpinner(new SpinnerNumberModel(1000, 0, 3_600_000, 100));
    private final JSpinner delayMin = new JSpinner(new SpinnerNumberModel(500, 0, 3_600_000, 100));
    private final JSpinner delayMax = new JSpinner(new SpinnerNumberModel(2000, 0, 3_600_000, 100));

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
                return new HarAnalysis(file, parsed, HarConverter.sortedHostnames(parsed), md5(content));
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
        addLabeledRow(panel, gbc, "har_import_idle_time", idleTime);
        addLabeledRow(panel, gbc, "har_import_delay", delayMode);

        buildDelayCards();
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        panel.add(delayCards, gbc);

        delayMode.addActionListener(e -> updateDelayCard());
        updateDelayCard();
        return panel;
    }

    private void buildDelayCards() {
        JPanel recordedCard = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.anchor = GridBagConstraints.WEST;
        addLabeledRow(recordedCard, g, "har_import_random_spread", recordedRandom);

        JPanel fixedCard = new JPanel(new GridBagLayout());
        GridBagConstraints g2 = new GridBagConstraints();
        g2.insets = new Insets(3, 3, 3, 3);
        g2.anchor = GridBagConstraints.WEST;
        addLabeledRow(fixedCard, g2, "har_import_delay_fixed_ms", fixedDelay);

        JPanel rangeCard = new JPanel(new GridBagLayout());
        GridBagConstraints g3 = new GridBagConstraints();
        g3.insets = new Insets(3, 3, 3, 3);
        g3.anchor = GridBagConstraints.WEST;
        addLabeledRow(rangeCard, g3, "har_import_delay_min_ms", delayMin);
        addLabeledRow(rangeCard, g3, "har_import_delay_max_ms", delayMax);

        delayCards.add(recordedCard, "recorded");
        delayCards.add(fixedCard, "fixed");
        delayCards.add(rangeCard, "range");
        delayCards.add(new JPanel(), "none");
    }

    private void updateDelayCard() {
        switch (delayMode.getSelectedIndex()) {
            case 1 -> delayCardLayout.show(delayCards, "fixed");
            case 2, 3 -> delayCardLayout.show(delayCards, "range");
            case 4 -> delayCardLayout.show(delayCards, "none");
            default -> delayCardLayout.show(delayCards, "recorded");
        }
    }

    private static void addLabeledRow(JPanel panel, GridBagConstraints gbc, String labelKey, Component field) {
        gbc.gridx = 0;
        gbc.gridy++;
        panel.add(new JLabel(JMeterUtils.getResString(labelKey)), gbc);
        gbc.gridx = 1;
        panel.add(field, gbc);
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
        HarImportOptions options = new HarImportOptions();
        options.setContinueOnError(continueOnError.isSelected());
        options.setIgnoreErrors(ignoreErrors.isSelected());
        options.setAddIndex(addIndex.isSelected());
        options.setDetectDynamicUrls(detectDynamicUrls.isSelected());
        options.setIdleTimeSeconds((Integer) idleTime.getValue());
        options.setDelayMode(switch (delayMode.getSelectedIndex()) {
            case 1 -> HarImportOptions.DelayMode.FIXED;
            case 2 -> HarImportOptions.DelayMode.RANDOM;
            case 3 -> HarImportOptions.DelayMode.GAUSSIAN;
            case 4 -> HarImportOptions.DelayMode.NONE;
            default -> HarImportOptions.DelayMode.AS_RECORDED;
        });
        options.setRecordedRandomPercent((Integer) recordedRandom.getValue());
        options.setFixedDelayMs(((Number) fixedDelay.getValue()).longValue());
        options.setDelayMinMs(((Number) delayMin.getValue()).longValue());
        options.setDelayMaxMs(((Number) delayMax.getValue()).longValue());

        result = new Result(entries, selectedHostnames(), options, harName, harMd5);
        dispose();
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

    private record HarAnalysis(File file, List<HarEntry> entries, List<String> hostnames, String md5) {
    }
}
