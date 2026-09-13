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

package org.apache.jmeter.gui.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeferredHeadersSettingTest extends JMeterTestCase {
    private static final String KEY = "httpclient5.defer_diagnostic_headers";

    @TempDir
    Path directory;

    @Test
    void searchableCheckboxDefaultsEnabledAndPersistsBothChoices() throws Exception {
        String previous = JMeterUtils.getProperty(KEY);
        SettingsCatalog catalog = SettingsCatalog.load();
        SettingsGroup group = catalog.getGroups().stream()
                .filter(candidate -> "httpclient5".equals(candidate.getId())).findFirst().orElseThrow();
        SettingDefinition definition = group.getSettings().stream()
                .filter(candidate -> KEY.equals(candidate.getKey())).findFirst().orElseThrow();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    SettingEditor editor = editor(catalog, group, definition);
                    assertTrue(editor.matches("defer"));
                    assertTrue(editor.matches("headers"));
                    assertTrue(checkbox(editor.getComponent()).isSelected());
                    for (boolean enabled : new boolean[] {false, true}) {
                        checkbox(editor.getComponent()).doClick();
                        assertTrue(editor.isSetRequested());
                        model(catalog).apply(group.getTarget(), Map.of(KEY, editor.getControlValue()), Set.of());
                        editor = editor(catalog, group, definition);
                        if (enabled) {
                            assertTrue(checkbox(editor.getComponent()).isSelected());
                        } else {
                            assertFalse(checkbox(editor.getComponent()).isSelected());
                        }
                        assertFalse(editor.isModified());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } finally {
            if (previous == null) {
                JMeterUtils.getJMeterProperties().remove(KEY);
            } else {
                JMeterUtils.setProperty(KEY, previous);
            }
        }
    }

    private SettingsModel model(SettingsCatalog catalog) throws IOException {
        return new SettingsModel(catalog, directory.resolve("jmeter.properties").toFile(),
                directory.resolve("user.properties").toFile(), directory.resolve("system.properties").toFile());
    }

    private SettingEditor editor(SettingsCatalog catalog, SettingsGroup group, SettingDefinition definition)
            throws IOException {
        return new SettingEditor(group, definition, model(catalog), () -> { });
    }

    private static JCheckBox checkbox(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JCheckBox checkBox) {
                return checkBox;
            }
            if (child instanceof Container container) {
                JCheckBox found = checkbox(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
