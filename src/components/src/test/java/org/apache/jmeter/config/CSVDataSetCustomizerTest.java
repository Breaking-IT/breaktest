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
                assertEquals("selected.csv", properties.get("filename"));
                assertEquals("files/selected.csv", properties.get("csvArchiveEntry"));
                assertEquals(ArchiveFiles.checksum(new byte[0]), properties.get("csvArchiveChecksum"));
                customizer.selection = null;
                browse.doClick();
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
        private boolean pickerOpened;

        @Override
        String chooseArchiveFile() {
            pickerOpened = true;
            return selection;
        }
    }
}
