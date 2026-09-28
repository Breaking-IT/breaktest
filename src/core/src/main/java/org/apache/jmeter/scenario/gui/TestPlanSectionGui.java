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


package org.apache.jmeter.scenario.gui;

import java.awt.BorderLayout;
import java.util.Collection;

import javax.swing.JMenu;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;

import org.apache.jmeter.gui.AbstractJMeterGuiComponent;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Editor of a fixed test plan section: its name, comments and a short explanation of what it holds.
 */
public abstract class TestPlanSectionGui extends AbstractJMeterGuiComponent {
    private static final long serialVersionUID = 1L;

    protected TestPlanSectionGui() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        VerticalPanel box = new VerticalPanel();
        box.add(makeTitlePanel());
        // Sections are a fixed part of every test plan, and Module Controller paths contain their names
        setNameEditable(false);
        JTextArea info = new JTextArea(JMeterUtils.getResString(getLabelResource() + "_info")); // $NON-NLS-1$
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setOpaque(false);
        box.add(info);
        add(box, BorderLayout.NORTH);
    }

    /** Sections are created with the test plan and cannot be added from a menu. */
    @Override
    public Collection<String> getMenuCategories() {
        return null;
    }

    @Override
    public JPopupMenu createPopupMenu() {
        JPopupMenu pop = new JPopupMenu();
        JMenu addMenu = new JMenu(JMeterUtils.getResString("add")); // $NON-NLS-1$
        addToAddMenu(addMenu);
        pop.add(addMenu);
        MenuFactory.addEditMenu(pop, isRemovableSection(), false);
        MenuFactory.addFileMenu(pop, false);
        return pop;
    }

    /**
     * @return whether the section can be removed; only sections created on demand can
     */
    protected boolean isRemovableSection() {
        return false;
    }

    /**
     * @param addMenu the Add menu of this section, to fill with the elements the section accepts
     */
    protected abstract void addToAddMenu(JMenu addMenu);
}
