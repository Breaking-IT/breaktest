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
 * Notified about a readiness validation run started through
 * {@link org.apache.jmeter.gui.action.Start#readinessValidationEvent}. Callbacks may run on any thread.
 */
public interface ReadinessRunListener {
    /**
     * @param segment the segment that started collecting evidence
     */
    void runStarted(RunSegment segment);

    /**
     * @param segment the segment that ended; its status says how
     */
    void runFinished(RunSegment segment);

    /**
     * @param message why the run could not start
     */
    void runNotStarted(String message);
}
