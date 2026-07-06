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

package org.apache.jmeter.visualizers;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.Format;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.InputMap;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.KeyStrokes;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.FileDialoger;
import org.apache.jmeter.gui.util.HeaderAsPropertyRendererWrapper;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.CSVSaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;
import org.apache.jorphan.gui.NumberRenderer;
import org.apache.jorphan.gui.ObjectTableModel;
import org.apache.jorphan.gui.ObjectTableSorter;
import org.apache.jorphan.gui.RateRenderer;
import org.apache.jorphan.gui.RendererUtils;
import org.apache.jorphan.math.StatCalculatorLong;
import org.apache.jorphan.reflect.Functor;

import net.miginfocom.swing.MigLayout;

/**
 * Rich table summary with configurable columns.
 */
@GUIMenuSortOrder(2)
@TestElementMetadata(labelResource = "performance_report")
public class PerformanceReport extends AbstractVisualizer implements Clearable, ActionListener {

    private static final long serialVersionUID = 242L;

    private static final String USE_GROUP_NAME = "useGroupName"; //$NON-NLS-1$
    private static final String SAVE_HEADERS = "saveHeaders"; //$NON-NLS-1$
    private static final String SELECTED_COLUMNS = "selectedColumns"; //$NON-NLS-1$
    private static final String IGNORE_ERROR_RESPONSE_TIMES = "ignoreErrorResponseTimes"; //$NON-NLS-1$

    private static final Float P50_VALUE = 0.50F;
    private static final Float P75_VALUE = 0.75F;
    private static final Float P90_VALUE = 0.90F;
    private static final Float P95_VALUE = 0.95F;
    private static final Float P99_VALUE = 0.99F;

    private static final String TOTAL_ROW_LABEL = JMeterUtils.getResString("aggregate_report_total_label"); //$NON-NLS-1$
    private static final int REFRESH_PERIOD = JMeterUtils.getPropDefault("jmeter.gui.refresh_period", 500); // $NON-NLS-1$
    private static final long ERROR_HIGHLIGHT_DURATION_MILLIS = 2000;
    private static final Color ERROR_FOREGROUND = new Color(178, 0, 0);
    private static final Color ERROR_HIGHLIGHT_BACKGROUND = new Color(255, 226, 226);

    static final int LABEL_COLUMN = 0;
    static final int ERROR_COUNT_COLUMN = 11;

    private static final ColumnDefinition[] COLUMNS = new ColumnDefinition[] {
            new ColumnDefinition("sampler_label", null, "getLabel", String.class, null, null), //$NON-NLS-1$ //$NON-NLS-2$
            new ColumnDefinition("aggregate_report_count", null, "getCount", Long.class, null, null), //$NON-NLS-1$ //$NON-NLS-2$
            new ColumnDefinition("average", null, "getMeanAsNumber", Long.class, null, null), //$NON-NLS-1$ //$NON-NLS-2$
            new ColumnDefinition("aggregate_report_min", null, "getMin", Long.class, null, null), //$NON-NLS-1$ //$NON-NLS-2$
            new ColumnDefinition("performance_report_p50", null, "getPercentPoint", //$NON-NLS-1$ //$NON-NLS-2$
                    Long.class, null, new Object[] { P50_VALUE }, true),
            new ColumnDefinition("performance_report_p75", null, "getPercentPoint", //$NON-NLS-1$ //$NON-NLS-2$
                    Long.class, null, new Object[] { P75_VALUE }, true),
            new ColumnDefinition("performance_report_p90", null, "getPercentPoint", //$NON-NLS-1$ //$NON-NLS-2$
                    Long.class, null, new Object[] { P90_VALUE }, true),
            new ColumnDefinition("performance_report_p95", null, "getPercentPoint", //$NON-NLS-1$ //$NON-NLS-2$
                    Long.class, null, new Object[] { P95_VALUE }, true),
            new ColumnDefinition("performance_report_p99", null, "getPercentPoint", //$NON-NLS-1$ //$NON-NLS-2$
                    Long.class, null, new Object[] { P99_VALUE }, true),
            new ColumnDefinition("performance_report_max", null, "getMax", Long.class, null, null), //$NON-NLS-1$ //$NON-NLS-2$
            new ColumnDefinition("performance_report_stddev", null, "getStandardDeviation", Double.class, //$NON-NLS-1$ //$NON-NLS-2$
                    new DecimalFormat("#0.00"), null), //$NON-NLS-1$
            new ColumnDefinition("aggregate_report_error_count", null, "getErrorCount", Long.class, null, null), //$NON-NLS-1$ //$NON-NLS-2$
            new ColumnDefinition("aggregate_report_error%", null, "getErrorPercentage", Double.class, //$NON-NLS-1$ //$NON-NLS-2$
                    new DecimalFormat("#0.000%"), null), //$NON-NLS-1$
            new ColumnDefinition("performance_report_rate", null, "getRate", Double.class, //$NON-NLS-1$ //$NON-NLS-2$
                    new DecimalFormat("#.00000"), null), //$NON-NLS-1$
            new ColumnDefinition("aggregate_report_bandwidth", null, "getKBPerSecond", Double.class, //$NON-NLS-1$ //$NON-NLS-2$
                    new DecimalFormat("#0.00"), null), //$NON-NLS-1$
            new ColumnDefinition("aggregate_report_sent_bytes_per_sec", null, "getSentKBPerSecond", Double.class, //$NON-NLS-1$ //$NON-NLS-2$
                    new DecimalFormat("#0.00"), null), //$NON-NLS-1$
            new ColumnDefinition("performance_report_sum_received_kb", null, "getTotalKB", Double.class, //$NON-NLS-1$ //$NON-NLS-2$
                    new DecimalFormat("#0.00"), null), //$NON-NLS-1$
            new ColumnDefinition("performance_report_sum_sent_kb", null, "getTotalSentKB", Double.class, //$NON-NLS-1$ //$NON-NLS-2$
                    new DecimalFormat("#0.00"), null), //$NON-NLS-1$
            new ColumnDefinition("average_bytes", null, "getAvgPageBytes", Double.class, //$NON-NLS-1$ //$NON-NLS-2$
                    new DecimalFormat("#.0"), null), //$NON-NLS-1$
            new ColumnDefinition("performance_report_avg_connect_time", null, "getAverageConnectTime", Long.class, null, null), //$NON-NLS-1$ //$NON-NLS-2$
    };

