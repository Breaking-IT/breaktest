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

package org.apache.jmeter.config.gui;

import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.Locale;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

import org.apache.jmeter.config.ClientCertificateConfig;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

import net.miginfocom.swing.MigLayout;

@TestElementMetadata(labelResource = "client_certificate_config")
public class ClientCertificateConfigGui extends AbstractConfigGui {
    private static final long serialVersionUID = 1L;
    private static final String[] MODES = {
        ClientCertificateConfig.INHERIT, ClientCertificateConfig.CERTIFICATE, ClientCertificateConfig.NONE
    };
    private final JComboBox<String> mode = new JComboBox<>(Arrays.stream(MODES)
            .map(value -> JMeterUtils.getResString("client_certificate_" + value)).toArray(String[]::new));
    private final JTextField store = new JTextField(35);
    private final JComboBox<String> type = new JComboBox<>(new String[] {"PKCS12", "JKS", "JCEKS"});
    private final JPasswordField password = new JPasswordField(25);
    private final JTextField alias = new JTextField(25);

    public ClientCertificateConfigGui() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);
        JPanel fields = new JPanel(new MigLayout("fillx, wrap 2", "[][grow,fill]"));
        fields.add(new JLabel(JMeterUtils.getResString("client_certificate_mode")));
        fields.add(mode);
        fields.add(new JLabel(JMeterUtils.getResString("client_certificate_store")));
        fields.add(store);
        fields.add(new JLabel(JMeterUtils.getResString("client_certificate_type")));
        fields.add(type);
        fields.add(new JLabel(JMeterUtils.getResString("client_certificate_password")));
        fields.add(password);
        fields.add(new JLabel(JMeterUtils.getResString("client_certificate_alias")));
        fields.add(alias);
        fields.add(new JLabel("<html><body style='width: 420px'>"
                + JMeterUtils.getResString("client_certificate_variables") + "</body></html>"), "span 2");
        add(fields, BorderLayout.CENTER);
        mode.addActionListener(event -> updateFields());
        updateFields();
    }

    private void updateFields() {
        boolean enabled = mode.getSelectedIndex() == 1;
        store.setEnabled(enabled);
        type.setEnabled(enabled);
        password.setEnabled(enabled);
        alias.setEnabled(enabled);
    }

    @Override
    public String getLabelResource() {
        return "client_certificate_config";
    }

    @Override
    public TestElement createTestElement() {
        ClientCertificateConfig config = new ClientCertificateConfig();
        modifyTestElement(config);
        return config;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        if (type.getSelectedItem() == null) {
            throw new IllegalArgumentException("Select a keystore type: PKCS12, JKS or JCEKS");
        }
        configureTestElement(element);
        element.setProperty(ClientCertificateConfig.MODE, MODES[mode.getSelectedIndex()]);
        element.setProperty(ClientCertificateConfig.STORE, store.getText());
        element.setProperty(ClientCertificateConfig.TYPE, (String) type.getSelectedItem());
        char[] secret = password.getPassword();
        element.setProperty(ClientCertificateConfig.PASSWORD, new String(secret));
        Arrays.fill(secret, '\0');
        element.setProperty(ClientCertificateConfig.ALIAS, alias.getText());
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        String selected = ((ClientCertificateConfig) element).getMode();
        mode.setSelectedIndex(Math.max(0, Arrays.asList(MODES).indexOf(selected)));
        store.setText(element.getPropertyAsString(ClientCertificateConfig.STORE));
        type.setSelectedIndex(-1);
        type.setSelectedItem(element.getPropertyAsString(ClientCertificateConfig.TYPE, "PKCS12").toUpperCase(Locale.ROOT));
        password.setText(element.getPropertyAsString(ClientCertificateConfig.PASSWORD));
        alias.setText(element.getPropertyAsString(ClientCertificateConfig.ALIAS));
        updateFields();
    }

    @Override
    public void clearGui() {
        super.clearGui();
        mode.setSelectedIndex(0);
        store.setText("");
        type.setSelectedItem("PKCS12");
        password.setText("");
        alias.setText("");
        updateFields();
    }
}
