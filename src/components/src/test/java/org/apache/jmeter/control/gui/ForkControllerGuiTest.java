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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.ForkController.FinalStopAction;
import org.apache.jmeter.control.ForkController.IterationEndAction;
import org.apache.jmeter.control.ForkController.RunningAction;
import org.junit.jupiter.api.Test;

class ForkControllerGuiTest {
    @Test
    void lifecycleChoicesRoundTripAndClearToDefaults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ForkControllerGui gui = new ForkControllerGui();
            for (IterationEndAction end : IterationEndAction.values()) {
                for (RunningAction running : RunningAction.values()) {
                    for (FinalStopAction stop : FinalStopAction.values()) {
                        ForkController source = new ForkController();
                        source.setIterationEndAction(end);
                        source.setRunningAction(running);
                        source.setFinalStopAction(stop);
                        gui.configure(source);
                        ForkController saved = (ForkController) gui.createTestElement();
                        assertEquals(end, saved.getIterationEndAction());
                        assertEquals(running, saved.getRunningAction());
                        assertEquals(stop, saved.getFinalStopAction());
                    }
                }
            }
            gui.clearGui();
            ForkController defaults = (ForkController) gui.createTestElement();
            assertEquals(IterationEndAction.GRACEFUL, defaults.getIterationEndAction());
            assertEquals(RunningAction.SKIP, defaults.getRunningAction());
            assertEquals(FinalStopAction.GRACEFUL, defaults.getFinalStopAction());
        });
    }

    @Test
    void keepRunningRoundTripsWithoutThreadGroupContext() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ForkControllerGui gui = new ForkControllerGui();
            ForkController source = new ForkController();
            source.setIterationEndAction(IterationEndAction.KEEP_RUNNING);
            gui.configure(source);
            assertEquals(IterationEndAction.KEEP_RUNNING,
                    ((ForkController) gui.createTestElement()).getIterationEndAction());
            gui.configure(source);
            assertEquals(IterationEndAction.KEEP_RUNNING,
                    ((ForkController) gui.createTestElement()).getIterationEndAction());
        });
    }
    @Test
    void selectingLegacyControllerDoesNotOptIntoNewLifecycle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ForkControllerGui gui = new ForkControllerGui();
            ForkController legacy = new ForkController();
            gui.configure(legacy);
            gui.modifyTestElement(legacy);
            assertFalse(legacy.hasLifecyclePolicy());
            assertEquals(IterationEndAction.LEGACY, legacy.getIterationEndAction());
            assertEquals(RunningAction.WAIT, legacy.getRunningAction());
        });
    }

    @Test
    void editingLegacyReentryPreservesCarryOverAndLegacyFinalWait() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ForkControllerGui gui = new ForkControllerGui();
            ForkController legacy = new ForkController();
            gui.configure(legacy);
            selectOption(gui, RunningAction.SKIP);
            gui.modifyTestElement(legacy);
            assertEquals(RunningAction.SKIP, legacy.getRunningAction());
            assertEquals(IterationEndAction.LEGACY, legacy.getIterationEndAction());
            assertFalse(legacy.hasLifecyclePolicy());
            gui.configure(legacy);
            gui.modifyTestElement(legacy);
            assertFalse(legacy.hasLifecyclePolicy());
            selectOption(gui, IterationEndAction.KEEP_RUNNING);
            selectOption(gui, FinalStopAction.IMMEDIATE);
            gui.modifyTestElement(legacy);
            assertEquals("KEEP_RUNNING", legacy.getPropertyAsString("ForkController.iteration_end_action"));
            assertEquals(FinalStopAction.IMMEDIATE, legacy.getFinalStopAction());
            assertEquals(RunningAction.SKIP, legacy.getRunningAction());
        });
    }

    private static void selectOption(Container parent, Enum<?> option) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JComboBox<?> combo
                    && option.getDeclaringClass().isInstance(combo.getSelectedItem())) {
                combo.setSelectedItem(option);
            } else if (component instanceof Container container) {
                selectOption(container, option);
            }
        }
    }

}
