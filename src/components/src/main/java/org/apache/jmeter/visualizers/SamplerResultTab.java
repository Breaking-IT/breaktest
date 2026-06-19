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
import java.awt.Insets;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.text.BadLocationException;
import javax.swing.text.Caret;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Document;
import javax.swing.text.EditorKit;
import javax.swing.text.Element;
import javax.swing.text.PlainView;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.gui.util.HeaderAsPropertyRenderer;
import org.apache.jmeter.gui.util.JSyntaxSearchToolBar;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.gui.util.JTextScrollPane;
import org.apache.jmeter.gui.util.TextBoxDialoger.TextBoxDoubleClick;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.SearchTextExtension.JEditorPaneSearchProvider;
import org.apache.jorphan.gui.GuiUtils;
import org.apache.jorphan.gui.ObjectTableModel;
import org.apache.jorphan.gui.RendererUtils;
import org.apache.jorphan.gui.ui.KerningOptimizer;
import org.apache.jorphan.reflect.Functor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Right side in View Results Tree
 *
 */
public abstract class SamplerResultTab implements ResultRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SamplerResultTab.class);
    // N.B. these are not multi-threaded, so don't make it static
    private final DateTimeFormatter dateFormat = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS z")  // ISO format $NON-NLS-1$
            .withZone(ZoneId.systemDefault());

    private static final String NL = "\n"; // $NON-NLS-1$

    private static final String SPACE = " "; // $NON-NLS-1$

    public static final Color SERVER_ERROR_COLOR = Color.red;

    public static final Color CLIENT_ERROR_COLOR = Color.blue;

    public static final Color REDIRECT_COLOR = Color.green;

    protected static final String TEXT_COMMAND = "text"; // $NON-NLS-1$

    protected static final String REQUEST_VIEW_COMMAND = "change_request_view"; // $NON-NLS-1$

    private static final String STYLE_SERVER_ERROR = "ServerError"; // $NON-NLS-1$

    private static final String STYLE_CLIENT_ERROR = "ClientError"; // $NON-NLS-1$

    private static final String STYLE_REDIRECT = "Redirect"; // $NON-NLS-1$

    private static final int SIMPLE_VIEW_LIMIT =
            JMeterUtils.getPropDefault("view.results.tree.simple_view_limit", 10_000); // $NON-NLS-1$

    private JTextPane stats;

    /** Response Data pane */
    private JPanel resultsPane;

    private JPanel cookiesPane;

    private JPanel variablesPane;

    /** Contains results; contained in resultsPane */
    protected JScrollPane resultsScrollPane;

    private JSyntaxTextArea responseData;
    /** Response Data shown here */
    protected JEditorPane results;

    private JLabel imageLabel;

    /** request pane content */
    private RequestPanel requestPanel;

    /** holds the tabbed panes */
    protected JTabbedPane rightSide;

    private int lastSelectedTab;

    private Object userObject = null; // Could be SampleResult or AssertionResult

    private SampleResult sampleResult = null;

    private AssertionResult assertionResult = null;

    protected SearchTextExtension searchTextExtension;

    protected boolean activateSearchExtension = true; // most current subclasses can process text

    private Color backGround;

    private static final String[] COLUMNS_RESULT = new String[] {
            " ", // one space for blank header // $NON-NLS-1$
            " " }; // one space for blank header  // $NON-NLS-1$

    private static final String[] COLUMNS_HEADERS = new String[] {
            "view_results_table_headers_key", // $NON-NLS-1$
            "view_results_table_headers_value" }; // $NON-NLS-1$

    private static final String[] COLUMNS_FIELDS = new String[] {
            "view_results_table_fields_key", // $NON-NLS-1$
            "view_results_table_fields_value" }; // $NON-NLS-1$

    private static final String[] COLUMNS_COOKIES = new String[] {
            "view_results_table_cookie_name", // $NON-NLS-1$
            "view_results_table_cookie_value" }; // $NON-NLS-1$

    private static final String[] COLUMNS_VARIABLES = new String[] {
            "view_results_table_variable_name", // $NON-NLS-1$
            "view_results_table_variable_value" }; // $NON-NLS-1$

    private final ObjectTableModel resultModel;

    private final ObjectTableModel resHeadersModel;

    private final ObjectTableModel resFieldsModel;

    private final ObjectTableModel cookiesModel;

    private final ObjectTableModel variablesModel;

    private JTable tableResult = null;

    private JTable tableResHeaders = null;

    private JTable tableResFields = null;

    private JTabbedPane tabbedResult = null;

    private JScrollPane paneRaw = null;

    private JSplitPane paneParsed = null;

    // to save last select tab (raw/parsed)
    private int lastResultTabIndex= 0;

    // Result column renderers
    private static final TableCellRenderer[] RENDERERS_RESULT = new TableCellRenderer[] {
            null, // Key
            null, // Value
    };

    // Response headers column renderers
    private static final TableCellRenderer[] RENDERERS_HEADERS = new TableCellRenderer[] {
            null, // Key
            null, // Value
    };

    // Response fields column renderers
    private static final TableCellRenderer[] RENDERERS_FIELDS = new TableCellRenderer[] {
            null, // Key
            null, // Value
    };

    protected SamplerResultTab() {
        // create tables
        resultModel = new ObjectTableModel(COLUMNS_RESULT, RowResult.class, // The object used for each row
                new Functor[] {
                        new Functor("getKey"), // $NON-NLS-1$
                        new Functor("getValue") }, // $NON-NLS-1$
                new Functor[] {
                        null, null }, new Class[] {
                        String.class, String.class }, false);
        resHeadersModel = new ObjectTableModel(COLUMNS_HEADERS,
                RowResult.class, // The object used for each row
                new Functor[] {
                        new Functor("getKey"), // $NON-NLS-1$
                        new Functor("getValue") }, // $NON-NLS-1$
                new Functor[] {
                        null, null }, new Class[] {
                        String.class, String.class }, false);
        resFieldsModel = new ObjectTableModel(COLUMNS_FIELDS, RowResult.class, // The object used for each row
                new Functor[] {
                        new Functor("getKey"), // $NON-NLS-1$
                        new Functor("getValue") }, // $NON-NLS-1$
                new Functor[] {
                        null, null }, new Class[] {
                        String.class, String.class }, false);
        cookiesModel = createKeyValueTableModel(COLUMNS_COOKIES);
        variablesModel = createKeyValueTableModel(COLUMNS_VARIABLES);
    }

    @Override
    public void clearData() {
        responseData.setInitialText(""); // $NON-NLS-1$
        results.setText("");// Response Data // $NON-NLS-1$
        requestPanel.clearData();// Request Data // $NON-NLS-1$
        stats.setText(""); // Sampler result // $NON-NLS-1$
        resultModel.clearData();
        resHeadersModel.clearData();
        resFieldsModel.clearData();
        cookiesModel.clearData();
        variablesModel.clearData();
    }

    @Override
    public void init() {
        rightSide.addTab(
                JMeterUtils.getResString("view_results_tab_sampler"), createResponseMetadataPanel()); // $NON-NLS-1$
        // Create the panels for the other tabs
        requestPanel = new RequestPanel();
        resultsPane = createResponseDataPanel();
        cookiesPane = createTablePanel(cookiesModel);
        variablesPane = createTablePanel(variablesModel);
    }

    @Override
    public void setupTabPane() {
        // Clear all data before display a new sample. The active tab is filled lazily below.
        this.clearData();
        sampleResult = null;
        assertionResult = null;
        if (userObject instanceof SampleResult result) {
            sampleResult = result;
            setupTabPaneForSampleResult();
        } else if (userObject instanceof AssertionResult result) {
            assertionResult = result;
            setupTabPaneForAssertionResult();
        }
    }

    @Override
    public void renderSelectedTab() {
        try {
            if (sampleResult != null) {
                renderSelectedSampleTab();
            } else if (assertionResult != null && isSelectedTopLevelTab("view_results_tab_assertion")) { // $NON-NLS-1$
                populateAssertionResult(assertionResult);
            }
        } catch (BadLocationException exc) {
            stats.setText(exc.getLocalizedMessage());
        }
    }

    private void renderSelectedSampleTab() throws BadLocationException {
        if (isSelectedTopLevelTab("view_results_tab_sampler")) { // $NON-NLS-1$
            populateSamplerResult(sampleResult);
        } else if (isSelectedTopLevelTab("view_results_tab_request")) { // $NON-NLS-1$
            requestPanel.setSamplerResult(sampleResult);
        } else if (isSelectedTopLevelTab("view_results_request_cookies")) { // $NON-NLS-1$
            populateCookies(sampleResult);
        } else if (isSelectedTopLevelTab("view_results_variables")) { // $NON-NLS-1$
            populateVariables(sampleResult);
        } else if (isSelectedTopLevelTab("view_results_tab_response")) { // $NON-NLS-1$
            setResponseDataText(sampleResult.getResponseHeaders(), null);
        }
        if (activateSearchExtension) {
            searchTextExtension.resetTextToFind();
        }
    }

    @SuppressWarnings({"boxing", "JdkObsolete"})
    private void populateSamplerResult(SampleResult result) throws BadLocationException {
        stats.setText(""); // $NON-NLS-1$
        resultModel.clearData();
        resHeadersModel.clearData();
        resFieldsModel.clearData();

        StyledDocument statsDoc = stats.getStyledDocument();
        final String samplerClass = result.getClass().getName();
        String typeResult = samplerClass.substring(1 + samplerClass.lastIndexOf('.'));

        StringBuilder statsBuff = new StringBuilder(200);
        statsBuff
                .append(JMeterUtils.getResString("view_results_thread_name")).append(SPACE) //$NON-NLS-1$
                .append(result.getThreadName()).append(NL);
        String startTime = dateFormat.format(Instant.ofEpochMilli(result.getStartTime()));
        statsBuff
                .append(JMeterUtils.getResString("view_results_sample_start")).append(SPACE) //$NON-NLS-1$
                .append(startTime).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_load_time")).append(SPACE) //$NON-NLS-1$
                .append(result.getTime()).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_connect_time")).append(SPACE) //$NON-NLS-1$
                .append(result.getConnectTime()).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_latency")).append(SPACE) //$NON-NLS-1$
                .append(result.getLatency()).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_size_in_bytes")).append(SPACE) //$NON-NLS-1$
                .append(formatSizeInBytes(result)).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_sent_bytes")).append(SPACE) //$NON-NLS-1$
                .append(result.getSentBytes()).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_size_headers_in_bytes")).append(SPACE) //$NON-NLS-1$
                .append(result.getHeadersSize()).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_size_body_in_bytes")).append(SPACE) //$NON-NLS-1$
                .append(result.getBodySizeAsLong()).append(NL);
        String networkEndpoint = result.getNetworkEndpoint();
        if (!networkEndpoint.isEmpty()) {
            statsBuff
                    .append(JMeterUtils.getResString("view_results_network")).append(SPACE) //$NON-NLS-1$
                    .append(networkEndpoint).append(NL);
        }
        statsBuff
                .append(JMeterUtils.getResString("view_results_sample_count")).append(SPACE) //$NON-NLS-1$
                .append(result.getSampleCount()).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_error_count")).append(SPACE) //$NON-NLS-1$
                .append(result.getErrorCount()).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_datatype")).append(SPACE) //$NON-NLS-1$
                .append(result.getDataType()).append(NL);
        statsDoc.insertString(statsDoc.getLength(), statsBuff.toString(), null);
        statsBuff.setLength(0);

        String responseCode = result.getResponseCode();
        int responseLevel = 0;
        if (responseCode != null) {
            try {
                responseLevel = Integer.parseInt(responseCode) / 100;
            } catch (NumberFormatException numberFormatException) {
                // no need to change the foreground color
            }
        }

        Style style = switch (responseLevel) {
            case 3 -> statsDoc.getStyle(STYLE_REDIRECT);
            case 4 -> statsDoc.getStyle(STYLE_CLIENT_ERROR);
            case 5 -> statsDoc.getStyle(STYLE_SERVER_ERROR);
            default -> null;
        };

        statsBuff.append(JMeterUtils.getResString("view_results_response_code")).append(responseCode).append(NL); //$NON-NLS-1$
        statsDoc.insertString(statsDoc.getLength(), statsBuff.toString(), style);
        statsBuff.setLength(0);

        String responseMsgStr = result.getResponseMessage();
        statsBuff
                .append(JMeterUtils.getResString("view_results_response_message")) //$NON-NLS-1$
                .append(responseMsgStr).append(NL)
                .append(NL)
                .append(NL)
                .append(typeResult).append(SPACE)
                .append(JMeterUtils.getResString("view_results_fields")).append(NL) // $NON-NLS-1$
                .append("ContentType: ").append(result.getContentType()).append(NL) //$NON-NLS-1$
                .append("DataEncoding: ").append(result.getStoredDataEncodingNoDefault()).append(NL); //$NON-NLS-1$
        statsDoc.insertString(statsDoc.getLength(), statsBuff.toString(), null);

        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_thread_name"), result.getThreadName())); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_sample_start"), startTime)); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_load_time"), result.getTime())); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_connect_time"), result.getConnectTime())); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_latency"), result.getLatency())); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_size_in_bytes"), formatSizeInBytes(result))); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_sent_bytes"), result.getSentBytes())); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_size_headers_in_bytes"), result.getHeadersSize())); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_size_body_in_bytes"), result.getBodySizeAsLong())); //$NON-NLS-1$
        if (!networkEndpoint.isEmpty()) {
            resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_network"), networkEndpoint)); //$NON-NLS-1$
        }
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_sample_count"), result.getSampleCount())); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_error_count"), result.getErrorCount())); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_response_code"), responseCode)); //$NON-NLS-1$
        resultModel.addRow(new RowResult(JMeterUtils.getParsedLabel("view_results_response_message"), responseMsgStr)); //$NON-NLS-1$

        LinkedHashMap<String, String> lhm = JMeterUtils.parseHeaders(result.getResponseHeaders());
        Set<Map.Entry<String, String>> keySet = lhm.entrySet();
        for (Map.Entry<String, String> entry : keySet) {
            resHeadersModel.addRow(new RowResult(entry.getKey(), entry.getValue()));
        }

        resFieldsModel.addRow(new RowResult("Type Result ", typeResult)); //$NON-NLS-1$
        resFieldsModel.addRow(new RowResult("ContentType", result.getContentType())); //$NON-NLS-1$
        resFieldsModel.addRow(new RowResult("DataEncoding", result.getStoredDataEncodingNoDefault())); //$NON-NLS-1$
        stats.setCaretPosition(Math.min(1, statsDoc.getLength()));
    }

    private void populateAssertionResult(AssertionResult result) throws BadLocationException {
        stats.setText(""); // $NON-NLS-1$
        StyledDocument statsDoc = stats.getStyledDocument();
        StringBuilder statsBuff = new StringBuilder(100);
        statsBuff
                .append(JMeterUtils.getResString("view_results_assertion_error")) //$NON-NLS-1$
                .append(result.isError()).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_assertion_failure")) //$NON-NLS-1$
                .append(result.isFailure()).append(NL);
        statsBuff
                .append(JMeterUtils.getResString("view_results_assertion_failure_message")) //$NON-NLS-1$
                .append(result.getFailureMessage()).append(NL);
        statsDoc.insertString(statsDoc.getLength(), statsBuff.toString(), null);
        stats.setCaretPosition(Math.min(1, statsDoc.getLength()));
    }

    private boolean isSelectedTopLevelTab(String resourceKey) {
        int selectedIndex = rightSide.getSelectedIndex();
        return selectedIndex >= 0 && JMeterUtils.getResString(resourceKey).equals(rightSide.getTitleAt(selectedIndex));
    }

    private void setupTabPaneForSampleResult() {
        // restore tabbed pane parsed if needed
        if (tabbedResult.getTabCount() < 2) {
            tabbedResult.insertTab(JMeterUtils.getResString("view_results_table_result_tab_parsed"), null, paneParsed, null, 1); //$NON-NLS-1$
            tabbedResult.setSelectedIndex(lastResultTabIndex); // select last tab
        }
        // Set the title for the first tab
        rightSide.setTitleAt(0, JMeterUtils.getResString("view_results_tab_sampler")); //$NON-NLS-1$
        // Add the other tabs if not present
        if(rightSide.indexOfTab(JMeterUtils.getResString("view_results_tab_request")) < 0) { // $NON-NLS-1$
            rightSide.addTab(JMeterUtils.getResString("view_results_tab_request"), requestPanel.getPanel()); // $NON-NLS-1$
        }
        if(rightSide.indexOfTab(JMeterUtils.getResString("view_results_tab_response")) < 0) { // $NON-NLS-1$
            rightSide.addTab(JMeterUtils.getResString("view_results_tab_response"), resultsPane); // $NON-NLS-1$
        }
        if(rightSide.indexOfTab(JMeterUtils.getResString("view_results_request_cookies")) < 0) { // $NON-NLS-1$
            rightSide.addTab(JMeterUtils.getResString("view_results_request_cookies"), cookiesPane); // $NON-NLS-1$
        }
        if(rightSide.indexOfTab(JMeterUtils.getResString("view_results_variables")) < 0) { // $NON-NLS-1$
            rightSide.addTab(JMeterUtils.getResString("view_results_variables"), variablesPane); // $NON-NLS-1$
        }
        // restore last selected tab
        if (lastSelectedTab < rightSide.getTabCount()) {
            rightSide.setSelectedIndex(lastSelectedTab);
        }
    }

    private void setupTabPaneForAssertionResult() {
        // Remove the other (parsed) tab if present
        if (tabbedResult.getTabCount() >= 2) {
            lastResultTabIndex = tabbedResult.getSelectedIndex();
            int parsedTabIndex = tabbedResult.indexOfTab(JMeterUtils.getResString("view_results_table_result_tab_parsed")); // $NON-NLS-1$
            if(parsedTabIndex >= 0) {
                tabbedResult.removeTabAt(parsedTabIndex);
            }
        }
        // Set the title for the first tab
        rightSide.setTitleAt(0, JMeterUtils.getResString("view_results_tab_assertion")); //$NON-NLS-1$
        // Remove the other tabs if present
        int requestTabIndex = rightSide.indexOfTab(JMeterUtils.getResString("view_results_tab_request")); // $NON-NLS-1$
        if(requestTabIndex >= 0) {
            rightSide.removeTabAt(requestTabIndex);
        }
        int responseTabIndex = rightSide.indexOfTab(JMeterUtils.getResString("view_results_tab_response")); // $NON-NLS-1$
        if(responseTabIndex >= 0) {
            rightSide.removeTabAt(responseTabIndex);
        }
        int cookiesTabIndex = rightSide.indexOfTab(JMeterUtils.getResString("view_results_request_cookies")); // $NON-NLS-1$
        if(cookiesTabIndex >= 0) {
            rightSide.removeTabAt(cookiesTabIndex);
        }
        int variablesTabIndex = rightSide.indexOfTab(JMeterUtils.getResString("view_results_variables")); // $NON-NLS-1$
        if(variablesTabIndex >= 0) {
            rightSide.removeTabAt(variablesTabIndex);
        }
    }

    private Component createResponseMetadataPanel() {
        stats = new JTextPane();
        stats.setEditable(false);
        stats.setBackground(backGround);

        // Add styles to use for different types of status messages
        StyledDocument doc = (StyledDocument) stats.getDocument();

        Style style = doc.addStyle(STYLE_REDIRECT, null);
        StyleConstants.setForeground(style, REDIRECT_COLOR);

        style = doc.addStyle(STYLE_CLIENT_ERROR, null);
        StyleConstants.setForeground(style, CLIENT_ERROR_COLOR);

        style = doc.addStyle(STYLE_SERVER_ERROR, null);
        StyleConstants.setForeground(style, SERVER_ERROR_COLOR);

        paneRaw = GuiUtils.makeScrollPane(stats);
        paneRaw.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Set up the 1st table Result with empty headers
        tableResult = new JTable(resultModel);
        JMeterUtils.applyHiDPI(tableResult);
        tableResult.setToolTipText(JMeterUtils.getResString("textbox_tooltip_cell")); // $NON-NLS-1$
        tableResult.addMouseListener(new TextBoxDoubleClick(tableResult));
        setFirstColumnPreferredSize(tableResult);
        RendererUtils.applyRenderers(tableResult, RENDERERS_RESULT);

        // Set up the 2nd table
        tableResHeaders = new JTable(resHeadersModel);
        JMeterUtils.applyHiDPI(tableResHeaders);
        tableResHeaders.setToolTipText(JMeterUtils.getResString("textbox_tooltip_cell")); // $NON-NLS-1$
        tableResHeaders.addMouseListener(new TextBoxDoubleClick(tableResHeaders));
        setFirstColumnPreferredSize(tableResHeaders);
        tableResHeaders.getTableHeader().setDefaultRenderer(
                new HeaderAsPropertyRenderer());
        RendererUtils.applyRenderers(tableResHeaders, RENDERERS_HEADERS);

        // Set up the 3rd table
        tableResFields = new JTable(resFieldsModel);
        JMeterUtils.applyHiDPI(tableResFields);
        tableResFields.setToolTipText(JMeterUtils.getResString("textbox_tooltip_cell")); // $NON-NLS-1$
        tableResFields.addMouseListener(new TextBoxDoubleClick(tableResFields));
        setFirstColumnPreferredSize(tableResFields);
        tableResFields.getTableHeader().setDefaultRenderer(
                new HeaderAsPropertyRenderer());
        RendererUtils.applyRenderers(tableResFields, RENDERERS_FIELDS);

        // Prepare the Results tabbed pane
        tabbedResult = new JTabbedPane(SwingConstants.BOTTOM);

        // Create the split pane
        JSplitPane topSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                GuiUtils.makeScrollPane(tableResHeaders),
                GuiUtils.makeScrollPane(tableResFields));
        topSplit.setOneTouchExpandable(true);
        topSplit.setResizeWeight(0.80); // set split ratio
        topSplit.setBorder(null); // see bug jdk 4131528

        paneParsed = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                GuiUtils.makeScrollPane(tableResult), topSplit);
        paneParsed.setOneTouchExpandable(true);
        paneParsed.setResizeWeight(0.40); // set split ratio
        paneParsed.setBorder(null); // see bug jdk 4131528

        // setup bottom tabs, first Raw, second Parsed
        tabbedResult.addTab(JMeterUtils.getResString("view_results_table_result_tab_raw"), paneRaw); //$NON-NLS-1$
        tabbedResult.addTab(JMeterUtils.getResString("view_results_table_result_tab_parsed"), paneParsed); //$NON-NLS-1$

        // Hint to background color on bottom tabs (grey, not blue)
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(tabbedResult);
        return panel;
    }

    private JPanel createResponseDataPanel() {
        results = new JEditorPane();
        results.setEditable(false);

        responseData = JSyntaxTextArea.getInstance(20, 80, true);
        responseData.setEditable(false);
        responseData.setLineWrap(true);
        responseData.setWrapStyleWord(true);

        JPanel responseAndSearchPanel = new JPanel(new BorderLayout());
        responseAndSearchPanel.add(new JSyntaxSearchToolBar(responseData).getToolBar(), BorderLayout.NORTH);
        responseAndSearchPanel.add(JTextScrollPane.getInstance(responseData), BorderLayout.CENTER);

        resultsScrollPane = GuiUtils.makeScrollPane(results);
        imageLabel = new JLabel();

        JPanel resultAndSearchPanel = new JPanel(new BorderLayout());
        resultAndSearchPanel.add(resultsScrollPane, BorderLayout.CENTER);


        if (activateSearchExtension) {
            // Add search text extension
            searchTextExtension = new SearchTextExtension(new JEditorPaneSearchProvider(results));
            resultAndSearchPanel.add(searchTextExtension.getSearchToolBar(), BorderLayout.NORTH);
        }

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(GuiUtils.makeScrollPane(responseAndSearchPanel));
        return panel;
    }

    private void showImage(Icon image) {
        imageLabel.setIcon(image);
        resultsScrollPane.setViewportView(imageLabel);
    }

    @Override
    public synchronized void setSamplerResult(Object sample) {
        userObject = sample;
    }

    @Override
    public synchronized void setRightSide(JTabbedPane side) {
        rightSide = side;
    }

    @Override
    public void setLastSelectedTab(int index) {
        lastSelectedTab = index;
    }

    @Override
    public void renderImage(SampleResult sampleResult) {
        byte[] responseBytes = sampleResult.getResponseData();
        if (responseBytes != null) {
            showImage(new ImageIcon(responseBytes)); //TODO implement other non-text types
        }
    }

    @Override
    public void setBackgroundColor(Color backGround){
        this.backGround = backGround;
    }

    private static void setFirstColumnPreferredSize(JTable table) {
        TableColumn column = table.getColumnModel().getColumn(0);
        column.setMaxWidth(300);
        column.setPreferredWidth(180);
    }

    private static ObjectTableModel createKeyValueTableModel(String[] columns) {
        return new ObjectTableModel(columns, RowResult.class,
                new Functor[] {
                        new Functor("getKey"), // $NON-NLS-1$
                        new Functor("getValue") }, // $NON-NLS-1$
                new Functor[] {
                        null, null }, new Class[] {
                        String.class, String.class }, false);
    }

    private static JPanel createTablePanel(ObjectTableModel model) {
        JTable table = new JTable(model);
        JMeterUtils.applyHiDPI(table);
        table.setToolTipText(JMeterUtils.getResString("textbox_tooltip_cell")); // $NON-NLS-1$
        table.addMouseListener(new TextBoxDoubleClick(table));
        table.getTableHeader().setDefaultRenderer(new HeaderAsPropertyRenderer());
        setFirstColumnPreferredSize(table);
        RendererUtils.applyRenderers(table, new TableCellRenderer[] {
                new WrappingTableCellRenderer(),
                new WrappingTableCellRenderer()
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(GuiUtils.makeScrollPane(table));
        return panel;
    }

    private void populateCookies(SampleResult sampleResult) {
        cookiesModel.clearData();
        String cookies = RequestViewRaw.getCookies(sampleResult);
        if (cookies.isEmpty()) {
            return;
        }
        for (String cookie : cookies.split(";")) { // $NON-NLS-1$
            String trimmedCookie = cookie.trim();
            if (trimmedCookie.isEmpty()) {
                continue;
            }
            int separatorIndex = trimmedCookie.indexOf('=');
            String name = separatorIndex < 0 ? trimmedCookie : trimmedCookie.substring(0, separatorIndex);
            String value = separatorIndex < 0 ? "" : trimmedCookie.substring(separatorIndex + 1); //$NON-NLS-1$
            cookiesModel.addRow(new RowResult(name, value));
        }
    }

    private void populateVariables(SampleResult sampleResult) {
        variablesModel.clearData();
        for (Map.Entry<String, String> entry : sampleResult.getJMeterVariables().entrySet()) {
            variablesModel.addRow(new RowResult(entry.getKey(), entry.getValue()));
        }
    }

    private static final class WrappingTableCellRenderer extends JTextArea implements TableCellRenderer {
        private static final long serialVersionUID = 1L;

        private WrappingTableCellRenderer() {
            setLineWrap(true);
            setWrapStyleWord(false);
            setOpaque(true);
            setMargin(new Insets(0, 3, 0, 3));
            setAlignmentX(LEFT_ALIGNMENT);
            setAlignmentY(TOP_ALIGNMENT);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {
            setText(value == null ? "" : value.toString()); //$NON-NLS-1$
            setFont(table.getFont());
            setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());

            int columnWidth = table.getColumnModel().getColumn(column).getWidth();
            setSize(columnWidth, Short.MAX_VALUE);
            int preferredHeight = Math.max(table.getRowHeight(), getPreferredSize().height);
            if (table.getRowHeight(row) != preferredHeight) {
                table.setRowHeight(row, preferredHeight);
            }
            return this;
        }
    }

    private static String formatSizeInBytes(SampleResult sampleResult) {
        long bytes = sampleResult.getBytesAsLong();
        if (!sampleResult.isResponseDataCompressed()) {
            return Long.toString(bytes);
        }
        long decompressedSize = sampleResult.computeDecompressedResponseDataSize();
        if (decompressedSize < 0) {
            return bytes + " (compressed)"; // $NON-NLS-1$
        }
        if (decompressedSize <= bytes) {
            return bytes + " (compressed)"; // $NON-NLS-1$
        }
        long savings = Math.round((bytes - decompressedSize) * 100.0d / decompressedSize);
        return bytes + " (decompressed: " + decompressedSize + ", " + savings + "% savings)"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * For model table
     */
    public static class RowResult {
        private String key;

        private Object value;

        public RowResult(String key, Object value) {
            this.key = key;
            this.value = value;
        }

        /**
         * @return the key
         */
        public synchronized String getKey() {
            return key;
        }

        /**
         * @param key
         *            the key to set
         */
        public synchronized void setKey(String key) {
            this.key = key;
        }

        /**
         * @return the value
         */
        public synchronized Object getValue() {
            return value;
        }

        /**
         * @param value
         *            the value to set
         */
        public synchronized void setValue(Object value) {
            this.value = value;
        }
    }

    /**
     * Optimized way to set text based on :
     * http://javatechniques.com/blog/faster-jtextpane-text-insertion-part-i/
     * @param data String data
     */
    protected void setTextOptimized(String data) {
        Document document = results.getDocument();
        Document blank = new DefaultStyledDocument();
        results.setDocument(blank);
        try {
            data = ViewResultsFullVisualizer.wrapLongLines(data);
            document.insertString(0, data == null ? "" : data, null);
        } catch (BadLocationException ex) {
            LOGGER.error("Error inserting text", ex);
        }
        if (sampleResult != null) {
            setResponseDataText(sampleResult.getResponseHeaders(), data);
        }
        if (SIMPLE_VIEW_LIMIT >= 0 && document.getLength() > SIMPLE_VIEW_LIMIT) {
            results.setEditorKit(new NonWrappingPlainTextEditorKit(results.getEditorKit()));
        }
        KerningOptimizer.INSTANCE.configureKerning(results, document.getLength());
        results.setDocument(document);
    }

    private void setResponseDataText(String responseHeaders, String responseBody) {
        responseData.setText(buildResponseData(responseHeaders, responseBody));
        responseData.setCaretPosition(0);
    }

    private static String buildResponseData(String responseHeaders, String responseBody) {
        if (responseHeaders == null || responseHeaders.isEmpty()) {
            return responseBody == null ? "" : responseBody; // $NON-NLS-1$
        }
        if (responseBody == null || responseBody.isEmpty()) {
            return responseHeaders;
        }
        return responseHeaders + "\n" + responseBody; // $NON-NLS-1$
    }
    static class NonWrappingPlainTextEditorKit extends EditorKit {

        private final EditorKit delegate;

        NonWrappingPlainTextEditorKit(EditorKit delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getContentType() {
            return delegate.getContentType();
        }

        @Override
        public ViewFactory getViewFactory() {
            //return new BasicTextAreaUI();
            return new ViewFactory() {
                @Override
                public View create(Element elem) {
                    return new PlainView(elem);
                }
            };
        }

        @Override
        public Action[] getActions() {
            return delegate.getActions();
        }

        @Override
        public Caret createCaret() {
            return delegate.createCaret();
        }

        @Override
        public Document createDefaultDocument() {
            return delegate.createDefaultDocument();
        }

        @Override
        public void read(InputStream in, Document doc, int pos) throws IOException, BadLocationException {
            delegate.read(in, doc, pos);
        }

        @Override
        public void write(OutputStream out, Document doc, int pos, int len) throws IOException, BadLocationException {
            delegate.write(out, doc, pos, len);
        }

        @Override
        public void read(Reader in, Document doc, int pos) throws IOException, BadLocationException {
            delegate.read(in, doc, pos);
        }

        @Override
        public void write(Writer out, Document doc, int pos, int len) throws IOException, BadLocationException {
            delegate.write(out, doc, pos, len);
        }
    }
}
