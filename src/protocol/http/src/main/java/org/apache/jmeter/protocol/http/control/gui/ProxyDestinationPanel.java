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
import java.net.URI;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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

/** Shared filter editor for HTTP requests and defaults. Preview never sends traffic. */
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
    private final JTextField preview = new JTextField(30);
    private final JTextArea description = helpText();
    private final JTextArea status = helpText();
    private final JLabel patternLabel = new JLabel(text("proxy_filter_patterns"));
    private final JLabel previewLabel = new JLabel(text("proxy_filter_preview"));

    public ProxyDestinationPanel() {
        super(new BorderLayout(0, 5));
        JPanel fields = new JPanel(new net.miginfocom.swing.MigLayout(
                "insets 0, wrap 2", "[][grow,fill]"));
        JLabel modeLabel = new JLabel(text("proxy_filter_mode"));
        modeLabel.setLabelFor(mode);
        mode.getAccessibleContext().setAccessibleName(text("proxy_filter_mode"));
        fields.add(bypass, "skip 1, growx");
        fields.add(modeLabel);
        fields.add(mode, "growx");
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
        previewLabel.setLabelFor(preview);
        fields.add(previewLabel);
        fields.add(preview, "growx");
        preview.putClientProperty("JTextField.placeholderText", "api.example.com");
        preview.setToolTipText(text("proxy_filter_preview_hint"));
        JTextArea previewHelp = helpText();
        previewHelp.setText(text("proxy_filter_preview_hint"));
        fields.add(previewHelp, "skip 1, growx");
        fields.add(status, "skip 1, growx");
        add(fields);
        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { validatePolicy(); }
            @Override public void removeUpdate(DocumentEvent event) { validatePolicy(); }
            @Override public void changedUpdate(DocumentEvent event) { validatePolicy(); }
        };
        patterns.getDocument().addDocumentListener(listener);
        preview.getDocument().addDocumentListener(listener);
        mode.addPropertyChangeListener("value", event -> {
            if (!updating) {
                validatePolicy();
            }
        });
        bypass.addActionListener(event -> validatePolicy());
        updating = true;
        showMode("proxy_filter_all");
        updating = false;
        validatePolicy();
    }

    private void showMode(String value) {
        ConfigTestElement settings = new ConfigTestElement();
        settings.setProperty(ProxyDestinationPolicy.MODE_PROPERTY, value);
        mode.updateUi(settings);
    }

    public void setEndpointFields(JTextComponent... fields) {
        endpointFields = fields;
        validatePolicy();
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

    private void validatePolicy() {
        ConfigTestElement element = new ConfigTestElement();
        mode.updateElement(element);
        String value = element.get(HTTPSamplerBaseSchema.INSTANCE.getProxy().getDestinationMode());
        String rules = patterns.getText();
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
        boolean canPreview = usesPatterns && !expression && !rules.contains("${");
        preview.setEnabled(canPreview);
        previewLabel.setEnabled(canPreview);
        description.setText(text(direct ? "proxy_filter_direct_help"
                : expression ? "proxy_filter_runtime" : value + "_help"));
        if (expression || (usesPatterns && rules.contains("${"))) {
            status.setText(text("proxy_filter_runtime"));
            return;
        }
        if (!usesPatterns) {
            status.setText("");
            return;
        }
        try {
            ProxyDestinationPolicy policy = ProxyDestinationPolicy.compile(value, rules);
            if (preview.getText().isBlank()) {
                status.setText("");
                return;
            }
            String host = preview.getText().trim();
            if (host.contains("://")) {
                URI uri = URI.create(host);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                    status.setText(text("proxy_filter_preview_invalid"));
                    return;
                }
                host = uri.toURL().getHost();
            }
            String matched = policy.matchingPattern(host);
            status.setText(text(policy.allowsProxy(host) ? "proxy_filter_preview_proxy" : "proxy_filter_preview_direct")
                    + (matched.isEmpty() ? "" : " " + text("proxy_filter_matched") + " " + matched));
        } catch (IllegalArgumentException | java.net.MalformedURLException e) {
            status.setText(e.getMessage());
        }
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
            preview.setText("");
        } finally {
            updating = false;
        }
        validatePolicy();
    }
}
