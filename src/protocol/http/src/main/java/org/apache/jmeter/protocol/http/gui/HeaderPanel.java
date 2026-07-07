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

package org.apache.jmeter.protocol.http.gui;

import java.awt.BorderLayout;

import javax.swing.BorderFactory;

import org.apache.jmeter.config.gui.AbstractConfigGui;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Allows the user to specify if they need HTTP header services, and give
 * parameters for this service. The table itself lives in {@link HeaderTablePanel},
 * shared with the HTTP Request sampler's Headers tab.
 */
@GUIMenuSortOrder(2)
@TestElementMetadata(labelResource = "header_manager_title")
public class HeaderPanel extends AbstractConfigGui {

    private static final long serialVersionUID = 241L;

    private final HeaderTablePanel headerTablePanel;
    private final HeaderManager headerManager;

    public HeaderPanel() {
        headerTablePanel = new HeaderTablePanel(true);
        headerManager = headerTablePanel.getModel();
        init();
    }

    @Override
    public TestElement createTestElement() {
        configureTestElement(headerManager);
        return (TestElement) headerManager.clone();
    }

    /**
     * Modifies a given TestElement to mirror the data in the gui components.
     *
     * @see org.apache.jmeter.gui.JMeterGUIComponent#modifyTestElement(TestElement)
     */
    @Override
    public void modifyTestElement(TestElement el) {
        headerTablePanel.stopEditing();
        el.clear();
        el.addTestElement(headerManager);
        configureTestElement(el);
    }

    @Override
    public void clearGui() {
        super.clearGui();
        headerTablePanel.clear();
    }

    @Override
    public void configure(TestElement el) {
        headerManager.clear();
        super.configure(el);
        headerManager.addTestElement(el);
        headerTablePanel.refresh();
    }

    @Override
    public String getLabelResource() {
        return "header_manager_title"; // $NON-NLS-1$
    }

    private void init() {// called from ctor, so must not be overridable
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());

        headerTablePanel.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString("headers_stored"))); // $NON-NLS-1$

        add(makeTitlePanel(), BorderLayout.NORTH);
        add(headerTablePanel, BorderLayout.CENTER);
    }
}
