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

package org.apache.jmeter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.HashMap;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;

class CSVDataSetCustomizerTest extends JMeterTestCase implements JMeterSerialTest {
    @Test
    void editorPopulationYieldsToEdtAndPreservesCompleteContent() throws Exception {
        String content = "first,value\r\nsecond,é😀\n".repeat(30000);
        var completed = new java.util.concurrent.CountDownLatch(1);
        var heartbeat = new java.util.concurrent.atomic.AtomicBoolean();
        var editor = new javax.swing.JTextArea();
        SwingUtilities.invokeAndWait(() -> {
            CSVDataSetCustomizer.populateEditor(editor, content, completed::countDown);
            SwingUtilities.invokeLater(() -> heartbeat.set(true));
            assertEquals("", editor.getText());
        });
        assertTrue(completed.await(15, java.util.concurrent.TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(heartbeat.get());
            assertEquals(content, editor.getText());
        });
    }

    @Test
    void backgroundLoadingLeavesEventThreadAvailableAndPropagatesErrors() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            CSVDataSetCustomizer customizer = new CSVDataSetCustomizer();
            try {
                String result = customizer.loadInBackground("CSV preview", () -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    // This would deadlock if loading blocked the EDT.
                    SwingUtilities.invokeAndWait(() -> assertTrue(SwingUtilities.isEventDispatchThread()));
                    return "loaded";
                });
                assertEquals("loaded", result);
                java.io.IOException failure = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                        () -> customizer.loadInBackground("CSV preview", () -> {
                            throw new java.io.IOException("Unreadable CSV");
                        }));
                assertEquals("Unreadable CSV", failure.getMessage());
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void filenameBrowseUsesArchivePickerAndCancellationKeepsSelection() throws Exception {
        TestPlan plan = new TestPlan();
        ArchiveFiles.put(plan, "selected.csv", new byte[0], false);
        ArchiveFiles.activate(plan);
        try {
            SwingUtilities.invokeAndWait(() -> {
                TestCustomizer customizer = new TestCustomizer();
                Map<String, Object> properties = new HashMap<>();
                customizer.setObject(properties);
                properties.put("filename", "external.csv");
                properties.put("useCsvFromArchive", true);
                customizer.setObject(properties);
                JButton browse = findBrowse(customizer);
                customizer.selection = "files/selected.csv";
                browse.doClick();
                assertTrue(customizer.pickerOpened);
                assertEquals("files/external.csv", customizer.currentEntry);
                assertEquals("selected.csv", properties.get("filename"));
                assertEquals("files/selected.csv", properties.get("csvArchiveEntry"));
                assertEquals(ArchiveFiles.checksum(new byte[0]), properties.get("csvArchiveChecksum"));
                customizer.selection = null;
                browse.doClick();
                assertEquals("files/selected.csv", customizer.currentEntry);
                assertEquals("selected.csv", properties.get("filename"));
                assertEquals("files/selected.csv", properties.get("csvArchiveEntry"));
                properties.put("useCsvFromArchive", false);
                customizer.setObject(properties);
                customizer.pickerOpened = false;
                assertFalse(customizer.browseArchiveFile());
                assertFalse(customizer.pickerOpened);
            });
        } finally {
            ArchiveFiles.activate(null);
        }
    }

    private static JButton findBrowse(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && JMeterUtils.getResString("browse").equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton button = findBrowse(child);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    private static class TestCustomizer extends CSVDataSetCustomizer {
        private String selection;
        private String currentEntry;
        private boolean pickerOpened;

        @Override
        String chooseArchiveFile(String currentEntry) {
            this.currentEntry = currentEntry;
            pickerOpened = true;
            return selection;
        }
    }
}
