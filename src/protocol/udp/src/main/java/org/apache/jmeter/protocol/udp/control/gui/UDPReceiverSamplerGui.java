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
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.protocol.udp.sampler.UDPReceiverSampler;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

@TestElementMetadata(labelResource = "udp_receiver_title")
public class UDPReceiverSamplerGui extends AbstractSamplerGui {

    private static final long serialVersionUID = 1L;

    private JTextField timeout;
    private JTextField socketId;
    private JCheckBox failOnTimeout;
    private UDPDataCodecPanel codecPanel;

    public UDPReceiverSamplerGui() {
        init();
    }

    @Override
    public String getLabelResource() {
        return "udp_receiver_title"; // $NON-NLS-1$
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        UDPReceiverSampler sampler = (UDPReceiverSampler) element;
        timeout.setText(sampler.getTimeout());
        codecPanel.setCodecClass(sampler.getEncoderClass());
        socketId.setText(sampler.getSocketID());
        failOnTimeout.setSelected(sampler.isFailOnTimeout());
    }

    @Override
    public TestElement createTestElement() {
        UDPReceiverSampler sampler = new UDPReceiverSampler();
        modifyTestElement(sampler);
        return sampler;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        String codecClass = codecPanel.getCodecClassOrReportError();
        if (codecClass == null) {
            return;
        }
        UDPReceiverSampler sampler = (UDPReceiverSampler) element;
        sampler.clear();
        sampler.setTimeout(timeout.getText());
        sampler.setEncoderClass(codecClass);
        sampler.setSocketID(socketId.getText());
        sampler.setFailOnTimeout(failOnTimeout.isSelected());
        super.configureTestElement(sampler);
    }

    @Override
    public void clearGui() {
        super.clearGui();
        timeout.setText("1000"); // $NON-NLS-1$
        codecPanel.clear();
        socketId.setText(""); // $NON-NLS-1$
        failOnTimeout.setSelected(false);
    }

    private void init() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        VerticalPanel content = new VerticalPanel();
        content.add(createSocketPanel());
        content.add(createReceivePanel());
        codecPanel = new UDPDataCodecPanel();
        codecPanel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString("udp_response_data")));
        content.add(codecPanel);
        add(content, BorderLayout.CENTER);
        clearGui();
    }

    private JPanel createSocketPanel() {
        JPanel panel = createSection("udp_shared_socket");
        socketId = new JTextField();
        addRow(panel, 0, "udp_socket_id", socketId);
        addHelpRow(panel, 1, "udp_receiver_socket_id_help");
        return panel;
    }

    private JPanel createReceivePanel() {
        JPanel panel = createSection("udp_receive_settings");
        timeout = new JTextField();
        addRow(panel, 0, "udp_timeout", timeout);
        addHelpRow(panel, 1, "udp_single_datagram_help");
        failOnTimeout = new JCheckBox(JMeterUtils.getResString("udp_fail_on_timeout"));
        GridBagConstraints failConstraints = constraints(0, 2);
        failConstraints.gridwidth = 2;
        failConstraints.anchor = GridBagConstraints.LINE_START;
        panel.add(failOnTimeout, failConstraints);
        return panel;
    }

    private static JPanel createSection(String titleResource) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString(titleResource)));
        return panel;
    }

    private static void addRow(JPanel panel, int row, String labelResource, JTextField field) {
        JLabel label = new JLabel(JMeterUtils.getResString(labelResource));
        label.setLabelFor(field);
        GridBagConstraints labelConstraints = constraints(0, row);
        labelConstraints.anchor = GridBagConstraints.LINE_END;
        panel.add(label, labelConstraints);

        GridBagConstraints fieldConstraints = constraints(1, row);
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, fieldConstraints);
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
