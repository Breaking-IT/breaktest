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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class CodexOutputCompatibilityTest {
    private static final List<String> COMMAND = List.of("codex", "exec", "--skip-git-repo-check", "user prompt");

    @Test
    void unknownCapabilitiesAndJsonWithoutReportSupportUseLegacyOutput() throws Exception {
        for (String help : List.of("", "Usage: codex exec --json")) {
            try (var compatibility = CodexOutputCompatibility.configure(COMMAND, help)) {
                assertEquals(COMMAND, compatibility.command());
            }
        }
    }

    @Test
    void independentReportRecoversStatusAndBlockerWhenEventsAreUnrecognized() throws Exception {
        Path report;
        try (var compatibility = CodexOutputCompatibility.configure(COMMAND, "--json --output-last-message")) {
            var command = compatibility.command();
            assertTrue(command.contains("--json"));
            assertEquals("user prompt", command.get(command.size() - 1));
            report = Path.of(command.get(command.indexOf("--output-last-message") + 1));
            Files.writeString(report, "Status: blocked\nRemaining blocker: target endpoint is unavailable.");
            var output = new AiRunOutput();
            var log = new ArrayList<String>();
            compatibility.recoverFinalReport(output, log::add);
            output.requireRepairCompletionStatus();
            assertTrue(output.hasRepairBlocker());
            assertTrue(output.followUpLines().stream().anyMatch(line -> line.contains("target endpoint is unavailable")));
            assertFalse(output.followUpLines().stream().anyMatch(line -> line.contains("without a completion report")));
            assertEquals(2, log.size());
        }
        assertFalse(Files.exists(report));
    }

    @Test
    void emptyReportPreservesStreamedCompletion() throws Exception {
        try (var compatibility = CodexOutputCompatibility.configure(COMMAND, "--output-last-message")) {
            assertFalse(compatibility.command().contains("--json"));
            var output = new AiRunOutput();
            output.captureFinalResponse("Status: completed");
            compatibility.recoverFinalReport(output, ignored -> { });
            assertTrue(output.hasCompletionStatus());
            assertFalse(output.hasRepairBlocker());
        }
    }
}
