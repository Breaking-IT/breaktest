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

import java.util.List;

import org.apache.jmeter.readiness.Finding;

/**
 * What a check found, before accepted exceptions are applied.
 *
 * @param findings findings
 * @param applicable whether anything in scope was subject to the check
 * @param summary one-line summary
 * @param notVerifiedReason set when the check could not run at all (for instance without runtime evidence)
 */
public record CheckOutcome(List<Finding> findings, boolean applicable, String summary, String notVerifiedReason) {
    public CheckOutcome {
        findings = List.copyOf(findings);
    }

    public static CheckOutcome of(List<Finding> findings, boolean applicable, String summary) {
        return new CheckOutcome(findings, applicable, summary, null);
    }

    public static CheckOutcome notVerified(String reason) {
        return new CheckOutcome(List.of(), true, reason, reason);
    }
}