    private final JButton saveTable = new JButton(JMeterUtils.getResString("aggregate_graph_save_table")); //$NON-NLS-1$

    private final JButton configureColumns = new JButton(JMeterUtils.getResString("performance_configure_columns")); //$NON-NLS-1$

    private final JCheckBox saveHeaders = new JCheckBox(
            JMeterUtils.getResString("aggregate_graph_save_table_header"), true); //$NON-NLS-1$

    private final JCheckBox useGroupName = new JCheckBox(
            JMeterUtils.getResString("aggregate_graph_use_group_name")); //$NON-NLS-1$

    private final JCheckBox ignoreErrorResponseTimes = new JCheckBox(
            JMeterUtils.getResString("performance_ignore_error_response_times")); //$NON-NLS-1$

    private final transient ObjectTableModel model;

    private final transient Object lock = new Object();

    private final Map<String, PerformanceReportData> tableRows = new ConcurrentHashMap<>();

    private final Deque<PerformanceReportData> newRows = new ConcurrentLinkedDeque<>();

    private final Map<String, Long> errorHighlightEndTimes = new ConcurrentHashMap<>();

    private JTable table;

    private TableColumn[] tableColumns;

    private JToggleButton filePanelToggle;

    private JPanel filePanelWrapper;

    private JButton detachButton;

    private JPanel visualizerPanel;

    private Component detachedPlaceholder;

    private JFrame detachedWindow;

    private JPanel detachedWindowContent;

    private TestElement configuredElement;

    private boolean[] selectedColumns = defaultSelectedColumns();

    private volatile boolean dataChanged;

    public PerformanceReport() {
        super();
        model = createObjectTableModel();
        clearData();
        init();
    }

    static ObjectTableModel createObjectTableModel() {
        return new ObjectTableModel(getLabels(), PerformanceReportData.class,
                getReadFunctors(), getWriteFunctors(), getColumnClasses(), false);
    }

    static String[] getColumnKeys() {
        String[] result = new String[COLUMNS.length];
        for (int i = 0; i < COLUMNS.length; i++) {
            result[i] = COLUMNS[i].key;
        }
        return result;
    }

    static boolean isResourceIntensiveColumn(int column) {
        return COLUMNS[column].resourceIntensive;
    }

    static boolean isDefaultSelectedColumn(int column) {
        return defaultSelectedColumns()[column];
    }

    static TableCellRenderer getRenderer(int column) {
        return createRenderers()[column];
    }

    @Deprecated
    public static boolean testFunctors(){
        PerformanceReport instance = new PerformanceReport();
        return instance.model.checkFunctors(null, instance.getClass());
    }

    @Override
    public String getLabelResource() {
        return "performance_report"; //$NON-NLS-1$
    }

    @Override
    public void add(final SampleResult res) {
        PerformanceReportData row = tableRows.computeIfAbsent(
                res.getSampleLabel(useGroupName.isSelected()),
                label -> {
                    PerformanceReportData newRow = new PerformanceReportData(label);
                    newRow.setIgnoreErrorResponseTimes(ignoreErrorResponseTimes.isSelected());
                    newRow.setTrackResponseTimeDistribution(shouldTrackResponseTimeDistribution());
                    newRows.add(newRow);
                    return newRow;
        });
        synchronized (row) {
            long previousErrorCount = row.getRawErrorCount();
            row.addSample(res);
            if (row.getRawErrorCount() > previousErrorCount) {
                highlightErrorIncrease(row.getLabel());
            }
        }
        PerformanceReportData total = tableRows.get(TOTAL_ROW_LABEL);
        synchronized (lock) {
            total.addSample(res);
        }
        dataChanged = true;
    }

    @Override
    public void clearData() {
        synchronized (lock) {
            model.clearData();
            tableRows.clear();
            newRows.clear();
            errorHighlightEndTimes.clear();
            PerformanceReportData total = new PerformanceReportData(TOTAL_ROW_LABEL);
            total.setIgnoreErrorResponseTimes(ignoreErrorResponseTimes.isSelected());
            total.setTrackResponseTimeDistribution(shouldTrackResponseTimeDistribution());
            tableRows.put(TOTAL_ROW_LABEL, total);
            model.addRow(tableRows.get(TOTAL_ROW_LABEL));
        }
        dataChanged = true;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.modifyTestElement(element);
        element.setProperty(USE_GROUP_NAME, useGroupName.isSelected(), false);
        element.setProperty(SAVE_HEADERS, saveHeaders.isSelected(), true);
        element.setProperty(IGNORE_ERROR_RESPONSE_TIMES, ignoreErrorResponseTimes.isSelected(), false);
        element.setProperty(SELECTED_COLUMNS, encodeSelectedColumns(), defaultEncodedColumns());
    }

    @Override
    public void configure(TestElement element) {
        configuredElement = element;
        super.configure(element);
        useGroupName.setSelected(element.getPropertyAsBoolean(USE_GROUP_NAME, false));
        saveHeaders.setSelected(element.getPropertyAsBoolean(SAVE_HEADERS, true));
        ignoreErrorResponseTimes.setSelected(element.getPropertyAsBoolean(IGNORE_ERROR_RESPONSE_TIMES, false));
        applyIgnoreErrorResponseTimes();
        selectedColumns = decodeSelectedColumns(element.getPropertyAsString(SELECTED_COLUMNS, defaultEncodedColumns()));
        applyResponseTimeDistributionTracking();
        if (table != null) {
            applySelectedColumns();
        }
        updateDetachedWindowTitle();
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (event.getSource() == saveTable) {
            saveTable();
        } else if (event.getSource() == configureColumns) {
            showColumnConfigurationDialog();
        } else if (event.getSource() == ignoreErrorResponseTimes) {
            applyIgnoreErrorResponseTimes();
        }
    }

