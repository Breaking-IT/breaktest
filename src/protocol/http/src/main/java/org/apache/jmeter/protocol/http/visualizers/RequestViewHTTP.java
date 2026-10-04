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

package org.apache.jmeter.protocol.http.visualizers;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.apache.hc.core5.http.ContentType;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.gui.action.KeyStrokes;
import org.apache.jmeter.gui.util.HeaderAsPropertyRenderer;
import org.apache.jmeter.gui.util.JSyntaxSearchToolBar;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.gui.util.JTextScrollPane;
import org.apache.jmeter.gui.util.TextBoxDialoger.TextBoxDoubleClick;
import org.apache.jmeter.protocol.http.config.MultipartUrlConfig;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.RenderAsJSON;
import org.apache.jmeter.visualizers.RequestView;
import org.apache.jmeter.visualizers.SamplerResultTab.RowResult;
import org.apache.jorphan.gui.ObjectTableModel;
import org.apache.jorphan.reflect.Functor;
import org.apache.jorphan.util.StringUtilities;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

/** Displays request metadata, query parameters, and form or raw bodies separately. */
@AutoService(RequestView.class)
public class RequestViewHTTP implements RequestView {
    private static final Logger log = LoggerFactory.getLogger(RequestViewHTTP.class);
    private static final String CHARSET_DECODE = StandardCharsets.ISO_8859_1.name();
    private static final String PARAM_CONCATENATE = "&";

    private final ObjectTableModel requestModel = model();
    private final ObjectTableModel paramsModel = model();
    private final ObjectTableModel bodyModel = model();
    private final ObjectTableModel headersModel = model();
    private JPanel paneParsed;
    private JPanel queryPane;
    private JPanel bodyPane;
    private JPanel bodyCards;
    private JSyntaxTextArea rawBody;
    private List<Field> queryFields = List.of();
    private List<Field> formFields;
    private boolean formBody;
    private boolean fileParts;
    private String originalBody = "";
    private String bodyContentType = "";

    private static ObjectTableModel model() {
        return new ObjectTableModel(
                new String[] {"view_results_table_request_params_key", "view_results_table_request_params_value"},
                RowResult.class, new Functor[] {new Functor("getKey"), new Functor("getValue")},
                new Functor[] {null, null}, new Class[] {String.class, String.class}, false);
    }

