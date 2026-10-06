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

import java.util.Collection;
import java.util.Map;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.functions.InvalidVariableException;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.RecordedValueReplacer;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies recorder variables while retaining the encoding of request values. */
final class RecordedVariableReplacer {
    private static final Logger log = LoggerFactory.getLogger(RecordedVariableReplacer.class);

    private RecordedVariableReplacer() {
    }

    static void replaceValues(TestElement sampler, TestElement[] configs, Collection<? extends Arguments> variables) {
        // Build the replacer from all the variables in the collection:
        ValueReplacer replacer = new ValueReplacer();
        Map<String, String> recordedVariables = new java.util.LinkedHashMap<>();
        for (Arguments variable : variables) {
            final Map<String, String> map = variable.getArgumentsAsMap();
            // Drop any empty values (Bug 45199)
            map.values().removeIf(""::equals);
            replacer.addVariables(map);
            recordedVariables.putAll(map);
        }

        try {
            HTTPSamplerBase recorded = sampler instanceof HTTPSamplerBase http
                    ? (HTTPSamplerBase) http.clone() : null;
            replacer.reverseReplace(sampler, false);
            if (recorded != null && sampler instanceof HTTPSamplerBase http) {
                http.setPath(recorded.getPath());
                for (int i = 0; i < http.getArguments().getArgumentCount(); i++) {
                    http.getArguments().getArgument(i).setValue(recorded.getArguments().getArgument(i).getValue());
                }
                for (int i = 0; i < http.getNativeHeaderList().size(); i++) {
                    http.getNativeHeaderList().get(i).setValue(recorded.getNativeHeaderList().get(i).getValue());
                }
            }
            for (TestElement config : configs) {
                if (config != null && !(config instanceof HeaderManager)) {
                    replacer.reverseReplace(config, false);
                }
            }
            // Parsed parameters are already decoded. Raw fields need an encoding function.
            for (var entry : recordedVariables.entrySet()) {
                String reference = "${" + entry.getKey() + "}";
                if (sampler instanceof HTTPSamplerBase httpSampler) {
                    RecordedValueReplacer.replaceSampler(httpSampler, entry.getValue(), reference);
                }
                for (TestElement config : configs) {
                    if (config instanceof HeaderManager headers) {
                        for (var property : headers.getHeaders()) {
                            Header header = (Header) property.getObjectValue();
                            header.setName(RecordedValueReplacer.replace(
                                    header.getName(), entry.getValue(), reference, true));
                            header.setValue(RecordedValueReplacer.replace(
                                    header.getValue(), entry.getValue(), reference, false));
                        }
                    }
                }
            }
        } catch (InvalidVariableException e) {
            log.warn("Invalid variables included for replacement into recorded sample", e);
        }
    }
}
