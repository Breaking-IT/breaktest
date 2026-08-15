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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;

class AiCliAvailabilityTest {
    @Test
    void configuredCommandControlsAvailabilityLabel() throws Exception {
        Properties properties = jmeterProperties();
        String key = "breaktest.codex.command";
        String previous = properties.getProperty(key);
        Path executable = Files.createTempFile("breaktest-cli-availability", ".command");
        try {
            executable.toFile().setExecutable(true);
            properties.setProperty(key, executable.toString());
            assertTrue(AiCliAvailability.isAvailable("codex"));
            assertEquals("Codex", AiCliAvailability.displayName("codex", "Codex"));

            properties.setProperty(key, executable.resolveSibling("missing-command").toString());
            assertFalse(AiCliAvailability.isAvailable("codex"));
            assertEquals("Codex (unavailable)", AiCliAvailability.displayName("codex", "Codex"));
        } finally {
            Files.deleteIfExists(executable);
            if (previous == null) {
                properties.remove(key);
            } else {
                properties.setProperty(key, previous);
            }
        }
    }

    @Test
    void unknownToolIsUnavailable() {
        assertFalse(AiCliAvailability.isAvailable("unknown-tool"));
    }

    @Test
    void toolsAreAlphabeticalWithUnavailableToolsLast() throws Exception {
        Properties properties = jmeterProperties();
        String[] keys = {
            "breaktest.codex.command",
            "breaktest.cursor.command",
            "breaktest.claude.command",
            "breaktest.gemini.command"
        };
        String[] previous = Arrays.stream(keys).map(properties::getProperty).toArray(String[]::new);
        Path executable = Files.createTempFile("breaktest-cli-sort", ".command");
        try {
            executable.toFile().setExecutable(true);
            properties.setProperty(keys[0], executable.toString());
            properties.setProperty(keys[1], executable.toString());
            properties.setProperty(keys[2], executable.resolveSibling("missing-claude").toString());
            properties.setProperty(keys[3], executable.resolveSibling("missing-gemini").toString());

            Tool[] sorted = AiCliAvailability.sortAvailableFirst(
                    new Tool[] {
                        new Tool("codex", "Zulu Available"),
                        new Tool("claude", "Alpha Unavailable"),
                        new Tool("gemini", "Beta Unavailable"),
                        new Tool("cursor", "Alpha Available")
                    },
                    Tool::id,
                    Tool::name);

            assertEquals(
                    List.of("Alpha Available", "Zulu Available", "Alpha Unavailable", "Beta Unavailable"),
                    Arrays.stream(sorted).map(Tool::name).toList());
        } finally {
            Files.deleteIfExists(executable);
            for (int i = 0; i < keys.length; i++) {
                if (previous[i] == null) {
                    properties.remove(keys[i]);
                } else {
                    properties.setProperty(keys[i], previous[i]);
                }
            }
        }
    }

    private record Tool(String id, String name) {
    }

    private static Properties jmeterProperties() throws Exception {
        Properties properties = JMeterUtils.getJMeterProperties();
        if (properties != null) {
            return properties;
        }
        Path emptyProperties = Files.createTempFile("breaktest-cli-availability", ".properties");
        try {
            JMeterUtils.loadJMeterProperties(emptyProperties.toString());
            return JMeterUtils.getJMeterProperties();
        } finally {
            Files.deleteIfExists(emptyProperties);
        }
    }
}
