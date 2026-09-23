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

package org.apache.jmeter.gui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.junit.JMeterTestCase;
import org.junit.jupiter.api.Test;

class ParameterFunctionCompletionTest extends JMeterTestCase {
    @Test
    void offersEveryInstalledFunctionWithoutExecutingIt() {
        Set<String> names = ParameterCompletionCatalog.functions().stream()
                .map(ParameterCompletionCatalog.Suggestion::name).collect(Collectors.toSet());
        Set<String> installed = Arrays.stream(CompoundVariable.getFunctionNames())
                .filter(name -> name.startsWith("__")).collect(Collectors.toSet());
        assertEquals(installed, names);
        assertTrue(names.containsAll(Set.of("__time", "__P", "__Random", "__groovy", "__threadNum",
                "__UUID", "__urlencode", "__RandomFromMultipleVars")));
        assertEquals("${__threadNum}", ParameterCompletionCatalog.functions().stream()
                .filter(value -> value.name().equals("__threadNum")).findFirst().orElseThrow().replacement());
        assertEquals("${__time()}", ParameterCompletionCatalog.functions().stream()
                .filter(value -> value.name().equals("__time")).findFirst().orElseThrow().replacement());
    }
}
