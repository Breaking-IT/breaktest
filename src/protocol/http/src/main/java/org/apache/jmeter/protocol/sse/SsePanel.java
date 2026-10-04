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

package org.apache.jmeter.protocol.sse;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.recording.RecordedSseEvent;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

/** Dedicated SSE sampler settings and read-only recorded events. */
public class SsePanel extends JPanel {
    static final int MAX_PREVIEW_CHARACTERS = 64 * 1024;
    private final JCheckBox count = new JCheckBox(text("sse_count"), true);
    private final JTextField session = new JTextField("sse", 16);
    private final JComboBox<String> existingAction = new JComboBox<>(
            new String[] {SseSampler.RECONNECT, SseSampler.REUSE, SseSampler.FAIL});
    private final JComboBox<String> nameMode = new JComboBox<>(new String[] {SseSampler.EVENT_NAME, SseSampler.FIXED_NAME});
    private final JTextField sampleName = new JTextField(24);
    private final JLabel sampleNameLabel = new JLabel();
    private final JLabel nameExample = new JLabel();
    private String requestName = "SSE Request";
    private final JTextField max = new JTextField("1048576", 9);
    private final JPanel settings = new JPanel(new net.miginfocom.swing.MigLayout(
            "insets 12, wrap 2, aligny top", "[][grow,fill]"));
    private final JTextArea payload = new JTextArea();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private List<RecordedSseEvent> events = List.of();

    public SsePanel() {
        super(new BorderLayout(5, 5));
        settings.add(new JLabel(text("sse_session")));
        settings.add(session);
        settings.add(new JLabel(text("sse_existing_action")));
        settings.add(existingAction);
        settings.add(help("sse_existing_action_hint"), "span 2, growx");
        settings.add(new JLabel(text("sse_max")));
        settings.add(max);
        settings.add(count, "span 2, gaptop 12");
        settings.add(help("sse_count_hint"), "span 2, growx");
        settings.add(new JLabel(text("sse_name_mode")));
        settings.add(nameMode);
        settings.add(sampleNameLabel);
        JPanel naming = new JPanel(new net.miginfocom.swing.MigLayout("insets 0", "[grow,fill][]"));
        naming.add(sampleName, "growx");
        naming.add(nameExample);
        settings.add(naming, "growx");
        settings.add(help("sse_name_hint"), "span 2, growx");
        count.addActionListener(event -> updateNaming());
        nameMode.addActionListener(event -> updateNaming());
        sampleName.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { updateNaming(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { updateNaming(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { updateNaming(); }
        });
        updateNaming();
        settings.add(help("sse_timeout_hint"), "span 2, growx, gaptop 12");
        settings.add(help("sse_iteration_hint"), "span 2, growx, gaptop 12");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        payload.setEditable(false);
        table.getSelectionModel().addListSelectionListener(event -> {
            int row = table.getSelectedRow();
            payload.setText(row < 0 ? "" : preview(events.get(table.convertRowIndexToModel(row)).data()));
            payload.setCaretPosition(0);
        });
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(payload));
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);
        add(new JLabel(text("sse_hint")), BorderLayout.SOUTH);
    }

    private void updateNaming() {
        boolean fixed = SseSampler.FIXED_NAME.equals(nameMode.getSelectedItem());
        nameMode.setEnabled(count.isSelected());
        sampleName.setEnabled(count.isSelected());
        sampleNameLabel.setEnabled(count.isSelected());
        nameExample.setEnabled(count.isSelected());
        sampleNameLabel.setText(text(fixed ? "sse_fixed_name" : "sse_name_prefix"));
        String name = sampleName.getText();
        String example = fixed ? (name.isEmpty() ? requestName : name)
                : (name.isEmpty() ? requestName + " / " : name) + "ready";
        // JLabel must display user-entered text literally, even when it starts with HTML.
        nameExample.putClientProperty("html.disable", true);
        nameExample.setText(text("sse_name_example") + " " + example);
    }

    public JPanel getSettingsPanel() {
        return settings;
    }

    static String preview(String data) {
        return data.length() <= MAX_PREVIEW_CHARACTERS ? data
                : data.substring(0, MAX_PREVIEW_CHARACTERS) + "\n\n" + text("sse_preview_truncated");
    }

    private static JTextArea help(String key) {
        JTextArea area = new JTextArea(text(key));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setFont(javax.swing.UIManager.getFont("Label.font"));
        return area;
    }

    private static String text(String key) { return JMeterUtils.getResString(key); }

    public void configure(TestElement element) {
        requestName = element.getName().isEmpty() ? text("sse_sampler") : element.getName();
        nameMode.setSelectedItem(element.getPropertyAsString(HTTPSamplerProxy.SSE_NAME_MODE, SseSampler.EVENT_NAME));
        sampleName.setText(element.getPropertyAsString(HTTPSamplerProxy.SSE_SAMPLE_NAME));
        session.setText(element.getPropertyAsString(HTTPSamplerProxy.SSE_SESSION, "sse"));
        count.setSelected(element.getPropertyAsBoolean(HTTPSamplerProxy.SSE_COUNT, true));
        existingAction.setSelectedItem(element.getPropertyAsString(HTTPSamplerProxy.SSE_EXISTING_ACTION, SseSampler.RECONNECT));
        max.setText(element.getPropertyAsString(HTTPSamplerProxy.SSE_MAX, "1048576"));
        updateNaming();
        setEvents(List.of());
    }

    public void modify(TestElement element) {
        element.setProperty(HTTPSamplerProxy.SSE_NAME_MODE, (String) nameMode.getSelectedItem(), SseSampler.EVENT_NAME);
        element.setProperty(HTTPSamplerProxy.SSE_SAMPLE_NAME, sampleName.getText(), "");
        element.setProperty(HTTPSamplerProxy.SSE_SESSION, session.getText(), "sse");
        element.setProperty(HTTPSamplerProxy.SSE_COUNT, count.isSelected(), true);
        element.setProperty(HTTPSamplerProxy.SSE_EXISTING_ACTION, (String) existingAction.getSelectedItem(), SseSampler.RECONNECT);
        element.setProperty(HTTPSamplerProxy.SSE_MAX, max.getText(), "1048576");
    }

    public void setEvents(List<RecordedSseEvent> values) {
        table.clearSelection();
        events = List.copyOf(values);
        payload.setText("");
        model.fireTableDataChanged();
        if (!events.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"sse_time", "sse_event", "sse_id", "sse_transaction", "sse_data"};
        @Override public int getRowCount() { return events.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return text(columns[column]); }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? java.math.BigDecimal.class : String.class; }
        @Override public Object getValueAt(int row, int column) {
            RecordedSseEvent event = events.get(row);
            return switch (column) {
                case 0 -> event.relativeTimeMs();
                case 1 -> event.eventName();
                case 2 -> event.eventId();
                case 3 -> event.transactionName().isEmpty() ? event.transactionId() : event.transactionName();
                default -> event.data().substring(0, Math.min(event.data().length(), 200));
            };
        }
    }
}
