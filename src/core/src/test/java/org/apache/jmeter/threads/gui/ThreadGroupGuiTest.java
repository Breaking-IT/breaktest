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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

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

    @Test
    void closedModelOffersStandardAndCustomThreadModes() {
        ThreadGroupGui gui = new ThreadGroupGui();

        JComboBox<?> threadMode = findNamedComponent(gui, JComboBox.class, "closedModelThreadMode");
        assertNotNull(threadMode);
        assertEquals(2, threadMode.getItemCount());
        assertEquals(0, threadMode.getSelectedIndex());
        JPanel standardSettings = findNamedComponent(gui, JPanel.class, "standardThreadSettings");
        JPanel customSettings = findNamedComponent(gui, JPanel.class, "customThreadSettings");
        assertNotNull(standardSettings);
        assertNotNull(customSettings);
        assertTrue(standardSettings.isVisible());
        assertFalse(customSettings.isVisible());

        threadMode.setSelectedIndex(1);

        assertFalse(standardSettings.isVisible());
        assertTrue(customSettings.isVisible());
    }

    @Test
    void customProfilePlacesSharedControlsBeforePhasesAndExcludesStartupDelay() {
        ThreadGroupGui gui = new ThreadGroupGui();
        JComboBox<?> loadProfile = findNamedComponent(gui, JComboBox.class, "closedModelThreadMode");
        assertNotNull(loadProfile);
        loadProfile.setSelectedIndex(1);

        JPanel closedModelSettings = findNamedComponent(gui, JPanel.class, "closedModelSettings");
        JPanel standardSettings = findNamedComponent(gui, JPanel.class, "standardThreadSettings");
        JPanel profileSettings = findNamedComponent(gui, JPanel.class, "loadProfileSettings");
        JPanel sameUserControls = findNamedComponent(gui, JPanel.class, "sameUserControls");
        Component delayedThreadCreation = findNamedComponent(gui, Component.class, "delayedThreadCreation");
        JPanel pacingControls = findNamedComponent(gui, JPanel.class, "pacingControls");
        JTextField startupDelay = findNamedComponent(gui, JTextField.class, "startupDelay");
        assertNotNull(closedModelSettings);
        assertNotNull(standardSettings);
        assertNotNull(profileSettings);
        assertNotNull(sameUserControls);
        assertNotNull(delayedThreadCreation);
        assertNotNull(pacingControls);
        assertNotNull(startupDelay);

        assertTrue(SwingUtilities.isDescendingFrom(startupDelay, standardSettings));
        assertComesBefore(closedModelSettings, sameUserControls, profileSettings);
        assertComesBefore(closedModelSettings, delayedThreadCreation, profileSettings);
        assertComesBefore(closedModelSettings, pacingControls, profileSettings);

        startupDelay.setText("30");
        ThreadGroup threadGroup = (ThreadGroup) gui.createTestElement();
        assertEquals(0, threadGroup.getDelay());
    }

    @Test
    void customThreadModeIsSavedOnTheThreadGroup() {
        ThreadGroupGui gui = new ThreadGroupGui();
        JComboBox<?> threadMode = findNamedComponent(gui, JComboBox.class, "closedModelThreadMode");
        assertNotNull(threadMode);
        threadMode.setSelectedIndex(1);

        ThreadGroup threadGroup = (ThreadGroup) gui.createTestElement();
        gui.modifyTestElement(threadGroup);

        assertEquals("Custom", threadGroup.getPropertyAsString("ThreadGroup.closed_mode"));
    }

    @Test
    void legacyThreadGroupWithPhasesOpensInCustomMode() {
        ThreadGroup threadGroup = threadGroupWithLoops(1);
        threadGroup.removeProperty(ThreadGroup.CLOSED_MODEL_MODE);
        threadGroup.setClosedModelSchedule("threadsPhase(10, 100)");
        ThreadGroupGui gui = new ThreadGroupGui();

        gui.configure(threadGroup);

        JComboBox<?> threadMode = findNamedComponent(gui, JComboBox.class, "closedModelThreadMode");
        assertNotNull(threadMode);
        assertEquals(1, threadMode.getSelectedIndex());
    }

    @Test
    void customPhasePreviewUsesCumulativeDurations() {
        List<ThreadGroup.ClosedModelPhase> phases = ThreadGroup.parseClosedModelSchedule("""
                threadsPhase(10, 100)
                threadsPhase(10, 150)
                threadsPhase(80, 300)
                """);

        assertArrayEquals(
                new double[] {100, 250, 550},
                ThreadGroupGui.closedModelPhaseEndTimes(phases, 0));
    }

    @Test
    void openModelSettingsUseOnlyTheirOwnPreferredHeight() {
        ThreadGroup threadGroup = threadGroupWithLoops(1);
        threadGroup.setThreadGroupModel(ThreadGroup.MODEL_OPEN);
        ThreadGroupGui gui = new ThreadGroupGui();

        gui.configure(threadGroup);

        JPanel modelSettings = findNamedComponent(gui, JPanel.class, "threadGroupModelSettings");
        JPanel closedModelSettings = findNamedComponent(gui, JPanel.class, "closedModelSettings");
        JPanel openModelSettings = findNamedComponent(gui, JPanel.class, "openModelSettings");
        assertNotNull(modelSettings);
        assertNotNull(closedModelSettings);
        assertNotNull(openModelSettings);
        assertFalse(closedModelSettings.isVisible());
        assertTrue(openModelSettings.isVisible());
        assertEquals(openModelSettings.getPreferredSize().height, modelSettings.getPreferredSize().height);
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

    private static void assertComesBefore(Container parent, Component first, Component second) {
        assertTrue(parent.getComponentZOrder(directChild(parent, first))
                < parent.getComponentZOrder(directChild(parent, second)));
    }

    private static Component directChild(Container parent, Component descendant) {
        Component child = descendant;
        while (child.getParent() != parent) {
            child = child.getParent();
            assertNotNull(child);
        }
        return child;
    }

    private static <T extends Component> T findNamedComponent(Container root, Class<T> type, String name) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T result = findNamedComponent(child, type, name);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }
}
