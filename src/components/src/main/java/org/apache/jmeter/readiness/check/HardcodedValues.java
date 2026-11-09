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
import java.util.List;
import java.util.ServiceLoader;

import org.apache.jmeter.readiness.PlanFingerprint;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

/**
 * Looks for recorded dynamic values sent literally (hardcoded) by requests in scope, through the existing HAR
 * correlation tooling, which modules provide as a {@link Finder}. Candidates are for review only.
 */
public final class HardcodedValues {

    /**
     * A request that sends a value a previous response returned, without extracting it.
     *
     * @param samplerId source id of the request sending the literal value
     * @param value redacted, bounded value
     * @param rule correlation rule that recognized it
     * @param location where the request sends it
     * @param sourceRequest the recorded request whose response returned the value
     */
    public record Candidate(String samplerId, String value, String rule, String location, String sourceRequest) {
    }

    /**
     * @param candidates candidates
     * @param available whether a finder could scan (recorded responses available)
     * @param note what limited the scan, empty otherwise
     */
    public record Scan(List<Candidate> candidates, boolean available, String note) {
        public Scan {
            candidates = List.copyOf(candidates);
            note = note == null ? "" : note;
        }

        public static Scan unavailable(String note) {
            return new Scan(List.of(), false, note);
        }
    }

    /** A scan bound to the plan content that was inspected. */
    public record Snapshot(Scan scan, String fingerprint) {
        /**
         * @param tree current in-scope plan
         * @param scope current thread group ids
         * @return the scan, or an explicit unverified result when the plan changed
         */
        public Scan forPlan(HashTree tree, List<String> scope) {
            return fingerprint.equals(PlanFingerprint.compute(tree, null, scope)) ? scan
                    : Scan.unavailable("the plan changed after the recorded-value scan; run the check again");
        }
    }

    /** Scans thread groups for hardcoded recorded values. */
    public interface Finder {
        /**
         * @param threadGroups GUI thread groups in scope
         * @return the scan
         */
        Scan find(List<TestElement> threadGroups);
    }

    private HardcodedValues() {
    }

    /**
     * @param threadGroups GUI thread groups in scope
     * @return combined scan of every finder; unavailable when there is none
     */
    public static Scan scan(List<TestElement> threadGroups) {
        List<Candidate> candidates = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean available = false;
        for (Finder finder : ServiceLoader.load(Finder.class, HardcodedValues.class.getClassLoader())) {
            Scan scan = finder.find(threadGroups);
            available |= scan.available();
            candidates.addAll(scan.candidates());
            if (!scan.note().isEmpty()) {
                notes.add(scan.note());
            }
        }
        if (!available && notes.isEmpty()) {
            notes.add("The correlation tooling is not available");
        }
        return new Scan(candidates, available, String.join("; ", notes));
    }
}
