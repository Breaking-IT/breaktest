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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.jmeter.threads.ValidationCheckpoints;

/**
 * Keeps the evidence of the latest readiness validation for the GUI session. Cleared when the test plan is cleared
 * or replaced; never saved.
 */
public final class ReadinessEvidenceStore {
    private static EvidenceSession latest;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> CLEAR_LISTENERS = new CopyOnWriteArrayList<>();

    private ReadinessEvidenceStore() {
    }

    /**
     * @return the latest evidence, or {@code null}
     */
    public static synchronized EvidenceSession latest() {
        return latest;
    }

    /**
     * @param listener called, on any thread, when evidence was added or cleared
     */
    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * @param listener called when the test plan is cleared or replaced, so reports built on it are dropped too
     */
    public static void addClearListener(Runnable listener) {
        CLEAR_LISTENERS.add(listener);
    }

    /**
     * Forgets all evidence, when the test plan is cleared or replaced.
     */
    public static void clear() {
        synchronized (ReadinessEvidenceStore.class) {
            latest = null;
        }
        for (Runnable listener : CLEAR_LISTENERS) {
            listener.run();
        }
        notifyListeners();
    }

    /**
     * @return whether a Resume validation would continue the latest readiness validation
     */
    public static synchronized boolean canContinueWithResume() {
        return latest != null && latest.main().checkpointGeneration() == ValidationCheckpoints.generation()
                && !ReadinessRecorder.isActive();
    }

    /**
     * Starts collecting a segment.
     *
     * @param segment segment that starts
     */
    public static void begin(RunSegment segment) {
        synchronized (ReadinessEvidenceStore.class) {
            switch (segment.kind()) {
                case MAIN -> latest = new EvidenceSession(segment);
                case RESUME -> {
                    if (latest != null) {
                        latest.addResume(segment);
                    }
                }
            }
        }
        ReadinessRecorder.begin(segment);
    }

    /**
     * Stops collecting a segment.
     *
     * @param segment segment that ended
     * @return the segment
     */
    public static RunSegment end(RunSegment segment) {
        ReadinessRecorder.end(segment);
        notifyListeners();
        return segment;
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }
}
