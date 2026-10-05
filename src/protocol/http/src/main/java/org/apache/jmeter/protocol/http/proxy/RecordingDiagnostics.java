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

package org.apache.jmeter.protocol.http.proxy;

import java.util.LinkedHashSet;
import java.util.Set;

/** Session diagnostics survive review and are reset only by starting a new recording. */
public final class RecordingDiagnostics {
    private int captured;
    private int filtered;
    private int failed;
    private int incomplete;
    private int processingErrors;
    private boolean reviewed;
    private final Set<String> issues = new LinkedHashSet<>();

    synchronized void captured(boolean failure) {
        captured++;
        if (failure) {
            failed++;
        }
    }

    synchronized void filtered() {
        filtered++;
    }

    synchronized void incomplete(String reason) {
        incomplete++;
        issue(reason);
    }

    public synchronized void processingError(String reason) {
        processingErrors++;
        issue(reason);
    }

    public synchronized void issue(String reason) {
        issues.add(reason);
        reviewed = false;
    }

    synchronized boolean pending() {
        return !reviewed && !issues.isEmpty();
    }

    synchronized void reviewed() {
        reviewed = true;
    }

    public synchronized String summary() {
        return "Captured: " + captured + " | Filtered: " + filtered + " | Failed: " + failed
                + " | Incomplete: " + incomplete + " | Processing errors: " + processingErrors;
    }

    public synchronized String details() {
        return String.join("\n", issues);
    }
}
