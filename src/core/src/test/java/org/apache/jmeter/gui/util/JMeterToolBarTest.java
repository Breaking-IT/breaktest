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

package org.apache.jmeter.gui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.LocaleChangeEvent;
import org.junit.jupiter.api.Test;

class JMeterToolBarTest extends JMeterTestCase {
    @Test
    void runButtonFollowsLifecycleAndSurvivesLocaleChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMeterToolBar toolbar = JMeterToolBar.createToolbar(false);
            JButton run = button(toolbar, ActionNames.ACTION_START);
            assertEquals("Run", run.getText());
            assertTrue(run.isEnabled());
            int componentCount = toolbar.getComponentCount();
            toolbar.setLocalTestStarted(true);
            assertEquals(ActionNames.ACTION_PAUSE, run.getActionCommand());
            assertEquals("Pause", run.getText());
            assertTrue(run.isEnabled());
            toolbar.setLocalTestPaused(true);
            assertEquals("Resume", run.getText());
            assertEquals("Resume", run.getAccessibleContext().getAccessibleName());
            assertEquals(JMeterUtils.getResString("resume"), run.getToolTipText());
            toolbar.localeChanged(new LocaleChangeEvent(toolbar));
            run = button(toolbar, ActionNames.ACTION_PAUSE);
            assertEquals("Resume", run.getText());
            assertTrue(run.isEnabled());
            toolbar.setLocalTestPaused(false);
            assertEquals("Pause", run.getText());
            toolbar.setLocalTestStopping();
            assertFalse(run.isEnabled());
            toolbar.setLocalTestStarted(false);
            assertEquals(ActionNames.ACTION_START, run.getActionCommand());
            assertEquals("Run", run.getText());
            assertTrue(run.isEnabled());
            assertEquals(componentCount, toolbar.getComponentCount());
        });
    }

    @Test
    void stopEscalatesAndResetsForNextRun() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMeterToolBar toolbar = JMeterToolBar.createToolbar(false);
            JButton stop = stopButton(toolbar);
            assertFalse(stop.isEnabled());
            toolbar.setLocalTestStarted(true);
            assertTrue(stop.isEnabled());
            assertEquals(ActionNames.ACTION_SHUTDOWN, stop.getActionCommand());
            assertEquals(JMeterUtils.getResString("stop_gracefully_tooltip"), stop.getToolTipText());
            var gracefulIcon = stop.getIcon();

            toolbar.setLocalTestStopping();
            assertTrue(stop.isEnabled());
            assertEquals(ActionNames.ACTION_STOP, stop.getActionCommand());
            assertEquals(JMeterUtils.getResString("stop_now_tooltip"), stop.getToolTipText());
            assertNotSame(gracefulIcon, stop.getIcon());
            assertFalse(button(toolbar, ActionNames.ACTION_START_NO_TIMERS).isEnabled());
            assertFalse(button(toolbar, ActionNames.ACTION_PAUSE).isEnabled());

            toolbar.localeChanged(new LocaleChangeEvent(toolbar));
            stop = stopButton(toolbar);
            assertTrue(stop.isEnabled());
            assertEquals(ActionNames.ACTION_STOP, stop.getActionCommand());

            toolbar.setLocalTestStarted(false);
            assertFalse(stop.isEnabled());
            assertTrue(button(toolbar, ActionNames.ACTION_START).isEnabled());
            toolbar.setLocalTestStarted(true);
            assertTrue(stop.isEnabled());
            assertEquals(ActionNames.ACTION_SHUTDOWN, stop.getActionCommand());
        });
    }

    @Test
    void legacyCustomToolbarCombinesRunPauseAndStopButtons() throws Exception {
        String previous = JMeterUtils.getProperty("jmeter.toolbar");
        try {
            JMeterUtils.setProperty("jmeter.toolbar", "test_start,test_pause,test_shutdown,test_stop");
            SwingUtilities.invokeAndWait(() -> {
                JMeterToolBar toolbar = JMeterToolBar.createToolbar(false);
                assertEquals(2, toolbar.getComponentCount());
                JButton run = button(toolbar, ActionNames.ACTION_START);
                toolbar.setLocalTestStarted(true);
                assertEquals(ActionNames.ACTION_PAUSE, run.getActionCommand());
                assertEquals(ActionNames.ACTION_SHUTDOWN, stopButton(toolbar).getActionCommand());
                toolbar.setLocalTestStopping();
                assertEquals(ActionNames.ACTION_STOP, stopButton(toolbar).getActionCommand());
            });
        } finally {
            if (previous == null) {
                JMeterUtils.getJMeterProperties().remove("jmeter.toolbar");
            } else {
                JMeterUtils.setProperty("jmeter.toolbar", previous);
            }
        }
    }

    private static JButton stopButton(JMeterToolBar toolbar) {
        var buttons = Arrays.stream(toolbar.getComponents())
                .filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> ActionNames.ACTION_STOP.equals(button.getActionCommand())
                        || ActionNames.ACTION_SHUTDOWN.equals(button.getActionCommand())).toList();
        assertEquals(1, buttons.size());
        return buttons.get(0);
    }

    private static JButton button(JMeterToolBar toolbar, String command) {
        return Arrays.stream(toolbar.getComponents())
                .filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> command.equals(button.getActionCommand())).findFirst().orElseThrow();
    }
}