    private void init() {
        this.setLayout(new BorderLayout());
        visualizerPanel = new JPanel(new BorderLayout());

        JPanel mainPanel = new JPanel();
        Border margin = new EmptyBorder(10, 10, 5, 10);

        mainPanel.setBorder(margin);
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        Container titlePanel = makeTitlePanel();
        stretchHorizontally(titlePanel);
        mainPanel.add(titlePanel);

        saveTable.addActionListener(this);
        configureColumns.addActionListener(this);
        ignoreErrorResponseTimes.addActionListener(this);
        JPanel opts = new JPanel(new MigLayout(
                "fillx, insets 0, hidemode 3", //$NON-NLS-1$
                "[][][][][][grow]")); //$NON-NLS-1$
        opts.add(useGroupName);
        opts.add(ignoreErrorResponseTimes);
        opts.add(configureColumns);
        opts.add(saveTable);
        opts.add(saveHeaders, "gapleft 8"); //$NON-NLS-1$
        stretchHorizontally(opts);
        mainPanel.add(opts);

        table = createTable();
        table.setRowSorter(new ObjectTableSorter(model).fixLastRow());
        JMeterUtils.applyHiDPI(table);
        HeaderAsPropertyRendererWrapper.setupDefaultRenderer(table);
        table.setPreferredScrollableViewportSize(new Dimension(500, 70));
        RendererUtils.applyRenderers(table, createRenderers());
        tableColumns = captureTableColumns(table);
        applySelectedColumns();
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                jumpToReportRowOnDoubleClick(event);
            }

            @Override
            public void mousePressed(MouseEvent event) {
                showReportTablePopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showReportTablePopup(event);
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        visualizerPanel.add(mainPanel, BorderLayout.NORTH);
        visualizerPanel.add(scrollPane, BorderLayout.CENTER);
        this.add(visualizerPanel, BorderLayout.CENTER);

        new Timer(REFRESH_PERIOD, e -> {
            boolean highlightNeedsRepaint = updateErrorHighlightState(System.currentTimeMillis());
            if (!dataChanged && !highlightNeedsRepaint) {
                return;
            }
            boolean tableDataChanged = dataChanged;
            dataChanged = false;
            if (tableDataChanged) {
                synchronized (lock) {
                    while (!newRows.isEmpty()) {
                        model.insertRow(newRows.pop(), model.getRowCount() - 1);
                    }
                }
                model.fireTableDataChanged();
            } else {
                table.repaint();
            }
        }).start();
    }

    @Override
    protected Container wrapTitlePanel(Container titlePanel) {
        configureTitlePanel(titlePanel);
        filePanelWrapper = new JPanel(new BorderLayout());
        filePanelWrapper.setVisible(false);
        stretchHorizontally(filePanelWrapper);

        JPanel wrapper = new JPanel(new MigLayout("fillx, wrap 1, insets 0, hidemode 3", "[fill,grow]")) { //$NON-NLS-1$ //$NON-NLS-2$
            private static final long serialVersionUID = 242L;

            @Override
            protected void addImpl(Component comp, Object constraints, int index) {
                if (comp == PerformanceReport.this.getFilePanel() && filePanelWrapper != null) {
                    filePanelWrapper.add(comp, BorderLayout.CENTER);
                    updateFilePanelVisibility();
                    return;
                }
                super.addImpl(comp, constraints, index);
            }
        };
        wrapper.add(titlePanel, "growx"); //$NON-NLS-1$
        wrapper.add(filePanelWrapper, "growx"); //$NON-NLS-1$
        stretchHorizontally(wrapper);
        updateFilePanelVisibility();
        return wrapper;
    }

    private void configureTitlePanel(Container titlePanel) {
        if (!(titlePanel instanceof JPanel panel) || panel.getComponentCount() < 6) {
            return;
        }

        Component[] titleComponents = panel.getComponents();
        stretchHorizontally(panel);
        filePanelToggle = new JToggleButton(JMeterUtils.getResString("view_results_file_panel_show")); //$NON-NLS-1$
        filePanelToggle.addActionListener(e -> updateFilePanelVisibility());
        detachButton = new JButton();
        detachButton.addActionListener(e -> toggleDetached());
        titleComponents[2].setMinimumSize(new Dimension(80, titleComponents[2].getPreferredSize().height));
        titleComponents[5].setMinimumSize(new Dimension(80, titleComponents[5].getPreferredSize().height));

        panel.removeAll();
        panel.setLayout(new MigLayout(
                "fillx, wrap 5, insets 0, hidemode 3", //$NON-NLS-1$
                "[][fill,grow,shrinkprio 200][right,shrinkprio 0][right,shrinkprio 0][right,shrinkprio 0]")); //$NON-NLS-1$
        panel.add(titleComponents[0], "span 5"); //$NON-NLS-1$
        panel.add(titleComponents[1]);
        panel.add(titleComponents[2], "growx, pushx, wmin 80"); //$NON-NLS-1$
        panel.add(filePanelToggle, "gapleft 8"); //$NON-NLS-1$
        panel.add(detachButton, "wmin button, gapleft 8"); //$NON-NLS-1$
        panel.add(titleComponents[3], "gapleft 8, wrap"); //$NON-NLS-1$
        panel.add(titleComponents[4], "newline"); //$NON-NLS-1$
        panel.add(titleComponents[5], "span 4, growx, wmin 80"); //$NON-NLS-1$
        updateDetachButton();
        updateFilePanelVisibility();
    }

