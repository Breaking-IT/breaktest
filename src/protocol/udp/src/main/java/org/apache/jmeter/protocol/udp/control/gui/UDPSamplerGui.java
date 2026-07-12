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

package org.apache.jmeter.protocol.udp.control.gui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.gui.util.JTextScrollPane;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.protocol.udp.sampler.UDPSampler;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

@TestElementMetadata(labelResource = "udp_sample_title")
public class UDPSamplerGui extends AbstractSamplerGui {

    private static final long serialVersionUID = 1L;

    private JTextField hostName;
    private JTextField port;
    private JTextField timeout;
    private JTextField socketId;
    private JTextField bindAddress;
    private JTextField bindPort;
    private JCheckBox waitResponse;
    private JCheckBox closeSocket;
    private JCheckBox allowUnexpectedDisconnect;
    private JLabel timeoutLabel;
    private JSyntaxTextArea requestData;
    private UDPDataCodecPanel codecPanel;

    public UDPSamplerGui() {
        init();
    }

    @Override
    public String getLabelResource() {
        return "udp_sample_title"; // $NON-NLS-1$
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        UDPSampler sampler = (UDPSampler) element;
        hostName.setText(sampler.getHostName());
        port.setText(sampler.getPort());
        timeout.setText(sampler.getTimeout());
        codecPanel.setCodecClass(sampler.getEncoderClass());
        socketId.setText(sampler.getSocketID());
        bindAddress.setText(sampler.getBindAddress());
        bindPort.setText(sampler.getBindPort());
        waitResponse.setSelected(sampler.isWaitResponse());
        closeSocket.setSelected(sampler.isCloseChannel());
        allowUnexpectedDisconnect.setSelected(sampler.isAllowUnexpectedDisconnect());
        requestData.setInitialText(sampler.getRequestData());
        requestData.setCaretPosition(0);
        updateResponseControls();
    }

    @Override
    public TestElement createTestElement() {
        UDPSampler sampler = new UDPSampler();
        modifyTestElement(sampler);
        return sampler;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        UDPSampler sampler = (UDPSampler) element;
        sampler.clear();
        sampler.setHostName(hostName.getText());
        sampler.setPort(port.getText());
        sampler.setTimeout(timeout.getText());
        sampler.setEncoderClass(codecPanel.getCodecClass());
        sampler.setSocketID(socketId.getText());
        sampler.setBindAddress(bindAddress.getText());
        sampler.setBindPort(bindPort.getText());
        sampler.setWaitResponse(waitResponse.isSelected());
        sampler.setCloseChannel(closeSocket.isSelected());
        sampler.setAllowUnexpectedDisconnect(allowUnexpectedDisconnect.isSelected());
        sampler.setRequestData(requestData.getText());
        super.configureTestElement(sampler);
    }

    @Override
    public void clearGui() {
        super.clearGui();
        hostName.setText(""); // $NON-NLS-1$
        port.setText(""); // $NON-NLS-1$
        timeout.setText("1000"); // $NON-NLS-1$
        codecPanel.clear();
        socketId.setText(""); // $NON-NLS-1$
        bindAddress.setText(""); // $NON-NLS-1$
        bindPort.setText(""); // $NON-NLS-1$
        waitResponse.setSelected(true);
        closeSocket.setSelected(false);
        allowUnexpectedDisconnect.setSelected(false);
        requestData.setInitialText(""); // $NON-NLS-1$
        updateResponseControls();
    }

    private void init() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        VerticalPanel settings = new VerticalPanel();
        settings.add(createEndpointPanel());
        settings.add(createResponsePanel());
        settings.add(createSocketPanel());

