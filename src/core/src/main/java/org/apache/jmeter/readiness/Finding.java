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

import java.util.List;
import java.util.Objects;

/**
 * One finding of a readiness check.
 *
 * @param checkId check that produced the finding
 * @param element element the finding is about, which also carries an accepted exception for it
 * @param findingKey stable key of the finding within the check and element (a variable name, a field, ...)
 * @param severity severity before exceptions are applied
 * @param message one-line description
 * @param evidence bounded, redacted evidence lines
 * @param transaction enclosing transaction or unit, used to group findings; may be {@code null}
 * @param exception the accepted exception covering this finding, or {@code null}
 */
public record Finding(CheckId checkId, ElementRef element, String findingKey, Severity severity, String message,
        List<String> evidence, ElementRef transaction, AcceptedException exception) {

    public Finding {
        Objects.requireNonNull(checkId, "checkId");
        Objects.requireNonNull(severity, "severity");
        findingKey = findingKey == null ? "" : findingKey;
        message = message == null ? "" : message;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public Finding(CheckId checkId, ElementRef element, String findingKey, Severity severity, String message,
            List<String> evidence, ElementRef transaction) {
        this(checkId, element, findingKey, severity, message, evidence, transaction, null);
    }

    /**
     * @return whether an accepted exception covers this finding
     */
    public boolean isAccepted() {
        return exception != null;
    }

    /**
     * @param accepted exception covering the finding
     * @return a copy with the exception
     */
    public Finding withException(AcceptedException accepted) {
        return new Finding(checkId, element, findingKey, severity, message, evidence, transaction, accepted);
    }

    /**
     * @return whether the finding counts against the check (not informational and not accepted)
     */
    public boolean isOpen() {
        return severity != Severity.INFO && exception == null;
    }
}
