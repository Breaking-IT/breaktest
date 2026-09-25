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

import javax.swing.SwingUtilities;

import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.ForkController.FinalStopAction;
import org.apache.jmeter.control.ForkController.IterationEndAction;
import org.apache.jmeter.control.ForkController.RunningAction;
import org.apache.jmeter.testelement.TestElement;
import org.junit.jupiter.api.Test;

class ForkControllerGuiTest {
    private static class TestGui extends ForkControllerGui {
        private static final long serialVersionUID = 1L;
        private boolean sameUser;

        @Override
        protected boolean isSameUserEnabled(TestElement element) {
            return sameUser;
        }
    }

    @Test
    void lifecycleChoicesRoundTripAndClearToDefaults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TestGui gui = new TestGui();
            gui.sameUser = true;
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
    void keepRunningIsUnavailableWithoutSameUser() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TestGui gui = new TestGui();
            ForkController source = new ForkController();
            source.setIterationEndAction(IterationEndAction.KEEP_RUNNING);
            gui.sameUser = true;
            gui.configure(source);
            assertEquals(IterationEndAction.KEEP_RUNNING,
                    ((ForkController) gui.createTestElement()).getIterationEndAction());
            gui.sameUser = false;
            gui.configure(source);
            assertEquals(IterationEndAction.GRACEFUL,
                    ((ForkController) gui.createTestElement()).getIterationEndAction());
        });
    }
}
