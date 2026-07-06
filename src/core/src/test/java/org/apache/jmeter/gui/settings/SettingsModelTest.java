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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsModelTest {

    @TempDir
    Path tempDir;

    private File jmeterFile;
    private File userFile;
    private File systemFile;
    private SettingsCatalog catalog;
    private SettingsGroup userGroup;
    private SettingDefinition setting;

    @BeforeEach
    void setUp() throws IOException {
        jmeterFile = tempDir.resolve("jmeter.properties").toFile();
        userFile = tempDir.resolve("user.properties").toFile();
        systemFile = tempDir.resolve("system.properties").toFile();
        Files.write(jmeterFile.toPath(), "from.jmeter=base\n".getBytes(StandardCharsets.ISO_8859_1));

        catalog = new SettingsCatalog();
        userGroup = new SettingsGroup();
        userGroup.setId("test");
        userGroup.setTitle("Test");
        setting = new SettingDefinition();
        setting.setKey("from.jmeter");
        setting.setDefaultValue("builtin");
        SettingDefinition unset = new SettingDefinition();
        unset.setKey("only.default");
        unset.setDefaultValue("fallback");
        userGroup.setSettings(List.of(setting, unset));
        catalog.setGroups(List.of(userGroup));
    }

    private SettingsModel newModel() throws IOException {
        return new SettingsModel(catalog, jmeterFile, userFile, systemFile);
    }

    @Test
    void resolvesLayeredValues() throws IOException {
        SettingsModel model = newModel();
        assertEquals("base", model.getValue(userGroup, setting), "jmeter.properties wins over builtin default");
        assertEquals(SettingsModel.ValueSource.JMETER_PROPERTIES, model.getSource(userGroup, setting));
        SettingDefinition unset = userGroup.getSettings().get(1);
        assertEquals("fallback", model.getValue(userGroup, unset), "builtin default used when files unset");
        assertEquals(SettingsModel.ValueSource.DEFAULT, model.getSource(userGroup, unset));
    }

    @Test
    void appliesOverridesToUserFileOnly() throws IOException {
        SettingsModel model = newModel();
        model.apply(SettingsGroup.Target.USER, Map.of("from.jmeter", "override"), Set.of());

        SettingsModel reloaded = newModel();
        assertEquals("override", reloaded.getValue(userGroup, setting));
        assertEquals(SettingsModel.ValueSource.OVERRIDE, reloaded.getSource(userGroup, setting));
        assertEquals("base", reloaded.getInheritedValue(userGroup, setting));
        String jmeterContent = Files.readString(jmeterFile.toPath(), StandardCharsets.ISO_8859_1);
        assertEquals("from.jmeter=base\n", jmeterContent, "jmeter.properties must never be modified");
    }

    @Test
    void removingOverrideRestoresInheritedValue() throws IOException {
        SettingsModel model = newModel();
        model.apply(SettingsGroup.Target.USER, Map.of("from.jmeter", "override"), Set.of());
        model.apply(SettingsGroup.Target.USER, Map.of(), Set.of("from.jmeter"));

        SettingsModel reloaded = newModel();
        assertEquals("base", reloaded.getValue(userGroup, setting));
        assertEquals(SettingsModel.ValueSource.JMETER_PROPERTIES, reloaded.getSource(userGroup, setting));
    }

    @Test
    void uncataloguedKeysAreReported() throws IOException {
        Files.write(userFile.toPath(), "custom.plugin.key=1\n".getBytes(StandardCharsets.ISO_8859_1));
        SettingsModel model = newModel();
        Set<String> keys = model.getUncataloguedKeys();
        assertTrue(keys.contains("custom.plugin.key"), "keys not in catalog are reported");
        assertTrue(!keys.contains("from.jmeter"), "catalogued keys are not reported");
        SettingsGroup other = model.buildOtherGroup("Other", "desc");
        assertEquals(1, other.getSettings().size());
    }
}
