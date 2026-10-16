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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JSONDataSetCustomizerTest extends JMeterTestCase implements JMeterSerialTest {
    @TempDir
    Path directory;
    private TestPlan plan;
    private static final String ROWS = "[{\"name\":\"Alice\",\"age\":30},{\"name\":\"Bob\",\"age\":40}]";

    @BeforeEach
    void activatePlan() {
        plan = new TestPlan();
        plan.setProperty("TestElement.gui_class", "org.apache.jmeter.control.gui.TestPlanGui");
        ArchiveFiles.activate(plan);
    }

    @AfterEach
    void deactivatePlan() {
        ArchiveFiles.activate(null);
    }

    private JSONDataSet localFile() throws IOException {
        Path path = directory.resolve("data.json");
        Files.writeString(path, ROWS);
        JSONDataSet data = new JSONDataSet();
        data.setFilename(path.toString());
        data.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        return data;
    }

    @Test
    void archiveSurvivesSaveMoveAndOriginalDeletion() throws Exception {
        JSONDataSet data = localFile();
        Path original = Path.of(data.getFilename());
        data.storeArchivedJson(data.readJsonContent());
        assertTrue(data.isUseJsonFromArchive());
        assertEquals("data.json", data.getFilename());
        HashTree tree = new HashTree();
        tree.add(plan).add(data);
        Path archive = directory.resolve("plan.jmx");
        try (var output = Files.newOutputStream(archive)) {
            SaveService.saveTree(tree, output);
        }
        Files.delete(original);
        ArchiveFiles.remove(plan, "data.json");
        Path moved = Files.move(archive, directory.resolve("moved.jmx"));
        HashTree restored = SaveService.loadTree(moved.toFile());
        TestPlan restoredPlan = (TestPlan) restored.getArray()[0];
        ArchiveFiles.activate(restoredPlan);
        JSONDataSet loaded = (JSONDataSet) restored.getTree(restoredPlan).getArray()[0];
        TestBeanHelper.prepare(loaded);
        assertArrayEquals(ROWS.getBytes(StandardCharsets.UTF_8), loaded.readJsonContent());
        assertTrue(loaded.readFirstSample(10).contains("${name} = Bob"));
        JMeterVariables variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
        loaded.iterationStart(null);
        assertEquals("Alice", variables.get("name"));
        assertEquals("30", variables.get("age"));
        loaded.iterationStart(null);
        assertEquals("Bob", variables.get("name"));
        assertEquals("40", variables.get("age"));
    }

    @Test
    void buttonsCopyAndPreviewMultipleVariablesWithoutConsumingRows() throws Exception {
        JSONDataSet data = localFile();
        SwingUtilities.invokeAndWait(() -> {
            TestCustomizer customizer = customizer(data);
            button(customizer, "Copy JSON into archive").doClick();
            assertEquals("data.json", customizer.properties.get("filename"));
            assertEquals(true, customizer.properties.get("useJsonFromArchive"));
            JTextArea preview = find(customizer, JTextArea.class);
            assertTrue(preview.getText().contains("${name} = Alice"));
            assertTrue(preview.getText().contains("${age} = 40"));
            String first = preview.getText();
            button(customizer, "Read first 10 samples").doClick();
            assertEquals(first, preview.getText());
        });
        assertArrayEquals(ROWS.getBytes(StandardCharsets.UTF_8), ArchiveFiles.read("data.json"));
    }

    @Test
    void editsLocalAndArchivedFilesAndRejectsInvalidReplacement() throws Exception {
        JSONDataSet data = localFile();
        SwingUtilities.invokeAndWait(() -> {
            TestCustomizer customizer = customizer(data);
            try {
                var local = customizer.loadEditor(data, bundle());
                assertEquals(ROWS, local.text());
                assertThrows(IOException.class, () -> customizer.saveEditor(local.file(), "broken", data));
                assertEquals(ROWS, Files.readString(Path.of(data.getFilename())));
                customizer.saveEditor(local.file(), "{\"name\":\"Edited\"}", data);
                data.storeArchivedJson(data.readJsonContent());
                var archived = customizer.loadEditor(data, bundle());
                customizer.saveEditor(archived.file(), ROWS, data);
                assertArrayEquals(ROWS.getBytes(StandardCharsets.UTF_8), data.readJsonContent());
                assertThrows(IOException.class, () -> customizer.saveEditor(archived.file(), "[]", data));
                assertArrayEquals(ROWS.getBytes(StandardCharsets.UTF_8), data.readJsonContent());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void archiveBrowseAndFilenameChangesSelectTheRequestedFile() throws Exception {
        ArchiveFiles.put(plan, "first.json", ROWS.getBytes(StandardCharsets.UTF_8), false);
        ArchiveFiles.put(plan, "second.json", "{\"name\":\"Second\"}".getBytes(StandardCharsets.UTF_8), false);
        JSONDataSet data = new JSONDataSet();
        data.setFilename("first.json");
        data.setUseJsonFromArchive(true);
        SwingUtilities.invokeAndWait(() -> {
            TestCustomizer customizer = customizer(data);
            customizer.selection = "files/second.json";
            button(customizer, JMeterUtils.getResString("browse")).doClick();
            assertEquals("second.json", customizer.properties.get("filename"));
            button(customizer, "Read first 10 samples").doClick();
            assertTrue(find(customizer, JTextArea.class).getText().contains("${name} = Second"));
            customizer.selection = null;
            button(customizer, JMeterUtils.getResString("browse")).doClick();
            assertEquals("second.json", customizer.properties.get("filename"));
        });
        JSONDataSet clone = (JSONDataSet) data.clone();
        clone.setFilename("second.json");
        assertTrue(clone.readFirstSample(1).contains("${name} = Second"));
    }

    @Test
    void missingEditorDoesNotCreateUntilSaveAndUnavailableArchiveIsNotReplaced() throws Exception {
        JSONDataSet data = new JSONDataSet();
        Path missing = directory.resolve("missing.json");
        data.setFilename(missing.toString());
        SwingUtilities.invokeAndWait(() -> {
            TestCustomizer customizer = customizer(data);
            try {
                var editor = customizer.loadEditor(data, bundle());
                assertFalse(Files.exists(missing));
                customizer.saveEditor(editor.file(), ROWS, data);
                assertEquals(ROWS, Files.readString(missing));
                data.setUseJsonFromArchive(true);
                data.setFilename("missing.json");
                var archived = customizer.loadEditor(data, bundle());
                assertFalse(ArchiveFiles.references(plan).containsKey("files/missing.json"));
                customizer.saveEditor(archived.file(), ROWS, data);
                assertTrue(ArchiveFiles.references(plan).containsKey("files/missing.json"));
                plan.setUnavailableArchiveFiles(java.util.Set.of("files/missing.json"));
                assertThrows(IOException.class, () -> customizer.loadEditor(data, bundle()));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void importRejectsCollisionsAndSaveWarnsAboutMissingArchiveSource() throws Exception {
        JSONDataSet data = localFile();
        byte[] original = "{\"name\":\"Existing\"}".getBytes(StandardCharsets.UTF_8);
        ArchiveFiles.put(plan, "data.json", original, false);
        assertThrows(IllegalArgumentException.class, () -> data.storeArchivedJson(data.readJsonContent()));
        assertArrayEquals(original, ArchiveFiles.read("data.json"));
        data.setFilename("missing.json");
        data.setUseJsonFromArchive(true);
        HashTree tree = new HashTree();
        tree.add(plan).add(data);
        assertTrue(SaveService.archiveWarnings(tree).stream().anyMatch(message -> message.contains("files/missing.json")
                && message.startsWith("JSON Data Set Config '")));
        assertThrows(IOException.class, data::readJsonContent);
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle(JSONDataSet.class.getName() + "Resources");
    }

    private static TestCustomizer customizer(JSONDataSet data) {
        TestCustomizer customizer = new TestCustomizer();
        customizer.setObject(customizer.properties);
        customizer.properties.put("filename", data.getFilename());
        customizer.properties.put("useJsonFromArchive", data.isUseJsonFromArchive());
        customizer.setObject(customizer.properties);
        return customizer;
    }

    private static JButton button(Container container, String label) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && label.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton result = button(child, label);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static <T> T find(Container container, Class<T> type) {
        for (Component component : container.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T result = find(child, type);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static class TestCustomizer extends JSONDataSetCustomizer {
        private final Map<String, Object> properties = new HashMap<>();
        private String selection;

        @Override
        <T> T loadInBackground(String title, Callable<T> operation) throws IOException {
            try {
                return operation.call();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        boolean confirmCreateJson(String message, ResourceBundle bundle) {
            return true;
        }

        @Override
        String chooseArchiveFile(String currentEntry) {
            return selection;
        }
    }
}
