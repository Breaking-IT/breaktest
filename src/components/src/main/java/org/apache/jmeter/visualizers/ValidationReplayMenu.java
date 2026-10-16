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

package org.apache.jmeter.visualizers;

import java.awt.event.ActionEvent;

import javax.swing.JMenuItem;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jmeter.util.JMeterUtils;

final class ValidationReplayMenu {
    private ValidationReplayMenu() {
    }

    static JMenuItem createMenuItem(SampleResult result) {
        JMenuItem replay = new JMenuItem(JMeterUtils.getResString("view_results_replay_from_here"));
        replay.setEnabled(!JMeterUtils.isTestRunning()
                && ValidationCheckpoints.canReplay(result));
        replay.setToolTipText(JMeterUtils.getResString("view_results_replay_from_here_tooltip"));
        replay.addActionListener(e -> ActionRouter.getInstance().actionPerformed(
                new ActionEvent(result, ActionEvent.ACTION_PERFORMED, ActionNames.RESUME_VALIDATION)));
        return replay;
    }

}
