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

package org.apache.jmeter.save;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.jmeter.control.gui.TestPlanGui;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.gui.ThreadGroupGui;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup;
import org.apache.jmeter.threads.openmodel.gui.OpenModelThreadGroupGui;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenModelScheduleMigrationTest extends JMeterTestCase implements JMeterSerialTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loadingMigratesCurrentAndLegacyGroupsInPlainAndArchivedJmx(boolean archive) throws Exception {
        ThreadGroup current = new ThreadGroup();
        current.setProperty(TestElement.GUI_CLASS, ThreadGroupGui.class.getName());
        current.setThreadGroupModel(ThreadGroup.MODEL_OPEN);
        current.setOpenModelSchedule("rate(2/sec) random_arrival(10 sec) pause(3 sec)");
        current.setOpenModelRandomSeedString("42");
        current.setClosedModelSchedule("threadsPhase(10, 20)");
        OpenModelThreadGroup legacy = new OpenModelThreadGroup();
        legacy.setProperty(TestElement.GUI_CLASS, OpenModelThreadGroupGui.class.getName());
        legacy.setScheduleString("rate(1/sec) even_arrival(10 sec) rate(2/sec)");
        legacy.setRandomSeedString("24");
        legacy.setEnabled(false);
        ThreadGroup dynamic = new ThreadGroup();
        dynamic.setProperty(TestElement.GUI_CLASS, ThreadGroupGui.class.getName());
        dynamic.setOpenModelSchedule("rate($" + "{rate}/sec) random_arrival(10 sec)");
        HashTree tree = new ListedHashTree();
        TestPlan plan = new TestPlan();
        plan.setProperty(TestElement.GUI_CLASS, TestPlanGui.class.getName());
        HashTree children = tree.add(plan);
        children.add(current);
        children.add(legacy);
        children.add(dynamic);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveTree(tree, output);
        byte[] bytes = output.toByteArray();
        if (!archive) {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (SaveService.TEST_PLAN_ZIP_ENTRY.equals(entry.getName())) {
                        bytes = zip.readAllBytes();
                        break;
                    }
                }
            }
        }
        Path path = directory.resolve("legacy.jmx");
        Files.write(path, bytes);
        HashTree loaded = SaveService.loadTree(path.toFile());
        Object[] groups = loaded.getTree(loaded.getArray()[0]).getArray();
        ThreadGroup migratedCurrent = (ThreadGroup) groups[0];
        OpenModelThreadGroup migratedLegacy = (OpenModelThreadGroup) groups[1];
        assertEquals("constantThreadsPerMinDuring(120, 10, true)\nconstantThreadsPerMinDuring(0, 3, false)",
                migratedCurrent.getOpenModelSchedule());
        assertEquals("rampThreadsPerMinDuring(60, 120, 10, false)", migratedLegacy.getScheduleString());
        assertEquals("42", migratedCurrent.getOpenModelRandomSeedString());
        assertEquals("24", migratedLegacy.getRandomSeedString());
        assertEquals("threadsPhase(10, 20)", migratedCurrent.getClosedModelSchedule());
        assertFalse(migratedLegacy.isEnabled());
        assertEquals(dynamic.getOpenModelSchedule(), ((ThreadGroup) groups[2]).getOpenModelSchedule());
        assertArrayEquals(bytes, Files.readAllBytes(path), "Loading must not rewrite the original file");
        Path saved = directory.resolve("migrated.jmx");
        SaveService.saveTreeToFile(loaded, saved);
        HashTree reopened = SaveService.loadTree(saved.toFile());
        Object[] reopenedGroups = reopened.getTree(reopened.getArray()[0]).getArray();
        assertEquals(migratedCurrent.getOpenModelSchedule(), ((ThreadGroup) reopenedGroups[0]).getOpenModelSchedule());
        assertEquals(migratedLegacy.getScheduleString(), ((OpenModelThreadGroup) reopenedGroups[1]).getScheduleString());
    }
}
