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
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Editor of a set of configuration: the shared configuration or a named profile.
 */
public abstract class AbstractProfileGui extends AbstractJMeterGuiComponent {
    private static final long serialVersionUID = 1L;

    protected final VerticalPanel box = new VerticalPanel();

    protected AbstractProfileGui() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        box.add(makeTitlePanel());
        JTextArea info = new JTextArea(JMeterUtils.getResString(getLabelResource() + "_info")); // $NON-NLS-1$
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setOpaque(false);
        box.add(info);
        add(box, BorderLayout.NORTH);
    }

    /** Profiles are only added from the Profiles section. */
    @Override
    public Collection<String> getMenuCategories() {
        return null;
    }

    @Override
    public JPopupMenu createPopupMenu() {
        JPopupMenu pop = new JPopupMenu();
        JMenu addMenu = new JMenu(JMeterUtils.getResString("add")); // $NON-NLS-1$
        addConfigurationMenus(addMenu);
        pop.add(addMenu);
        MenuFactory.addEditMenu(pop, isRemovable());
        MenuFactory.addFileMenu(pop, false);
        return pop;
    }

    /**
     * @return whether the element can be removed from the test plan
     */
    protected abstract boolean isRemovable();

    /**
     * @param addMenu an Add menu to fill with the elements a profile can hold
     */
    static void addConfigurationMenus(JMenu addMenu) {
        addMenu.add(MenuFactory.makeMenu(MenuFactory.CONFIG_ELEMENTS, ActionNames.ADD));
        addMenu.addSeparator();
        addMenu.add(MenuFactory.makeMenu(MenuFactory.TIMERS, ActionNames.ADD));
        addMenu.addSeparator();
        addMenu.add(MenuFactory.makeMenu(MenuFactory.PRE_PROCESSORS, ActionNames.ADD));
        addMenu.add(MenuFactory.makeMenu(MenuFactory.POST_PROCESSORS, ActionNames.ADD));
        addMenu.add(MenuFactory.makeMenu(MenuFactory.ASSERTIONS, ActionNames.ADD));
    }
}
