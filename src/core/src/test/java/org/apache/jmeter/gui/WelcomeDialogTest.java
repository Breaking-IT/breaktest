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

package org.apache.jmeter.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.gui.settings.SettingsCatalog;
import org.apache.jmeter.gui.settings.SettingsGroup;
import org.apache.jmeter.gui.settings.SettingsModel;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WelcomeDialogTest extends JMeterTestCase {
    @TempDir
    Path tempDir;

    private Object previous;

    @BeforeEach
    void saveProperty() {
        previous = JMeterUtils.getJMeterProperties().remove(WelcomeDialog.SHOW_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (previous == null) {
            JMeterUtils.getJMeterProperties().remove(WelcomeDialog.SHOW_PROPERTY);
        } else {
            JMeterUtils.getJMeterProperties().put(WelcomeDialog.SHOW_PROPERTY, previous);
        }
    }

    @ParameterizedTest
    @CsvSource({",true", "true,true", "false,false"})
    void welcomeUsesThePropertyAndSkipsExplicitFiles(String configured, boolean expected) {
        if (configured != null) {
            JMeterUtils.setProperty(WelcomeDialog.SHOW_PROPERTY, configured);
        }
        assertEquals(expected, WelcomeDialog.shouldShow(null));
        assertFalse(WelcomeDialog.shouldShow("test.jmx"));
    }

    @Test
    void userOverrideRoundTripLeavesDistributionDefaultsUntouched() throws IOException {
        Path jmeter = tempDir.resolve("jmeter.properties");
        Path user = tempDir.resolve("user.properties");
        Path system = tempDir.resolve("system.properties");
        String defaults = "#welcome.show=true\n";
        Files.writeString(jmeter, defaults);
        Files.writeString(user, "unrelated.setting=keep\n");
        SettingsCatalog catalog = SettingsCatalog.load();
        SettingsGroup general = catalog.getGroups().stream()
                .filter(group -> group.getId().equals("general")).findFirst().orElseThrow();
        SettingsModel model = new SettingsModel(catalog, jmeter.toFile(), user.toFile(), system.toFile());
        model.apply(SettingsGroup.Target.USER, Map.of(WelcomeDialog.SHOW_PROPERTY, "false"), Set.of());
        assertFalse(WelcomeDialog.shouldShow(null));
        SettingsModel reloaded = new SettingsModel(catalog, jmeter.toFile(), user.toFile(), system.toFile());
        assertEquals("false", reloaded.getValue(general, catalog.findSetting(WelcomeDialog.SHOW_PROPERTY)));
        assertTrue(Files.readString(user).contains("welcome.show=false"));
        assertEquals(defaults, Files.readString(jmeter));

        reloaded.apply(SettingsGroup.Target.USER, Map.of(), Set.of(WelcomeDialog.SHOW_PROPERTY));
        assertTrue(WelcomeDialog.shouldShow(null));
        assertNull(JMeterUtils.getJMeterProperties().getProperty(WelcomeDialog.SHOW_PROPERTY));
        SettingsModel reset = new SettingsModel(catalog, jmeter.toFile(), user.toFile(), system.toFile());
        assertEquals("true", reset.getValue(general, catalog.findSetting(WelcomeDialog.SHOW_PROPERTY)));
        assertFalse(Files.readString(user).contains("welcome.show"));
        assertTrue(Files.readString(user).contains("unrelated.setting=keep"));
        assertEquals(defaults, Files.readString(jmeter));
        assertFalse(Files.exists(system));
    }
}