        JPanel content = new JPanel(new BorderLayout(0, 5));
        content.add(settings, BorderLayout.NORTH);
        content.add(createPayloadPanel(), BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);
        clearGui();
    }

    private JPanel createEndpointPanel() {
        JPanel panel = createSection("udp_remote_endpoint");
        hostName = new JTextField();
        port = new JTextField(8);
        addAddressAndPortRow(panel, 0, "udp_host", hostName, "udp_port", port);

        bindAddress = new JTextField();
        bindPort = new JTextField(8);
        addAddressAndPortRow(panel, 1, "udp_bind_address", bindAddress, "udp_bind_port", bindPort);
        return panel;
    }

    private JPanel createResponsePanel() {
        JPanel panel = createSection("udp_response_handling");
        waitResponse = new JCheckBox(JMeterUtils.getResString("udp_wait_response"));
        waitResponse.addActionListener(event -> updateResponseControls());
        GridBagConstraints waitConstraints = constraints(0, 0);
        waitConstraints.gridwidth = 2;
        waitConstraints.anchor = GridBagConstraints.LINE_START;
        panel.add(waitResponse, waitConstraints);
        addHelpRow(panel, 1, "udp_single_datagram_help");

        timeout = new JTextField();
        timeoutLabel = addRow(panel, 2, "udp_timeout", timeout);

        allowUnexpectedDisconnect = new JCheckBox(JMeterUtils.getResString("udp_allow_disconnect"));
        allowUnexpectedDisconnect.setToolTipText(JMeterUtils.getResString("udp_allow_disconnect_help"));
        GridBagConstraints disconnectConstraints = constraints(0, 3);
        disconnectConstraints.gridwidth = 2;
        disconnectConstraints.anchor = GridBagConstraints.LINE_START;
        panel.add(allowUnexpectedDisconnect, disconnectConstraints);
        return panel;
    }

    private JPanel createSocketPanel() {
        JPanel panel = createSection("udp_socket_settings");
        socketId = new JTextField();
        addRow(panel, 0, "udp_socket_id", socketId);
        addHelpRow(panel, 1, "udp_socket_id_help");

        closeSocket = new JCheckBox(JMeterUtils.getResString("udp_close_socket"));
        closeSocket.setToolTipText(JMeterUtils.getResString("udp_close_socket_help"));
        GridBagConstraints closeConstraints = constraints(0, 2);
        closeConstraints.gridwidth = 2;
        closeConstraints.anchor = GridBagConstraints.LINE_START;
        panel.add(closeSocket, closeConstraints);
        return panel;
    }

    private JPanel createPayloadPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString("udp_payload")));
        codecPanel = new UDPDataCodecPanel();
        panel.add(codecPanel, BorderLayout.NORTH);

        requestData = JSyntaxTextArea.getInstance(10, 80);
        requestData.setLanguage("text"); // $NON-NLS-1$
        JLabel requestLabel = new JLabel(JMeterUtils.getResString("udp_request_data"));
        requestLabel.setLabelFor(requestData);
        JPanel editor = new JPanel(new BorderLayout(0, 3));
        editor.add(requestLabel, BorderLayout.NORTH);
        editor.add(JTextScrollPane.getInstance(requestData), BorderLayout.CENTER);
        panel.add(editor, BorderLayout.CENTER);
        return panel;
    }

    private void updateResponseControls() {
        boolean enabled = waitResponse.isSelected();
        timeout.setEnabled(enabled);
        timeoutLabel.setEnabled(enabled);
    }

    private static JPanel createSection(String titleResource) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString(titleResource)));
        return panel;
    }

    private static JLabel addRow(JPanel panel, int row, String labelResource, JTextField field) {
        JLabel label = new JLabel(JMeterUtils.getResString(labelResource));
        label.setLabelFor(field);
        GridBagConstraints labelConstraints = constraints(0, row);
        labelConstraints.anchor = GridBagConstraints.LINE_END;
        panel.add(label, labelConstraints);

        GridBagConstraints fieldConstraints = constraints(1, row);
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, fieldConstraints);
        return label;
    }

    private static void addAddressAndPortRow(
            JPanel panel,
            int row,
            String addressLabelResource,
            JTextField addressField,
            String portLabelResource,
            JTextField portField) {
        JLabel addressLabel = new JLabel(JMeterUtils.getResString(addressLabelResource));
        addressLabel.setLabelFor(addressField);
        GridBagConstraints addressLabelConstraints = constraints(0, row);
        addressLabelConstraints.anchor = GridBagConstraints.LINE_END;
        panel.add(addressLabel, addressLabelConstraints);

        GridBagConstraints addressFieldConstraints = constraints(1, row);
        addressFieldConstraints.weightx = 1;
        addressFieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(addressField, addressFieldConstraints);

        JLabel portLabel = new JLabel(JMeterUtils.getResString(portLabelResource));
        portLabel.setLabelFor(portField);
        GridBagConstraints portLabelConstraints = constraints(2, row);
        portLabelConstraints.anchor = GridBagConstraints.LINE_END;
        panel.add(portLabel, portLabelConstraints);

        GridBagConstraints portFieldConstraints = constraints(3, row);
        portFieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(portField, portFieldConstraints);
    }

    private static void addHelpRow(JPanel panel, int row, String resource) {
        JLabel help = new JLabel(JMeterUtils.getResString(resource));
        GridBagConstraints constraints = constraints(1, row);
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.LINE_START;
        panel.add(help, constraints);
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.insets = new Insets(3, 5, 3, 5);
        return constraints;
    }
}
