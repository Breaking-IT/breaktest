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

package org.apache.jmeter.gui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.Collection;
import java.util.Collections;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

import org.apache.jmeter.testelement.MissingTestElement;
import org.apache.jmeter.testelement.TestElement;

/**
 * Displays a placeholder for JMX elements whose plugin classes are unavailable.
 */
public class MissingTestElementGui extends JPanel implements JMeterGUIComponent {
    private static final long serialVersionUID = 241L;

    private static final String LABEL = "Missing Test Element"; // $NON-NLS-1$

    private final JLabel name = new JLabel();
    private final JTextArea message = new JTextArea();

    public MissingTestElementGui() {
        init();
    }

    private void init() {
        setLayout(new BorderLayout(0, 12));

        JPanel details = new JPanel(new GridBagLayout());
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.anchor = GridBagConstraints.NORTHWEST;
        labelConstraints.gridx = 0;
        labelConstraints.gridy = 0;

        GridBagConstraints valueConstraints = new GridBagConstraints();
        valueConstraints.anchor = GridBagConstraints.NORTHWEST;
        valueConstraints.fill = GridBagConstraints.HORIZONTAL;
        valueConstraints.weightx = 1;
        valueConstraints.gridx = 1;
        valueConstraints.gridy = 0;

        details.add(new JLabel("Name: "), labelConstraints); // $NON-NLS-1$
        details.add(name, valueConstraints);

        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.setFocusable(false);

        add(details, BorderLayout.NORTH);
        add(message, BorderLayout.CENTER);
    }

    @Override
    public void clearGui() {
        name.setText(""); // $NON-NLS-1$
        message.setText(""); // $NON-NLS-1$
    }

    @Override
    public void setName(String name) {
        super.setName(name);
        if (this.name != null) {
            this.name.setText(name);
        }
    }

    @Override
    public String getName() {
        if (name != null) {
            return name.getText();
        }
        return ""; // $NON-NLS-1$
    }

    @Override
    public String getStaticLabel() {
        return LABEL;
    }

    @Override
    public String getLabelResource() {
        return ""; // $NON-NLS-1$
    }

    @Override
    public String getDocAnchor() {
        return null;
    }

    @Override
    public void configure(TestElement testElement) {
        setName(testElement.getName());
        String testClass = testElement.getPropertyAsString(MissingTestElement.MISSING_TEST_CLASS);
        String guiClass = testElement.getPropertyAsString(MissingTestElement.MISSING_GUI_CLASS);
        String reason = testElement.getPropertyAsString(MissingTestElement.MISSING_REASON);
        message.setText("""
                This element cannot be loaded because a plugin class is missing.
                It will be ignored when running or validating the test plan.

                Missing test class: %s
                Missing GUI class: %s
                Reason: %s
                """.formatted(emptyToUnavailable(testClass), emptyToUnavailable(guiClass), emptyToUnavailable(reason)));
    }

    private static String emptyToUnavailable(String value) {
        return value == null || value.isEmpty() ? "unavailable" : value; // $NON-NLS-1$
    }

    @Override
    public JPopupMenu createPopupMenu() {
        return null;
    }

    @Override
    public Collection<String> getMenuCategories() {
        return Collections.emptyList();
    }

    @Override
    public TestElement makeTestElement() {
        return new MissingTestElement();
    }

    @Override
    public void modifyTestElement(TestElement testElement) {
        testElement.setName(getName());
        testElement.setProperty(TestElement.TEST_CLASS, MissingTestElement.class.getName());
        testElement.setProperty(TestElement.GUI_CLASS, getClass().getName());
        testElement.setEnabled(false);
    }
}
