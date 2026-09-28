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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.LocaleChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JMeterToolBarTest extends JMeterTestCase {
    private Locale previousLocale;

    @BeforeEach
    void initializeLocale() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            previousLocale = JMeterUtils.getLocale();
            // The test JVM may not have initialized application resources, and the
            // lifecycle assertions intentionally check the English button labels.
            JMeterUtils.setLocale(Locale.ENGLISH);
        });
    }

    @AfterEach
    void restoreLocale() throws Exception {
        if (previousLocale != null) {
            SwingUtilities.invokeAndWait(() -> JMeterUtils.setLocale(previousLocale));
        }
    }

    @Test
    void runLabelsFollowLocaleWhilePaused() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMeterToolBar toolbar = JMeterToolBar.createToolbar(false);
            try {
                JMeterUtils.setLocale(Locale.FRENCH);
                assertEquals("Lancer", button(toolbar, ActionNames.ACTION_START).getText());
                toolbar.setLocalTestStarted(true);
                assertEquals("Pause", button(toolbar, ActionNames.ACTION_PAUSE).getText());
                toolbar.setLocalTestPaused(true);
                assertEquals("Reprendre", button(toolbar, ActionNames.ACTION_PAUSE).getText());
                JMeterUtils.setLocale(Locale.GERMAN);
                JButton resume = button(toolbar, ActionNames.ACTION_PAUSE);
                assertEquals("Fortsetzen", resume.getText());
                assertEquals("Fortsetzen", resume.getAccessibleContext().getAccessibleName());
                toolbar.setLocalTestStarted(false);
                assertEquals("Start", button(toolbar, ActionNames.ACTION_START).getText());
            } finally {
                JMeterUtils.removeLocaleChangeListener(toolbar);
            }
        });
    }

    @Test
    void customRunAndPauseIconsSurviveStateChanges(@TempDir Path directory) throws Exception {
        String prefix = "org/apache/jmeter/images/vrt/";
        String run = prefix + "32x32/security-low-2.png";
        String runPressed = prefix + "32x32/security-high-2.png";
        String pause = prefix + "24x24/security-low-2.png";
        String pausePressed = prefix + "24x24/security-high-2.png";
        Properties icons = new Properties();
        icons.setProperty("test_start", "start,ACTION_START," + run + "," + runPressed);
        icons.setProperty("test_pause", "pause,ACTION_PAUSE," + pause + "," + pausePressed);
        Path iconFile = directory.resolve("icons.properties");
        try (var writer = Files.newBufferedWriter(iconFile)) {
            icons.store(writer, "Custom toolbar icons");
        }
        String previous = JMeterUtils.getProperty(JMeterToolBar.USER_DEFINED_TOOLBAR_PROPERTY_FILE);
        try {
            JMeterUtils.setProperty(JMeterToolBar.USER_DEFINED_TOOLBAR_PROPERTY_FILE, iconFile.toString());
            SwingUtilities.invokeAndWait(() -> {
                JMeterToolBar toolbar = JMeterToolBar.createToolbar(false);
                try {
                    assertIcons(button(toolbar, ActionNames.ACTION_START), run, runPressed);
                    toolbar.setLocalTestStarted(true);
                    assertIcons(button(toolbar, ActionNames.ACTION_PAUSE), pause, pausePressed);
                    toolbar.setLocalTestPaused(true);
                    assertIcons(button(toolbar, ActionNames.ACTION_PAUSE), run, runPressed);
                    toolbar.localeChanged(new LocaleChangeEvent(toolbar));
                    assertIcons(button(toolbar, ActionNames.ACTION_PAUSE), run, runPressed);
                    toolbar.setLocalTestPaused(false);
                    assertIcons(button(toolbar, ActionNames.ACTION_PAUSE), pause, pausePressed);
                    toolbar.setLocalTestStarted(false);
                    assertIcons(button(toolbar, ActionNames.ACTION_START), run, runPressed);
                } finally {
                    JMeterUtils.removeLocaleChangeListener(toolbar);
                }
            });
        } finally {
            if (previous == null) {
                JMeterUtils.getJMeterProperties().remove(JMeterToolBar.USER_DEFINED_TOOLBAR_PROPERTY_FILE);
            } else {
                JMeterUtils.setProperty(JMeterToolBar.USER_DEFINED_TOOLBAR_PROPERTY_FILE, previous);
            }
        }
    }

    private static void assertIcons(JButton button, String normal, String pressed) {
        ClassLoader loader = JMeterUtils.class.getClassLoader();
        assertEquals(loader.getResource(normal).toString(), ((ImageIcon) button.getIcon()).getDescription());
        assertEquals(loader.getResource(pressed).toString(), ((ImageIcon) button.getPressedIcon()).getDescription());
    }

    @Test
    void runDoubleClickCannotPauseNewTest() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMeterToolBar toolbar = JMeterToolBar.createToolbar(false);
            assertFalse(toolbar.isRunActionGuarded(ActionNames.ACTION_START, System.nanoTime()));
            toolbar.setLocalTestStarted(true);
            long startedAt = System.nanoTime();
            assertTrue(toolbar.isRunActionGuarded(ActionNames.ACTION_PAUSE, startedAt));
            assertFalse(toolbar.isRunActionGuarded(ActionNames.ACTION_PAUSE, startedAt + 600_000_000L));

            toolbar.localeChanged(new LocaleChangeEvent(toolbar));
            assertTrue(toolbar.isRunActionGuarded(ActionNames.ACTION_PAUSE, startedAt));
            toolbar.setLocalTestPaused(true);
            assertFalse(toolbar.isRunActionGuarded(ActionNames.ACTION_PAUSE, startedAt));
            toolbar.setLocalTestStarted(false);
            assertFalse(toolbar.isRunActionGuarded(ActionNames.ACTION_START, startedAt));
            toolbar.setLocalTestStarted(true);
            assertTrue(toolbar.isRunActionGuarded(ActionNames.ACTION_PAUSE, System.nanoTime()));
        });
    }

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
