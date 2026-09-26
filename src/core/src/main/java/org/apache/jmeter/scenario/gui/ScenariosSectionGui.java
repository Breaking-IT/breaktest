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

import javax.swing.JMenu;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.scenario.ScenariosSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

public class ScenariosSectionGui extends TestPlanSectionGui {
    private static final long serialVersionUID = 1L;

    @Override
    public String getLabelResource() {
        return "scenarios_section"; // $NON-NLS-1$
    }

    @Override
    public TestElement makeTestElement() {
        return new ScenariosSection();
    }

    @Override
    protected void addToAddMenu(JMenu addMenu) {
        addMenu.add(MenuFactory.makeMenuItem(JMeterUtils.getResString("scenario_title"), // $NON-NLS-1$
                ScenarioGui.class.getName(), ActionNames.ADD));
    }
}
