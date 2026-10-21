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

package org.apache.jmeter.readiness;

import java.util.Locale;

/**
 * The checks of the script readiness wizard, with whether each one is mandatory for a Ready verdict and whether it
 * needs evidence from a validation run.
 */
public enum CheckId {
    VALIDATION("validation", "Successful validation", true, Evidence.RUNTIME),
    VERIFICATION_COVERAGE("verification-coverage", "Transaction verification coverage", true, Evidence.BOTH),
    UNUSED_EXTRACTIONS("unused-extractions", "Unused extractions", false, Evidence.STATIC),
    UNMATCHED_EXTRACTIONS("unmatched-extractions", "Unmatched extractions", true, Evidence.RUNTIME),
    EXTRACTION_TIME("extraction-time", "Extraction processing time", false, Evidence.RUNTIME),
    THINK_TIME("think-time", "Think-time review", false, Evidence.STATIC),
    UNRESOLVED_VARIABLES("unresolved-variables", "Unresolved variables sent in requests", true, Evidence.BOTH),
    FILES("files", "Files and configuration present", true, Evidence.STATIC),
    EXERCISE_COVERAGE("exercise-coverage", "Validation exercise coverage", false, Evidence.RUNTIME),
    HARDCODED_VALUES("hardcoded-values", "Hardcoded recorded values", false, Evidence.STATIC),
    REPEATABILITY("repeatability", "Repeatability", false, Evidence.RUNTIME),
    RESPONSE_BODY_STORAGE("response-body-storage", "Response body storage", false, Evidence.STATIC);

    /** Where a check gets its evidence from. */
    public enum Evidence {
        /** The test plan configuration only */
        STATIC,
        /** A validation run only */
        RUNTIME,
        /** The configuration, confirmed by a validation run */
        BOTH
    }

    private final String key;
    private final String title;
    private final boolean mandatory;
    private final Evidence evidence;

    CheckId(String key, String title, boolean mandatory, Evidence evidence) {
        this.key = key;
        this.title = title;
        this.mandatory = mandatory;
        this.evidence = evidence;
    }

    /**
     * @return stable identifier, used in accepted exception property names
     */
    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    /**
     * @return whether Ready requires this check to have run against the current plan
     */
    public boolean isMandatory() {
        return mandatory;
    }

    public Evidence evidence() {
        return evidence;
    }

    /**
     * @return whether the check needs a validation run for (part of) its verdict
     */
    public boolean needsValidationRun() {
        return evidence != Evidence.STATIC;
    }

    /**
     * @param key a {@link #key()}
     * @return the check, or {@code null} when the key is unknown
     */
    public static CheckId fromKey(String key) {
        for (CheckId id : values()) {
            if (id.key.equals(key.toLowerCase(Locale.ROOT))) {
                return id;
            }
        }
        return null;
    }
}
