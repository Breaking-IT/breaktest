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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class VariableReferencesTest {

    @Test
    void findsPlainReferences() {
        VariableReferences.Result result = VariableReferences.parse("/a/${id}?q=${query}&x=${id}");
        assertEquals(Set.of("id", "query"), result.names());
        assertFalse(result.dynamic());
    }

    @Test
    void functionsAreNotVariablesButTheirArgumentsAreParsed() {
        VariableReferences.Result result = VariableReferences.parse(
                "${__P(host,localhost)}/${__urlencode(${token})}/${__Random(1,9,rnd)}");
        assertEquals(Set.of("token"), result.names());
        assertTrue(result.functions().containsAll(Set.of("__P", "__urlencode", "__Random")));
        assertTrue(result.possiblyDefined().contains("rnd"));
        assertFalse(result.possiblyDefined().contains("host"));
    }

    @Test
    void computedNamesAreDynamic() {
        assertTrue(VariableReferences.parse("${__V(item_${i})}").dynamic());
        VariableReferences.Result nested = VariableReferences.parse("${item_${i}}");
        assertTrue(nested.dynamic());
        assertEquals(Set.of("i"), nested.names());
    }

    @Test
    void escapedAndUnterminatedReferencesAreLiterals() {
        assertTrue(VariableReferences.parse("price \\${amount}").names().isEmpty());
        assertTrue(VariableReferences.parse("${unterminated").names().isEmpty());
    }

    @Test
    void scriptsReadAndWriteThroughVars() {
        VariableReferences.ScriptAccess access = VariableReferences.scriptAccess(
                "def t = vars.get(\"token\"); vars.put('out', t); log.info(\"${user}\")");
        assertEquals(Set.of("token", "user"), access.reads());
        assertEquals(Set.of("out"), access.writes());
        assertFalse(access.dynamicReads());
        assertTrue(VariableReferences.scriptAccess("vars.get(name)").dynamicReads());
        assertTrue(VariableReferences.scriptAccess("vars.put(prefix + i, v)").dynamicWrites());
    }

    @Test
    void generatedFamilies() {
        assertTrue(VariableReferences.isInFamily("id", "id"));
        assertTrue(VariableReferences.isInFamily("id", "id_3"));
        assertTrue(VariableReferences.isInFamily("id", "id_matchNr"));
        assertTrue(VariableReferences.isInFamily("id", "id_g1"));
        assertTrue(VariableReferences.isInFamily("id", "id_2_g1"));
        assertTrue(VariableReferences.isInFamily("id", "id_ALL"));
        assertFalse(VariableReferences.isInFamily("id", "id_other"));
        assertFalse(VariableReferences.isInFamily("id", "identity"));
    }
}
