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

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.jmeter.testelement.AbstractScopedTestElement;
import org.apache.jmeter.threads.JMeterVariables;

/** Variables and match count produced by an isolated recorded-response preview. */
public record ExtractorTestResult(Map<String, String> variables, Set<String> countVariables, int matchCount) {
    public static JMeterVariables newVariables(AbstractScopedTestElement extractor) {
        if (extractor.isScopeVariable(extractor.fetchScope())) {
            throw new IllegalArgumentException("Runtime variables are unavailable in the recorded-response tester");
        }
        JMeterVariables variables = new JMeterVariables();
        // The constructor preloads START.* properties, which are not extractor output.
        variables.clear();
        return variables;
    }

    public static ExtractorTestResult from(JMeterVariables variables, String reference, int matchCount) {
        return from(variables, Set.of(reference + "_matchNr"), matchCount);
    }

    public static ExtractorTestResult from(JMeterVariables variables, Set<String> counts, int matchCount) {
        Map<String, String> values = new TreeMap<>();
        variables.getIterator().forEachRemaining(entry -> values.put(entry.getKey(), (String) entry.getValue()));
        return new ExtractorTestResult(Collections.unmodifiableMap(values), Set.copyOf(counts), matchCount);
    }
}
