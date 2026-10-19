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

import java.util.Objects;

/**
 * A readiness finding a person accepted, with the reason. Stored on the affected test element, so it travels with
 * the script; see {@link ReadinessExceptions}.
 *
 * @param checkId check whose finding is accepted
 * @param findingKey key of the accepted finding, or {@link ReadinessExceptions#ANY_KEY} for the whole element
 * @param reason why the finding is acceptable
 * @param author who accepted it, empty when unknown
 * @param date ISO-8601 date the exception was recorded
 */
public record AcceptedException(CheckId checkId, String findingKey, String reason, String author, String date) {
    public AcceptedException {
        Objects.requireNonNull(checkId, "checkId");
        findingKey = findingKey == null || findingKey.isEmpty() ? ReadinessExceptions.ANY_KEY : findingKey;
        reason = reason == null ? "" : reason;
        author = author == null ? "" : author;
        date = date == null ? "" : date;
    }

    /**
     * @param finding a finding
     * @return whether this exception covers the finding, assuming it is stored on the finding's element
     */
    public boolean covers(Finding finding) {
        return finding.checkId() == checkId
                && (ReadinessExceptions.ANY_KEY.equals(findingKey) || findingKey.equals(finding.findingKey()));
    }
}