    @Override
    public void init() {
        paneParsed = new JPanel(new BorderLayout());
        queryPane = section("view_results_request_query", table(paramsModel));
        rawBody = JSyntaxTextArea.getInstance(10, 80, true);
        rawBody.setEditable(false);
        JPanel rawPane = new JPanel(new BorderLayout());
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.add(new JSyntaxSearchToolBar(rawBody).getToolBar(), BorderLayout.CENTER);
        JButton pretty = new JButton(JMeterUtils.getResString("view_results_pretty_print"));
        pretty.addActionListener(event -> {
            rawBody.setText(prettyPrintBody(originalBody, bodyContentType));
            rawBody.setCaretPosition(0);
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(pretty);
        JButton open = new JButton(JMeterUtils.getResString("view_results_request_body_open_window"));
        open.addActionListener(event -> openBodyWindow());
        actions.add(open);
        toolbar.add(actions, BorderLayout.EAST);
        rawPane.add(toolbar, BorderLayout.NORTH);
        rawPane.add(JTextScrollPane.getInstance(rawBody), BorderLayout.CENTER);
        bodyCards = new JPanel(new CardLayout()) {
            @Override
            public Dimension getPreferredSize() {
                for (Component component : getComponents()) {
                    if (component.isVisible()) {
                        return component.getPreferredSize();
                    }
                }
                return super.getPreferredSize();
            }
        };
        bodyCards.add(table(bodyModel), "fields");
        bodyCards.add(rawPane, "raw");
        bodyPane = section("view_results_request_body", bodyCards);
        RequestContent content = new RequestContent();
        content.add(section("view_results_request_details", table(requestModel)));
        content.add(queryPane);
        content.add(section("view_results_request_headers", table(headersModel)));
        content.add(bodyPane);
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        paneParsed.add(scroll);
        clearData();
    }

    private void openBodyWindow() {
        // Keep a snapshot so selecting another result does not replace the detached body.
        String text = rawBody.getText();
        String contentType = bodyContentType;
        JDialog window = new JDialog(SwingUtilities.getWindowAncestor(paneParsed),
                JMeterUtils.getResString("view_results_request_body"), Dialog.ModalityType.MODELESS);
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JSyntaxTextArea body = JSyntaxTextArea.getInstance(30, 100, true);
        body.setEditable(false);
        body.setInitialText(text);
        body.setCaretPosition(0);
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.add(new JSyntaxSearchToolBar(body).getToolBar(), BorderLayout.CENTER);
        JButton pretty = new JButton(JMeterUtils.getResString("view_results_pretty_print"));
        pretty.addActionListener(event -> {
            body.setText(prettyPrintBody(text, contentType));
            body.setCaretPosition(0);
        });
        toolbar.add(pretty, BorderLayout.EAST);
        window.add(toolbar, BorderLayout.NORTH);
        window.add(JTextScrollPane.getInstance(body), BorderLayout.CENTER);
        window.getRootPane().registerKeyboardAction(event -> window.dispose(),
                KeyStrokes.ESC, JComponent.WHEN_IN_FOCUSED_WINDOW);
        Rectangle screen = window.getGraphicsConfiguration().getBounds();
        window.setSize(Math.min(1000, screen.width), Math.min(700, screen.height));
        window.setLocationRelativeTo(paneParsed);
        window.setVisible(true);
    }

    @Override
    public void clearData() {
        requestModel.clearData();
        paramsModel.clearData();
        headersModel.clearData();
        bodyModel.clearData();
        queryFields = List.of();
        formFields = null;
        formBody = false;
        fileParts = false;
        originalBody = "";
        bodyContentType = "";
        rawBody.setText("");
        queryPane.setVisible(false);
        bodyPane.setVisible(false);
    }

    @Override
    public void setSamplerResult(Object objectResult) {
        clearData();
        if (!(objectResult instanceof HTTPSampleResult sampleResult)) {
            requestModel.addRow(new RowResult("",
                    JMeterUtils.getResString("view_results_table_request_http_nohttp")));
            return;
        }
        addDetail("view_results_table_request_http_method", sampleResult.getHTTPMethod());
        URL url = sampleResult.getURL();
        if (url != null) {
            addDetail("view_results_table_request_http_protocol",
                    sampleResult.getUrlAsString().split(":", 2)[0]);
            addDetail("view_results_table_request_http_host", url.getHost());
            addDetail("view_results_table_request_http_port",
                    Integer.toString(url.getPort() < 0 ? url.getDefaultPort() : url.getPort()));
            addDetail("view_results_table_request_http_path", url.getPath().isEmpty() ? "/" : url.getPath());
            queryFields = addParameters(paramsModel, url.getQuery(), StandardCharsets.UTF_8);
        }
        addDetail("view_results_protocol_version", sampleResult.getProtocolVersion());
        String tls = sampleResult.getTlsVersion();
        if (tls.isEmpty() && url != null) {
            tls = JMeterUtils.getResString("https".equalsIgnoreCase(url.getProtocol())
                    ? "view_results_request_tls_enabled" : "view_results_request_tls_disabled");
        }
        addDetail("view_results_request_tls", tls);
        addDetail("view_results_request_local_endpoint", sampleResult.getLocalEndpoint());
        addDetail("view_results_request_destination_endpoint", sampleResult.getDestinationEndpoint());
        // Preserve duplicate headers and accept case-insensitive Content-Type names.
        sampleResult.getRequestHeaders().lines().forEach(line -> {
            int colon = line.indexOf(':', line.startsWith(":") ? 1 : 0);
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headersModel.addRow(new RowResult(name, value));
                if ("Content-Type".equalsIgnoreCase(name)) {
                    bodyContentType = value;
                }
            }
        });
        if (StringUtilities.isNotEmpty(sampleResult.getCookies())) {
            headersModel.addRow(new RowResult("Cookie", sampleResult.getCookies()));
        }
        originalBody = sampleResult.getQueryString() == null ? "" : sampleResult.getQueryString();
        boolean parsedBody = parseBody();
        formBody = parsedBody;
        rawBody.setInitialText(originalBody);
        rawBody.setCaretPosition(0);
        ((CardLayout) bodyCards.getLayout()).show(bodyCards, parsedBody ? "fields" : "raw");
        bodyPane.setVisible(!originalBody.isEmpty());
        queryPane.setVisible(paramsModel.getRowCount() > 0);
        paneParsed.revalidate();
        paneParsed.repaint();
    }

    record Field(String name, String value, boolean nameDecoded, boolean valueDecoded) {
        Field(String name, String value) {
            this(name, value, true, true);
        }
    }

    private record Parameter(String text, boolean decoded) { }

    record ParsedRequest(List<Field> details, List<Field> query, List<Field> headers,
            List<Field> form, String body, String contentType, boolean formBody, boolean fileParts) { }

    ParsedRequest snapshot() {
        return new ParsedRequest(fields(requestModel), queryFields, fields(headersModel),
                formFields == null ? fields(bodyModel) : formFields,
                originalBody, bodyContentType, formBody, fileParts);
    }

    private static List<Field> fields(ObjectTableModel model) {
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            fields.add(new Field(String.valueOf(model.getValueAt(i, 0)), String.valueOf(model.getValueAt(i, 1))));
        }
        return fields;
    }

