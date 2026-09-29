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
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import org.apache.jmeter.gui.util.JSyntaxSearchToolBar;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.gui.util.JTextScrollPane;
import org.apache.jmeter.gui.util.TextBoxDialoger.TextBoxDoubleClick;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.RequestView;

import com.google.auto.service.AutoService;

/** Compares parsed request fields without hiding missing or repeated parameters. */
@AutoService(RequestView.class)
public class RequestViewHTTPDiff implements RequestView {
    private JPanel panel;
    private Supplier<JSyntaxSearchToolBar.DiffContent> supplier;
    private RequestViewHTTP recordedParser;
    private RequestViewHTTP currentParser;

    @Override
    public void setDiffContentSupplier(Supplier<JSyntaxSearchToolBar.DiffContent> supplier) {
        this.supplier = supplier;
    }

    @Override
    public void init() {
        panel = new JPanel(new BorderLayout());
        clearData();
    }

    @Override
    public void clearData() {
        panel.removeAll();
        panel.add(new JLabel(label("unavailable")), BorderLayout.NORTH);
        panel.revalidate();
        panel.repaint();
    }

    @Override
    public void setSamplerResult(Object result) {
        clearData();
        if (!(result instanceof HTTPSampleResult current) || supplier == null) {
            return;
        }
        JSyntaxSearchToolBar.DiffContent comparison = supplier.get();
        if (comparison == null) {
            return;
        }
        HTTPSampleResult recorded;
        try {
            recorded = recordedSample(comparison.recorded());
        } catch (IllegalArgumentException | MalformedURLException e) {
            panel.removeAll();
            panel.add(new JLabel(label("unparseable")), BorderLayout.NORTH);
            return;
        }
        if (recordedParser == null) {
            recordedParser = new RequestViewHTTP();
            currentParser = new RequestViewHTTP();
            recordedParser.init();
            currentParser.init();
        }
        recordedParser.setSamplerResult(recorded);
        currentParser.setSamplerResult(current);
        RequestViewHTTP.ParsedRequest before = recordedParser.snapshot();
        RequestViewHTTP.ParsedRequest after = currentParser.snapshot();
        RequestViewHTTP.RequestContent content = new RequestViewHTTP.RequestContent();
        List<DiffRow> details = compare(before.details(), after.details(), false, true);
        // HAR request text has no negotiated TLS version or socket endpoint metadata.
        String tlsKey = JMeterUtils.getParsedLabel("view_results_request_tls");
        details.replaceAll(row -> row.name().equals(tlsKey)
                ? new DiffRow(row.name(), null, row.current(), "not_recorded") : row);
        content.add(section("view_results_request_details", table(details)));
        if (!before.query().isEmpty() || !after.query().isEmpty()) {
            content.add(section("view_results_request_query",
                    table(compare(before.query(), after.query(), false, false))));
        }
        content.add(section("view_results_request_headers",
                table(compare(before.headers(), after.headers(), true, false))));
        if (before.formBody() && after.formBody()) {
            JPanel fields = new JPanel(new BorderLayout());
            fields.add(table(compare(before.form(), after.form(), false, false)));
            if (before.fileParts() || after.fileParts()) {
                fields.add(new JLabel(label("files_not_compared")), BorderLayout.SOUTH);
            }
            content.add(section("view_results_request_body", fields));
        } else {
            JPanel body = new JPanel(new BorderLayout());
            boolean same = before.body().equals(after.body());
            body.add(new JLabel(label(same ? "body_same" : "body_changed")), BorderLayout.NORTH);
            JPanel columns = new JPanel(new GridLayout(1, 2, 8, 0));
            JSyntaxSearchToolBar.DiffContent bodyDiff =
                    new JSyntaxSearchToolBar.DiffContent(before.body(), after.body());
            columns.add(bodyText("recorded", before.body(), before.contentType(), bodyDiff));
            columns.add(bodyText("current", after.body(), after.contentType(), bodyDiff));
            body.add(columns, BorderLayout.CENTER);
            content.add(section("view_results_request_body", body));
        }
        panel.removeAll();
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll);
        panel.revalidate();
        panel.repaint();
    }

    static HTTPSampleResult recordedSample(String request) throws MalformedURLException {
        int firstLine = request.indexOf('\n');
        String[] start = (firstLine < 0 ? request : request.substring(0, firstLine)).trim().split("\\s+", 3);
        if (start.length < 2) {
            throw new IllegalArgumentException("Missing request URL");
        }
        HTTPSampleResult result = new HTTPSampleResult();
        result.setHTTPMethod(start[0]);
        result.setURL(URI.create(start[1]).toURL());
        if (start.length > 2) {
            result.setProtocolVersion(start[2]);
        }
        // Locate the separator without normalizing line endings inside the body.
        int boundary = request.indexOf("\r\n\r\n");
        int separatorLength = 4;
        int lfBoundary = request.indexOf("\n\n");
        if (boundary < 0 || lfBoundary >= 0 && lfBoundary < boundary) {
            boundary = lfBoundary;
            separatorLength = 2;
        }
        int headerStart = firstLine < 0 ? request.length() : firstLine + 1;
        result.setRequestHeaders(request.substring(headerStart,
                boundary < 0 ? request.length() : Math.max(headerStart, boundary)));
        result.setQueryString(boundary < 0 ? "" : request.substring(boundary + separatorLength));
        return result;
    }

    record DiffRow(String name, String recorded, String current, String status) { }

    private record FieldKey(String name, boolean decoded, int occurrence) { }

    static List<DiffRow> compare(List<RequestViewHTTP.Field> before, List<RequestViewHTTP.Field> after,
            boolean ignoreNameCase, boolean metadata) {
        Map<FieldKey, RequestViewHTTP.Field> current = index(after, ignoreNameCase);
        List<DiffRow> rows = new ArrayList<>();
        for (Map.Entry<FieldKey, RequestViewHTTP.Field> entry : index(before, ignoreNameCase).entrySet()) {
            RequestViewHTTP.Field old = entry.getValue();
            RequestViewHTTP.Field value = current.remove(entry.getKey());
            String status = value == null ? (metadata ? "not_available" : "removed")
                    : old.valueDecoded() == value.valueDecoded() && old.value().equals(value.value()) ? "same" : "changed";
            rows.add(new DiffRow(old.name(), old.value(), value == null ? null : value.value(), status));
        }
        current.values().forEach(field -> rows.add(new DiffRow(field.name(), null, field.value(),
                metadata ? "not_recorded" : "added")));
        return rows;
    }

    private static Map<FieldKey, RequestViewHTTP.Field> index(List<RequestViewHTTP.Field> fields, boolean ignoreCase) {
        Map<FieldKey, Integer> occurrences = new LinkedHashMap<>();
        Map<FieldKey, RequestViewHTTP.Field> result = new LinkedHashMap<>();
        for (RequestViewHTTP.Field field : fields) {
            String name = ignoreCase ? field.name().toLowerCase(Locale.ROOT) : field.name();
            int occurrence = occurrences.merge(new FieldKey(name, field.nameDecoded(), 0), 1, Integer::sum);
            result.put(new FieldKey(name, field.nameDecoded(), occurrence), field);
        }
        return result;
    }

    private static Component table(List<DiffRow> rows) {
        DefaultTableModel model = new DefaultTableModel(
                new String[] {label("field"), label("recorded"), label("current"), label("status")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (DiffRow row : rows) {
            model.addRow(new Object[] {row.name(), row.recorded() == null ? label("missing") : row.recorded(),
                    row.current() == null ? label("missing") : row.current(), label(row.status())});
        }
        JTable table = new JTable(model);
        JMeterUtils.applyHiDPI(table);
        table.addMouseListener(new TextBoxDoubleClick(table));
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected,
                    boolean focused, int row, int column) {
                Component cell = super.getTableCellRendererComponent(owner, value, selected, focused, row, column);
                if (!selected) {
                    String status = rows.get(owner.convertRowIndexToModel(row)).status();
                    Color background = owner.getBackground();
                    if (List.of("changed", "added", "removed").contains(status)) {
                        Color highlight = UIManager.getColor("Table.selectionBackground");
                        if (highlight != null) {
                            background = new Color((background.getRed() * 3 + highlight.getRed()) / 4,
                                    (background.getGreen() * 3 + highlight.getGreen()) / 4,
                                    (background.getBlue() * 3 + highlight.getBlue()) / 4);
                        }
                    }
                    cell.setBackground(background);
                }
                return cell;
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(0).setMaxWidth(160);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setMaxWidth(120);
        JPanel content = new JPanel(new BorderLayout());
        content.add(table.getTableHeader(), BorderLayout.NORTH);
        content.add(table);
        return content;
    }

    private static JPanel bodyText(String title, String text, String type,
            JSyntaxSearchToolBar.DiffContent comparison) {
        JSyntaxTextArea area = JSyntaxTextArea.getInstance(14, 40, true);
        area.setEditable(false);
        area.setInitialText(text);
        area.setCaretPosition(0);
        JSyntaxSearchToolBar search = new JSyntaxSearchToolBar(area);
        search.setDiffContentSupplier(() -> comparison);
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.add(search.getToolBar());
        JButton pretty = new JButton(JMeterUtils.getResString("view_results_pretty_print"));
        pretty.addActionListener(event -> {
            area.setText(RequestViewHTTP.prettyPrintBody(text, type));
            area.setCaretPosition(0);
        });
        toolbar.add(pretty, BorderLayout.EAST);
        JPanel body = new JPanel(new BorderLayout());
        body.add(toolbar, BorderLayout.NORTH);
        body.add(JTextScrollPane.getInstance(area));
        return section("view_results_parsed_diff_" + title, body);
    }

    private static JPanel section(String key, Component content) {
        return RequestViewHTTP.section(key, content);
    }

    private static String label(String key) {
        return JMeterUtils.getResString("view_results_parsed_diff_" + key);
    }

    @Override
    public JPanel getPanel() {
        return panel;
    }

    @Override
    public String getLabel() {
        return label("title");
    }
}