    private static void stretchHorizontally(Component component) {
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        if (component instanceof JComponent jComponent) {
            jComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
    }

    private void updateFilePanelVisibility() {
        if (filePanelToggle == null || filePanelWrapper == null) {
            return;
        }
        boolean visible = filePanelToggle.isSelected();
        filePanelWrapper.setVisible(visible);
        filePanelToggle.setText(JMeterUtils.getResString(
                visible ? "view_results_file_panel_hide" : "view_results_file_panel_show")); //$NON-NLS-1$ //$NON-NLS-2$
        filePanelToggle.setToolTipText(JMeterUtils.getResString(
                visible ? "view_results_file_panel_hide_tooltip" : "view_results_file_panel_show_tooltip")); //$NON-NLS-1$ //$NON-NLS-2$
        revalidateFilePanel();
    }

    private void revalidateFilePanel() {
        Component target = filePanelWrapper.getParent() == null ? filePanelWrapper : filePanelWrapper.getParent();
        target.revalidate();
        target.repaint();
    }

    private void toggleDetached() {
        if (detachedWindow == null) {
            detachReport();
        } else {
            dockReport(true);
        }
    }

    private void detachReport() {
        if (detachedWindow != null) {
            return;
        }
        remove(visualizerPanel);
        detachedPlaceholder = createDetachedPlaceholder();
        add(detachedPlaceholder, BorderLayout.CENTER);

        detachedWindow = new JFrame(detachedWindowTitle());
        detachedWindow.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        detachedWindow.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                dockReport(true);
            }
        });
        detachedWindowContent = new JPanel(new BorderLayout());
        detachedWindowContent.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        detachedWindowContent.add(visualizerPanel, BorderLayout.CENTER);
        detachedWindow.getContentPane().add(detachedWindowContent, BorderLayout.CENTER);
        installDetachedWindowShortcuts();
        detachedWindow.setSize(detachedWindowSize());
        GuiPackage guiPackage = GuiPackage.getInstance();
        detachedWindow.setLocationRelativeTo(guiPackage == null ? null : guiPackage.getMainFrame());
        detachedWindow.setVisible(true);

        updateDetachButton();
        revalidate();
        repaint();
    }

    private void dockReport(boolean selectVisualizer) {
        if (detachedWindow == null) {
            return;
        }

        JFrame window = detachedWindow;
        detachedWindow = null;
        if (detachedWindowContent != null) {
            detachedWindowContent.remove(visualizerPanel);
            detachedWindowContent = null;
        } else {
            window.getContentPane().remove(visualizerPanel);
        }
        window.dispose();

        if (detachedPlaceholder != null) {
            remove(detachedPlaceholder);
            detachedPlaceholder = null;
        }
        add(visualizerPanel, BorderLayout.CENTER);
        updateDetachButton();
        revalidate();
        repaint();

        if (selectVisualizer) {
            selectConfiguredVisualizerNode();
        }
    }

    private void installDetachedWindowShortcuts() {
        InputMap inputMap = detachedWindow.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = detachedWindow.getRootPane().getActionMap();
        installDetachedWindowAction(inputMap, actionMap, KeyStrokes.CLEAR_ALL, ActionNames.CLEAR_ALL);
        inputMap.put(KeyStrokes.CLEAR, ActionNames.CLEAR);
        actionMap.put(ActionNames.CLEAR, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                clearData();
            }
        });
        installDetachedWindowAction(inputMap, actionMap, KeyStrokes.ACTION_START, ActionNames.ACTION_START);
        installDetachedWindowAction(inputMap, actionMap, KeyStrokes.VALIDATE, ActionNames.VALIDATE_TG);
        installDetachedWindowAction(inputMap, actionMap, KeyStrokes.ACTION_START_NO_PAUSE,
                ActionNames.ACTION_START_NO_TIMERS);
        installDetachedWindowAction(inputMap, actionMap, KeyStrokes.ACTION_STOP, ActionNames.ACTION_STOP);
        installDetachedWindowAction(inputMap, actionMap, KeyStrokes.ACTION_SHUTDOWN, ActionNames.ACTION_SHUTDOWN);
    }

    private void installDetachedWindowAction(InputMap inputMap, ActionMap actionMap,
            KeyStroke keyStroke, String actionName) {
        inputMap.put(keyStroke, actionName);
        actionMap.put(actionName, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                ActionRouter.getInstance().actionPerformed(
                        new ActionEvent(detachedWindow, event.getID(), actionName));
            }
        });
    }

    private Component createDetachedPlaceholder() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        JLabel message = new JLabel(JMeterUtils.getResString("performance_report_detached_message")); //$NON-NLS-1$
        message.setHorizontalAlignment(SwingConstants.CENTER);
        JButton dockButton = new JButton(JMeterUtils.getResString("view_results_dock")); //$NON-NLS-1$
        dockButton.addActionListener(e -> dockReport(true));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        buttonPanel.add(dockButton);
        panel.add(message, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        panel.setBorder(BorderFactory.createEmptyBorder(30, 30, 30, 30));
        return panel;
    }

    private Dimension detachedWindowSize() {
        return new Dimension(Math.max(getWidth(), 900), Math.max(getHeight(), 650));
    }

    private void updateDetachButton() {
        if (detachButton == null) {
            return;
        }
        if (detachedWindow == null) {
            detachButton.setText(JMeterUtils.getResString("view_results_detach")); //$NON-NLS-1$
            detachButton.setToolTipText(JMeterUtils.getResString("performance_report_detach_tooltip")); //$NON-NLS-1$
        } else {
            detachButton.setText(JMeterUtils.getResString("view_results_dock")); //$NON-NLS-1$
            detachButton.setToolTipText(JMeterUtils.getResString("performance_report_dock_tooltip")); //$NON-NLS-1$
        }
    }

    private String detachedWindowTitle() {
        String name = getName().trim();
        String title = JMeterUtils.getResString("performance_report"); //$NON-NLS-1$
        return name.isEmpty() || name.equals(title) ? title : name + " - " + title; //$NON-NLS-1$
    }

    private void updateDetachedWindowTitle() {
        if (detachedWindow != null) {
            detachedWindow.setTitle(detachedWindowTitle());
        }
    }

    private void selectConfiguredVisualizerNode() {
        if (configuredElement == null) {
            return;
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null || guiPackage.getTreeListener() == null) {
            return;
        }
        jumpToTestPlanElement(guiPackage.getNodeOf(configuredElement));
    }

    private void jumpToReportRowOnDoubleClick(MouseEvent event) {
        if (event.getClickCount() != 2 || event.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        int viewRow = table.rowAtPoint(event.getPoint());
        int viewColumn = table.columnAtPoint(event.getPoint());
        if (viewRow < 0 || viewColumn < 0 || table.convertColumnIndexToModel(viewColumn) != LABEL_COLUMN) {
            return;
        }
        table.setRowSelectionInterval(viewRow, viewRow);
        jumpToTestPlanElement(findTestPlanNode(rowDataAt(viewRow)));
    }

    private void showReportTablePopup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int viewRow = table.rowAtPoint(event.getPoint());
        int viewColumn = table.columnAtPoint(event.getPoint());
        if (viewRow < 0 || viewColumn < 0 || table.convertColumnIndexToModel(viewColumn) != LABEL_COLUMN) {
            return;
        }
        table.setRowSelectionInterval(viewRow, viewRow);
        JMeterTreeNode testPlanNode = findTestPlanNode(rowDataAt(viewRow));
        JMenuItem jumpTo = new JMenuItem("Jump to"); //$NON-NLS-1$
        jumpTo.setEnabled(testPlanNode != null);
        jumpTo.addActionListener(e -> jumpToTestPlanElement(testPlanNode));

        JPopupMenu popup = new JPopupMenu();
        popup.add(jumpTo);
        popup.show(table, event.getX(), event.getY());
    }

    private PerformanceReportData rowDataAt(int viewRow) {
        int modelRow = table.convertRowIndexToModel(viewRow);
        return (PerformanceReportData) model.getObjectListAsList().get(modelRow);
    }

    private static JMeterTreeNode findTestPlanNode(PerformanceReportData row) {
        if (row == null || row.getSourceTestElementPath().isEmpty()) {
            return null;
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return null;
        }
        List<SampleResult.TestElementPathEntry> sourcePath = row.getSourceTestElementPath();
        JMeterTreeNode current = findDescendant((JMeterTreeNode) guiPackage.getTreeModel().getRoot(), sourcePath.get(0));
        for (SampleResult.TestElementPathEntry pathEntry : sourcePath.subList(1, sourcePath.size())) {
            current = findChild(current, pathEntry);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static JMeterTreeNode findDescendant(JMeterTreeNode parent, SampleResult.TestElementPathEntry pathEntry) {
        JMeterTreeNode child = findChild(parent, pathEntry);
        if (child != null) {
            return child;
        }
        Enumeration<?> children = parent.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode descendant = findDescendant((JMeterTreeNode) children.nextElement(), pathEntry);
            if (descendant != null) {
                return descendant;
            }
        }
        return null;
    }

    private static JMeterTreeNode findChild(JMeterTreeNode parent, SampleResult.TestElementPathEntry pathEntry) {
        if (parent == null) {
            return null;
        }
        int occurrence = 0;
        Enumeration<?> children = parent.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            Object userObject = child.getUserObject();
            if (userObject != null
                    && userObject.getClass().getName().equals(pathEntry.className())
                    && Objects.equals(child.getName(), pathEntry.name())) {
                if (occurrence == pathEntry.occurrence()) {
                    return child;
                }
                occurrence++;
            }
        }
        return null;
    }

    private static void jumpToTestPlanElement(JMeterTreeNode testPlanNode) {
        if (testPlanNode == null) {
            return;
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null || guiPackage.getTreeListener() == null) {
            return;
        }
        JTree testPlanTree = guiPackage.getTreeListener().getJTree();
        if (testPlanTree == null) {
            return;
        }
        TreePath testPlanPath = new TreePath(testPlanNode.getPath());
        testPlanTree.setSelectionPath(testPlanPath);
        testPlanTree.scrollPathToVisible(testPlanPath);
        testPlanTree.requestFocusInWindow();
    }

    private JTable createTable() {
        return new JTable(model) {
            private static final long serialVersionUID = 242L;

            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                if (isCellSelected(row, column)) {
                    return component;
                }
                int modelRow = convertRowIndexToModel(row);
                Number errorCount = (Number) getModel().getValueAt(modelRow, ERROR_COUNT_COLUMN);
                String label = (String) getModel().getValueAt(modelRow, LABEL_COLUMN);
                boolean hasErrors = !TOTAL_ROW_LABEL.equals(label) && errorCount != null && errorCount.longValue() > 0;
                component.setForeground(hasErrors ? ERROR_FOREGROUND : getForeground());
                if (isErrorHighlightActive(label, System.currentTimeMillis())) {
                    component.setBackground(ERROR_HIGHLIGHT_BACKGROUND);
                } else {
                    component.setBackground(getBackground());
                }
                return component;
            }
        };
    }

    private void saveTable() {
        JFileChooser chooser = FileDialoger.promptToSaveFile("performance-report.csv"); //$NON-NLS-1$
        if (chooser == null) {
            return;
        }
        try (FileOutputStream output = new FileOutputStream(chooser.getSelectedFile());
                OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            CSVSaveService.saveCSVStats(getVisibleTableData(), writer,
                    saveHeaders.isSelected() ? getVisibleLabels() : null);
        } catch (IOException e) {
            JMeterUtils.reportErrorToUser(e.getMessage(), "Error saving data"); //$NON-NLS-1$
        }
    }

    private List<List<Object>> getVisibleTableData() {
        List<List<Object>> data = new ArrayList<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            List<Object> rowData = new ArrayList<>();
            data.add(rowData);
            for (int viewColumn = 0; viewColumn < table.getColumnCount(); viewColumn++) {
                int modelColumn = table.convertColumnIndexToModel(viewColumn);
                Object value = model.getValueAt(row, modelColumn);
                Format formatter = COLUMNS[modelColumn].format;
                rowData.add(value == null ? "" : formatter == null ? value : formatter.format(value)); //$NON-NLS-1$
            }
        }
        return data;
    }

    private String[] getVisibleLabels() {
        String[] labels = new String[table.getColumnCount()];
        for (int viewColumn = 0; viewColumn < table.getColumnCount(); viewColumn++) {
            labels[viewColumn] = model.getColumnName(table.convertColumnIndexToModel(viewColumn));
        }
        return labels;
    }

    private void showColumnConfigurationDialog() {
        JCheckBox[] checkBoxes = new JCheckBox[COLUMNS.length];
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(JMeterUtils.getResString("performance_resource_note")), BorderLayout.NORTH); //$NON-NLS-1$

        JPanel columnPanel = new JPanel(new GridLayout(0, 2));
        for (int i = 0; i < COLUMNS.length; i++) {
            checkBoxes[i] = new JCheckBox(getColumnConfigurationLabel(i), selectedColumns[i]);
            if (i == LABEL_COLUMN) {
                checkBoxes[i].setEnabled(false);
            }
            columnPanel.add(checkBoxes[i]);
        }
        panel.add(columnPanel, BorderLayout.CENTER);
        panel.add(new JLabel(JMeterUtils.getResString("performance_resource_tracking_note")), BorderLayout.SOUTH); //$NON-NLS-1$

        int result = JOptionPane.showConfirmDialog(this, panel,
                JMeterUtils.getResString("performance_configure_columns_title"), //$NON-NLS-1$
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        for (int i = 0; i < checkBoxes.length; i++) {
            selectedColumns[i] = i == LABEL_COLUMN || checkBoxes[i].isSelected();
        }
        applyResponseTimeDistributionTracking();
        applySelectedColumns();
    }

    private static String getColumnConfigurationLabel(int column) {
        String label = getLabels()[column];
        if (COLUMNS[column].resourceIntensive) {
            return label + " (" + JMeterUtils.getResString("performance_resource_cost") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return label;
    }

    private void applySelectedColumns() {
        TableColumnModel columnModel = table.getColumnModel();
        for (int modelColumn = 0; modelColumn < tableColumns.length; modelColumn++) {
            boolean shouldShow = selectedColumns[modelColumn];
            boolean isVisible = isColumnVisible(columnModel, tableColumns[modelColumn]);
            if (shouldShow && !isVisible) {
                columnModel.addColumn(tableColumns[modelColumn]);
                columnModel.moveColumn(columnModel.getColumnCount() - 1, countVisibleColumnsBefore(modelColumn));
            } else if (!shouldShow && isVisible) {
                columnModel.removeColumn(tableColumns[modelColumn]);
            }
        }
    }

    private void applyResponseTimeDistributionTracking() {
        boolean trackDistribution = shouldTrackResponseTimeDistribution();
        for (PerformanceReportData row : tableRows.values()) {
            row.setTrackResponseTimeDistribution(trackDistribution);
        }
        dataChanged = true;
    }

    private boolean shouldTrackResponseTimeDistribution() {
        return shouldTrackResponseTimeDistribution(selectedColumns);
    }

    private static boolean shouldTrackResponseTimeDistribution(boolean[] columns) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i] && COLUMNS[i].resourceIntensive) {
                return true;
            }
        }
        return false;
    }

    private static TableColumn[] captureTableColumns(JTable table) {
        TableColumnModel columnModel = table.getColumnModel();
        TableColumn[] columns = new TableColumn[columnModel.getColumnCount()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = columnModel.getColumn(i);
        }
        return columns;
    }

    private static boolean isColumnVisible(TableColumnModel columnModel, TableColumn column) {
        for (int i = 0; i < columnModel.getColumnCount(); i++) {
            if (columnModel.getColumn(i) == column) {
                return true;
            }
        }
        return false;
    }

    private int countVisibleColumnsBefore(int modelColumn) {
        int count = 0;
        for (int i = 0; i < modelColumn; i++) {
            if (selectedColumns[i]) {
                count++;
            }
        }
        return count;
    }

    private void highlightErrorIncrease(String rowLabel) {
        errorHighlightEndTimes.put(rowLabel, System.currentTimeMillis() + ERROR_HIGHLIGHT_DURATION_MILLIS);
    }

    private void applyIgnoreErrorResponseTimes() {
        boolean selected = ignoreErrorResponseTimes.isSelected();
        for (PerformanceReportData row : tableRows.values()) {
            row.setIgnoreErrorResponseTimes(selected);
        }
        dataChanged = true;
    }

    private boolean updateErrorHighlightState(long now) {
        boolean hadHighlights = !errorHighlightEndTimes.isEmpty();
        errorHighlightEndTimes.values().removeIf(expiresAt -> expiresAt <= now);
        return hadHighlights;
    }

    private boolean isErrorHighlightActive(String rowLabel, long now) {
        Long expiresAt = errorHighlightEndTimes.get(rowLabel);
        return expiresAt != null && expiresAt > now;
    }

    private String encodeSelectedColumns() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < selectedColumns.length; i++) {
            if (selectedColumns[i]) {
                if (result.length() > 0) {
                    result.append(',');
                }
                result.append(COLUMNS[i].key);
            }
        }
        return result.toString();
    }

    private static String defaultEncodedColumns() {
        boolean[] defaults = defaultSelectedColumns();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < defaults.length; i++) {
            if (defaults[i]) {
                if (result.length() > 0) {
                    result.append(',');
                }
                result.append(COLUMNS[i].key);
            }
        }
        return result.toString();
    }

    private static boolean[] decodeSelectedColumns(String encodedColumns) {
        boolean[] result = new boolean[COLUMNS.length];
        String[] keys = encodedColumns.split(","); //$NON-NLS-1$
        for (String key : keys) {
            for (int i = 0; i < COLUMNS.length; i++) {
                if (COLUMNS[i].key.equals(key)) {
                    result[i] = true;
                    break;
                }
            }
        }
        result[LABEL_COLUMN] = true;
        return result;
    }

    private static boolean[] defaultSelectedColumns() {
        boolean[] defaults = new boolean[COLUMNS.length];
        for (int i = 0; i < COLUMNS.length; i++) {
            String key = COLUMNS[i].key;
            defaults[i] = "sampler_label".equals(key) //$NON-NLS-1$
                    || "aggregate_report_count".equals(key) //$NON-NLS-1$
                    || "average".equals(key) //$NON-NLS-1$
                    || "aggregate_report_min".equals(key) //$NON-NLS-1$
                    || "performance_report_max".equals(key) //$NON-NLS-1$
                    || "aggregate_report_error_count".equals(key) //$NON-NLS-1$
                    || "aggregate_report_error%".equals(key) //$NON-NLS-1$
                    || "performance_report_rate".equals(key) //$NON-NLS-1$
                    || "aggregate_report_bandwidth".equals(key) //$NON-NLS-1$
                    || "aggregate_report_sent_bytes_per_sec".equals(key); //$NON-NLS-1$
        }
        return defaults;
    }

    private static String[] getLabels() {
        String[] labels = new String[COLUMNS.length];
        for (int i = 0; i < COLUMNS.length; i++) {
            labels[i] = MessageFormat.format(JMeterUtils.getResString(COLUMNS[i].key), COLUMNS[i].messageParameters);
        }
        return labels;
    }

    private static Functor[] getReadFunctors() {
        Functor[] functors = new Functor[COLUMNS.length];
        for (int i = 0; i < COLUMNS.length; i++) {
            functors[i] = COLUMNS[i].functorParameters == null
                    ? new Functor(COLUMNS[i].functor)
                    : new Functor(COLUMNS[i].functor, COLUMNS[i].functorParameters);
        }
        return functors;
    }

    private static Functor[] getWriteFunctors() {
        return new Functor[COLUMNS.length];
    }

    private static Class<?>[] getColumnClasses() {
        Class<?>[] classes = new Class[COLUMNS.length];
        for (int i = 0; i < COLUMNS.length; i++) {
            classes[i] = COLUMNS[i].columnClass;
        }
        return classes;
    }

    private static TableCellRenderer[] createRenderers() {
        TableCellRenderer[] renderers = new TableCellRenderer[COLUMNS.length];
        for (int i = 0; i < COLUMNS.length; i++) {
            String key = COLUMNS[i].key;
            if ("performance_report_stddev".equals(key)) { //$NON-NLS-1$
                renderers[i] = new NumberRenderer("#0.00"); //$NON-NLS-1$
            } else if ("aggregate_report_error_count".equals(key)) { //$NON-NLS-1$
                renderers[i] = new NumberRenderer("#0"); //$NON-NLS-1$
            } else if ("aggregate_report_error%".equals(key)) { //$NON-NLS-1$
                renderers[i] = new NumberRenderer("#0.00%"); //$NON-NLS-1$
            } else if ("performance_report_rate".equals(key)) { //$NON-NLS-1$
                renderers[i] = new EmptyRateRenderer("#.0"); //$NON-NLS-1$
            } else if ("aggregate_report_bandwidth".equals(key) //$NON-NLS-1$
                    || "aggregate_report_sent_bytes_per_sec".equals(key) //$NON-NLS-1$
                    || "performance_report_sum_received_kb".equals(key) //$NON-NLS-1$
                    || "performance_report_sum_sent_kb".equals(key)) { //$NON-NLS-1$
                renderers[i] = new NumberRenderer("#0.00"); //$NON-NLS-1$
            } else if ("average_bytes".equals(key)) { //$NON-NLS-1$
                renderers[i] = new NumberRenderer("#.0"); //$NON-NLS-1$
            }
        }
        return renderers;
    }

    public static final class PerformanceReportData {
        private static final double BYTES_PER_KB = 1024.0;

        private final String label;
        private final BasicLongStats allResponseTimes = new BasicLongStats();
        private final BasicLongStats successfulResponseTimes = new BasicLongStats();
        private final BasicLongStats allConnectTimes = new BasicLongStats();
        private final BasicLongStats successfulConnectTimes = new BasicLongStats();

        private long count;
        private long errorCount;
        private long totalBytes;
        private long totalSentBytes;
        private long firstTime = Long.MAX_VALUE;
        private long endTime;
        private List<SampleResult.TestElementPathEntry> sourceTestElementPath = List.of();
        private StatCalculatorLong allResponseTimeDistribution;
        private StatCalculatorLong successfulResponseTimeDistribution;
        private volatile boolean ignoreErrorResponseTimes;
        private boolean trackResponseTimeDistribution;

        public PerformanceReportData() {
            this(""); //$NON-NLS-1$
        }

        public PerformanceReportData(String label) {
            this.label = label;
        }

        void setIgnoreErrorResponseTimes(boolean ignoreErrorResponseTimes) {
            this.ignoreErrorResponseTimes = ignoreErrorResponseTimes;
        }

        synchronized void setTrackResponseTimeDistribution(boolean trackResponseTimeDistribution) {
            if (this.trackResponseTimeDistribution == trackResponseTimeDistribution) {
                return;
            }
            this.trackResponseTimeDistribution = trackResponseTimeDistribution;
            if (trackResponseTimeDistribution) {
                allResponseTimeDistribution = new StatCalculatorLong();
                successfulResponseTimeDistribution = new StatCalculatorLong();
            } else {
                allResponseTimeDistribution = null;
                successfulResponseTimeDistribution = null;
            }
        }

        synchronized void addSample(SampleResult result) {
            int sampleCount = result.getSampleCount();
            count += sampleCount;
            errorCount += result.getErrorCount();
            totalBytes += result.getBytesAsLong();
            totalSentBytes += result.getSentBytes();
            if (sourceTestElementPath.isEmpty() && !result.getSourceTestElementPath().isEmpty()) {
                sourceTestElementPath = result.getSourceTestElementPath();
            }
            allResponseTimes.addValue(result.getTime(), sampleCount);
            if (allResponseTimeDistribution != null) {
                allResponseTimeDistribution.addValue(result.getTime(), sampleCount);
            }
            long connectTime = result.getConnectTime();
            if (connectTime > 0) {
                allConnectTimes.addValue(connectTime, sampleCount);
            }
            if (result.isSuccessful()) {
                successfulResponseTimes.addValue(result.getTime(), sampleCount);
                if (successfulResponseTimeDistribution != null) {
                    successfulResponseTimeDistribution.addValue(result.getTime(), sampleCount);
                }
                if (connectTime > 0) {
                    successfulConnectTimes.addValue(connectTime, sampleCount);
                }
            }
            firstTime = Math.min(firstTime, result.getStartTime());
            endTime = Math.max(endTime, result.getEndTime());
        }

        public String getLabel() {
            return label;
        }

        public Long getCount() {
            return count;
        }

        public Long getErrorCount() {
            return count == 0 ? null : errorCount;
        }

        long getRawErrorCount() {
            return errorCount;
        }

        List<SampleResult.TestElementPathEntry> getSourceTestElementPath() {
            return sourceTestElementPath;
        }

        public Double getErrorPercentage() {
            return count == 0 ? null : errorCount / (double) count;
        }

        public Number getMeanAsNumber() {
            BasicLongStats stats = responseTimeStats();
            return stats.getCount() == 0 ? null : (long) stats.getMean();
        }

        public Number getPercentPoint(Float percent) {
            StatCalculatorLong stats = responseTimeDistribution();
            return stats == null || stats.getCount() == 0 ? null : stats.getPercentPoint(percent);
        }

        public Number getMin() {
            BasicLongStats stats = responseTimeStats();
            return stats.getCount() == 0 ? null : stats.getMin();
        }

        public Number getMax() {
            BasicLongStats stats = responseTimeStats();
            return stats.getCount() == 0 ? null : stats.getMax();
        }

        public Double getStandardDeviation() {
            BasicLongStats stats = responseTimeStats();
            return stats.getCount() == 0 ? null : stats.getStandardDeviation();
        }

        public Double getRate() {
            if (count == 0) {
                return null;
            }
            return getRatePerSecond(count);
        }

        public Double getKBPerSecond() {
            if (count == 0) {
                return null;
            }
            return getRatePerSecond(totalBytes) / BYTES_PER_KB;
        }

        public Double getSentKBPerSecond() {
            if (count == 0) {
                return null;
            }
            return getRatePerSecond(totalSentBytes) / BYTES_PER_KB;
        }

        public Double getTotalKB() {
            return count == 0 ? null : totalBytes / BYTES_PER_KB;
        }

        public Double getTotalSentKB() {
            return count == 0 ? null : totalSentBytes / BYTES_PER_KB;
        }

        public Double getAvgPageBytes() {
            return count == 0 ? null : totalBytes / (double) count;
        }

        public Number getAverageConnectTime() {
            BasicLongStats stats = connectTimeStats();
            return stats.getCount() == 0 ? null : (long) stats.getMean();
        }

        private BasicLongStats responseTimeStats() {
            return ignoreErrorResponseTimes ? successfulResponseTimes : allResponseTimes;
        }

        private StatCalculatorLong responseTimeDistribution() {
            return ignoreErrorResponseTimes
                    ? successfulResponseTimeDistribution
                    : allResponseTimeDistribution;
        }

        private BasicLongStats connectTimeStats() {
            return ignoreErrorResponseTimes ? successfulConnectTimes : allConnectTimes;
        }

        private double getRatePerSecond(long value) {
            long elapsed = endTime - firstTime;
            if (elapsed <= 0) {
                return 0.0;
            }
            return value / (elapsed / 1000.0);
        }
    }

    private static final class EmptyRateRenderer extends RateRenderer {
        private static final long serialVersionUID = 242L;

        private EmptyRateRenderer(String format) {
            super(format);
        }

        @Override
        public void setValue(Object value) {
            if (value == null) {
                setText(""); //$NON-NLS-1$
                return;
            }
            super.setValue(value);
        }
    }

    private static final class BasicLongStats {
        private long count;
        private double sum;
        private double sumOfSquares;
        private double mean;
        private double standardDeviation;
        private long min = Long.MAX_VALUE;
        private long max = Long.MIN_VALUE;

        private void addValue(long value, int sampleCount) {
            count += sampleCount;
            double doubleValue = value;
            sum += doubleValue;
            long actualValue = value;
            if (sampleCount > 1) {
                sumOfSquares += doubleValue * doubleValue / sampleCount;
                actualValue = value / sampleCount;
            } else {
                sumOfSquares += doubleValue * doubleValue;
            }
            mean = sum / count;
            standardDeviation = Math.sqrt((sumOfSquares / count) - (mean * mean));
            max = Math.max(max, actualValue);
            min = Math.min(min, actualValue);
        }

        private long getCount() {
            return count;
        }

        private double getMean() {
            return mean;
        }

        private long getMin() {
            return min;
        }

        private long getMax() {
            return max;
        }

        private double getStandardDeviation() {
            return standardDeviation;
        }
    }

    private static final class ColumnDefinition {
        private final String key;
        private final Object[] messageParameters;
        private final String functor;
        private final Class<?> columnClass;
        private final Format format;
        private final Object[] functorParameters;
        private final boolean resourceIntensive;

        private ColumnDefinition(String key, Object[] messageParameters, String functor,
                Class<?> columnClass, Format format, Object[] functorParameters) {
            this(key, messageParameters, functor, columnClass, format, functorParameters, false);
        }

        private ColumnDefinition(String key, Object[] messageParameters, String functor,
                Class<?> columnClass, Format format, Object[] functorParameters, boolean resourceIntensive) {
            this.key = key;
            this.messageParameters = messageParameters;
            this.functor = functor;
            this.columnClass = columnClass;
            this.format = format;
            this.functorParameters = functorParameters;
            this.resourceIntensive = resourceIntensive;
        }
    }
}
