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

/**
 * State of one check in a readiness report.
 */
public enum CheckState {
    PASSED("Passed"),
    /** Every failing finding is covered by an accepted exception */
    ACCEPTED("Accepted"),
    NOT_APPLICABLE("Not applicable"),
    BLOCKER("Blocker"),
    WARNING("Warning"),
    REVIEW("Review required"),
    NOT_VERIFIED("Not verified"),
    /** The check was not selected for this run */
    NOT_RUN("Not run");

    private final String label;

    CheckState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
