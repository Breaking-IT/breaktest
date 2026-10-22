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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import org.apache.jmeter.gui.util.HttpProtocolDisplay;
import org.apache.jmeter.gui.util.JSyntaxSearchToolBar;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.gui.util.JTextScrollPane;
import org.apache.jmeter.gui.util.SampleResultNodeResolver;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.GuiUtils;
import org.apache.jorphan.gui.JFactory;
import org.apache.jorphan.util.StringUtilities;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

import com.google.auto.service.AutoService;

/**
 * (historical) Panel to view request data
 *
 */
@AutoService(RequestView.class)
public class RequestViewRaw implements RequestView {

    // Used by Request Panel
    static final String KEY_LABEL = "view_results_table_request_tab_raw"; //$NON-NLS-1$

    private JSyntaxTextArea requestData;

    private JPanel paneRaw; /** request pane content */

    private JSyntaxSearchToolBar searchToolBar;

    private JButton prettyPrintButton;
    private SampleResult selectedResult;
    private JButton replayButton;
    private JButton copyCurlButton;
    private CurlRequest selectedRequest;
    private boolean replaying;
    private Callable<SampleResult> selectedReplay;
    private Consumer<SampleResult> replayResultConsumer;
    private final List<RequestReplay> replayProviders = ServiceLoader.load(RequestReplay.class).stream()
            .map(ServiceLoader.Provider::get).toList();

    private final Supplier<JSyntaxSearchToolBar.DiffContent> diffContentSupplier;

    public RequestViewRaw() {
        this(null);
    }

    public RequestViewRaw(Supplier<JSyntaxSearchToolBar.DiffContent> diffContentSupplier) {
        this.diffContentSupplier = diffContentSupplier;
    }

    @Override
    public void init() {
        paneRaw = new JPanel(new BorderLayout(0, 5));

        requestData = JSyntaxTextArea.getInstance(20, 80, true);
        requestData.setEditable(false);
        requestData.setLineWrap(false);
        requestData.setWrapStyleWord(false);
        requestData.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        JPanel requestDataAndSearchPanel = new JPanel(new BorderLayout());
        searchToolBar = new JSyntaxSearchToolBar(requestData);
        searchToolBar.setDiffContentSupplier(diffContentSupplier);
        searchToolBar.setDiffButtonVisible(false);
        prettyPrintButton = new JButton(JMeterUtils.getResString("view_results_pretty_print"));
        JFactory.small(prettyPrintButton);
        prettyPrintButton.addActionListener(event -> {
            if (selectedResult != null) {
                requestData.setText(formatRequest(selectedResult, true));
                requestData.setCaretPosition(0);
            }
        });
        searchToolBar.getToolBar().add(prettyPrintButton);
        replayButton = new JButton(JMeterUtils.getResString("view_results_request_replay"));
        copyCurlButton = new JButton(JMeterUtils.getResString("view_results_request_copy_curl"));
        JFactory.small(replayButton);
        JFactory.small(copyCurlButton);
        replayButton.addActionListener(event -> replayRequest());
        copyCurlButton.addActionListener(event -> copyCurl());
        searchToolBar.getToolBar().add(replayButton);
        searchToolBar.getToolBar().add(copyCurlButton);
        updateRequestActions();
        requestDataAndSearchPanel.add(searchToolBar.getToolBar(), BorderLayout.NORTH);
        requestDataAndSearchPanel.add(JTextScrollPane.getInstance(requestData), BorderLayout.CENTER);

        paneRaw.add(requestDataAndSearchPanel, BorderLayout.CENTER);

    }

    @Override
    public void clearData() {
        selectedReplay = null;
        selectedResult = null;
        selectedRequest = null;
        updateRequestActions();
        requestData.setInitialText(""); //$NON-NLS-1$
    }

    @Override
    public void setSamplerResult(Object objectResult) {
        selectedResult = objectResult instanceof SampleResult result ? result : null;
        selectedRequest = objectResult instanceof SampleResult result ? CurlRequest.from(result) : null;
        selectedReplay = selectedResult == null ? null : replayProviders.stream()
                .map(provider -> provider.prepare(selectedResult)).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        updateRequestActions();
        if (objectResult instanceof SampleResult sampleResult) {
            String data = formatRequest(sampleResult);
            requestData.setText(StringUtilities.isNotEmpty(data)
                    ? data
                    : JMeterUtils.getResString("view_results_table_request_raw_nodata")); //$NON-NLS-1$
            requestData.setCaretPosition(0);
        }
    }

    void setReplayResultConsumer(Consumer<SampleResult> consumer) {
        replayResultConsumer = consumer;
        updateRequestActions();
    }

    void setDiffButtonVisible(boolean visible) {
        if (searchToolBar != null) {
            searchToolBar.setDiffButtonVisible(visible);
        }
    }

