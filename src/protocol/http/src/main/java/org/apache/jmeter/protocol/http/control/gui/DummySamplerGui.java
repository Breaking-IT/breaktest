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

package org.apache.jmeter.protocol.http.control.gui;

import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;

import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.protocol.http.sampler.DummySampler;
import org.apache.jmeter.protocol.http.sampler.DummySampler.ResultType;
import org.apache.jmeter.protocol.http.sampler.DummySamplerField;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

import net.miginfocom.swing.MigLayout;

/** Native dummy sampler editor; inactive type-specific values survive type switches. */
@TestElementMetadata(labelResource = "dummy_sampler_title")
public class DummySamplerGui extends AbstractSamplerGui {
    private static final long serialVersionUID = 1L;

    private final JComboBox<ResultType> resultType = new JComboBox<>(ResultType.values());
    private final Map<DummySamplerField, JTextComponent> fields = new LinkedHashMap<>();
    private final Map<DummySamplerField, JComboBox<String>> selectors = new LinkedHashMap<>();
    private final Map<String, JPanel> groups = new LinkedHashMap<>();
    private final JTabbedPane tabs = new JTabbedPane();

    public DummySamplerGui() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        JPanel selection = new JPanel(new MigLayout("fillx, insets 0", "[][grow]"));
        JLabel label = new JLabel(JMeterUtils.getResString("dummy_sampler_type"));
        label.setLabelFor(resultType);
        selection.add(label);
        selection.add(resultType, "wrap");
        selection.add(new JLabel(JMeterUtils.getResString("dummy_sampler_help")), "span 2, growx");
        content.add(selection, BorderLayout.NORTH);
        for (DummySamplerField field : DummySamplerField.values()) {
            JPanel group = groups.computeIfAbsent(field.group(), key ->
                    new JPanel(new MigLayout("fillx, wrap 2", "[][grow,fill]")));
            JTextComponent editor = field.multiline() ? new JTextArea(6, 50) : new JTextField(30);
            JComponent control = editor;
            String[] choices = switch (field) {
                case SUCCESSFUL -> new String[] {"true", "false", "${variable_name}"};
                case SIMULATE_TIME, BASE64 -> new String[] {"true", "false"};
                case DATA_TYPE -> new String[] {"text", "bin"};
                default -> new String[0];
            };
            if (choices.length > 0) {
                JComboBox<String> choice = new JComboBox<>(choices);
                choice.setEditable(true);
                editor = (JTextComponent) choice.getEditor().getEditorComponent();
                control = choice;
                selectors.put(field, choice);
            }
            editor.setName(field.propertyName());
            JLabel fieldLabel = new JLabel(JMeterUtils.getResString(field.resourceKey()));
            fieldLabel.setLabelFor(control);
            group.add(fieldLabel, "aligny top");
            if (field.multiline()) {
                JScrollPane scroll = new JScrollPane(editor);
                group.add(scroll, "growx, hmin 90");
            } else {
                group.add(control, "growx");
            }
            fields.put(field, editor);
        }
        content.add(tabs, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);
        resultType.addActionListener(event -> updateTabs());
        clearGui();
    }

    private void updateTabs() {
        String selected = tabs.getSelectedIndex() < 0 ? null : tabs.getTitleAt(tabs.getSelectedIndex());
        tabs.removeAll();
        ResultType type = (ResultType) resultType.getSelectedItem();
        groups.forEach((name, panel) -> {
            boolean relevant = fields.keySet().stream()
                    .anyMatch(field -> field.group().equals(name) && field.appliesTo(type));
            if (relevant) {
                JScrollPane scroll = new JScrollPane(panel);
                scroll.setBorder(BorderFactory.createEmptyBorder());
                scroll.getVerticalScrollBar().setUnitIncrement(16);
                String title = JMeterUtils.getResString("dummy_sampler_group_" + name);
                tabs.addTab(title, scroll);
                if (title.equals(selected)) {
                    tabs.setSelectedIndex(tabs.getTabCount() - 1);
                }
            }
        });
        revalidate();
        repaint();
    }

    @Override
    public String getLabelResource() {
        return "dummy_sampler_title";
    }

    @Override
    public TestElement createTestElement() {
        DummySampler sampler = new DummySampler();
        modifyTestElement(sampler);
        return sampler;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        configureTestElement(element);
        element.setProperty(DummySampler.RESULT_TYPE, ((ResultType) resultType.getSelectedItem()).name());
        fields.forEach((field, editor) -> element.setProperty(field.propertyName(), editor.getText()));
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        DummySampler sampler = (DummySampler) element;
        fields.keySet().forEach(field -> setValue(field, sampler.value(field)));
        resultType.setSelectedItem(sampler.getResultType());
        updateTabs();
    }

    private void setValue(DummySamplerField field, String value) {
        JComboBox<String> selector = selectors.get(field);
        if (selector == null) {
            fields.get(field).setText(value);
        } else {
            selector.setSelectedItem(value);
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        fields.keySet().forEach(field -> setValue(field, field.defaultValue()));
        resultType.setSelectedItem(ResultType.HTTP);
        updateTabs();
    }
}
