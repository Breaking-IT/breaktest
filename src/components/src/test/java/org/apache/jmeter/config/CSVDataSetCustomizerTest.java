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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;

import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CSVDataSetCustomizerTest extends JMeterTestCase implements JMeterSerialTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void renamedCloneUsesNewArchiveFilename(boolean productionExists) throws Exception {
        TestPlan plan = new TestPlan();
        byte[] acceptance = "name\nacceptance\n".getBytes(StandardCharsets.UTF_8);
        byte[] production = "name\nproduction\n".getBytes(StandardCharsets.UTF_8);
        ArchiveFiles.put(plan, "acceptance.csv", acceptance, false);
        if (productionExists) {
            ArchiveFiles.put(plan, "production.csv", production, false);
        }
        ArchiveFiles.activate(plan);
        try {
            SwingUtilities.invokeAndWait(() -> {
                TestCustomizer customizer = new TestCustomizer();
                CSVDataSet original = new CSVDataSet();
                original.setFilename("acceptance.csv");
                original.setProperty("filename", "acceptance.csv");
                original.storeArchivedCsv(acceptance);
                CSVDataSet clone = (CSVDataSet) original.clone();
                Map<String, Object> properties = new HashMap<>();
                customizer.setObject(properties);
                properties.put("filename", clone.getPropertyAsString("filename"));
                properties.put("useCsvFromArchive", clone.isUseCsvFromArchive());
                properties.put("csvArchiveEntry", clone.getCsvArchiveEntry());
                properties.put("csvArchiveChecksum", clone.getCsvArchiveChecksum());
                customizer.setObject(properties);
                customizer.saveGuiFields();
                assertEquals(original.getCsvArchiveEntry(), properties.get("csvArchiveEntry"));
                assertEquals(original.getCsvArchiveChecksum(), properties.get("csvArchiveChecksum"));

                findTextField(customizer, "acceptance.csv").setText("production.csv");
                customizer.saveGuiFields();
                // Subsequent commits must not restore the hidden metadata either.
                customizer.saveGuiFields();
                assertEquals("production.csv", properties.get("filename"));
                assertEquals("", properties.get("csvArchiveEntry"));
                assertEquals("", properties.get("csvArchiveChecksum"));
                clone.setFilename((String) properties.get("filename"));
                clone.setCsvArchiveEntry((String) properties.get("csvArchiveEntry"));
                clone.setCsvArchiveChecksum((String) properties.get("csvArchiveChecksum"));
                clone.setFileEncoding("UTF-8");
                customizer.create = true;
                try {
                    var loaded = customizer.loadEditor(clone,
                            ResourceBundle.getBundle(CSVDataSet.class.getName() + "Resources"));
                    assertEquals(Path.of("files/production.csv"), loaded.file().getPath());
                    if (productionExists) {
                        assertEquals("name\nproduction\n", loaded.text());
                        assertEquals("name\nproduction\n", Files.readString(clone.resolveCsvFile()));
                        assertTrue(clone.readFirstSample(1).contains("${name} = production"));
                    } else {
                        assertEquals("", loaded.text());
                        assertTrue(customizer.creationMessage.contains("production.csv"));
                        assertThrows(IOException.class, clone::readCsvContent);
                        assertThrows(IOException.class, clone::resolveCsvFile);
                    }
                    clone.storeArchivedCsv(production);
                    assertEquals("files/production.csv", clone.getCsvArchiveEntry());
                    assertEquals("name\nacceptance\n", new String(original.readCsvContent(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            });
        } finally {
            ArchiveFiles.activate(null);
        }
    }

    private static JTextField findTextField(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTextField field && text.equals(field.getText())) {
                return field;
            }
            if (component instanceof Container child) {
                JTextField field = findTextField(child, text);
                if (field != null) {
                    return field;
                }
            }
        }
        return null;
    }

    @Test
    void missingCsvOffersCreationAndCancellationLeavesFileMissing(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path directory) throws Exception {
        TestPlan plan = new TestPlan();
        ArchiveFiles.activate(plan);
        try {
            SwingUtilities.invokeAndWait(() -> {
                var bundle = java.util.ResourceBundle.getBundle(CSVDataSet.class.getName() + "Resources");
                for (boolean archived : new boolean[] {false, true}) {
                    TestCustomizer customizer = new TestCustomizer();
                    CSVDataSet csv = new CSVDataSet();
                    java.nio.file.Path path = directory.resolve("new.csv");
                    csv.setFilename(archived ? "new.csv" : path.toString());
                    csv.setUseCsvFromArchive(archived);
                    csv.setFileEncoding("UTF-8");
                    try {
                        org.junit.jupiter.api.Assertions.assertNull(customizer.loadEditor(csv, bundle));
                        assertTrue(customizer.creationMessage.contains("new.csv"));
                        customizer.create = true;
                        var loaded = customizer.loadEditor(csv, bundle);
                        assertEquals("", loaded.text());
                        assertEquals(archived ? java.nio.file.Path.of("files/new.csv") : path,
                                loaded.file().getPath());
                        assertFalse(java.nio.file.Files.exists(path));
                        assertTrue(ArchiveFiles.references(plan).isEmpty());
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                }
            });
        } finally {
            ArchiveFiles.activate(null);
        }
    }

    @Test
    void unreadableArchivedCsvReportsTheErrorInsteadOfOfferingCreation() throws Exception {
        TestPlan plan = new TestPlan();
        ArchiveFiles.activate(plan);
        try {
            SwingUtilities.invokeAndWait(() -> {
                var bundle = java.util.ResourceBundle.getBundle(CSVDataSet.class.getName() + "Resources");
                TestCustomizer customizer = new TestCustomizer();
                // Accept any prompt, so an offer to create would replace the stored rows.
                customizer.create = true;
                CSVDataSet csv = new CSVDataSet();
                csv.setFilename("orders.csv");
                csv.setUseCsvFromArchive(true);
                csv.setFileEncoding("UTF-8");
                ArchiveFiles.put(plan, "files/orders.csv",
                        "id,name\n1,Ada\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
                // A plan opened without its archive beside it: the entry is referenced, not readable.
                plan.setUnavailableArchiveFiles(java.util.Set.of("files/orders.csv"));

                org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                        () -> customizer.loadEditor(csv, bundle));
                org.junit.jupiter.api.Assertions.assertNull(customizer.creationMessage,
                        "An unreadable entry must not be offered for creation");
                assertEquals(java.util.Set.of("files/orders.csv"), ArchiveFiles.references(plan).keySet());
            });
        } finally {
            ArchiveFiles.activate(null);
        }
    }

    @Test
    void editorPopulationYieldsToEdtAndPreservesCompleteContent() throws Exception {
        // Exercise several 256 KiB chunks without making Swing lay out tens of thousands
        // of lines under the constant-hashcode CI stress configuration.
        String content = ("first,value\r\nsecond,é😀" + "x".repeat(1000) + "\n").repeat(600);
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
        private boolean create;
        private String creationMessage;

        @Override
        <T> T loadInBackground(String title, Callable<T> operation) throws IOException {
            try {
                return operation.call();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        boolean confirmCreateCsv(String message, java.util.ResourceBundle bundle) {
            creationMessage = message;
            return create;
        }

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
