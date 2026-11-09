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

package org.apache.jmeter.readiness.check;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.readiness.evidence.EvidenceSession;
import org.apache.jmeter.readiness.evidence.ExtractorObservation;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.readiness.evidence.SampleObservation;
import org.apache.jmeter.readiness.evidence.UnresolvedObservation;

/**
 * Indexed runtime evidence of the main readiness validation (and its resumed runs), with whether it still matches the
 * plan.
 */
public final class RuntimeEvidence {
    private final EvidenceSession session;
    private final boolean current;
    private final String staleReason;
    private final Set<String> executedSamplers = new HashSet<>();
    private final Set<String> executedPathIds = new HashSet<>();
    private final Map<String, List<SampleObservation>> samplesBySampler = new LinkedHashMap<>();
    private final Map<String, List<ExtractorObservation>> extractionsById = new LinkedHashMap<>();
    private final List<UnresolvedObservation> unresolved;

    /**
     * @param session evidence
     * @param current whether its fingerprint matches the plan
     * @param staleReason why it does not, when it does not
     */
    public RuntimeEvidence(EvidenceSession session, boolean current, String staleReason) {
        this.session = session;
        this.current = current;
        this.staleReason = staleReason == null ? "" : staleReason;
        for (SampleObservation sample : session.allSamples()) {
            executedSamplers.add(sample.samplerId());
            samplesBySampler.computeIfAbsent(sample.samplerId(), ignored -> new ArrayList<>()).add(sample);
            for (String id : sample.executionId().split("/")) {
                if (!id.isEmpty()) {
                    executedPathIds.add(id);
                }
            }
        }
        for (ExtractorObservation extraction : session.allExtractions()) {
            extractionsById.computeIfAbsent(extraction.extractorId(), ignored -> new ArrayList<>()).add(extraction);
        }
        unresolved = session.allUnresolved();
    }

    public EvidenceSession session() {
        return session;
    }

    public RunSegment main() {
        return session.main();
    }

    public boolean isCurrent() {
        return current;
    }

    public String staleReason() {
        return staleReason;
    }

    /**
     * @param sourceId source id of a sampler or controller
     * @return whether it executed (a controller: whether a sampler below it executed)
     */
    public boolean executed(String sourceId) {
        return executedSamplers.contains(sourceId) || executedPathIds.contains(sourceId);
    }

    public List<SampleObservation> samplesOf(String samplerId) {
        return samplesBySampler.getOrDefault(samplerId, List.of());
    }

    public List<ExtractorObservation> extractionsOf(String extractorId) {
        return extractionsById.getOrDefault(extractorId, List.of());
    }

    public List<UnresolvedObservation> unresolved() {
        return unresolved;
    }

    /**
     * @param controllerId source id of a controller
     * @return samples executed below it
     */
    public List<SampleObservation> samplesBelow(String controllerId) {
        List<SampleObservation> result = new ArrayList<>();
        String marker = "/" + controllerId + "/";
        for (List<SampleObservation> samples : samplesBySampler.values()) {
            for (SampleObservation sample : samples) {
                if (sample.executionId().contains(marker)) {
                    result.add(sample);
                }
            }
        }
        return result;
    }
}
