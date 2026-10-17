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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.gui.action.validation.ValidationSettings;

/**
 * Evidence collected by one validation run: what ran, how the run ended, and the fingerprint, settings and scope it
 * ran against. Collections are bounded; {@link #isTruncated()} tells when a bound was reached. Thread safe while the
 * run is collecting, read only afterwards.
 */
public final class RunSegment {
    static final int MAX_SAMPLES = 20_000;
    static final int MAX_EXTRACTIONS = 50_000;
    static final int MAX_UNRESOLVED = 2_000;
    static final int MAX_CAPTURES = 1_000;
    /** Responses larger than this are not kept for offline timing */
    static final int MAX_CAPTURED_RESPONSE_BYTES = 1 << 20;

    private final RunKind kind;
    private final String fingerprint;
    private final ValidationSettings settings;
    private final List<String> scope;
    private final long checkpointGeneration;
    private final Set<String> resumedFrom;
    private final Instant start = Instant.now();
    private Instant end;
    private RunStatus status = RunStatus.RUNNING;
    private final List<String> statusDetails = new ArrayList<>();
    private final List<SampleObservation> samples = new ArrayList<>();
    private final List<ExtractorObservation> extractions = new ArrayList<>();
    private final List<UnresolvedObservation> unresolved = new ArrayList<>();
    private final Map<String, ExtractorCapture> captures = new LinkedHashMap<>();
    private final Set<String> invokedExtractors = new LinkedHashSet<>();
    private final Set<String> stoppedGroups = new LinkedHashSet<>();
    private boolean truncated;

    /**
     * @param kind why the run happens
     * @param fingerprint fingerprint of the scope when the run started
     * @param settings validation settings the run uses
     * @param scope source ids of the thread groups in scope
     * @param checkpointGeneration validation checkpoint generation the source ids belong to
     * @param resumedFrom execution ids of the checkpoints a resume run starts from; empty otherwise
     */
    public RunSegment(RunKind kind, String fingerprint, ValidationSettings settings, List<String> scope,
            long checkpointGeneration, Set<String> resumedFrom) {
        this.kind = kind;
        this.fingerprint = fingerprint;
        this.settings = settings;
        this.scope = List.copyOf(scope);
        this.checkpointGeneration = checkpointGeneration;
        this.resumedFrom = Set.copyOf(resumedFrom);
    }

    public RunKind kind() {
        return kind;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public ValidationSettings settings() {
        return settings;
    }

    public List<String> scope() {
        return scope;
    }

    public long checkpointGeneration() {
        return checkpointGeneration;
    }

    public Set<String> resumedFrom() {
        return resumedFrom;
    }

    public Instant start() {
        return start;
    }

    public synchronized Instant end() {
        return end;
    }

    public synchronized RunStatus status() {
        return status;
    }

    public synchronized List<String> statusDetails() {
        return List.copyOf(statusDetails);
    }

    /**
     * Records why the run did not complete. The first reason that is not {@link RunStatus#COMPLETED} wins, so a
     * cancellation is not hidden by the end of the run that follows it.
     *
     * @param newStatus status
     * @param detail human readable detail, may be {@code null}
     */
    public synchronized void markStatus(RunStatus newStatus, String detail) {
        if (status == RunStatus.RUNNING || status == RunStatus.COMPLETED) {
            status = newStatus;
        }
        if (detail != null && statusDetails.size() < 50) {
            statusDetails.add(detail);
        }
    }

    synchronized void finish() {
        if (end == null) {
            end = Instant.now();
        }
        if (status == RunStatus.RUNNING) {
            status = samples.isEmpty() ? RunStatus.INCOMPLETE : RunStatus.COMPLETED;
            if (samples.isEmpty()) {
                statusDetails.add("No sampler executed");
            }
        }
    }

    /**
     * Records that a thread of a group stopped before the end of its iterations.
     *
     * @param groupId source id of the thread group
     * @param newStatus why
     * @param detail detail
     */
    public synchronized void groupStopped(String groupId, RunStatus newStatus, String detail) {
        if (groupId != null && !groupId.isEmpty()) {
            stoppedGroups.add(groupId);
        }
        markStatus(newStatus, detail);
    }

    /**
     * @return source ids of the thread groups whose threads stopped early
     */
    public synchronized Set<String> stoppedGroups() {
        return Set.copyOf(stoppedGroups);
    }

    synchronized void addSample(SampleObservation observation) {
        if (samples.size() < MAX_SAMPLES) {
            samples.add(observation);
        } else {
            truncated = true;
        }
    }

    synchronized void addExtraction(ExtractorObservation observation) {
        if (extractions.size() < MAX_EXTRACTIONS) {
            extractions.add(observation);
        } else {
            truncated = true;
        }
    }

    synchronized void addUnresolved(UnresolvedObservation observation) {
        if (unresolved.size() < MAX_UNRESOLVED) {
            unresolved.add(observation);
        } else {
            truncated = true;
        }
    }

    /**
     * @param extractorId source id
     * @return whether this is the first invocation of the extractor in the run; records it
     */
    synchronized boolean firstInvocation(String extractorId) {
        return invokedExtractors.add(extractorId);
    }

    synchronized boolean wantsCapture(String extractorId) {
        return captures.size() < MAX_CAPTURES && !captures.containsKey(extractorId);
    }

    synchronized void addCapture(ExtractorCapture capture) {
        captures.putIfAbsent(capture.extractorId(), capture);
    }

    public synchronized List<SampleObservation> samples() {
        return List.copyOf(samples);
    }

    public synchronized List<ExtractorObservation> extractions() {
        return List.copyOf(extractions);
    }

    public synchronized List<UnresolvedObservation> unresolved() {
        return List.copyOf(unresolved);
    }

    public synchronized Map<String, ExtractorCapture> captures() {
        return Map.copyOf(captures);
    }

    public synchronized boolean isTruncated() {
        return truncated;
    }
}
