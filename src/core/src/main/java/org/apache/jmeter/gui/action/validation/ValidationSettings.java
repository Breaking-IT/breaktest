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

package org.apache.jmeter.gui.action.validation;

/**
 * Settings a validation run clones the test plan with. Ordinary validation uses {@link #defaults()}, read once from
 * the {@code testplan_validation.*} properties; a script readiness run may override them for its own run only.
 *
 * @param threadsPerThreadGroup threads each validated thread group runs
 * @param ignoreTimers whether timers are left out of the validation tree
 * @param iterations iterations of each validated thread group with a loop controller
 * @param ignoreBackends whether backend listeners are left out of the validation tree
 */
public record ValidationSettings(int threadsPerThreadGroup, boolean ignoreTimers, int iterations,
        boolean ignoreBackends) {

    /**
     * @return the settings from the {@code testplan_validation.*} properties
     */
    public static ValidationSettings defaults() {
        return TreeClonerForValidation.DEFAULT_SETTINGS;
    }

    /**
     * @param iterations iterations to run
     * @return these settings with another iteration count
     */
    public ValidationSettings withIterations(int iterations) {
        return new ValidationSettings(threadsPerThreadGroup, ignoreTimers, iterations, ignoreBackends);
    }

    @Override
    public String toString() {
        return "threads=" + threadsPerThreadGroup + ", iterations=" + iterations
                + ", ignoreTimers=" + ignoreTimers + ", ignoreBackends=" + ignoreBackends;
    }
}
