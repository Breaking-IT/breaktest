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

package org.apache.jmeter.protocol.http.proxy.gui;

import java.awt.event.ActionEvent;
import java.util.Set;

import javax.swing.tree.TreePath;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.AbstractActionWithNoRunningTest;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Command;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.apache.jmeter.threads.gui.ThreadGroupGui;

import com.google.auto.service.AutoService;

/** Creates a recording destination and opens a recorder configured to use it. */
@AutoService(Command.class)
public final class WelcomeRecorderAction extends AbstractActionWithNoRunningTest {
    @Override
    public Set<String> getActionNames() {
        return Set.of(ActionNames.WELCOME_RECORDER);
    }

    @Override
    public void doActionAfterCheck(ActionEvent event) throws IllegalUserActionException {
        GuiPackage gui = GuiPackage.getInstance();
        gui.updateCurrentNode();
        JMeterTreeNode recorder = createRecordingPlan(gui);
        gui.setDirty(true);
        TreePath path = new TreePath(recorder.getPath());
        gui.getMainFrame().getTree().expandPath(path.getParentPath());
        gui.getTreeListener().setSelectionPathWithoutEdit(path);
        ActionRouter.getInstance().doActionNow(new ActionEvent(event.getSource(), event.getID(), ActionNames.EDIT));
    }

    static JMeterTreeNode createRecordingPlan(GuiPackage gui) throws IllegalUserActionException {
        JMeterTreeModel model = gui.getTreeModel();
        JMeterTreeNode plan = (JMeterTreeNode) model.getTestPlan().getArray()[0];
        JMeterTreeNode group = model.addComponent(gui.createTestElement(ThreadGroupGui.class.getName()), plan);
        ProxyControl recorder = (ProxyControl) gui.createTestElement(ProxyControlGui.class.getName());
        JMeterTreeNode recorderNode = model.addComponent(recorder, plan);
        // Set the target after insertion: adding a component configures its GUI and can reset the target.
        recorder.setTarget(group);
        return recorderNode;
    }
}
