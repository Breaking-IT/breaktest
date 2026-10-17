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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The runtime evidence of the latest readiness validation: its main run, the Resume validation runs that continued
 * it, and an optional repeatability run.
 */
public final class EvidenceSession {
    private final List<RunSegment> segments = new ArrayList<>();
    private RunSegment repeatability;

    EvidenceSession(RunSegment main) {
        segments.add(main);
    }

    public synchronized RunSegment main() {
        return segments.get(0);
    }

    /**
     * @return the main run followed by its resume runs
     */
    public synchronized List<RunSegment> segments() {
        return List.copyOf(segments);
    }

    public synchronized RunSegment repeatability() {
        return repeatability;
    }

    synchronized void addResume(RunSegment resume) {
        segments.add(resume);
    }

    synchronized void setRepeatability(RunSegment segment) {
        repeatability = segment;
    }

    /**
     * Combined completion of the main run and its resume runs.
     *
     * @param status combined status
     * @param details why the combination is not complete
     */
    public record Completion(RunStatus status, List<String> details) {
        public Completion {
            details = List.copyOf(details);
        }
    }

    /**
     * A run continued with Resume validation is complete only when the resume runs, against one unchanged fingerprint
     * and the same settings, carried every thread group that did not complete to its end.
     *
     * @return the combined completion
     */
    public synchronized Completion completion() {
        RunSegment main = segments.get(0);
        if (segments.size() == 1) {
            return new Completion(main.status(), main.statusDetails());
        }
        List<String> details = new ArrayList<>();
        for (RunSegment segment : segments.subList(1, segments.size())) {
            if (!Objects.equals(segment.fingerprint(), main.fingerprint())) {
                details.add("A resumed run started after the plan changed; run a fresh readiness validation");
                return new Completion(RunStatus.INCOMPLETE, details);
            }
            if (!Objects.equals(segment.settings(), main.settings())
                    || segment.checkpointGeneration() != main.checkpointGeneration()) {
                details.add("A resumed run used other validation settings or checkpoints");
                return new Completion(RunStatus.INCOMPLETE, details);
            }
        }
        RunSegment last = segments.get(segments.size() - 1);
        if (!last.status().isComplete()) {
            details.add("The latest resumed run did not complete: " + last.status().label());
            details.addAll(last.statusDetails());
            return new Completion(last.status(), details);
        }
        // Every group that ended early must have been carried to its end by a later, complete segment
        Set<String> pending = new HashSet<>();
        for (RunSegment segment : segments) {
            if (segment.status().isComplete()) {
                pending.removeAll(segment.scope());
            } else {
                Set<String> stopped = segment.stoppedGroups();
                pending.addAll(stopped.isEmpty() ? Set.copyOf(segment.scope()) : stopped);
            }
        }
        if (!pending.isEmpty()) {
            details.add("Resumed runs did not cover every thread group that stopped");
            return new Completion(RunStatus.INCOMPLETE, details);
        }
        details.add("Completed through " + (segments.size() - 1) + " resumed run(s)");
        return new Completion(RunStatus.COMPLETED, details);
    }

    /**
     * @return every segment of the main validation, for evidence that does not depend on a single run
     */
    public synchronized List<SampleObservation> allSamples() {
        List<SampleObservation> all = new ArrayList<>();
        segments.forEach(s -> all.addAll(s.samples()));
        return all;
    }

    public synchronized List<ExtractorObservation> allExtractions() {
        List<ExtractorObservation> all = new ArrayList<>();
        segments.forEach(s -> all.addAll(s.extractions()));
        return all;
    }

    public synchronized List<UnresolvedObservation> allUnresolved() {
        List<UnresolvedObservation> all = new ArrayList<>();
        segments.forEach(s -> all.addAll(s.unresolved()));
        return all;
    }

    public synchronized boolean isTruncated() {
        return segments.stream().anyMatch(RunSegment::isTruncated);
    }
}
