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

package org.apache.jmeter.readiness.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Set;

import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.junit.jupiter.api.Test;

class EvidenceSessionTest {
    private static final ValidationSettings SETTINGS = ValidationSettings.defaults();

    private static RunSegment segment(RunKind kind, String fingerprint, List<String> scope) {
        return new RunSegment(kind, fingerprint, SETTINGS, scope, 1, Set.of());
    }

    private static void sample(RunSegment segment, String group) {
        segment.addSample(new SampleObservation("s", "/" + group + "/s", group, "t-1", 1, true, "200", List.of(),
                Set.of(), false));
    }

    @Test
    void completedRunIsComplete() {
        RunSegment main = segment(RunKind.MAIN, "f", List.of("g1"));
        sample(main, "g1");
        main.finish();
        assertEquals(RunStatus.COMPLETED, new EvidenceSession(main).completion().status());
    }

    @Test
    void runWithoutSamplesIsIncomplete() {
        RunSegment main = segment(RunKind.MAIN, "f", List.of("g1"));
        main.finish();
        assertEquals(RunStatus.INCOMPLETE, main.status());
    }

    @Test
    void cancelledRunStaysCancelledWhenItEnds() {
        RunSegment main = segment(RunKind.MAIN, "f", List.of("g1"));
        sample(main, "g1");
        main.markStatus(RunStatus.CANCELLED, "Cancelled by the user");
        main.markStatus(RunStatus.TIMED_OUT, "later");
        main.finish();
        assertEquals(RunStatus.CANCELLED, new EvidenceSession(main).completion().status());
    }

    @Test
    void resumedRunsCoveringTheStoppedGroupComplete() {
        RunSegment main = segment(RunKind.MAIN, "f", List.of("g1", "g2"));
        sample(main, "g1");
        sample(main, "g2");
        main.groupStopped("g2", RunStatus.STOPPED_ON_ERROR, "stopped after failed sampler");
        main.finish();
        EvidenceSession session = new EvidenceSession(main);
        assertEquals(RunStatus.STOPPED_ON_ERROR, session.completion().status());
        RunSegment resume = segment(RunKind.RESUME, "f", List.of("g2"));
        sample(resume, "g2");
        resume.finish();
        session.addResume(resume);
        assertEquals(RunStatus.COMPLETED, session.completion().status());
    }

    @Test
    void resumedRunsOfAnotherGroupAreIncomplete() {
        RunSegment main = segment(RunKind.MAIN, "f", List.of("g1", "g2"));
        sample(main, "g2");
        main.groupStopped("g2", RunStatus.STOPPED_ON_ERROR, "stopped");
        main.finish();
        EvidenceSession session = new EvidenceSession(main);
        RunSegment resume = segment(RunKind.RESUME, "f", List.of("g1"));
        sample(resume, "g1");
        resume.finish();
        session.addResume(resume);
        assertEquals(RunStatus.INCOMPLETE, session.completion().status());
    }

    @Test
    void resumedRunsAfterAPlanChangeAreIncomplete() {
        RunSegment main = segment(RunKind.MAIN, "f", List.of("g1"));
        sample(main, "g1");
        main.groupStopped("g1", RunStatus.STOPPED_ON_ERROR, "stopped");
        main.finish();
        EvidenceSession session = new EvidenceSession(main);
        RunSegment resume = segment(RunKind.RESUME, "changed", List.of("g1"));
        sample(resume, "g1");
        resume.finish();
        session.addResume(resume);
        assertEquals(RunStatus.INCOMPLETE, session.completion().status());
    }

    @Test
    void storeKeepsTheLatestReadinessValidationUntilCleared() {
        RunSegment main = segment(RunKind.MAIN, "f", List.of("g1"));
        ReadinessEvidenceStore.begin(main);
        ReadinessEvidenceStore.end(main);
        assertSame(main, ReadinessEvidenceStore.latest().main());
        ReadinessEvidenceStore.clear();
        assertEquals(null, ReadinessEvidenceStore.latest());
    }
}