    private void addDetail(String key, String value) {
        if (StringUtilities.isNotEmpty(value)) {
            requestModel.addRow(new RowResult(JMeterUtils.getParsedLabel(key), value));
        }
    }

    private boolean parseBody() {
        try {
            ContentType type = bodyContentType.isEmpty() ? null : ContentType.parse(bodyContentType);
            if (type == null) {
                return false;
            }
            if ("application/x-www-form-urlencoded".equalsIgnoreCase(type.getMimeType())) {
                Charset charset = type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset();
                formFields = addParameters(bodyModel, originalBody, charset);
                return true;
            }
            if ("multipart/form-data".equalsIgnoreCase(type.getMimeType())) {
                String boundary = type.getParameter("boundary");
                if (StringUtilities.isEmpty(boundary)) {
                    return false;
                }
                MultipartUrlConfig config = new MultipartUrlConfig(boundary);
                config.parseArguments(originalBody);
                for (JMeterProperty property : config.getArguments()) {
                    Argument arg = (Argument) property.getObjectValue();
                    bodyModel.addRow(new RowResult(arg.getName(), arg.getValue()));
                }
                for (int i = 0; i < config.getHTTPFileArgs().getHTTPFileArgCount(); i++) {
                    HTTPFileArg file = config.getHTTPFileArgs().getHTTPFileArg(i);
                    bodyModel.addRow(new RowResult(file.getParamName(), file.getMimeType() == null
                            || file.getMimeType().isEmpty() ? file.getPath()
                            : file.getPath() + " (" + file.getMimeType() + ")"));
                    fileParts = true;
                }
                return bodyModel.getRowCount() > 0;
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            // A malformed or incomplete captured body remains available verbatim.
            bodyModel.clearData();
        }
        return false;
    }

    static List<Field> addParameters(ObjectTableModel model, String query, Charset charset) {
        List<Field> fields = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            return fields;
        }
        for (String part : query.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int equals = part.indexOf('=');
            String name = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            Parameter decodedName = decodeParameter(name, charset);
            Parameter decodedValue = decodeParameter(value, charset);
            model.addRow(new RowResult(decodedName.text(), decodedValue.text()));
            fields.add(new Field(decodedName.text(), decodedValue.text(),
                    decodedName.decoded(), decodedValue.decoded()));
        }
        return fields;
    }

    private static Parameter decodeParameter(String value, Charset charset) {
        StringBuilder result = new StringBuilder(value.length());
        ByteArrayOutputStream run = new ByteArrayOutputStream();
        int i = 0;
        try {
            while (i < value.length()) {
                char c = value.charAt(i);
                if (c == '%') {
                    if (i + 2 >= value.length()
                            || Character.digit(value.charAt(i + 1), 16) < 0
                            || Character.digit(value.charAt(i + 2), 16) < 0) {
                        return new Parameter(value, false);
                    }
                    run.write(Character.digit(value.charAt(i + 1), 16) * 16
                            + Character.digit(value.charAt(i + 2), 16));
                    i += 3;
                } else {
                    flush(run, charset, result);
                    result.append(c == '+' ? ' ' : c);
                    i++;
                }
            }
            flush(run, charset, result);
            return new Parameter(result.toString(), true);
        } catch (CharacterCodingException e) {
            // Retain both the original escapes and decoding validity. An invalid
            // %E9 must differ from valid UTF-8 %C3%A9 and literal text %25E9.
            return new Parameter(value, false);
        }
    }

