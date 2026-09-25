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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.ForkController.ErrorAction;
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
    void missingSettingsUseCurrentDefaults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ForkControllerGui gui = new ForkControllerGui();
            ForkController controller = new ForkController();
            gui.configure(controller);
            gui.modifyTestElement(controller);
            assertEquals(IterationEndAction.GRACEFUL, controller.getIterationEndAction());
            assertEquals(RunningAction.SKIP, controller.getRunningAction());
        });
    }

    @Test
    void editingReentryPreservesIterationEndChoice() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ForkControllerGui gui = new ForkControllerGui();
            ForkController controller = new ForkController();
            gui.configure(controller);
            selectOption(gui, RunningAction.SKIP);
            gui.modifyTestElement(controller);
            assertEquals(RunningAction.SKIP, controller.getRunningAction());
            assertEquals(IterationEndAction.GRACEFUL, controller.getIterationEndAction());
            gui.configure(controller);
            gui.modifyTestElement(controller);
            selectOption(gui, IterationEndAction.KEEP_RUNNING);
            selectOption(gui, FinalStopAction.IMMEDIATE);
            gui.modifyTestElement(controller);
            assertEquals("KEEP_RUNNING", controller.getPropertyAsString("ForkController.iteration_end_action"));
            assertEquals(FinalStopAction.IMMEDIATE, controller.getFinalStopAction());
            assertEquals(RunningAction.SKIP, controller.getRunningAction());
        });
    }

    @Test
    void errorPoliciesRoundTripAndDefaultToContinue() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ForkControllerGui gui = new ForkControllerGui();
            for (ErrorAction action : ErrorAction.values()) {
                ForkController source = new ForkController();
                source.setErrorAction(action);
                gui.configure(source);
                assertEquals(action, ((ForkController) gui.createTestElement()).getErrorAction());
            }
            gui.clearGui();
            assertEquals(ErrorAction.CONTINUE, ((ForkController) gui.createTestElement()).getErrorAction());
            gui.configure(new ForkController());
            assertEquals(ErrorAction.CONTINUE, ((ForkController) gui.createTestElement()).getErrorAction());
        });
    }

    @Test
    void keepRunningIsHiddenForDifferentUsersWithoutOverwritingSavedChoices() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicBoolean sameUser = new AtomicBoolean(false);
            ForkControllerGui gui = new ForkControllerGui() {
                private static final long serialVersionUID = 1L;

                @Override
                boolean isKeepRunningAvailable() {
                    return sameUser.get();
                }
            };
            gui.clearGui();
            assertFalse(findOption(gui, IterationEndAction.KEEP_RUNNING).isVisible());
            assertEquals(IterationEndAction.GRACEFUL, ((ForkController) gui.createTestElement()).getIterationEndAction());
            ForkController saved = new ForkController();
            saved.setIterationEndAction(IterationEndAction.KEEP_RUNNING);
            saved.setFinalStopAction(FinalStopAction.IMMEDIATE);
            gui.configure(saved);
            assertFalse(findOption(gui, IterationEndAction.KEEP_RUNNING).isVisible());
            assertTrue(findOption(gui, IterationEndAction.IMMEDIATE).isSelected());
            selectOption(gui, RunningAction.RESTART);
            gui.modifyTestElement(saved);
            assertEquals(IterationEndAction.KEEP_RUNNING, saved.getIterationEndAction());
            sameUser.set(true);
            gui.configure(saved);
            assertTrue(findOption(gui, IterationEndAction.KEEP_RUNNING).isVisible());
            assertTrue(findOption(gui, IterationEndAction.KEEP_RUNNING).isSelected());
            sameUser.set(false);
            gui.configure(saved);
            selectOption(gui, IterationEndAction.GRACEFUL);
            gui.modifyTestElement(saved);
            assertEquals(IterationEndAction.GRACEFUL, saved.getIterationEndAction());
        });
    }

    private static JRadioButton findOption(Container parent, Enum<?> option) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JRadioButton button && button.getClientProperty("fork.option") == option) {
                return button;
            }
            if (component instanceof Container container) {
                JRadioButton found = findOption(container, option);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void selectOption(Container parent, Enum<?> option) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JRadioButton button
                    && button.getClientProperty("fork.option") == option) {
                button.doClick();
            } else if (component instanceof Container container) {
                selectOption(container, option);
            }
        }
    }

}
