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
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.text.JTextComponent;

import org.apache.jmeter.extractor.ExtractorTestResult;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.AbstractScopedTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JMeterUtils;

/** Shared recorded-response tester for declarative extractors. */
public final class ExtractorTesterPanel<E extends AbstractScopedTestElement> extends JPanel {
    private static final long serialVersionUID = 240L;
    private static final long DEFAULT_PREVIEW_TIMEOUT_MILLIS = 2000L;
    private static final long PREVIEW_TIMEOUT_MILLIS = Math.max(100L,
            JMeterUtils.getPropDefault("extractor_tester_timeout_ms", //$NON-NLS-1$
                    DEFAULT_PREVIEW_TIMEOUT_MILLIS));
    private static final ThreadPoolExecutor PREVIEW_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            daemonThreadFactory("extractor-preview")); //$NON-NLS-1$
    private static final ScheduledExecutorService PREVIEW_TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("extractor-watchdog")); //$NON-NLS-1$
    /**
     * Latest run submitted by any tester. Extractor GUIs are not cleared when another element is
     * selected, so a new submission cancels the previous one to free the single worker and queue slot.
     */
    private static final AtomicReference<ExtractorTesterPanel<?>.PreviewRun> ACTIVE_RUN = new AtomicReference<>();
    /** Re-runs the preview that last reported busy, once the worker finishes its current evaluation. */
    private static final AtomicReference<Runnable> BUSY_RETRY = new AtomicReference<>();

    private JLabel testResultStatus;
    private ExtractorTestTiming testParseTime;
    private JTable testResultTable;
    private DefaultTableModel testResultModel;
    private SampleResult recordedSampleResult;
    private String recordedSampleMessage;
    private TestElement configuredExtractor;
    private List<ReplacementCandidate> displayedCandidates = List.of();
    /** Detached copies of later samplers, so replacement counting can run off the EDT. */
    private List<ReplacementSource> replacementSources;
    private final AtomicLong previewGeneration = new AtomicLong();
    private volatile PreviewRun previewRun;

    private final Supplier<E> extractorSupplier;

    private final Predicate<E> hasExpression;
    private final Evaluation<E> evaluation;
    private final String titleResource;

    @FunctionalInterface
    public interface Evaluation<T> {
        ExtractorTestResult evaluate(T extractor, SampleResult sample) throws Exception;
    }

    public ExtractorTesterPanel(String titleResource, Supplier<E> extractorSupplier,
            Predicate<E> hasExpression, Evaluation<E> evaluation) {
        super(new BorderLayout());
        this.extractorSupplier = extractorSupplier;
        this.hasExpression = hasExpression;
        this.evaluation = evaluation;
        this.titleResource = titleResource;
        add(makeTestResultPanel(), BorderLayout.CENTER);
    }

    /**
     * Watches editor controls, including scope, parser options and namespace text areas.
     * Components in {@code ignored} (such as the title panel) and their children are not watched.
     */
    public void watch(Component component, BooleanSupplier suspended, Component... ignored) {
        watch(component, suspended, Set.of(ignored));
    }

    private void watch(Component component, BooleanSupplier suspended, Set<Component> ignored) {
        if (component == this || ignored.contains(component) || component instanceof JScrollBar) {
            return;
        }
        Runnable update = () -> {
            if (!suspended.getAsBoolean()) {
                updateTestResult();
            }
        };
        if (component instanceof JTextComponent text) {
            text.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent event) {
                    update.run();
                }

                @Override
                public void removeUpdate(DocumentEvent event) {
                    update.run();
                }

                @Override
                public void changedUpdate(DocumentEvent event) {
                    update.run();
                }
            });
        }
        if (component instanceof AbstractButton button) {
            button.addActionListener(event -> update.run());
        }
        if (component instanceof JComboBox<?> combo) {
            // Do not descend: the arrow button and editor are internal parts of the combo.
            combo.addActionListener(event -> update.run());
            return;
        }
        if (component instanceof JSpinner spinner) {
            spinner.addChangeListener(event -> update.run());
            return;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                watch(child, suspended, ignored);
            }
        }
    }

    public void configure(TestElement extractor) {
        clear();
        configuredExtractor = extractor;
        recordedSampleResult = loadRecordedSampleResult(extractor);
        updateTestResult();
    }

    public void clear() {
        cancelPreview();
        previewGeneration.incrementAndGet();
        recordedSampleResult = null;
        recordedSampleMessage = null;
        configuredExtractor = null;
        replacementSources = null;
        clearTestResult();
    }

    private JPanel makeTestResultPanel() {
        testResultStatus = new JLabel();
        testParseTime = new ExtractorTestTiming();
        testResultStatus.putClientProperty("html.disable", true);
        testResultStatus.setToolTipText(JMeterUtils.getResString("extractor_test_parse_time_help"));
        testResultStatus.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        testResultModel = new DefaultTableModel(
                new Object[] {
                        JMeterUtils.getResString("regex_test_variable"), //$NON-NLS-1$
                        JMeterUtils.getResString("regex_test_extracted_value"), //$NON-NLS-1$
                        JMeterUtils.getResString("regex_test_occurrence_count"), //$NON-NLS-1$
                        "" //$NON-NLS-1$
                }, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 3 && row >= 0 && row < displayedCandidates.size()
                        && displayedCandidates.get(row).replaceable()
                        && ((Number) getValueAt(row, 2)).intValue() > 0;
            }
        };
        testResultTable = new JTable(testResultModel);
        testResultTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        testResultTable.setRowHeight(28);
        testResultTable.getColumnModel().getColumn(0).setPreferredWidth(210);
        testResultTable.getColumnModel().getColumn(1).setPreferredWidth(500);
        testResultTable.getColumnModel().getColumn(2).setPreferredWidth(145);
        TableColumn replaceColumn = testResultTable.getColumnModel().getColumn(3);
        replaceColumn.setPreferredWidth(100);
        replaceColumn.setMinWidth(100);
        replaceColumn.setMaxWidth(100);
        for (int column = 0; column < 3; column++) {
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
                        && testResultTable.convertColumnIndexToModel(column) == 1) {
                    showFullValue(testResultTable.convertRowIndexToModel(row));
                }
            }
        });

        JScrollPane testResultPane = new JScrollPane(testResultTable);
        testResultPane.setPreferredSize(new Dimension(0, 140));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString(titleResource))); //$NON-NLS-1$
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        statusPanel.add(testResultStatus);
        statusPanel.add(testParseTime);
        panel.add(statusPanel, BorderLayout.NORTH);
        panel.add(testResultPane, BorderLayout.CENTER);
        return panel;
    }

    private void showFullValue(int row) {
        JTextArea value = new JTextArea(String.valueOf(testResultModel.getValueAt(row, 1)));
        value.setEditable(false);
        value.setLineWrap(true);
        value.setWrapStyleWord(true);
        value.setCaretPosition(0);
        JScrollPane scrollPane = new JScrollPane(value);
        scrollPane.setPreferredSize(new Dimension(720, 320));
        JOptionPane.showMessageDialog(this, scrollPane,
                JMeterUtils.getResString("regex_test_extracted_value") + " — " //$NON-NLS-1$ //$NON-NLS-2$
                        + testResultModel.getValueAt(row, 0), JOptionPane.PLAIN_MESSAGE);
    }

    public void updateTestResult() {
        cancelPreview();
        ExtractorTesterPanel<?>.PreviewRun active = ACTIVE_RUN.getAndSet(null);
        if (active != null) {
            active.cancel();
        }
        BUSY_RETRY.set(null);
        PREVIEW_EXECUTOR.purge();
        long generation = previewGeneration.incrementAndGet();
        clearTestResult();
        E extractor = extractorSupplier.get();
        if (!hasExpression.test(extractor)) {
            return;
        }
        if (extractor.isScopeVariable(extractor.fetchScope())) {
            showStatus(JMeterUtils.getResString("extractor_test_variable_scope"));
            return;
        }
        if (recordedSampleResult == null) {
            showStatus(recordedSampleMessage == null
                    ? JMeterUtils.getResString("regex_test_no_recorded_response") : recordedSampleMessage);
            return;
        }
        if (replacementSources == null) {
            replacementSources = snapshotReplacementSources(findParentSamplerNode(configuredExtractor));
        }
        PreviewRun run = new PreviewRun(generation, extractor, recordedSampleResult, replacementSources);
        previewRun = run;
        ACTIVE_RUN.set(run);
        showStatus(JMeterUtils.getResString("regex_test_running"));
        if (!run.start()) {
            previewRun = null;
            ACTIVE_RUN.compareAndSet(run, null);
            showStatus(JMeterUtils.getResString("extractor_test_busy"));
            scheduleBusyRetry(generation);
        }
    }

    private void scheduleBusyRetry(long generation) {
        BUSY_RETRY.set(() -> {
            if (previewGeneration.get() == generation) {
                updateTestResult();
            }
        });
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
            testParseTime.setVisible(false);
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
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException ignored) {
            // Other extraction sources are still available without a valid recorded URL.
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


    private static List<ReplacementCandidate> buildCandidates(ExtractorTestResult result) {
        return result.variables().entrySet().stream()
                .map(entry -> new ReplacementCandidate(entry.getKey(), entry.getValue(),
                        !result.countVariables().contains(entry.getKey()) && isReplaceable(entry.getValue())))
                .toList();
    }

    /** Runs on the preview thread against detached sampler copies; never touches the live tree. */
    private static Map<String, Integer> countReplacementOccurrences(List<ReplacementCandidate> candidates,
            List<ReplacementSource> sources, AtomicBoolean stopped) {
        Map<String, Integer> occurrences = new HashMap<>();
        for (ReplacementCandidate candidate : candidates) {
            if (stopped.get()) {
                break;
            }
            if (candidate.replaceable() && !occurrences.containsKey(candidate.value())) {
                int total = 0;
                for (ReplacementSource source : sources) {
                    total += countOccurrences(source.copy(), candidate.value());
                }
                occurrences.put(candidate.value(), total);
            }
        }
        return occurrences;
    }

    private void showMatches(ExtractorTestResult result, Map<String, Integer> occurrencesByValue,
            long elapsedNanos) {
        displayedCandidates = buildCandidates(result);
        testResultModel.setRowCount(0);
        for (ReplacementCandidate candidate : displayedCandidates) {
            int occurrences = candidate.replaceable() ? occurrencesByValue.getOrDefault(candidate.value(), 0) : 0;
            testResultModel.addRow(new Object[] {
                    candidate.variableName(), candidate.value(), occurrences,
                    candidate.replaceable() ? JMeterUtils.getResString("regex_test_replace") : ""
            });
        }
        showStatus(JMeterUtils.getResString("regex_test_match_count") + " " + result.matchCount());
        testParseTime.setElapsed(elapsedNanos);
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

    private static List<ReplacementSource> snapshotReplacementSources(JMeterTreeNode sourceNode) {
        if (sourceNode == null) {
            return List.of();
        }
        List<ReplacementSource> sources = new ArrayList<>();
        for (JMeterTreeNode node : findLaterReplaceableSamplers(sourceNode)) {
            sources.add(new ReplacementSource(node, (TestElement) node.getTestElement().clone()));
        }
        return sources;
    }

    private static List<ReplacementTarget> findReplacementTargets(JMeterTreeNode sourceNode, String literal) {
        if (literal.isEmpty()) {
            return List.of();
        }
        List<ReplacementTarget> targets = new ArrayList<>();
        for (JMeterTreeNode node : findLaterReplaceableSamplers(sourceNode)) {
            int occurrences = countOccurrences(node.getTestElement(), literal);
            if (occurrences > 0) {
                targets.add(new ReplacementTarget(node, occurrences, (Replaceable) node.getTestElement()));
            }
        }
        return targets;
    }

    private static List<JMeterTreeNode> findLaterReplaceableSamplers(JMeterTreeNode sourceNode) {
        JMeterTreeNode threadGroup = findThreadGroupNode(sourceNode);
        if (threadGroup == null) {
            return List.of();
        }
        List<JMeterTreeNode> samplers = new ArrayList<>();
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
                    && node.getTestElement() instanceof Replaceable) {
                samplers.add(node);
            }
        }
        return samplers;
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
        if (literal == null || literal.isEmpty() || !(element instanceof Replaceable)) {
            return 0;
        }
        // Dry-run the actual replacement on a detached copy: metadata, names and
        // existing variables must have exactly the same eligibility as when applying.
        Replaceable preview = (Replaceable) element.clone();
        try {
            return preview.replaceLiteral(literal, "${__regex_preview}"); //$NON-NLS-1$
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to count extractor replacements", ex);
        }
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
                    throw new IllegalStateException("Unable to replace extractor value", ex);
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
        replacementSources = null;
        updateTestResult();
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
        private final E extractor;
        private final SampleResult sampleResult;
        private final List<ReplacementSource> replacementSources;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Future<?> task;
        private volatile ScheduledFuture<?> timeoutTask;
        private ScheduledFuture<?> queueTimeoutTask;
        private boolean evaluationStarted;

        private PreviewRun(long generation, E extractor, SampleResult sampleResult,
                List<ReplacementSource> replacementSources) {
            this.generation = generation;
            this.extractor = extractor;
            this.sampleResult = sampleResult;
            this.replacementSources = replacementSources;
        }

        private synchronized boolean start() {
            try {
                task = PREVIEW_EXECUTOR.submit(this::evaluate);
                queueTimeoutTask = PREVIEW_TIMEOUT_EXECUTOR.schedule(this::queueTimeout,
                        PREVIEW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                return true;
            } catch (RejectedExecutionException e) {
                return false;
            }
        }

        private synchronized boolean beginEvaluation() {
            if (finished.get()) {
                return false;
            }
            evaluationStarted = true;
            queueTimeoutTask.cancel(false);
            // Give execution its own budget, separate from the bounded queue wait.
            timeoutTask = PREVIEW_TIMEOUT_EXECUTOR.schedule(this::timeout,
                    PREVIEW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        }

        private synchronized void queueTimeout() {
            if (!evaluationStarted && finished.compareAndSet(false, true)) {
                // Leave the task queued: once the worker frees up it runs as a no-op (finished is set)
                // and its finally block fires the retry. Cancelling it here could race with the worker
                // dequeuing it, so the retry would never run.
                showResult(JMeterUtils.getResString("extractor_test_busy")); //$NON-NLS-1$
                // Registered under the run lock, before the worker can observe this run as finished.
                scheduleBusyRetry(generation);
            }
        }

        private void evaluate() {
            try {
                if (beginEvaluation()) {
                    evaluateStarted();
                }
            } finally {
                // The worker is about to become free: retry a preview that gave up waiting for it.
                Runnable retry = BUSY_RETRY.getAndSet(null);
                if (retry != null) {
                    SwingUtilities.invokeLater(retry);
                }
            }
        }

        private void evaluateStarted() {
            long started = System.nanoTime();
            long elapsed = -1L;
            try {
                ExtractorTestResult matches = evaluation.evaluate(extractor, sampleResult);
                elapsed = System.nanoTime() - started;
                // Replacement counting is excluded from the reported parse time.
                Map<String, Integer> occurrences = countReplacementOccurrences(
                        buildCandidates(matches), replacementSources, finished);
                if (finished.compareAndSet(false, true)) {
                    cancelTimeout();
                    showResult(matches, occurrences, elapsed);
                }
            } catch (Exception | StackOverflowError e) {
                if (elapsed < 0) {
                    elapsed = System.nanoTime() - started;
                }
                if (finished.compareAndSet(false, true)) {
                    cancelTimeout();
                    showResult(JMeterUtils.getResString("extractor_test_error") //$NON-NLS-1$
                            + " " + e, elapsed); //$NON-NLS-1$
                }
            }
        }

        private void timeout() {
            if (finished.compareAndSet(false, true)) {
                Future<?> currentTask = task;
                if (currentTask != null) {
                    currentTask.cancel(true);
                }
                showResult(JMeterUtils.getResString("extractor_test_timeout") //$NON-NLS-1$
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

        private synchronized void cancelTimeout() {
            if (queueTimeoutTask != null) {
                queueTimeoutTask.cancel(false);
            }
            ScheduledFuture<?> currentTimeoutTask = timeoutTask;
            if (currentTimeoutTask != null) {
                currentTimeoutTask.cancel(false);
            }
        }

        private void showResult(String result) {
            showResult(result, -1L);
        }

        private void showResult(String result, long elapsedNanos) {
            SwingUtilities.invokeLater(() -> {
                if (previewGeneration.get() == generation && testResultStatus != null) {
                    clearTestTable();
                    showStatus(result);
                    if (elapsedNanos >= 0) {
                        testParseTime.setElapsed(elapsedNanos);
                    }
                }
            });
        }

        private void showResult(ExtractorTestResult matches, Map<String, Integer> occurrences,
                long elapsedNanos) {
            SwingUtilities.invokeLater(() -> {
                if (previewGeneration.get() == generation && testResultTable != null) {
                    showMatches(matches, occurrences, elapsedNanos);
                }
            });
        }
    }

    private record ReplacementCandidate(String variableName, String value, boolean replaceable) {
    }

    private record ReplacementTarget(JMeterTreeNode node, int occurrences, Replaceable replaceable) {
    }

    private record ReplacementSource(JMeterTreeNode node, TestElement copy) {
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

}
