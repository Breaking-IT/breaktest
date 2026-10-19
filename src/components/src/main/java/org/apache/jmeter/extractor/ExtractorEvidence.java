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

package org.apache.jmeter.extractor;

import org.apache.jmeter.readiness.evidence.ReadinessRecorder;
import org.apache.jmeter.testelement.TestElement;

/**
 * Sibling of {@link ExtractorFailure}: reports, for every invocation and whatever the "fail on no match" setting,
 * whether a supported extractor matched. Only a script readiness validation records it; otherwise this is a single
 * volatile read.
 */
public final class ExtractorEvidence {

    private ExtractorEvidence() {
    }

    /**
     * @param extractor the extractor reporting
     * @param matched whether it matched
     * @param expression its expression, for evidence
     * @param anchor literal text in front of the expected value, used to take a response excerpt; may be empty
     */
    public static void outcome(TestElement extractor, boolean matched, String expression, String anchor) {
        if (ReadinessRecorder.isActive()) {
            ReadinessRecorder.extractorOutcome(extractor, matched, expression, anchor);
        }
    }

    /**
     * @param regex a regular expression
     * @return its literal prefix (up to the first special character), usable as an excerpt anchor
     */
    static String literalPrefix(String regex) {
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                break;
            }
            prefix.append(c);
        }
        return prefix.toString();
    }
}
