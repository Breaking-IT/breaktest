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

package org.apache.jmeter.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;

class ArchiveFileTest extends JMeterTestCase implements JMeterSerialTest {
    @Test
    void registeredExpressionResolvesFileFromUnsavedPlan() throws Exception {
        assertTrue(ServiceLoader.load(Function.class).stream()
                .anyMatch(provider -> provider.type() == ArchiveFile.class));
        TestPlan plan = new TestPlan();
        ArchiveFiles.put(plan, "payload.json", "{}".getBytes(StandardCharsets.UTF_8), false);
        ArchiveFiles.activate(plan);
        try {
            String resolved = new CompoundVariable("${__archiveFile(payload.json)}").execute();
            assertEquals("{}", Files.readString(Path.of(resolved)));
        } finally {
            ArchiveFiles.activate(null);
        }
    }

    @Test
    void resolvesAnArchivedFileAndReportsMissingFiles() throws Exception {
        TestPlan plan = new TestPlan();
        ArchiveFiles.put(plan, "payload.json", "{}".getBytes(StandardCharsets.UTF_8), false);
        ArchiveFiles.activate(plan);
        try {
            ArchiveFile function = new ArchiveFile();
            function.setParameters(List.of(new CompoundVariable("payload.json")));
            assertEquals("{}", Files.readString(Path.of(function.execute(null, null))));
            function.setParameters(List.of(new CompoundVariable("missing.json")));
            assertThrows(InvalidVariableException.class, () -> function.execute(null, null));
        } finally {
            ArchiveFiles.activate(null);
        }
    }
}
