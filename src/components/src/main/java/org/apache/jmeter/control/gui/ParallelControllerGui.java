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

package org.apache.jmeter.control.gui;

import java.awt.BorderLayout;

import javax.swing.JPanel;
import javax.swing.JTextField;

import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.util.MenuInfo;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

import net.miginfocom.swing.MigLayout;

@GUIMenuSortOrder(MenuInfo.SORT_ORDER_DEFAULT + 1)
@TestElementMetadata(labelResource = "parallel_controller_title")
public class ParallelControllerGui extends AbstractControllerGui {
    private static final long serialVersionUID = 240L;

    private JTextField maxParallel;

    public ParallelControllerGui() {
        init();
    }

    @Override
    public TestElement createTestElement() {
        ParallelController controller = new ParallelController();
        modifyTestElement(controller);
        return controller;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        configureTestElement(element);
        ParallelController controller = (ParallelController) element;
        try {
            controller.setMaxParallel(Integer.parseInt(maxParallel.getText().trim()));
        } catch (NumberFormatException e) {
            controller.setMaxParallel(maxParallel.getText().trim());
        }
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        maxParallel.setText(((ParallelController) element).getMaxParallelString());
    }

    @Override
    public void clearGui() {
        super.clearGui();
        maxParallel.setText("6"); // $NON-NLS-1$
    }

    @Override
    public String getLabelResource() {
        return "parallel_controller_title"; // $NON-NLS-1$
    }

    private void init() {
        setLayout(new BorderLayout());
        setBorder(makeBorder());

        JPanel panel = new JPanel(new MigLayout("fillx, wrap 2", "[][fill,grow]"));
        maxParallel = new JTextField(8);
        maxParallel.setText("6"); // $NON-NLS-1$
        panel.add(JMeterUtils.labelFor(maxParallel, "parallel_controller_max_parallel"));
        panel.add(maxParallel);

        add(makeTitlePanel(), BorderLayout.NORTH);
        add(panel, BorderLayout.CENTER);
    }
}
