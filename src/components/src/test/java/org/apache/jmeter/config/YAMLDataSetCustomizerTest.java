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

class YAMLDataSetCustomizerTest extends JMeterTestCase implements JMeterSerialTest {
    @TempDir
    Path directory;
    private TestPlan plan;
    private static final String ROWS = "- name: Alice\n  age: 30\n- name: Bob\n  age: 40\n";

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

    private YAMLDataSet localFile() throws IOException {
        Path path = directory.resolve("data.yaml");
        Files.writeString(path, ROWS);
        YAMLDataSet data = new YAMLDataSet();
        data.setFilename(path.toString());
        data.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        return data;
    }

    @Test
    void archiveSurvivesSaveMoveAndOriginalDeletion() throws Exception {
        YAMLDataSet data = localFile();
        Path original = Path.of(data.getFilename());
        data.storeArchivedYaml(data.readYamlContent());
        assertTrue(data.isUseYamlFromArchive());
        assertEquals("data.yaml", data.getFilename());
        HashTree tree = new HashTree();
        tree.add(plan).add(data);
        Path archive = directory.resolve("plan.jmx");
        try (var output = Files.newOutputStream(archive)) {
            SaveService.saveTree(tree, output);
        }
        Files.delete(original);
        ArchiveFiles.remove(plan, "data.yaml");
        Path moved = Files.move(archive, directory.resolve("moved.jmx"));
        HashTree restored = SaveService.loadTree(moved.toFile());
        TestPlan restoredPlan = (TestPlan) restored.getArray()[0];
        ArchiveFiles.activate(restoredPlan);
        YAMLDataSet loaded = (YAMLDataSet) restored.getTree(restoredPlan).getArray()[0];
        TestBeanHelper.prepare(loaded);
        assertArrayEquals(ROWS.getBytes(StandardCharsets.UTF_8), loaded.readYamlContent());
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
        YAMLDataSet data = localFile();
        SwingUtilities.invokeAndWait(() -> {
            TestCustomizer customizer = customizer(data);
            button(customizer, "Copy YAML into archive").doClick();
            assertEquals("data.yaml", customizer.properties.get("filename"));
            assertEquals(true, customizer.properties.get("useYamlFromArchive"));
            JTextArea preview = find(customizer, JTextArea.class);
            assertTrue(preview.getText().contains("${name} = Alice"));
            assertTrue(preview.getText().contains("${age} = 40"));
            String first = preview.getText();
            button(customizer, "Read first 10 samples").doClick();
            assertEquals(first, preview.getText());
        });
        assertArrayEquals(ROWS.getBytes(StandardCharsets.UTF_8), ArchiveFiles.read("data.yaml"));
    }

