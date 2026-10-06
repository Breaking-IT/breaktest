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

package org.apache.jmeter.samplers.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import org.apache.jmeter.control.IfController;
import org.apache.jmeter.control.gui.IfControllerPanel;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.samplers.SampleIgnorePolicy;
import org.apache.jmeter.samplers.WaitForValue;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

@TestElementMetadata(labelResource = "wait_for_value_title")
public class WaitForValueGui extends AbstractSamplerGui {
    private static final long serialVersionUID = 1L;
    private final IfControllerPanel conditions = new IfControllerPanel(false, true);
    private final JTextField timeout = new JTextField("30000", 12);

    public WaitForValueGui() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        JPanel header = new JPanel(new BorderLayout(0, 5));
        header.add(makeTitlePanel(), BorderLayout.NORTH);
        add(header, BorderLayout.NORTH);
        add(conditions, BorderLayout.CENTER);
        JPanel timeoutPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        timeoutPanel.add(JMeterUtils.labelFor(timeout, "wait_for_value_timeout"));
        timeoutPanel.add(timeout);
        timeoutPanel.add(new JLabel(JMeterUtils.getResString("wait_for_value_hint")));
        JPanel settings = new JPanel(new BorderLayout(0, 5));
        settings.add(timeoutPanel, BorderLayout.NORTH);
        JPanel resultPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        resultPanel.add(new JLabel(JMeterUtils.getResString("wait_for_value_result_handling")));
        ButtonGroup policies = new ButtonGroup();
        for (SampleIgnorePolicy policy : SampleIgnorePolicy.values()) {
            JRadioButton option = new JRadioButton(JMeterUtils.getResString(policy.getLabelResource()));
            option.setToolTipText(JMeterUtils.getResString("sampler_ignore_tooltip"));
            option.addActionListener(e -> setSampleIgnorePolicy(policy));
            addPropertyChangeListener("sampleIgnorePolicy", e -> option.setSelected(e.getNewValue() == policy));
            policies.add(option);
            resultPanel.add(option);
        }
        settings.add(resultPanel, BorderLayout.SOUTH);
        header.add(settings, BorderLayout.SOUTH);
        setSampleIgnorePolicy(SampleIgnorePolicy.ON_SUCCESS);
    }

    @Override
    public String getLabelResource() {
        return "wait_for_value_title";
    }

    @Override
    public TestElement createTestElement() {
        WaitForValue action = new WaitForValue();
        modifyTestElement(action);
        return action;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        configureTestElement(element);
        WaitForValue action = (WaitForValue) element;
        IfController settings = new IfController();
        conditions.modifyTestElement(settings);
        action.setConditions(settings);
        action.setTimeout(timeout.getText());
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        WaitForValue action = (WaitForValue) element;
        conditions.configure(action.getConditions());
        timeout.setText(action.getTimeout());
    }

    @Override
    public void clearGui() {
        super.clearGui();
        conditions.clearGui();
        timeout.setText("30000");
        setSampleIgnorePolicy(SampleIgnorePolicy.ON_SUCCESS);
    }
}
