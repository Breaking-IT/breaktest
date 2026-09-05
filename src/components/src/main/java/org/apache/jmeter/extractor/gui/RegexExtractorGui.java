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

package org.apache.jmeter.extractor.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.DefaultCellEditor;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableCellRenderer;

import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.RegexExtractor.TestMatch;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.processor.gui.AbstractPostProcessorGui;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.AbstractScopedTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jorphan.gui.JLabeledTextField;

/**
 * Regular Expression Extractor Post-Processor GUI
 */
@GUIMenuSortOrder(4)
@TestElementMetadata(labelResource = "regex_extractor_title")
public class RegexExtractorGui extends AbstractPostProcessorGui {
    private static final long serialVersionUID = 240L;
    private static final long DEFAULT_PREVIEW_TIMEOUT_MILLIS = 500L;
    private static final long PREVIEW_TIMEOUT_MILLIS = Math.max(100L,
            JMeterUtils.getPropDefault("regex_extractor_tester_timeout_ms", //$NON-NLS-1$
                    DEFAULT_PREVIEW_TIMEOUT_MILLIS));
    private static final ThreadPoolExecutor PREVIEW_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            daemonThreadFactory("regex-extractor-preview")); //$NON-NLS-1$
    private static final ScheduledExecutorService PREVIEW_TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("regex-extractor-watchdog")); //$NON-NLS-1$

    private JLabeledTextField regexField;
    private JLabeledTextField templateField;
    private JLabeledTextField defaultField;
    private JLabeledTextField matchNumberField;
    private JLabeledTextField refNameField;
    private JRadioButton useBody;
    private JRadioButton useUnescapedBody;
    private JRadioButton useBodyAsDocument;
    private JRadioButton useHeaders;
    private JRadioButton useRequestHeaders;
    private JRadioButton useURL;
    private JRadioButton useCode;
    private JRadioButton useMessage;
    private ButtonGroup group;
    private JCheckBox emptyDefaultValue;
    private JCheckBox failOnNoMatch;
    private JLabel testResultStatus;
    private JTable testResultTable;
    private DefaultTableModel testResultModel;
    private SampleResult recordedSampleResult;
    private String recordedSampleMessage;
    private TestElement configuredExtractor;
    private List<ReplacementCandidate> displayedCandidates = List.of();
    private final AtomicLong previewGeneration = new AtomicLong();
    private volatile PreviewRun previewRun;
    private boolean configuring;

    public RegexExtractorGui() {
        super();
        init();
    }

    @Override
    public String getLabelResource() {
        return "regex_extractor_title"; //$NON-NLS-1$
    }

    @Override
    public JPopupMenu createPopupMenu() {
        return MenuFactory.getPredefinedCorrelationExtractorMenu();
    }

    @Override
    public void configure(TestElement el) {
        configuring = true;
        cancelPreview();
        try {
            configureFields(el);
        } finally {
            configuring = false;
        }
        updateTestResult();
    }

    private void configureFields(TestElement el) {
        super.configure(el);
        if (el instanceof RegexExtractor re){
            configuredExtractor = el;
            showScopeSettings(re, true);
            useHeaders.setSelected(re.useHeaders());
            useRequestHeaders.setSelected(re.useRequestHeaders());
            useBody.setSelected(re.useBody());
            useUnescapedBody.setSelected(re.useUnescapedBody());
            useBodyAsDocument.setSelected(re.useBodyAsDocument());
            useURL.setSelected(re.useUrl());
            useCode.setSelected(re.useCode());
            useMessage.setSelected(re.useMessage());
            regexField.setText(re.getRegex());
            templateField.setText(re.getTemplate());
            defaultField.setText(re.getDefaultValue());
            emptyDefaultValue.setSelected(re.isEmptyDefaultValue());
            failOnNoMatch.setSelected(re.isFailOnNoMatch());
            matchNumberField.setText(re.getMatchNumberAsString());
            refNameField.setText(re.getRefName());
            recordedSampleResult = loadRecordedSampleResult(el);
        }
    }

    /**
     * @see org.apache.jmeter.gui.JMeterGUIComponent#createTestElement()
     */
    @Override
    public TestElement createTestElement() {
        AbstractScopedTestElement extractor = new RegexExtractor();
        modifyTestElement(extractor);
        return extractor;
    }

    /**
     * Modifies a given TestElement to mirror the data in the gui components.
     *
     * @see org.apache.jmeter.gui.JMeterGUIComponent#modifyTestElement(TestElement)
     */
    @Override
    public void modifyTestElement(TestElement extractor) {
        super.configureTestElement(extractor);
        if (extractor instanceof RegexExtractor regex) {
            saveScopeSettings(regex);
            regex.setUseField(group.getSelection().getActionCommand());
            regex.setRefName(refNameField.getText());
            regex.setRegex(regexField.getText());
            regex.setTemplate(templateField.getText());
            regex.setDefaultValue(defaultField.getText());
            regex.setDefaultEmptyValue(emptyDefaultValue.isSelected());
            regex.setFailOnNoMatch(failOnNoMatch.isSelected());
            regex.setMatchNumber(matchNumberField.getText());
        }
    }

    /**
     * Implements JMeterGUIComponent.clearGui
     */
    @Override
    public void clearGui() {
        super.clearGui();
        cancelPreview();
        previewGeneration.incrementAndGet();

        useBody.setSelected(true);

        regexField.setText(""); //$NON-NLS-1$
        templateField.setText(""); //$NON-NLS-1$
        defaultField.setText(""); //$NON-NLS-1$
        emptyDefaultValue.setSelected(false);
        failOnNoMatch.setSelected(true);
        refNameField.setText(""); //$NON-NLS-1$
        matchNumberField.setText(""); //$NON-NLS-1$
        clearTestResult();
        recordedSampleResult = null;
        recordedSampleMessage = null;
        configuredExtractor = null;
    }

    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        setLayout(new BorderLayout());
        setBorder(makeBorder());

        Box box = Box.createVerticalBox();
        box.add(makeTitlePanel());
        box.add(createScopePanel(true));
        box.add(makeSourcePanel());
        add(box, BorderLayout.NORTH);
        JPanel editorPane = new JPanel(new BorderLayout(0, 16));
        editorPane.add(makeParameterPanel(), BorderLayout.NORTH);
        editorPane.add(makeTestResultPanel(), BorderLayout.CENTER);
        add(editorPane, BorderLayout.CENTER);
    }

    private JPanel makeSourcePanel() {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString("regex_source"))); //$NON-NLS-1$

        useBody = new JRadioButton(JMeterUtils.getResString("regex_src_body")); //$NON-NLS-1$
        useUnescapedBody = new JRadioButton(JMeterUtils.getResString("regex_src_body_unescaped")); //$NON-NLS-1$
        useBodyAsDocument = new JRadioButton(JMeterUtils.getResString("regex_src_body_as_document")); //$NON-NLS-1$
        useHeaders = new JRadioButton(JMeterUtils.getResString("regex_src_hdrs")); //$NON-NLS-1$
        useRequestHeaders = new JRadioButton(JMeterUtils.getResString("regex_src_hdrs_req")); //$NON-NLS-1$
        useURL = new JRadioButton(JMeterUtils.getResString("regex_src_url")); //$NON-NLS-1$
        useCode = new JRadioButton(JMeterUtils.getResString("assertion_code_resp")); //$NON-NLS-1$
        useMessage = new JRadioButton(JMeterUtils.getResString("assertion_message_resp")); //$NON-NLS-1$

        group = new ButtonGroup();
        group.add(useBody);
        group.add(useUnescapedBody);
        group.add(useBodyAsDocument);
        group.add(useHeaders);
        group.add(useRequestHeaders);
        group.add(useURL);
        group.add(useCode);
        group.add(useMessage);

        panel.add(useBody);
        panel.add(useUnescapedBody);
        panel.add(useBodyAsDocument);
        panel.add(useHeaders);
        panel.add(useRequestHeaders);
        panel.add(useURL);
        panel.add(useCode);
        panel.add(useMessage);

        useBody.setSelected(true);

        // So we know which button is selected
        useBody.setActionCommand(RegexExtractor.USE_BODY);
        useUnescapedBody.setActionCommand(RegexExtractor.USE_BODY_UNESCAPED);
        useBodyAsDocument.setActionCommand(RegexExtractor.USE_BODY_AS_DOCUMENT);
        useHeaders.setActionCommand(RegexExtractor.USE_HDRS);
        useRequestHeaders.setActionCommand(RegexExtractor.USE_REQUEST_HDRS);
        useURL.setActionCommand(RegexExtractor.USE_URL);
        useCode.setActionCommand(RegexExtractor.USE_CODE);
        useMessage.setActionCommand(RegexExtractor.USE_MESSAGE);

        useBody.addActionListener(e -> updateTestResult());
        useUnescapedBody.addActionListener(e -> updateTestResult());
        useBodyAsDocument.addActionListener(e -> updateTestResult());
        useHeaders.addActionListener(e -> updateTestResult());
        useRequestHeaders.addActionListener(e -> updateTestResult());
        useURL.addActionListener(e -> updateTestResult());
        useCode.addActionListener(e -> updateTestResult());
        useMessage.addActionListener(e -> updateTestResult());

        return panel;
    }

    private JPanel makeParameterPanel() {
        regexField = new JLabeledTextField(JMeterUtils.getResString("regex_field")); //$NON-NLS-1$
        templateField = new JLabeledTextField(JMeterUtils.getResString("template_field")); //$NON-NLS-1$
        refNameField = new JLabeledTextField(JMeterUtils.getResString("ref_name_field")); //$NON-NLS-1$
        matchNumberField = new JLabeledTextField(JMeterUtils.getResString("match_num_field")); //$NON-NLS-1$

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        initConstraints(gbc);
        addField(panel, refNameField, gbc);
        resetContraints(gbc);
        addField(panel, regexField, gbc);
        resetContraints(gbc);
        addField(panel, templateField, gbc);
        resetContraints(gbc);
        addField(panel, matchNumberField, gbc);
        resetContraints(gbc);
        gbc.weighty = 0;

        defaultField = new JLabeledTextField(JMeterUtils.getResString("default_value_field")); //$NON-NLS-1$
        List<JComponent> item = defaultField.getComponentList();
        panel.add(item.get(0), gbc.clone());
        JPanel p = new JPanel(new BorderLayout());
        p.add(item.get(1), BorderLayout.WEST);
        emptyDefaultValue = new JCheckBox(JMeterUtils.getResString("assertion_regex_empty_default_value")); //$NON-NLS-1$
        emptyDefaultValue.addItemListener(evt -> {
            if(emptyDefaultValue.isSelected()) {
                defaultField.setText(""); //$NON-NLS-1$
            }
            defaultField.setEnabled(!emptyDefaultValue.isSelected());
            updateTestResult();
        });

        p.add(emptyDefaultValue, BorderLayout.CENTER);
        gbc.gridx++;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(p, gbc.clone());
        resetContraints(gbc);
        panel.add(new JLabel(JMeterUtils.getResString("extractor_assertion_error_on_no_match")), gbc.clone()); //$NON-NLS-1$
        failOnNoMatch = new JCheckBox();
        failOnNoMatch.setSelected(true);
        gbc.gridx++;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(failOnNoMatch, gbc.clone());
        resetContraints(gbc);
        gbc.gridwidth = 2;
        gbc.weighty = 1;
        panel.add(Box.createVerticalGlue(), gbc.clone());

        return panel;
    }

    private JPanel makeTestResultPanel() {
        testResultStatus = new JLabel();
        testResultStatus.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        testResultModel = new DefaultTableModel(
                new Object[] {
                        JMeterUtils.getResString("regex_test_match"), //$NON-NLS-1$
                        JMeterUtils.getResString("regex_test_variable"), //$NON-NLS-1$
                        JMeterUtils.getResString("regex_test_extracted_value"), //$NON-NLS-1$
                        JMeterUtils.getResString("regex_test_occurrence_count"), //$NON-NLS-1$
                        "" //$NON-NLS-1$
                }, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 4 && row >= 0 && row < displayedCandidates.size()
                        && displayedCandidates.get(row).replaceable()
                        && ((Number) getValueAt(row, 3)).intValue() > 0;
            }
        };
        testResultTable = new JTable(testResultModel);
        testResultTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        testResultTable.setRowHeight(28);
        testResultTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        testResultTable.getColumnModel().getColumn(1).setPreferredWidth(210);
        testResultTable.getColumnModel().getColumn(2).setPreferredWidth(500);
        testResultTable.getColumnModel().getColumn(3).setPreferredWidth(145);
        TableColumn replaceColumn = testResultTable.getColumnModel().getColumn(4);
        replaceColumn.setPreferredWidth(100);
        replaceColumn.setMinWidth(100);
        replaceColumn.setMaxWidth(100);
        for (int column = 0; column < 4; column++) {
            testResultTable.getColumnModel().getColumn(column).setCellRenderer(new SingleLineRenderer());
        }
        replaceColumn.setCellRenderer(new ReplaceButtonRenderer());
        replaceColumn.setCellEditor(new ReplaceButtonEditor());
        testResultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = testResultTable.rowAtPoint(event.getPoint());
                int column = testResultTable.columnAtPoint(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2
                        && row >= 0 && column >= 0
                        && testResultTable.convertColumnIndexToModel(column) == 2) {
                    showFullValue(testResultTable.convertRowIndexToModel(row));
                }
            }
        });

        DocumentListener previewListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateTestResult();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateTestResult();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateTestResult();
            }
        };
        getTextField(regexField).getDocument().addDocumentListener(previewListener);
        getTextField(templateField).getDocument().addDocumentListener(previewListener);
        getTextField(matchNumberField).getDocument().addDocumentListener(previewListener);
        getTextField(defaultField).getDocument().addDocumentListener(previewListener);
        getTextField(refNameField).getDocument().addDocumentListener(previewListener);

        JScrollPane testResultPane = new JScrollPane(testResultTable);
        testResultPane.setPreferredSize(new Dimension(0, 140));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString("regexp_tester_title"))); //$NON-NLS-1$
        panel.add(testResultStatus, BorderLayout.NORTH);
        panel.add(testResultPane, BorderLayout.CENTER);
        return panel;
    }

    private void showFullValue(int row) {
        JTextArea value = new JTextArea(String.valueOf(testResultModel.getValueAt(row, 2)));
        value.setEditable(false);
        value.setLineWrap(true);
        value.setWrapStyleWord(true);
        value.setCaretPosition(0);
        JScrollPane scrollPane = new JScrollPane(value);
        scrollPane.setPreferredSize(new Dimension(720, 320));
        JOptionPane.showMessageDialog(this, scrollPane,
                JMeterUtils.getResString("regex_test_extracted_value") + " — " //$NON-NLS-1$ //$NON-NLS-2$
                        + testResultModel.getValueAt(row, 1), JOptionPane.PLAIN_MESSAGE);
    }

    private void updateTestResult() {
        if (configuring || testResultTable == null || regexField == null
                || templateField == null || matchNumberField == null) {
            return;
        }
        cancelPreview();
        PREVIEW_EXECUTOR.purge();
        long generation = previewGeneration.incrementAndGet();
        String regex = regexField.getText();
        if (regex.isEmpty()) {
            clearTestResult();
            return;
        }
        if (recordedSampleResult == null) {
            showStatus(recordedSampleMessage == null
                    ? JMeterUtils.getResString("regex_test_no_recorded_response") //$NON-NLS-1$
                    : recordedSampleMessage);
            return;
        }

        try {
            RegexExtractor extractor = (RegexExtractor) createTestElement();
            PreviewRun run = new PreviewRun(generation, extractor, recordedSampleResult);
            previewRun = run;
            clearTestTable();
            showStatus(JMeterUtils.getResString("regex_test_running")); //$NON-NLS-1$
            if (!run.start()) {
                previewRun = null;
                showStatus(JMeterUtils.getResString("regex_test_busy")); //$NON-NLS-1$
            }
        } catch (RuntimeException e) {
            showStatus(e.toString());
        }
    }

    private void clearTestResult() {
        clearTestTable();
        showStatus(""); //$NON-NLS-1$
    }

    private void clearTestTable() {
        if (testResultTable != null && testResultTable.isEditing()) {
            testResultTable.getCellEditor().cancelCellEditing();
        }
        if (testResultModel != null) {
            testResultModel.setRowCount(0);
        }
        displayedCandidates = List.of();
    }

    private void showStatus(String status) {
        if (testResultStatus != null) {
            testResultStatus.setText(status);
        }
    }

    private void cancelPreview() {
        PreviewRun run = previewRun;
        if (run != null) {
            run.cancel();
            previewRun = null;
        }
    }

    private SampleResult loadRecordedSampleResult(TestElement extractor) {
        recordedSampleMessage = null;
        TestElement sampler = findParentSampler(extractor);
        if (sampler == null) {
            recordedSampleMessage = JMeterUtils.getResString("regex_test_no_recorded_response"); //$NON-NLS-1$
            return null;
        }
        RecordedHarExchangeResolver.Resolution resolution = RecordedHarExchangeResolver.resolveFor(sampler);
        if (resolution.exchange().isEmpty()) {
            recordedSampleMessage = resolution.status() == RecordedHarExchangeResolver.Status.NOT_LINKED
                    ? JMeterUtils.getResString("regex_test_no_recorded_response") //$NON-NLS-1$
                    : resolution.responseText();
            return null;
        }
        RecordedHarExchangeResolver.RecordedExchange exchange = resolution.exchange().orElseThrow();
        SampleResult result = new SampleResult();
        result.setResponseData(exchange.responseBody(), null);
        result.setRequestHeaders(exchange.requestHeaders());
        result.setResponseHeaders(exchange.responseHeaders());
        result.setResponseCode(exchange.responseCode());
        result.setResponseMessage(exchange.responseMessage());
        try {
            result.setURL(new URI(exchange.requestUrl()).toURL());
        } catch (MalformedURLException | URISyntaxException e) {
            // URL is only used when URL is selected as the extraction source.
        }
        return result;
    }

    private static TestElement findParentSampler(TestElement extractor) {
        JMeterTreeNode node = findParentSamplerNode(extractor);
        return node == null ? null : node.getTestElement();
    }

    private static JMeterTreeNode findParentSamplerNode(TestElement extractor) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null || extractor == null) {
            return null;
        }
        JMeterTreeNode node = guiPackage.getNodeOf(extractor);
        while (node != null) {
            if (node.getTestElement() instanceof Sampler) {
                return node;
            }
            node = node.getParent() instanceof JMeterTreeNode parent ? parent : null;
        }
        return null;
    }

    private static int countOccurrences(String input, String searchValue) {
        if (input == null || searchValue == null || searchValue.isEmpty()) {
            return 0;
        }
        int count = 0;
        int offset = 0;
        while (offset < input.length()) {
            int variableStart = input.indexOf("${", offset); //$NON-NLS-1$
            int searchEnd = variableStart < 0 ? input.length() : variableStart;
            int match = input.indexOf(searchValue, offset);
            if (match >= 0 && match + searchValue.length() <= searchEnd) {
                count++;
                offset = match + searchValue.length();
            } else if (variableStart >= 0) {
                int variableEnd = input.indexOf('}', variableStart + 2);
                offset = variableEnd >= 0 ? variableEnd + 1 : input.length();
            } else {
                break;
            }
        }
        return count;
    }

    private static int countOccurrences(List<ReplacementTarget> targets) {
        return targets.stream().mapToInt(ReplacementTarget::occurrences).sum();
    }

    private void showMatches(List<TestMatch> matches, RegexExtractor extractor, long elapsedNanos) {
        displayedCandidates = buildCandidates(matches, extractor);
        testResultModel.setRowCount(0);
        JMeterTreeNode sourceNode = findParentSamplerNode(configuredExtractor);
        for (ReplacementCandidate candidate : displayedCandidates) {
            int occurrences = candidate.replaceable() && sourceNode != null
                    ? countOccurrences(findReplacementTargets(sourceNode, candidate.value())) : 0;
            testResultModel.addRow(new Object[] {
                    candidate.displayMatch(), candidate.variableName(), candidate.value(), occurrences,
                    candidate.replaceable()
                            ? JMeterUtils.getResString("regex_test_replace") : "" //$NON-NLS-1$
            });
        }
        StringBuilder status = new StringBuilder();
        status.append(JMeterUtils.getResString("regex_test_match_count")) //$NON-NLS-1$
                .append(' ').append(matches.size());
        if (extractor.getMatchNumber() < 0) {
            status.append("  ").append(extractor.getRefName()).append("_matchNr = ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(matches.size());
            if (!matches.isEmpty()) {
                ReplacementCandidate random = displayedCandidates.stream()
                        .filter(candidate -> candidate.variableName().equals(extractor.getRefName() + "_rand")) //$NON-NLS-1$
                        .findFirst().orElse(null);
                status.append("  ").append(extractor.getRefName()).append("_rand = ") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(random == null ? "" : random.value()); //$NON-NLS-1$
            }
        }
        status.append("  ").append(JMeterUtils.getResString("regex_test_parse_time")) //$NON-NLS-1$
                .append(' ').append(formatElapsedMillis(elapsedNanos));
        showStatus(status.toString());
    }

    private static List<ReplacementCandidate> buildCandidates(List<TestMatch> matches, RegexExtractor extractor) {
        if (matches.isEmpty()) {
            return extractor.getMatchNumber() < 0
                    ? List.of(new ReplacementCandidate(0, extractor.getRefName() + "_matchNr", //$NON-NLS-1$
                            "0", "", false)) //$NON-NLS-1$ //$NON-NLS-2$
                    : List.of();
        }
        int matchNumber = extractor.getMatchNumber();
        List<ReplacementCandidate> candidates = new ArrayList<>();
        if (matchNumber > 0) {
            if (matchNumber <= matches.size()) {
                addMatchCandidates(candidates, matchNumber, matches.get(matchNumber - 1), extractor.getRefName());
            }
            return candidates;
        }
        if (matchNumber == 0) {
            int randomIndex = JMeterUtils.getRandomInt(matches.size());
            addMatchCandidates(candidates, randomIndex + 1, matches.get(randomIndex), extractor.getRefName());
            return candidates;
        }
        for (int i = 0; i < matches.size(); i++) {
            addMatchCandidates(candidates, i + 1, matches.get(i),
                    extractor.getRefName() + "_" + (i + 1)); //$NON-NLS-1$
        }
        candidates.add(new ReplacementCandidate(0, extractor.getRefName() + "_matchNr", //$NON-NLS-1$
                Integer.toString(matches.size()), "", false)); //$NON-NLS-1$
        int randomIndex = JMeterUtils.getRandomInt(matches.size());
        TestMatch randomMatch = matches.get(randomIndex);
        candidates.add(new ReplacementCandidate(randomIndex + 1, extractor.getRefName() + "_rand",
                randomMatch.extractedValue(), "rand", isReplaceable(randomMatch.extractedValue()))); //$NON-NLS-1$
        return candidates;
    }

    private static void addMatchCandidates(List<ReplacementCandidate> candidates, int matchIndex,
            TestMatch match, String variableName) {
        candidates.add(new ReplacementCandidate(matchIndex, variableName, match.extractedValue(),
                Integer.toString(matchIndex), isReplaceable(match.extractedValue())));
        candidates.add(new ReplacementCandidate(matchIndex, variableName + "_g", //$NON-NLS-1$
                Integer.toString(Math.max(0, match.groups().size() - 1)), Integer.toString(matchIndex), false));
        for (int i = 0; i < match.groups().size(); i++) {
            String value = match.groups().get(i);
            candidates.add(new ReplacementCandidate(matchIndex, variableName + "_g" + i, //$NON-NLS-1$
                    value == null ? "" : value, Integer.toString(matchIndex), isReplaceable(value))); //$NON-NLS-1$
        }
    }

    private static boolean isReplaceable(String value) {
        return value != null && !value.isEmpty();
    }

    private void replaceCandidate(ReplacementCandidate candidate) {
        GuiPackage gui = GuiPackage.getInstance();
        JMeterTreeNode sourceNode = findParentSamplerNode(configuredExtractor);
        if (gui == null || sourceNode == null) {
            JMeterUtils.reportInfoToUser(
                    JMeterUtils.getResString("regex_test_no_replacement_targets"), //$NON-NLS-1$
                    JMeterUtils.getResString("regex_test_replacement")); //$NON-NLS-1$
            return;
        }
        List<ReplacementTarget> targets = findReplacementTargets(sourceNode, candidate.value());
        if (targets.isEmpty()) {
            JMeterUtils.reportInfoToUser(
                    JMeterUtils.getResString("regex_test_no_replacement_targets"), //$NON-NLS-1$
                    JMeterUtils.getResString("regex_test_replacement")); //$NON-NLS-1$
            return;
        }
        JPanel targetsPanel = new JPanel();
        targetsPanel.setLayout(new javax.swing.BoxLayout(targetsPanel, javax.swing.BoxLayout.Y_AXIS));
        targetsPanel.add(new JLabel(MessageFormat.format(
                JMeterUtils.getResString("regex_test_replace_confirmation"), //$NON-NLS-1$
                candidate.variableName(), candidate.value())));
        targetsPanel.add(Box.createVerticalStrut(8));
        for (ReplacementTarget target : targets) {
            targetsPanel.add(new JLabel(MessageFormat.format(
                    JMeterUtils.getResString("regex_test_replacement_target"), //$NON-NLS-1$
                    formatNodePath(target.node()), target.occurrences())));
        }
        int answer = JOptionPane.showConfirmDialog(
                gui.getMainFrame(), targetsPanel,
                JMeterUtils.getResString("regex_test_replacement"), //$NON-NLS-1$
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        applyReplacement(gui, targets, candidate.value(), candidate.variableName());
    }

    private static List<ReplacementTarget> findReplacementTargets(JMeterTreeNode sourceNode, String literal) {
        JMeterTreeNode threadGroup = findThreadGroupNode(sourceNode);
        if (threadGroup == null || literal.isEmpty()) {
            return List.of();
        }
        List<ReplacementTarget> targets = new ArrayList<>();
        boolean afterSource = false;
        Enumeration<?> nodes = threadGroup.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            Object next = nodes.nextElement();
            if (!(next instanceof JMeterTreeNode node)) {
                continue;
            }
            if (node == sourceNode) {
                afterSource = true;
                continue;
            }
            if (afterSource && node.getTestElement() instanceof Sampler
                    && node.getTestElement() instanceof Replaceable replaceable) {
                int occurrences = countOccurrences(node.getTestElement(), literal);
                if (occurrences > 0) {
                    targets.add(new ReplacementTarget(node, occurrences, replaceable));
                }
            }
        }
        return targets;
    }

    private static JMeterTreeNode findThreadGroupNode(JMeterTreeNode node) {
        JMeterTreeNode current = node;
        while (current != null) {
            if (current.getTestElement() instanceof AbstractThreadGroup) {
                return current;
            }
            current = current.getParent() instanceof JMeterTreeNode parent ? parent : null;
        }
        return null;
    }

    private static String formatNodePath(JMeterTreeNode node) {
        StringJoiner path = new StringJoiner(" > "); //$NON-NLS-1$
        for (Object element : node.getPath()) {
            if (element instanceof JMeterTreeNode treeNode) {
                path.add(treeNode.getName());
            }
        }
        return path.toString();
    }

    private static int countOccurrences(TestElement element, String literal) {
        if (element instanceof Argument argument) {
            return countOccurrences(argument.getName(), literal)
                    + countOccurrences(argument.getValue(), literal);
        }
        int count = 0;
        PropertyIterator properties = element.propertyIterator();
        while (properties.hasNext()) {
            count += countOccurrences(properties.next(), literal);
        }
        return count;
    }

    private static int countOccurrences(JMeterProperty property, String literal) {
        if (property instanceof MultiProperty multiProperty) {
            int count = 0;
            PropertyIterator properties = multiProperty.iterator();
            while (properties.hasNext()) {
                count += countOccurrences(properties.next(), literal);
            }
            return count;
        }
        if (property instanceof TestElementProperty testElementProperty
                && testElementProperty.getElement() != null) {
            return countOccurrences(testElementProperty.getElement(), literal);
        }
        return countOccurrences(property.getStringValue(), literal);
    }

    private void applyReplacement(GuiPackage gui, List<ReplacementTarget> targets,
            String literal, String variableName) {
        int replacementCount = 0;
        gui.updateCurrentNode();
        gui.beginUndoTransaction();
        try {
            for (ReplacementTarget target : targets) {
                try {
                    int replaced = target.replaceable().replaceLiteral(literal,
                            "${" + variableName + "}"); //$NON-NLS-1$
                    replacementCount += replaced;
                    if (replaced > 0) {
                        gui.getTreeModel().nodeChanged(target.node());
                    }
                } catch (Exception ex) {
                    throw new IllegalStateException("Unable to replace regex extractor value", ex);
                }
            }
        } finally {
            gui.endUndoTransaction();
        }
        if (replacementCount > 0) {
            gui.setDirty(true);
            gui.refreshCurrentGui();
            gui.getMainFrame().repaint();
        }
        JMeterUtils.reportInfoToUser(MessageFormat.format(
                JMeterUtils.getResString("regex_test_replacement_applied"), replacementCount), //$NON-NLS-1$
                JMeterUtils.getResString("regex_test_replacement")); //$NON-NLS-1$
        updateTestResult();
    }

    private static String formatElapsedMillis(long elapsedNanos) {
        return String.format(Locale.ROOT, "%.1f ms", elapsedNanos / 1_000_000d); //$NON-NLS-1$
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setName(name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class PreviewRun {
        private final long generation;
        private final RegexExtractor extractor;
        private final SampleResult sampleResult;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Future<?> task;
        private volatile ScheduledFuture<?> timeoutTask;

        private PreviewRun(long generation, RegexExtractor extractor, SampleResult sampleResult) {
            this.generation = generation;
            this.extractor = extractor;
            this.sampleResult = sampleResult;
        }

        private boolean start() {
            try {
                task = PREVIEW_EXECUTOR.submit(this::evaluate);
                timeoutTask = PREVIEW_TIMEOUT_EXECUTOR.schedule(this::timeout,
                        PREVIEW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                return true;
            } catch (RejectedExecutionException e) {
                return false;
            }
        }

        private void evaluate() {
            long started = System.nanoTime();
            try {
                List<TestMatch> matches = extractor.extractForTestingWithGroups(sampleResult);
                long elapsed = System.nanoTime() - started;
                if (finished.compareAndSet(false, true)) {
                    cancelTimeout();
                    showResult(matches, extractor, elapsed);
                }
            } catch (RuntimeException | StackOverflowError e) {
                long elapsed = System.nanoTime() - started;
                if (finished.compareAndSet(false, true)) {
                    cancelTimeout();
                    showResult(JMeterUtils.getResString("regex_test_error") //$NON-NLS-1$
                            + " " + e + "\n" //$NON-NLS-1$
                            + JMeterUtils.getResString("regex_test_parse_time") //$NON-NLS-1$
                            + " " + formatElapsedMillis(elapsed)); //$NON-NLS-1$
                }
            }
        }

        private void timeout() {
            if (finished.compareAndSet(false, true)) {
                Future<?> currentTask = task;
                if (currentTask != null) {
                    currentTask.cancel(true);
                }
                showResult(JMeterUtils.getResString("regex_test_timeout") //$NON-NLS-1$
                        + " " + PREVIEW_TIMEOUT_MILLIS + " ms"); //$NON-NLS-1$
            }
        }

        private void cancel() {
            if (finished.compareAndSet(false, true)) {
                cancelTimeout();
                Future<?> currentTask = task;
                if (currentTask != null) {
                    currentTask.cancel(true);
                }
            }
        }

        private void cancelTimeout() {
            ScheduledFuture<?> currentTimeoutTask = timeoutTask;
            if (currentTimeoutTask != null) {
                currentTimeoutTask.cancel(false);
            }
        }

        private void showResult(String result) {
            SwingUtilities.invokeLater(() -> {
                if (previewGeneration.get() == generation && testResultStatus != null) {
                    clearTestTable();
                    showStatus(result);
                }
            });
        }

        private void showResult(List<TestMatch> matches, RegexExtractor extractor, long elapsedNanos) {
            SwingUtilities.invokeLater(() -> {
                if (previewGeneration.get() == generation && testResultTable != null) {
                    showMatches(matches, extractor, elapsedNanos);
                }
            });
        }
    }

    private record ReplacementCandidate(int matchIndex, String variableName, String value,
            String displayMatch, boolean replaceable) {
    }

    private record ReplacementTarget(JMeterTreeNode node, int occurrences, Replaceable replaceable) {
    }

    private static final int REPLACE_BUTTON_WIDTH = 84;
    private static final int REPLACE_BUTTON_HEIGHT = 26;

    private static final class SingleLineRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        private SingleLineRenderer() {
            putClientProperty("html.disable", true); //$NON-NLS-1$
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            String text = value == null ? "" : value.toString(); //$NON-NLS-1$
            int newline = text.indexOf('\n');
            int carriageReturn = text.indexOf('\r');
            int end = newline < 0 ? text.length() : newline;
            if (carriageReturn >= 0) {
                end = Math.min(end, carriageReturn);
            }
            String preview = end < text.length() ? text.substring(0, end) + "..." : text; //$NON-NLS-1$
            super.getTableCellRendererComponent(table, preview, isSelected, hasFocus, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            return this;
        }
    }

    private static final class ReplaceButtonRenderer extends JPanel implements TableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final JButton button = createReplaceButton();

        private ReplaceButtonRenderer() {
            super(new FlowLayout(FlowLayout.LEFT, 0, 0));
            setOpaque(false);
            add(button);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            button.setText(JMeterUtils.getResString("regex_test_replace")); //$NON-NLS-1$
            button.setEnabled(table.isCellEditable(row, column));
            return this;
        }
    }

    private final class ReplaceButtonEditor extends DefaultCellEditor {
        private static final long serialVersionUID = 1L;
        private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        private final JButton button = createReplaceButton();
        private int row;

        private ReplaceButtonEditor() {
            super(new JCheckBox());
            panel.setOpaque(false);
            panel.add(button);
            button.addActionListener(event -> {
                fireEditingStopped();
                int modelRow = testResultTable.convertRowIndexToModel(row);
                if (modelRow >= 0 && modelRow < displayedCandidates.size()) {
                    replaceCandidate(displayedCandidates.get(modelRow));
                }
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                int row, int column) {
            this.row = row;
            button.setText(JMeterUtils.getResString("regex_test_replace")); //$NON-NLS-1$
            button.setEnabled(table.isCellEditable(row, column));
            return panel;
        }

        @Override
        public Object getCellEditorValue() {
            return JMeterUtils.getResString("regex_test_replace"); //$NON-NLS-1$
        }
    }

    private static JButton createReplaceButton() {
        JButton button = new JButton();
        Dimension size = new Dimension(REPLACE_BUTTON_WIDTH, REPLACE_BUTTON_HEIGHT);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        return button;
    }

    private static JTextField getTextField(JLabeledTextField field) {
        return (JTextField) field.getComponentList().get(1);
    }

    private static void addField(JPanel panel, JLabeledTextField field, GridBagConstraints gbc) {
        List<JComponent> item = field.getComponentList();
        panel.add(item.get(0), gbc.clone());
        gbc.gridx++;
        gbc.weightx = 1;
        gbc.fill=GridBagConstraints.HORIZONTAL;
        panel.add(item.get(1), gbc.clone());
    }

    // Next line
    private static void resetContraints(GridBagConstraints gbc) {
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        gbc.fill=GridBagConstraints.NONE;
    }

    private static void initConstraints(GridBagConstraints gbc) {
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.gridheight = 1;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        gbc.weighty = 0;
    }
}