    private void updateRequestActions() {
        prettyPrintButton.setEnabled(selectedResult != null
                && StringUtilities.isNotEmpty(getRequestBody(selectedResult)));
        replayButton.setEnabled(selectedReplay != null && replayResultConsumer != null && !replaying);
        copyCurlButton.setEnabled(selectedRequest != null);
        replayButton.setToolTipText(JMeterUtils.getResString(selectedReplay == null
                ? "view_results_request_unavailable" : "view_results_request_replay_tooltip"));
        copyCurlButton.setToolTipText(JMeterUtils.getResString(selectedRequest == null
                ? "view_results_request_unavailable" : "view_results_request_copy_curl_tooltip"));
    }

    private void copyCurl() {
        if (selectedRequest == null) {
            return;
        }
        try {
            GuiUtils.copyTextToClipboard(selectedRequest.command());
        } catch (IllegalStateException ex) {
            JOptionPane.showMessageDialog(paneRaw, ex.getMessage(),
                    JMeterUtils.getResString("view_results_request_copy_curl"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void replayRequest() {
        if (selectedReplay == null || replayResultConsumer == null || replaying) {
            return;
        }
        Callable<SampleResult> replay = selectedReplay;
        SampleResult original = selectedResult;
        Consumer<SampleResult> resultConsumer = replayResultConsumer;
        replaying = true;
        updateRequestActions();
        new SwingWorker<SampleResult, Void>() {
            @Override
            protected SampleResult doInBackground() throws Exception {
                return replay.call();
            }

            @Override
            protected void done() {
                replaying = false;
                updateRequestActions();
                try {
                    SampleResult result = get();
                    if (original != null) {
                        SampleResultNodeResolver.inheritReplaySource(original, result);
                    }
                    resultConsumer.accept(result);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    JOptionPane.showMessageDialog(paneRaw, ex.getCause().getMessage(),
                            JMeterUtils.getResString("view_results_request_replay"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    static String formatRequest(SampleResult sampleResult) {
        return formatRequest(sampleResult, false);
    }

    static String formatRequest(SampleResult sampleResult, boolean prettyPrint) {
        // Don't display Request headers label if rh is null or empty
        String rh = sampleResult.getRequestHeaders();
        String cookies = getCookies(sampleResult);
        String protocol = getHttpProtocolVersion(sampleResult);
        boolean usesPseudoHeaders = usesPseudoHeaders(protocol);
        String requestHeaders = buildRequestHeaders(rh, cookies, sampleResult, usesPseudoHeaders);
        String body = getRequestBody(sampleResult);
        if (prettyPrint && StringUtilities.isNotEmpty(body)) {
            String contentType = rh == null ? "" : rh.lines()
                    .filter(line -> line.regionMatches(true, 0, "Content-Type:", 0, 13))
                    .map(line -> line.substring(13).trim().toLowerCase(Locale.ROOT))
                    .findFirst().orElse("");
            if (contentType.contains("xml")) {
                body = Jsoup.parse(body, "", Parser.xmlParser()).outerHtml();
            } else if (contentType.contains("html")) {
                body = Jsoup.parse(body).outerHtml();
            } else {
                body = RenderAsJSON.prettyJSON(body);
            }
        }
        return buildRequestData(buildRequestLine(sampleResult, protocol), requestHeaders, body);
    }

    private static String buildRequestData(String requestLine, String requestHeaders, String requestBody) {
        StringBuilder requestDataBuilder = new StringBuilder();
        if (StringUtilities.isNotEmpty(requestLine)) {
            requestDataBuilder.append(requestLine).append('\n');
        }
        if (StringUtilities.isNotEmpty(requestHeaders)) {
            requestDataBuilder.append(requestHeaders);
        }
        if (StringUtilities.isNotEmpty(requestBody)) {
            if (!requestDataBuilder.isEmpty()) {
                if (requestDataBuilder.charAt(requestDataBuilder.length() - 1) != '\n') {
                    requestDataBuilder.append('\n');
                }
                requestDataBuilder.append('\n');
            }
            requestDataBuilder.append(requestBody);
        }
        return requestDataBuilder.toString();
    }

    private static String buildRequestHeaders(
            String requestHeaders, String cookies, SampleResult sampleResult, boolean usesPseudoHeaders) {
        StringBuilder requestHeadersBuilder = new StringBuilder();
        if (usesPseudoHeaders) {
            appendPseudoHeaders(requestHeadersBuilder, sampleResult);
        }
        if (StringUtilities.isNotEmpty(requestHeaders)) {
            requestHeadersBuilder.append(requestHeaders);
        }
        appendCookieHeader(requestHeadersBuilder, cookies, usesPseudoHeaders);
        return sortedHeaderLines(requestHeadersBuilder.toString(), usesPseudoHeaders);
    }

    private static String buildRequestLine(SampleResult sampleResult, String protocol) {
        String method = invokeStringMethod(sampleResult, "getHTTPMethod"); //$NON-NLS-1$
        String url = sampleResult.getUrlAsString();
        if (StringUtilities.isEmpty(method) || StringUtilities.isEmpty(url)) {
            return ""; //$NON-NLS-1$
        }
        if (StringUtilities.isEmpty(protocol)) {
            return method + " " + url; //$NON-NLS-1$
        }
        return method + " " + url + " " + protocol; //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String getHttpProtocolVersion(SampleResult sampleResult) {
        String protocolVersion = HttpProtocolDisplay.version(sampleResult.getProtocolVersion());
        if (StringUtilities.isNotEmpty(protocolVersion)) {
            return protocolVersion;
        }
        String responseHeaders = sampleResult.getResponseHeaders();
        if (StringUtilities.isEmpty(responseHeaders)) {
            return ""; //$NON-NLS-1$
        }
        int separator = responseHeaders.indexOf(' ');
        String responseProtocol = separator < 0 ? responseHeaders : responseHeaders.substring(0, separator);
        return HttpProtocolDisplay.version(responseProtocol);
    }

    private static boolean usesPseudoHeaders(String protocol) {
        return "HTTP/2".equalsIgnoreCase(protocol) || "HTTP/3".equalsIgnoreCase(protocol); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void appendPseudoHeaders(StringBuilder requestHeaders, SampleResult sampleResult) {
        URL url = sampleResult.getURL();
        String method = invokeStringMethod(sampleResult, "getHTTPMethod"); //$NON-NLS-1$
        if (url == null || StringUtilities.isEmpty(method)) {
            return;
        }
        String host = url.getHost();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) { //$NON-NLS-1$
            host = '[' + host + ']';
        }
        String authority = url.getPort() < 0 ? host : host + ':' + url.getPort();
        String path = StringUtilities.isEmpty(url.getPath()) ? "/" : url.getPath(); //$NON-NLS-1$
        if (StringUtilities.isNotEmpty(url.getQuery())) {
            path += '?' + url.getQuery();
        }
        appendHeader(requestHeaders, ":authority", authority); //$NON-NLS-1$
        appendHeader(requestHeaders, ":method", method); //$NON-NLS-1$
        appendHeader(requestHeaders, ":path", path); //$NON-NLS-1$
        appendHeader(requestHeaders, ":scheme", url.getProtocol()); //$NON-NLS-1$
    }

    private static void appendCookieHeader(
            StringBuilder requestDataBuilder, String cookies, boolean lowercaseHeaderNames) {
        if (StringUtilities.isEmpty(cookies) || hasCookieHeader(requestDataBuilder)) {
            return;
        }
        appendHeader(requestDataBuilder, lowercaseHeaderNames ? "cookie" : "Cookie", cookies); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void appendHeader(StringBuilder requestHeaders, String name, String value) {
        if (!requestHeaders.isEmpty() && requestHeaders.charAt(requestHeaders.length() - 1) != '\n') {
            requestHeaders.append('\n');
        }
        requestHeaders.append(name).append(": ").append(value).append('\n'); //$NON-NLS-1$
    }

    private static boolean hasCookieHeader(StringBuilder requestDataBuilder) {
        return requestDataBuilder.toString().lines()
                .anyMatch(line -> line.regionMatches(true, 0, "Cookie:", 0, "Cookie:".length())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String sortedHeaderLines(String requestHeaders, boolean lowercaseHeaderNames) {
        if (StringUtilities.isEmpty(requestHeaders)) {
            return ""; //$NON-NLS-1$
        }
        List<String> lines = new ArrayList<>();
        requestHeaders.lines()
                .filter(StringUtilities::isNotEmpty)
                .map(line -> lowercaseHeaderNames ? lowercaseHeaderName(line) : line)
                .forEach(lines::add);
        lines.sort(Comparator
                .comparing(RequestViewRaw::headerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(String::compareToIgnoreCase));
        StringBuilder sortedHeaders = new StringBuilder();
        for (String line : lines) {
            sortedHeaders.append(line).append('\n');
        }
        return sortedHeaders.toString();
    }

    private static String headerName(String headerLine) {
        int separator = headerSeparatorIndex(headerLine);
        return separator < 0 ? headerLine : headerLine.substring(0, separator);
    }

    private static String lowercaseHeaderName(String headerLine) {
        int separator = headerSeparatorIndex(headerLine);
        if (separator < 0) {
            return headerLine;
        }
        return headerLine.substring(0, separator).toLowerCase(Locale.ROOT) + headerLine.substring(separator);
    }

    private static int headerSeparatorIndex(String headerLine) {
        return headerLine.startsWith(":") ? headerLine.indexOf(':', 1) : headerLine.indexOf(':'); //$NON-NLS-1$
    }

    private static String getRequestBody(SampleResult sampleResult) {
        String httpBody = invokeStringMethod(sampleResult, "getQueryString"); //$NON-NLS-1$
        if (httpBody != null) {
            return httpBody;
        }
        return sampleResult.getSamplerData();
    }

    static String getCookies(SampleResult sampleResult) {
        String cookies = invokeStringMethod(sampleResult, "getCookies"); //$NON-NLS-1$
        return cookies == null ? "" : cookies; //$NON-NLS-1$
    }

    static String invokeStringMethod(SampleResult sampleResult, String methodName) {
        try {
            Method method = sampleResult.getClass().getMethod(methodName);
            Object value = method.invoke(sampleResult);
            return value instanceof String stringValue ? stringValue : null;
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            return null;
        }
    }

    @Override
    public JPanel getPanel() {
        return paneRaw;
    }

    @Override
    public String getLabel() {
        return JMeterUtils.getResString(KEY_LABEL);
    }

}
