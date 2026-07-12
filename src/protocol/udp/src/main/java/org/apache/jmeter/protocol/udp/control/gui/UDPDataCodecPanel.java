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

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.apache.jmeter.protocol.udp.sampler.HexStringUDPTrafficCodec;
import org.apache.jmeter.protocol.udp.sampler.RawUDPTrafficCodec;
import org.apache.jmeter.protocol.udp.sampler.UTF8StringUDPTrafficCodec;
import org.apache.jmeter.util.JMeterUtils;

public final class UDPDataCodecPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final CodecOption TEXT = new CodecOption(
            "udp_payload_format_text", UTF8StringUDPTrafficCodec.class.getName(), "udp_payload_format_text_help");
    private static final CodecOption HEX = new CodecOption(
            "udp_payload_format_hex", HexStringUDPTrafficCodec.class.getName(), "udp_payload_format_hex_help");
    private static final CodecOption SINGLE_BYTE = new CodecOption(
            "udp_payload_format_single_byte", RawUDPTrafficCodec.class.getName(),
            "udp_payload_format_single_byte_help");
    private static final CodecOption CUSTOM = new CodecOption(
            "udp_payload_format_custom", null, "udp_payload_format_custom_help");

    private final JComboBox<CodecOption> payloadFormat = new JComboBox<>(
            new CodecOption[] {TEXT, HEX, SINGLE_BYTE, CUSTOM});
    private final JLabel description = new JLabel();
    private final JLabel customClassLabel = new JLabel(JMeterUtils.getResString("udp_custom_codec_class"));
    private final JTextField customClass = new JTextField();

    public UDPDataCodecPanel() {
        super(new GridBagLayout());
        init();
    }

    String getCodecClass() {
        CodecOption selected = (CodecOption) payloadFormat.getSelectedItem();
        return CUSTOM.equals(selected) ? customClass.getText().trim() : selected.className();
    }

    void setCodecClass(String className) {
        String effectiveClassName = className == null ? "" : className.trim();
        CodecOption option = findOption(effectiveClassName);
        payloadFormat.setSelectedItem(option);
        customClass.setText(CUSTOM.equals(option) ? effectiveClassName : "");
        updateSelection();
    }

    void clear() {
        payloadFormat.setSelectedItem(TEXT);
        customClass.setText("");
        updateSelection();
    }

    private void init() {
        GridBagConstraints labelConstraints = constraints(0, 0);
        labelConstraints.anchor = GridBagConstraints.LINE_END;
        JLabel formatLabel = new JLabel(JMeterUtils.getResString("udp_payload_format"));
        formatLabel.setLabelFor(payloadFormat);
        add(formatLabel, labelConstraints);

        GridBagConstraints fieldConstraints = constraints(1, 0);
        fieldConstraints.anchor = GridBagConstraints.LINE_START;
        add(payloadFormat, fieldConstraints);

        GridBagConstraints descriptionConstraints = constraints(2, 0);
        descriptionConstraints.weightx = 1;
        descriptionConstraints.fill = GridBagConstraints.HORIZONTAL;
        descriptionConstraints.anchor = GridBagConstraints.LINE_START;
        add(description, descriptionConstraints);

        GridBagConstraints customLabelConstraints = constraints(0, 1);
        customLabelConstraints.anchor = GridBagConstraints.LINE_END;
        customClassLabel.setLabelFor(customClass);
        add(customClassLabel, customLabelConstraints);

        GridBagConstraints customFieldConstraints = constraints(1, 1);
        customFieldConstraints.gridwidth = 2;
        customFieldConstraints.weightx = 1;
        customFieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        add(customClass, customFieldConstraints);

        payloadFormat.addActionListener(event -> updateSelection());
        clear();
    }

    private void updateSelection() {
        CodecOption selected = (CodecOption) payloadFormat.getSelectedItem();
        boolean custom = CUSTOM.equals(selected);
        description.setText(JMeterUtils.getResString(selected.descriptionResource()));
        customClassLabel.setVisible(custom);
        customClass.setVisible(custom);
        revalidate();
        repaint();
    }

    private static CodecOption findOption(String className) {
        if (className.isEmpty() || RawUDPTrafficCodec.class.getName().equals(className)) {
            return SINGLE_BYTE;
        }
        if (UTF8StringUDPTrafficCodec.class.getName().equals(className)) {
            return TEXT;
        }
        if (HexStringUDPTrafficCodec.class.getName().equals(className)) {
            return HEX;
        }
        return CUSTOM;
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.insets = new Insets(3, 5, 3, 5);
        return constraints;
    }

    private record CodecOption(String labelResource, String className, String descriptionResource) {
        @Override
        public String toString() {
            return JMeterUtils.getResString(labelResource);
        }
    }
}
