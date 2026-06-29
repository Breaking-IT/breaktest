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

package org.apache.jmeter.threads.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ThreadGroupSchema;
import org.junit.jupiter.api.Test;

class ThreadGroupGuiTest {
    @Test
    void newThreadGroupDelaysThreadCreationByDefault() {
        ThreadGroup threadGroup = (ThreadGroup) new ThreadGroupGui().createTestElement();

        assertTrue(threadGroup.get(ThreadGroupSchema.INSTANCE.getDelayedStart()));
        assertEquals(AbstractThreadGroup.PACING_DISABLED, threadGroup.getPacingMode());
        assertEquals("0", threadGroup.getFixedPacing());
        assertEquals("0", threadGroup.getPacingMin());
        assertEquals("0", threadGroup.getPacingMax());
    }

    @Test
    void fixedPacingShowsIterationsPerMinute() {
        assertEquals(
                "60 iterations/min",
                ThreadGroupGui.formatPacingRate(AbstractThreadGroup.PACING_FIXED, "1000", "0", "0"));
    }

    @Test
    void randomPacingShowsIterationsPerMinuteFromAverage() {
        assertEquals(
                "0.5 iterations/min",
                ThreadGroupGui.formatPacingRate(AbstractThreadGroup.PACING_RANDOM, "0", "60000", "180000"));
    }

    @Test
    void disabledPacingDoesNotShowIterationsPerMinute() {
        assertEquals(
                "",
                ThreadGroupGui.formatPacingRate(AbstractThreadGroup.PACING_DISABLED, "1000", "60000", "180000"));
    }

    @Test
    void setupAndPostThreadGroupsDoNotEnableHiddenDelayedStartByDefault() {
        ThreadGroup setupThreadGroup = (ThreadGroup) new SetupThreadGroupGui().createTestElement();
        ThreadGroup postThreadGroup = (ThreadGroup) new PostThreadGroupGui().createTestElement();

        assertFalse(setupThreadGroup.get(ThreadGroupSchema.INSTANCE.getDelayedStart()));
        assertFalse(postThreadGroup.get(ThreadGroupSchema.INSTANCE.getDelayedStart()));
    }

    @Test
    void modifyTestElementPreservesBreakTestHarMetadata() {
        ThreadGroup threadGroup = (ThreadGroup) new ThreadGroupGui().createTestElement();
        threadGroup.setProperty("BreakTest.har.filename", "recording.har");
        threadGroup.setProperty("BreakTest.har.md5", "3d8eeea2288e42557c6c4ced7920243b");

        ThreadGroupGui gui = new ThreadGroupGui();
        gui.configure(threadGroup);
        gui.modifyTestElement(threadGroup);

        assertEquals("recording.har", threadGroup.getPropertyAsString("BreakTest.har.filename"));
        assertEquals("3d8eeea2288e42557c6c4ced7920243b", threadGroup.getPropertyAsString("BreakTest.har.md5"));
    }

    @Test
    void legacySchedulerThreadGroupUsesDurationPolicy() {
        ThreadGroup threadGroup = threadGroupWithLoops(3);
        threadGroup.setScheduler(true);
        threadGroup.setDuration(30);

        ThreadGroupGui gui = new ThreadGroupGui();
        gui.configure(threadGroup);
        gui.modifyTestElement(threadGroup);

        assertTrue(threadGroup.getScheduler());
        assertEquals(LoopController.INFINITE_LOOP_COUNT, mainController(threadGroup).getLoops());
    }

    @Test
    void legacyInfiniteLoopThreadGroupUsesNoLimitPolicy() {
        ThreadGroup threadGroup = threadGroupWithLoops(LoopController.INFINITE_LOOP_COUNT);
        threadGroup.setScheduler(false);

        ThreadGroupGui gui = new ThreadGroupGui();
        gui.configure(threadGroup);
        gui.modifyTestElement(threadGroup);

        assertFalse(threadGroup.getScheduler());
        assertEquals(LoopController.INFINITE_LOOP_COUNT, mainController(threadGroup).getLoops());
    }

    @Test
    void legacyFiniteLoopThreadGroupKeepsLoopCountPolicy() {
        ThreadGroup threadGroup = threadGroupWithLoops(3);
        threadGroup.setScheduler(false);

        ThreadGroupGui gui = new ThreadGroupGui();
        gui.configure(threadGroup);
        gui.modifyTestElement(threadGroup);

        assertFalse(threadGroup.getScheduler());
        assertEquals(3, mainController(threadGroup).getLoops());
    }

    private static ThreadGroup threadGroupWithLoops(int loops) {
        ThreadGroup threadGroup = (ThreadGroup) new ThreadGroupGui().createTestElement();
        LoopController loopController = new LoopController();
        loopController.setLoops(loops);
        threadGroup.setSamplerController(loopController);
        return threadGroup;
    }

    private static LoopController mainController(ThreadGroup threadGroup) {
        return (LoopController) threadGroup.getSamplerController();
    }
}
