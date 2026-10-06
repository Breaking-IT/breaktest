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

package org.apache.jmeter.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;

import org.junit.jupiter.api.Test;

class CompoundVariableDependenciesTest {
    @Test
    void compiledComponentsDistinguishReferencesFromEscapedLiterals() {
        assertEquals(Set.of(), new CompoundVariable("literal").getVariableDependencies());
        assertEquals(Set.of("a", "b"), new CompoundVariable("${a}-${b}-${a}").getVariableDependencies());
        assertEquals(Set.of("b"), new CompoundVariable("\\${a}-${b}").getVariableDependencies());
    }

    @Test
    void customEvaluationCannotClaimStaticDependencies() {
        CompoundVariable custom = new CompoundVariable("${a}") {
            @Override
            public String execute() {
                return "custom";
            }
        };
        assertNull(custom.getVariableDependencies());
    }
}
