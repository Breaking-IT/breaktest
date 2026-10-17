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

/**
 * How a validation run that collected readiness evidence ended.
 */
public enum RunStatus {
    RUNNING("Running", false),
    COMPLETED("Completed", true),
    CANCELLED("Cancelled", false),
    STOPPED_ON_ERROR("Stopped on error", false),
    STOPPED_BY_ACTION("Stopped by a sampler or flow control action", false),
    TIMED_OUT("Timed out", false),
    FAILED_TO_START("Failed to start", false),
    INCOMPLETE("Incomplete", false);

    private final String label;
    private final boolean complete;

    RunStatus(String label, boolean complete) {
        this.label = label;
        this.complete = complete;
    }

    public String label() {
        return label;
    }

    /**
     * @return whether the run executed to its end
     */
    public boolean isComplete() {
        return complete;
    }
}
