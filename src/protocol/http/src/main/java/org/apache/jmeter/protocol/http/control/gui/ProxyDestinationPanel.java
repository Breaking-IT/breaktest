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

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.JTextComponent;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.Binding;
import org.apache.jmeter.gui.JEnumPropertyEditor;
import org.apache.jmeter.gui.JTextComponentBinding;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBaseSchema;
import org.apache.jmeter.protocol.http.sampler.ProxyDestinationPolicy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.locale.ResourceKeyed;

/** Shared filter editor for HTTP requests and defaults. */
public class ProxyDestinationPanel extends JPanel implements Binding {
    private enum Filter implements ResourceKeyed {
        NONE("proxy_filter_all"), INCLUDE("proxy_filter_include"), EXCLUDE("proxy_filter_exclude");

        private final String key;
        Filter(String key) { this.key = key; }
        @Override public String getResourceKey() { return key; }
    }

    private final JEnumPropertyEditor<Filter> mode = new JEnumPropertyEditor<>(
            HTTPSamplerBaseSchema.INSTANCE.getProxy().getDestinationMode(), "",
            Filter.class, key -> key.isEmpty() ? "" : JMeterUtils.getResString(key));
    private final JCheckBox bypass = new JCheckBox(text("proxy_filter_direct"));
    private JTextComponent[] endpointFields = new JTextComponent[0];
    private boolean updating;
    private final JTextArea patterns = new JTextArea(3, 36);
    private final JTextComponentBinding patternsBinding = new JTextComponentBinding(
            patterns, HTTPSamplerBaseSchema.INSTANCE.getProxy().getDestinationPatterns());
    private final JTextArea description = helpText();
    private final JLabel patternLabel = new JLabel(text("proxy_filter_patterns"));

    public ProxyDestinationPanel() {
        super(new BorderLayout(0, 5));
        JPanel fields = new JPanel(new net.miginfocom.swing.MigLayout(
                "insets 0, wrap 2", "[][grow,fill]"));
        JLabel modeLabel = new JLabel(text("proxy_filter_mode"));
        modeLabel.setLabelFor(mode);
        mode.getAccessibleContext().setAccessibleName(text("proxy_filter_mode"));
        fields.add(modeLabel);
        JPanel filterControls = new JPanel(new net.miginfocom.swing.MigLayout("insets 0", "[]12[]"));
        filterControls.add(mode);
        filterControls.add(bypass);
        fields.add(filterControls, "growx");
        fields.add(description, "skip 1, growx");
        patternLabel.setLabelFor(patterns);
        fields.add(patternLabel, "aligny top");
        JPanel patternFields = new JPanel(new BorderLayout(12, 0));
        patternFields.add(new JScrollPane(patterns), BorderLayout.CENTER);
        JTextArea examples = helpText();
        examples.setText(text("proxy_filter_examples"));
        examples.setLineWrap(false);
        patternFields.add(examples, BorderLayout.EAST);
        fields.add(patternFields, "growx");
        JTextArea hint = helpText();
        hint.setText(text("proxy_filter_hint"));
        fields.add(hint, "skip 1, growx");
        add(fields);
        mode.addPropertyChangeListener("value", event -> {
            if (!updating) {
                updateControls();
            }
        });
        bypass.addActionListener(event -> updateControls());
        updating = true;
        showMode("proxy_filter_all");
        updating = false;
        updateControls();
    }

    private void showMode(String value) {
        ConfigTestElement settings = new ConfigTestElement();
        settings.setProperty(ProxyDestinationPolicy.MODE_PROPERTY, value);
        mode.updateUi(settings);
    }

    public void setEndpointFields(JTextComponent... fields) {
        endpointFields = fields;
        updateControls();
    }

    private static JTextArea helpText() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(javax.swing.UIManager.getFont("Label.font").deriveFont(java.awt.Font.PLAIN));
        return area;
    }

    private static String text(String key) {
        return JMeterUtils.getResString(key);
    }

    private void updateControls() {
        ConfigTestElement element = new ConfigTestElement();
        mode.updateElement(element);
        String value = element.get(HTTPSamplerBaseSchema.INSTANCE.getProxy().getDestinationMode());
        boolean direct = bypass.isSelected();
        mode.setEnabled(!direct);
        for (JTextComponent field : endpointFields) {
            field.setEnabled(!direct);
            if (field.getParent() instanceof javax.swing.JComboBox<?> combo) {
                combo.setEnabled(!direct);
            }
        }
        boolean expression = value.contains("${");
        boolean usesPatterns = !direct && (expression || value.equals("proxy_filter_include") || value.equals("proxy_filter_exclude"));
        patterns.setEnabled(usesPatterns);
        patternLabel.setEnabled(usesPatterns);
        description.setText(text(direct ? "proxy_filter_direct_help"
                : expression ? "proxy_filter_runtime" : value + "_help"));
    }

    @Override
    public void updateElement(TestElement element) {
        mode.updateElement(element);
        String value = element.getPropertyAsString(ProxyDestinationPolicy.MODE_PROPERTY);
        if (bypass.isSelected()) {
            element.setProperty(ProxyDestinationPolicy.MODE_PROPERTY, "proxy_filter_direct");
            element.removeProperty(ProxyDestinationPolicy.PATTERNS_PROPERTY);
        } else if ("proxy_filter_all".equals(value) || value.isEmpty()) {
            element.removeProperty(ProxyDestinationPolicy.MODE_PROPERTY);
            element.removeProperty(ProxyDestinationPolicy.PATTERNS_PROPERTY);
            if (ProxyDestinationPolicy.hasSettings(element)) {
                element.setProperty(ProxyDestinationPolicy.MODE_PROPERTY, "proxy_filter_all");
            }
        } else {
            patternsBinding.updateElement(element);
        }
    }

    @Override
    public void updateUi(TestElement element) {
        updating = true;
        try {
            String value = element.getPropertyAsString(ProxyDestinationPolicy.MODE_PROPERTY);
            bypass.setSelected("proxy_filter_direct".equals(value));
            showMode(value.isEmpty() || bypass.isSelected() ? "proxy_filter_all" : value);
            patternsBinding.updateUi(element);
        } finally {
            updating = false;
        }
        updateControls();
    }
}
