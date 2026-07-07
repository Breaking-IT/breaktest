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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SettingsCatalogTest {

    @Test
    void catalogLoadsWithGroupsAndSettings() {
        SettingsCatalog catalog = SettingsCatalog.load();
        assertFalse(catalog.getGroups().isEmpty(), "catalog should contain groups");
        int settingCount = 0;
        for (SettingsGroup group : catalog.getGroups()) {
            assertNotNull(group.getId(), "group id");
            assertNotNull(group.getTitle(), "group title");
            assertFalse(group.getSettings().isEmpty(), "group " + group.getId() + " should contain settings");
            settingCount += group.getSettings().size();
        }
        assertTrue(settingCount > 200, "catalog should be comprehensive, found only " + settingCount);
    }

    @Test
    void keysAreUniqueAcrossGroups() {
        SettingsCatalog catalog = SettingsCatalog.load();
        Set<String> keys = new HashSet<>();
        for (SettingsGroup group : catalog.getGroups()) {
            for (SettingDefinition setting : group.getSettings()) {
                assertNotNull(setting.getKey(), "setting key in group " + group.getId());
                assertTrue(keys.add(setting.getKey()), "duplicate key " + setting.getKey());
            }
        }
    }

    @Test
    void enumSettingsListTheirDefaultAmongOptions() {
        SettingsCatalog catalog = SettingsCatalog.load();
        for (SettingsGroup group : catalog.getGroups()) {
            for (SettingDefinition setting : group.getSettings()) {
                if (setting.getType() == SettingType.ENUM) {
                    assertFalse(setting.getOptions().isEmpty(),
                            "enum setting " + setting.getKey() + " must define options");
                    if (setting.getDefaultValue() != null) {
                        assertTrue(setting.getOptions().contains(setting.getDefaultValue()),
                                "default of " + setting.getKey() + " must be one of its options");
                    }
                }
            }
        }
    }

    @Test
    void findSettingLocatesKnownKey() {
        SettingsCatalog catalog = SettingsCatalog.load();
        assertNotNull(catalog.findSetting("jmeter.laf"), "jmeter.laf should be catalogued");
        assertNotNull(catalog.findSetting("jmeter.save.saveservice.output_format"),
                "results file settings should be catalogued");
    }
}