    @Test
    void editsLocalAndArchivedFilesAndRejectsInvalidReplacement() throws Exception {
        YAMLDataSet data = localFile();
        SwingUtilities.invokeAndWait(() -> {
            TestCustomizer customizer = customizer(data);
            try {
                var local = customizer.loadEditor(data, bundle());
                assertEquals(ROWS, local.text());
                assertThrows(IOException.class, () -> customizer.saveEditor(local.file(), "broken", data));
                assertEquals(ROWS, Files.readString(Path.of(data.getFilename())));
                customizer.saveEditor(local.file(), "{\"name\":\"Edited\"}", data);
                data.storeArchivedYaml(data.readYamlContent());
                var archived = customizer.loadEditor(data, bundle());
                customizer.saveEditor(archived.file(), ROWS, data);
                assertArrayEquals(ROWS.getBytes(StandardCharsets.UTF_8), data.readYamlContent());
                assertThrows(IOException.class, () -> customizer.saveEditor(archived.file(), "[]", data));
                assertArrayEquals(ROWS.getBytes(StandardCharsets.UTF_8), data.readYamlContent());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void archiveBrowseAndFilenameChangesSelectTheRequestedFile() throws Exception {
        ArchiveFiles.put(plan, "first.yaml", ROWS.getBytes(StandardCharsets.UTF_8), false);
        ArchiveFiles.put(plan, "second.yaml", "{\"name\":\"Second\"}".getBytes(StandardCharsets.UTF_8), false);
        YAMLDataSet data = new YAMLDataSet();
        data.setFilename("first.yaml");
        data.setUseYamlFromArchive(true);
        SwingUtilities.invokeAndWait(() -> {
            TestCustomizer customizer = customizer(data);
            customizer.selection = "files/second.yaml";
            button(customizer, JMeterUtils.getResString("browse")).doClick();
            assertEquals("second.yaml", customizer.properties.get("filename"));
            button(customizer, "Read first 10 samples").doClick();
            assertTrue(find(customizer, JTextArea.class).getText().contains("${name} = Second"));
            customizer.selection = null;
            button(customizer, JMeterUtils.getResString("browse")).doClick();
            assertEquals("second.yaml", customizer.properties.get("filename"));
        });
        YAMLDataSet clone = (YAMLDataSet) data.clone();
        clone.setFilename("second.yaml");
        assertTrue(clone.readFirstSample(1).contains("${name} = Second"));
    }

    @Test
    void missingEditorDoesNotCreateUntilSaveAndUnavailableArchiveIsNotReplaced() throws Exception {
        YAMLDataSet data = new YAMLDataSet();
        Path missing = directory.resolve("missing.yaml");
        data.setFilename(missing.toString());
        SwingUtilities.invokeAndWait(() -> {
            TestCustomizer customizer = customizer(data);
            try {
                var editor = customizer.loadEditor(data, bundle());
                assertFalse(Files.exists(missing));
                customizer.saveEditor(editor.file(), ROWS, data);
                assertEquals(ROWS, Files.readString(missing));
                data.setUseYamlFromArchive(true);
                data.setFilename("missing.yaml");
                var archived = customizer.loadEditor(data, bundle());
                assertFalse(ArchiveFiles.references(plan).containsKey("files/missing.yaml"));
                customizer.saveEditor(archived.file(), ROWS, data);
                assertTrue(ArchiveFiles.references(plan).containsKey("files/missing.yaml"));
                plan.setUnavailableArchiveFiles(java.util.Set.of("files/missing.yaml"));
                assertThrows(IOException.class, () -> customizer.loadEditor(data, bundle()));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void importRejectsCollisionsAndSaveWarnsAboutMissingArchiveSource() throws Exception {
        YAMLDataSet data = localFile();
        byte[] original = "{\"name\":\"Existing\"}".getBytes(StandardCharsets.UTF_8);
        ArchiveFiles.put(plan, "data.yaml", original, false);
        assertThrows(IllegalArgumentException.class, () -> data.storeArchivedYaml(data.readYamlContent()));
        assertArrayEquals(original, ArchiveFiles.read("data.yaml"));
        data.setFilename("missing.yaml");
        data.setUseYamlFromArchive(true);
        HashTree tree = new HashTree();
        tree.add(plan).add(data);
        assertTrue(SaveService.archiveWarnings(tree).stream().anyMatch(message -> message.contains("files/missing.yaml")));
        assertThrows(IOException.class, data::readYamlContent);
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle(YAMLDataSet.class.getName() + "Resources");
    }

    private static TestCustomizer customizer(YAMLDataSet data) {
        TestCustomizer customizer = new TestCustomizer();
        customizer.setObject(customizer.properties);
        customizer.properties.put("filename", data.getFilename());
        customizer.properties.put("useYamlFromArchive", data.isUseYamlFromArchive());
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

    private static class TestCustomizer extends YAMLDataSetCustomizer {
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
        boolean confirmCreateYaml(String message, ResourceBundle bundle) {
            return true;
        }

        @Override
        String chooseArchiveFile(String currentEntry) {
            return selection;
        }
    }
}