    private static void flush(ByteArrayOutputStream run, Charset charset, StringBuilder out)
            throws CharacterCodingException {
        if (run.size() == 0) {
            return;
        }
        byte[] bytes = run.toByteArray();
        run.reset();
        out.append(charset.newDecoder().decode(ByteBuffer.wrap(bytes)));
    }

    static String prettyPrintBody(String body, String contentType) {
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("xml")) {
            return Jsoup.parse(body, "", Parser.xmlParser()).outerHtml();
        }
        if (type.contains("html")) {
            return Jsoup.parse(body).outerHtml();
        }
        return RenderAsJSON.prettyJSON(body);
    }

    private static Component table(ObjectTableModel model) {
        JTable table = new JTable(model);
        JMeterUtils.applyHiDPI(table);

        table.addMouseListener(new TextBoxDoubleClick(table));
        table.getTableHeader().setDefaultRenderer(new HeaderAsPropertyRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(0).setMaxWidth(160);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(table.getTableHeader(), BorderLayout.NORTH);
        panel.add(table, BorderLayout.CENTER);
        return panel;
    }

    static JPanel section(String key, Component content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString(key)));
        panel.add(content);
        panel.setMinimumSize(new Dimension(0, 0));
        return panel;
    }

    /**
     * Tables take only their row height; the body receives spare space. One outer
     * scrollbar keeps every section reachable even in a small results pane.
     */
    static class RequestContent extends JPanel implements Scrollable {
        RequestContent() {
            super(null);
        }

        @Override
        public Dimension getPreferredSize() {
            int height = 0;
            for (Component component : getComponents()) {
                if (component.isVisible()) {
                    height += component.getPreferredSize().height;
                }
            }
            return new Dimension(600, height);
        }

        @Override
        public void doLayout() {
            int y = 0;
            for (int i = 0; i < getComponentCount(); i++) {
                Component component = getComponent(i);
                if (!component.isVisible()) {
                    continue;
                }
                int height = component.getPreferredSize().height;
                if (i == getComponentCount() - 1) {
                    height = Math.max(height, getHeight() - y);
                }
                component.setBounds(0, y, getWidth(), height);
                y += height;
            }
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getComponent(getComponentCount() - 1).isVisible()
                    && getParent() != null && getPreferredSize().height < getParent().getHeight();
        }
    }

    @Override
    public JPanel getPanel() {
        return paneParsed;
    }

    @Override
    public String getLabel() {
        return JMeterUtils.getResString("view_results_table_result_tab_parsed");
    }

    /**
     * @param query query to parse for param and value pairs
     * @return Map params and values
     */
    //TODO: move to utils class (JMeterUtils?)
    public static Map<String, String[]> getQueryMap(String query) {

        var map = new HashMap<String, String[]>();
        var params = query.split(PARAM_CONCATENATE);
        for (String param : params) {
            var paramSplit = param.split("=");
            if (paramSplit.length == 0) {
                continue; // We found no key-/value-pair, so continue on the next param
            }
            String name = decodeQuery(paramSplit[0]);

            // hack for SOAP request (generally)
            if (name.trim().startsWith("<?")) { // $NON-NLS-1$
                map.put(" ", new String[] {query}); //blank name // $NON-NLS-1$
                return map;
            }

            // the post payload is not key=value
            if((param.startsWith("=") && paramSplit.length == 1) || paramSplit.length > 2) {
                map.put(" ", new String[] {query}); //blank name // $NON-NLS-1$
                return map;
            }

            String value = "";
            if(paramSplit.length>1) {
                value = decodeQuery(paramSplit[1]);
            }

            String[] known = map.get(name);
            if(known == null) {
                known = new String[] {value};
            }
            else {
                String[] tmp = new String[known.length+1];
                tmp[tmp.length-1] = value;
                System.arraycopy(known, 0, tmp, 0, known.length);
                known = tmp;
            }
            map.put(name, known);
        }
        return map;
    }

    /**
     * Decode a query string
     *
     * @param query
     *            to decode
     * @return the decoded query string, if it can be url-decoded. Otherwise the original
     *            query will be returned.
     */
    public static String decodeQuery(String query) {
        if (StringUtilities.isNotEmpty(query)) {
            try {
                return URLDecoder.decode(query, CHARSET_DECODE); // better  ISO-8859-1 than UTF-8
            } catch (IllegalArgumentException | UnsupportedEncodingException e) {
                log.warn(
                        "Error decoding query, maybe your request parameters should be encoded:"
                                + query, e);
                return query;
            }
        }
        return "";
    }

}
