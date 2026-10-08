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
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.net.URI;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A modal wizard that imports a HAR file: choose the file (which immediately
 * advances to hostname filtering), review detected file uploads, then pick conversion options. Mirrors the
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
        private HarUploadCapture.Result uploads = HarUploadCapture.Result.empty();

        public HarUploadCapture.Result getUploads() {
            return uploads;
        }

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

    private static final URI RECORDER_CHROME_STORE_URI = URI.create(
            "https://chromewebstore.google.com/detail/breaktest-browser-recorde/nhndlgmkjpgkpmfkpmecedmccdgbbajm");
    private static final URI RECORDER_EDGE_STORE_URI = URI.create(
            "https://microsoftedge.microsoft.com/addons/detail/breaktest-browser-recorde/keilkcnnahbilkeakefndohijlkknkdp");
    private static final URI RECORDER_FIREFOX_STORE_URI = URI.create(
            "https://addons.mozilla.org/nl/firefox/addon/breaktest-browser-recorder/");

    private static final int STEP_FILE = 0;
    private static final int STEP_HOSTS = 1;
    private static final int STEP_FILE_UPLOAD = 2;
    private static final int STEP_OPTIONS = 3;
    private static final int STEP_CORRELATIONS = 4;
    static final boolean DEFAULT_FIND_PREDEFINED_CORRELATIONS = true;

    private static final String[] STORAGE_MODE_KEYS = {
            "har_import_recording_storage_all", // $NON-NLS-1$
            "har_import_recording_storage_without_static_bodies", // $NON-NLS-1$
            "har_import_recording_storage_none"}; // $NON-NLS-1$
    private static final RecordingStorageMode[] STORAGE_MODES = {
            RecordingStorageMode.ALL,
            RecordingStorageMode.OMIT_STATIC_BODIES,
            RecordingStorageMode.NONE};

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
    private HarUploadCapture.Result uploads = HarUploadCapture.Result.empty();
    private List<String> hostnames;
    private String harName;
    private String harMd5;
    private byte[] harContent;

    // Step 2 state
    private final RecordingHostsPanel hostsPanel = new RecordingHostsPanel(this::updateButtons);

    private final JLabel uploadFilesLabel = new JLabel(" ");
    private final JLabel uploadWarningLabel = new JLabel(" ");
    private final JTextArea captureWarnings = new JTextArea(3, 60);
    private final JScrollPane captureWarningsScroll = new JScrollPane(captureWarnings);
    private final JRadioButton useArchiveUploadFiles = new JRadioButton(
            JMeterUtils.getResString("har_import_upload_archive"), true);
    private final JRadioButton useLocalUploadFiles = new JRadioButton(
            JMeterUtils.getResString("har_import_upload_local_file"));
    private final JRadioButton referenceUploadFiles = new JRadioButton(
            JMeterUtils.getResString("har_import_upload_reference_only"));

    private final JRadioButton recordedUploadBody = new JRadioButton(
            JMeterUtils.getResString("har_import_upload_recorded_body"));

    // Step 3 controls
    private final JCheckBox ignoreErrors = new JCheckBox(JMeterUtils.getResString("har_import_ignore_errors"), true);
    private final JCheckBox addIndex = new JCheckBox(JMeterUtils.getResString("har_import_add_index"));
    private final JCheckBox findPredefinedCorrelations =
            new JCheckBox(JMeterUtils.getResString("har_import_find_predefined_correlations"),
                    DEFAULT_FIND_PREDEFINED_CORRELATIONS);
    private final JSpinner idleTime = new JSpinner(new SpinnerNumberModel(4, 0, 3600, 1));
    private final JLabel idleTimeLabel = new JLabel(JMeterUtils.getResString("har_import_idle_time"));
    private final JComboBox<String> recordingStorageMode = new JComboBox<>();
    private SwingWorker<Map<RecordingStorageMode, Long>, Void> storageEstimateWorker;

    private final JComboBox<String> delayMode = new JComboBox<>(new String[] {
            JMeterUtils.getResString("har_import_delay_as_recorded"),
            JMeterUtils.getResString("har_import_delay_fixed"),
            JMeterUtils.getResString("har_import_delay_random"),
            JMeterUtils.getResString("har_import_delay_gaussian"),
            JMeterUtils.getResString("har_import_delay_none")});
    private final JSpinner recordedRandom = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
    private final JTextField fixedDelay = new JTextField("1000", 12);
    private final JTextField delayMin = new JTextField(HarImportOptions.DEFAULT_DELAY_MIN, 12);
    private final JTextField delayMax = new JTextField(HarImportOptions.DEFAULT_DELAY_MAX, 12);
    private final JLabel recordedRandomLabel = new JLabel(JMeterUtils.getResString("har_import_random_spread"));
    private final JLabel fixedDelayLabel = new JLabel(JMeterUtils.getResString("har_import_delay_fixed_ms"));
    private final JLabel delayMinLabel = new JLabel(JMeterUtils.getResString("har_import_delay_min_ms"));
    private final JLabel delayMaxLabel = new JLabel(JMeterUtils.getResString("har_import_delay_max_ms"));
    private final JCheckBox useDelayVariables =
            new JCheckBox(JMeterUtils.getResString("har_import_delay_variables"));

    // Step 4 state
    private final HarCorrelationMatchesPanel correlationsPanel = new HarCorrelationMatchesPanel();
    private final JLabel correlationsSummary = new JLabel(" ");
    private List<HarPredefinedCorrelation> predefinedCorrelations = List.of();
    private SwingWorker<List<HarPredefinedCorrelation>, Void> correlationWorker;
    private boolean correlationAnalysisComplete;

    private Result result;

    public HarImportWizard(Frame owner) {
        super(owner, JMeterUtils.getResString("har_import_title"), true);
        this.mainFrame = owner instanceof MainFrame frame ? frame : null;
        buildUi();
        setMinimumSize(new Dimension(620, 360));
        setSize(760, 500);
        setLocationRelativeTo(owner);
    }

    /** @return the user's selections, or {@code null} if the wizard was cancelled */
    public Result getResult() {
        return result;
    }

    private void buildUi() {
        cards.add(buildFileCard(), "file");
        cards.add(buildHostsCard(), "hosts");
        cards.add(buildFileUploadCard(), "fileUpload");
        cards.add(buildOptionsCard(), "options");
        cards.add(buildCorrelationsCard(), "correlations");

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        buttons.add(Box.createHorizontalGlue());
        backButton.addActionListener(e -> goBack());
        nextButton.addActionListener(e -> goNext());
        finishButton.addActionListener(e -> finish());
        cancelButton.addActionListener(e -> {
            cancelCorrelationAnalysis();
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
        panel.add(buildRecorderPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildRecorderPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString("har_import_recorder_title")));
        JTextArea description = new JTextArea(JMeterUtils.getResString("har_import_recorder_description"));
        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        description.setFont(fileLabel.getFont());
        description.setBorder(BorderFactory.createEmptyBorder());
        description.setRows(1);
        panel.add(description, BorderLayout.CENTER);
        JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        links.add(buildRecorderStoreLink("har_import_recorder_chrome_store", RECORDER_CHROME_STORE_URI));
        links.add(buildRecorderStoreLink("har_import_recorder_edge_store", RECORDER_EDGE_STORE_URI));
        links.add(buildRecorderStoreLink("har_import_recorder_firefox_store", RECORDER_FIREFOX_STORE_URI));
        panel.add(links, BorderLayout.SOUTH);
        return panel;
    }

    private JButton buildRecorderStoreLink(String labelKey, URI storeUri) {
        JButton storeLink = new JButton(JMeterUtils.getResString(labelKey));
        storeLink.setMargin(new Insets(6, 16, 6, 16));
        storeLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        storeLink.setToolTipText(storeUri.toString());
        storeLink.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(storeUri);
            } catch (IOException | UnsupportedOperationException | SecurityException ex) {
                LOG.warn("Could not open HAR recorder store", ex);
                JTextArea fallback = new JTextArea(JMeterUtils.getResString("har_import_recorder_browser_error")
                        + "\n\n" + storeUri);
                fallback.setEditable(false);
                JOptionPane.showMessageDialog(this, fallback,
                        JMeterUtils.getResString("har_import_recorder_title"), JOptionPane.WARNING_MESSAGE);
            }
        });
        return storeLink;
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
                HarParser.Recording recording = HarParser.parseRecording(content);
                return new HarAnalysis(file, recording, HarConverter.sortedHostnames(recording.entries()), md5(content), content);
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
        this.uploads = analysis.recording.uploads();
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
        return hostsPanel;
    }

    private void rebuildHostsPanel() {
        hostsPanel.setEntries(entries);
    }

    private Set<String> selectedHostnames() {
        return hostsPanel.selectedHostnames();
    }

    private JPanel buildFileUploadCard() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JLabel detected = new JLabel(JMeterUtils.getResString("har_import_upload_detected"));
        detected.setAlignmentX(Component.LEFT_ALIGNMENT);
        uploadFilesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        uploadWarningLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        useLocalUploadFiles.setAlignmentX(Component.LEFT_ALIGNMENT);
        useArchiveUploadFiles.setAlignmentX(Component.LEFT_ALIGNMENT);
        referenceUploadFiles.setAlignmentX(Component.LEFT_ALIGNMENT);
        recordedUploadBody.setAlignmentX(Component.LEFT_ALIGNMENT);

        ButtonGroup choices = new ButtonGroup();
        choices.add(useArchiveUploadFiles);
        choices.add(useLocalUploadFiles);
        choices.add(referenceUploadFiles);
        choices.add(recordedUploadBody);

        panel.add(detected);
        panel.add(Box.createVerticalStrut(10));
        panel.add(uploadFilesLabel);
        panel.add(Box.createVerticalStrut(6));
        panel.add(uploadWarningLabel);
        captureWarnings.setEditable(false);
        captureWarnings.setLineWrap(true);
        captureWarnings.setWrapStyleWord(true);
        captureWarningsScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        captureWarningsScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        panel.add(captureWarningsScroll);
        panel.add(Box.createVerticalStrut(16));
        panel.add(useArchiveUploadFiles);
        panel.add(useLocalUploadFiles);
        panel.add(referenceUploadFiles);
        panel.add(recordedUploadBody);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private List<HarEntry.NameValue> selectedFileUploads() {
        Set<String> selected = selectedHostnames();
        List<HarEntry.NameValue> uploads = new ArrayList<>();
        if (entries == null) {
            return uploads;
        }
        for (HarEntry entry : entries) {
            if (!selected.contains(HarConverter.hostnameOf(entry.getUrl())) || entry.getPostData() == null) {
                continue;
            }
            entry.getPostData().getParams().stream()
                    .filter(HarEntry.NameValue::isFileUpload)
                    .forEach(uploads::add);
        }
        return uploads;
    }

    private boolean hasSelectedFileUploads() {
        return shouldReviewFileUploads(uploads, selectedFileUploads());
    }

    static boolean shouldReviewFileUploads(HarUploadCapture.Result capture, List<HarEntry.NameValue> selectedUploads) {
        // Recorders include uploadCapture even when no files were selected or submitted.
        return !selectedUploads.isEmpty() || !capture.resources().isEmpty() || !capture.warnings().isEmpty();
    }

    private void updateFileUploadCard() {
        List<HarEntry.NameValue> uploads = selectedFileUploads();
        boolean canArchive = !this.uploads.resources().isEmpty()
                || uploads.stream().anyMatch(HarEntry.NameValue::hasFileContent);
        captureWarnings.setText(String.join("\n", this.uploads.warnings()));
        captureWarningsScroll.setVisible(!this.uploads.warnings().isEmpty());
        captureWarnings.setCaretPosition(0);
        useLocalUploadFiles.setText(MessageFormat.format(
                JMeterUtils.getResString("har_import_upload_local_file"), HarImportAction.uploadWorkingDirectory()));
        useArchiveUploadFiles.setEnabled(canArchive);
        Set<String> selectedHosts = selectedHostnames();
        boolean canKeepBody = !uploads.isEmpty() && entries != null && entries.stream()
                .filter(entry -> selectedHosts.contains(HarConverter.hostnameOf(entry.getUrl())))
                .filter(entry -> entry.getPostData() != null)
                .filter(entry -> entry.getPostData().getParams().stream().anyMatch(HarEntry.NameValue::isFileUpload))
                .allMatch(HarConverter::hasRecordedUploadBody);
        recordedUploadBody.setEnabled(canKeepBody);
        if (!canKeepBody && recordedUploadBody.isSelected()) {
            referenceUploadFiles.setSelected(true);
        }
        if (!canArchive && useArchiveUploadFiles.isSelected()) {
            referenceUploadFiles.setSelected(true);
        }
        String fileNames = java.util.stream.Stream.concat(uploads.stream(), this.uploads.resources().stream())
                .map(upload -> HarEntry.localFileName(upload.getFileName()))
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String missingFiles = uploads.stream()
                .filter(upload -> !upload.hasFileContent())
                .map(upload -> HarEntry.localFileName(upload.getFileName()))
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        uploadFilesLabel.setText(MessageFormat.format(
                JMeterUtils.getResString("har_import_upload_files"), fileNames));
        uploadWarningLabel.setText(missingFiles.isEmpty()
                ? " "
                : MessageFormat.format(
                        JMeterUtils.getResString("har_import_upload_missing_content"), missingFiles));
    }

    // ---------------------------------------------------------------------
    // Step 3: options
    // ---------------------------------------------------------------------

    private JPanel buildOptionsCard() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;

        form.add(findPredefinedCorrelations, gbc);
        gbc.gridy++;
        form.add(ignoreErrors, gbc);
        gbc.gridy++;
        form.add(addIndex, gbc);

        gbc.gridwidth = 1;
        setStorageChoiceLabels(null, false);
        addLabeledRow(form, gbc, "har_import_recording_storage", recordingStorageMode);
        addLabeledRow(form, gbc, idleTimeLabel, idleTime);
        addLabeledRow(form, gbc, "har_import_delay", delayMode);

        addLabeledRow(form, gbc, recordedRandomLabel, recordedRandom);
        addLabeledRow(form, gbc, fixedDelayLabel, fixedDelay);
        addLabeledRow(form, gbc, delayMinLabel, delayMin);
        addLabeledRow(form, gbc, delayMaxLabel, delayMax);
        gbc.gridx = 1;
        gbc.gridy++;
        form.add(useDelayVariables, gbc);

        delayMode.addActionListener(e -> updateDelayFields());
        findPredefinedCorrelations.addActionListener(e -> updateButtons());
        updateDelayFields();
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    // ---------------------------------------------------------------------
    // Step 4: predefined correlation review
    // ---------------------------------------------------------------------

    private JPanel buildCorrelationsCard() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel heading = new JPanel();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel prompt = new JLabel(JMeterUtils.getResString("har_import_correlations_prompt"));
        prompt.setAlignmentX(Component.LEFT_ALIGNMENT);
        correlationsSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(prompt);
        heading.add(Box.createVerticalStrut(6));
        heading.add(correlationsSummary);
        panel.add(heading, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(correlationsPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void analyzePredefinedCorrelations() {
        cancelCorrelationAnalysis();
        correlationAnalysisComplete = false;
        predefinedCorrelations = List.of();
        correlationsPanel.setCorrelations(List.of());
        correlationsSummary.setText(" ");
        correlationsPanel.removeAll();
        correlationsPanel.add(new JLabel(JMeterUtils.getResString("har_import_correlations_analyzing")));
        JProgressBar progress = new JProgressBar(0, 100);
        progress.setStringPainted(true);
        correlationsPanel.add(progress);
        correlationsPanel.revalidate();
        correlationsPanel.repaint();
        updateButtons();

        List<HarEntry> parsedEntries = List.copyOf(entries);
        Set<String> selectedHosts = Set.copyOf(selectedHostnames());
        List<HarPredefinedCorrelation.Rule> correlationRules = availableCorrelationRules();
        correlationWorker = new SwingWorker<>() {
            @Override
            protected List<HarPredefinedCorrelation> doInBackground() {
                return HarPredefinedCorrelation.find(parsedEntries.stream()
                        .filter(entry -> selectedHosts.contains(HarConverter.hostnameOf(entry.getUrl())))
                        .toList(), correlationRules, this::setProgress);
            }

            @Override
            protected void done() {
                if (correlationWorker != this) {
                    return;
                }
                try {
                    predefinedCorrelations = get();
                    correlationAnalysisComplete = true;
                    rebuildCorrelationsPanel();
                } catch (CancellationException ignored) {
                    // The user navigated back or started a newer analysis.
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    LOG.warn("Unable to analyze predefined HAR correlations", ex.getCause());
                    predefinedCorrelations = List.of();
                    correlationAnalysisComplete = true;
                    correlationsPanel.setCorrelations(List.of());
                    correlationsPanel.removeAll();
                    correlationsPanel.add(new JLabel(JMeterUtils.getResString("har_import_correlations_error")));
                    correlationsPanel.revalidate();
                    correlationsPanel.repaint();
                } finally {
                    updateButtons();
                }
            }
        };
        correlationWorker.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName()) && event.getSource() == correlationWorker) {
                progress.setValue((Integer) event.getNewValue());
            }
        });
        correlationWorker.execute();
    }

    private static List<HarPredefinedCorrelation.Rule> availableCorrelationRules() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return HarCorrelationRuleCatalog.sharedRules();
        }
        return gui.getTreeModel().getNodesOfType(TestPlan.class).stream()
                .findFirst()
                .map(node -> HarCorrelationRuleCatalog.rulesFor(node.getTestElement()))
                .orElseGet(HarCorrelationRuleCatalog::sharedRules);
    }

    private void rebuildCorrelationsPanel() {
        int replacementCount = predefinedCorrelations.stream()
                .mapToInt(correlation -> correlation.getReplacements().size())
                .sum();
        correlationsSummary.setText(MessageFormat.format(
                JMeterUtils.getResString("har_import_correlations_summary"),
                predefinedCorrelations.size(), replacementCount));
        correlationsPanel.setCorrelations(predefinedCorrelations);
    }

    private List<HarPredefinedCorrelation> selectedPredefinedCorrelations() {
        return correlationsPanel.getSelectedCorrelations();
    }

    private void cancelCorrelationAnalysis() {
        if (correlationWorker != null) {
            correlationWorker.cancel(true);
            correlationWorker = null;
        }
    }

    private void updateDelayFields() {
        int selected = delayMode.getSelectedIndex();
        setRowVisible(recordedRandomLabel, recordedRandom, selected == 0);
        setRowVisible(fixedDelayLabel, fixedDelay, selected == 1);
        setRowVisible(delayMinLabel, delayMin, selected == 2 || selected == 3);
        setRowVisible(delayMaxLabel, delayMax, selected == 2 || selected == 3);
        useDelayVariables.setVisible(selected >= 1 && selected <= 3);
    }

    private void updateIdleTimeVisibility() {
        Set<String> selectedHosts = selectedHostnames();
        List<HarEntry> selectedEntries = entries == null ? List.of() : entries.stream()
                .filter(entry -> selectedHosts.contains(HarConverter.hostnameOf(entry.getUrl())))
                .toList();
        setRowVisible(idleTimeLabel, idleTime, !HarConverter.hasExplicitTransactions(selectedEntries));
    }

    private void updateStorageEstimates() {
        if (entries == null || harContent == null) {
            return;
        }
        if (storageEstimateWorker != null) {
            storageEstimateWorker.cancel(true);
        }
        Set<String> selectedHosts = Set.copyOf(selectedHostnames());
        List<HarEntry> parsedEntries = List.copyOf(entries);
        byte[] content = harContent;
        String sourceName = harName;
        String sourceMd5 = harMd5;
        setStorageChoiceLabels(null, true);
        storageEstimateWorker = new SwingWorker<>() {
            @Override
            protected Map<RecordingStorageMode, Long> doInBackground() throws IOException {
                HarImportOptions estimateOptions = new HarImportOptions();
                HashTree convertedTree = new HarConverter(
                        parsedEntries, estimateOptions, sourceName, sourceMd5).convert(selectedHosts);
                return HarArchiveFilter.estimateStoredSizes(content, convertedTree, sourceName);
            }

            @Override
            protected void done() {
                if (storageEstimateWorker != this) {
                    return;
                }
                try {
                    setStorageChoiceLabels(get(), false);
                } catch (CancellationException e) {
                    // A newer estimate is already running.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    LOG.debug("Unable to estimate HAR recording sizes", e.getCause());
                    setStorageChoiceLabels(null, false);
                }
            }
        };
        storageEstimateWorker.execute();
    }

    private void setStorageChoiceLabels(Map<RecordingStorageMode, Long> estimates, boolean calculating) {
        int selectedIndex = Math.max(recordingStorageMode.getSelectedIndex(), 0);
        recordingStorageMode.removeAllItems();
        for (int i = 0; i < STORAGE_MODES.length; i++) {
            String label = JMeterUtils.getResString(STORAGE_MODE_KEYS[i]);
            if (calculating) {
                label = MessageFormat.format(
                        JMeterUtils.getResString("har_import_recording_storage_calculating"), label);
            } else if (estimates != null && estimates.containsKey(STORAGE_MODES[i])) {
                String estimateKey = STORAGE_MODES[i] == RecordingStorageMode.NONE
                        ? "har_import_recording_storage_exact" // $NON-NLS-1$
                        : "har_import_recording_storage_estimate"; // $NON-NLS-1$
                label = MessageFormat.format(
                        JMeterUtils.getResString(estimateKey),
                        label, formatStoredSize(estimates.get(STORAGE_MODES[i])));
            }
            recordingStorageMode.addItem(label);
        }
        recordingStorageMode.setSelectedIndex(Math.min(selectedIndex, STORAGE_MODES.length - 1));
    }

    static String formatStoredSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B"; // $NON-NLS-1$
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0); // $NON-NLS-1$
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0); // $NON-NLS-1$
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0); // $NON-NLS-1$
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
            if (step == STEP_CORRELATIONS) {
                cancelCorrelationAnalysis();
            }
            step = switch (step) {
                case STEP_HOSTS -> STEP_FILE;
                case STEP_FILE_UPLOAD -> STEP_HOSTS;
                case STEP_OPTIONS -> hasSelectedFileUploads() ? STEP_FILE_UPLOAD : STEP_HOSTS;
                case STEP_CORRELATIONS -> STEP_OPTIONS;
                default -> STEP_FILE;
            };
            showStep();
        }
    }

    private void goNext() {
        if (step == STEP_FILE) {
            step = STEP_HOSTS;
        } else if (step == STEP_HOSTS) {
            step = hasSelectedFileUploads() ? STEP_FILE_UPLOAD : STEP_OPTIONS;
        } else if (step == STEP_FILE_UPLOAD) {
            step = STEP_OPTIONS;
        } else if (step == STEP_OPTIONS && findPredefinedCorrelations.isSelected()) {
            step = STEP_CORRELATIONS;
        }
        showStep();
    }

    private void showStep() {
        switch (step) {
            case STEP_HOSTS -> cardLayout.show(cards, "hosts");
            case STEP_FILE_UPLOAD -> {
                updateFileUploadCard();
                cardLayout.show(cards, "fileUpload");
            }
            case STEP_OPTIONS -> {
                updateIdleTimeVisibility();
                updateStorageEstimates();
                cardLayout.show(cards, "options");
            }
            case STEP_CORRELATIONS -> {
                cardLayout.show(cards, "correlations");
                analyzePredefinedCorrelations();
            }
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
        } else if (step == STEP_FILE_UPLOAD) {
            nextButton.setEnabled(true);
        } else if (step == STEP_OPTIONS) {
            nextButton.setEnabled(findPredefinedCorrelations.isSelected());
        } else {
            nextButton.setEnabled(false);
        }
        boolean finishOnOptions = step == STEP_OPTIONS && !findPredefinedCorrelations.isSelected();
        boolean finishOnCorrelations = step == STEP_CORRELATIONS && correlationAnalysisComplete;
        finishButton.setEnabled((finishOnOptions || finishOnCorrelations) && canLeaveFile && canLeaveHosts);
    }

    private void finish() {
        HarImportOptions.DelayMode selectedDelayMode = selectedDelayMode();
        if (!validateDelayFields(selectedDelayMode)) {
            return;
        }
        HarImportOptions options = new HarImportOptions();
        options.setIgnoreErrors(ignoreErrors.isSelected());
        options.setAddIndex(addIndex.isSelected());
        options.setRecordingStorageMode(switch (recordingStorageMode.getSelectedIndex()) {
            case 1 -> RecordingStorageMode.OMIT_STATIC_BODIES;
            case 2 -> RecordingStorageMode.NONE;
            default -> RecordingStorageMode.ALL;
        });
        options.setFileUploadMode(useArchiveUploadFiles.isSelected()
                ? HarImportOptions.FileUploadMode.ARCHIVE
                : useLocalUploadFiles.isSelected()
                ? HarImportOptions.FileUploadMode.LOCAL_FILE
                : recordedUploadBody.isSelected()
                ? HarImportOptions.FileUploadMode.RECORDED_BODY
                : HarImportOptions.FileUploadMode.REFERENCE_ONLY);
        options.setIdleTimeSeconds((Integer) idleTime.getValue());
        options.setDelayMode(selectedDelayMode);
        options.setRecordedRandomPercent((Integer) recordedRandom.getValue());
        options.setFixedDelay(fixedDelay.getText());
        options.setDelayMin(delayMin.getText());
        options.setDelayMax(delayMax.getText());
        options.setUseDelayVariables(useDelayVariables.isSelected());
        if (findPredefinedCorrelations.isSelected()) {
            options.setPredefinedCorrelations(selectedPredefinedCorrelations());
        }

        result = new Result(entries, selectedHostnames(), options, harName, harMd5, harContent);
        result.uploads = uploads;
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
        private final HarParser.Recording recording;
        private final List<String> hostnames;
        private final String md5;
        private final byte[] content;

        private HarAnalysis(File file, HarParser.Recording recording, List<String> hostnames, String md5, byte[] content) {
            this.file = file;
            this.recording = recording;
            this.hostnames = hostnames;
            this.md5 = md5;
            this.content = content;
        }

        private File file() {
            return file;
        }

        private List<HarEntry> entries() {
            return recording.entries();
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
