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

import java.util.Set;

import org.junit.jupiter.api.Test;

class ScriptVariableNamesTest {
    @Test
    void findsSingleAndDoubleQuotedNamesAcrossWhitespaceAndComments() {
        String script = """
                vars.put('credential', token)
                vars /* explanation */ . put (
                    "userid", response.id.toString())
                vars.put('credential', 'another value')
                """;
        assertEquals(Set.of("credential", "userid"), ScriptVariableNames.find(script, "groovy"));
    }

    @Test
    void ignoresCommentsAndQuotedExamplesIncludingMultilineStringsAndRegexes() {
        String script = """
                // vars.put('lineComment', value)
                /* vars.put("blockComment", value) */
                # vars.put('hashComment', value)
                def example = "vars.put('quotedExample', value)"
                def multiline = '''vars.put('multilineExample', value)
                    vars.put('moreExample', value)'''
                def pattern = /vars.put('regexExample', value)/
                def dollarSlashy = $/vars.put('slashyExample', value)/$
                vars.put('real', 'private value')
                """;
        assertEquals(Set.of("real"), ScriptVariableNames.find(script, "groovy"));
    }

    @Test
    void ignoresComputedNamesOtherReceiversAndOtherMethods() {
        String script = """
                vars.put(prefix, value)
                vars.put('prefix' + id, value)
                vars.put("user_${id}", value)
                vars.put("user_$id", value)
                other.vars.put('anotherReceiver', value)
                myvars.put('anotherVariable', value)
                vars.get('readOnly')
                vars.putObject('objectOnly', value)
                vars.put('literal'.toUpperCase(), value)
                """;
        assertTrue(ScriptVariableNames.find(script, "groovy").isEmpty());
    }

    @Test
    void respectsLiteralDollarSignsAndLanguageInterpolationRules() {
        assertEquals(Set.of("literal_$id"), ScriptVariableNames.find("vars.put('literal_$id', value)", "groovy"));
        assertEquals(Set.of("literal_$id"), ScriptVariableNames.find("vars.put(\"literal_$id\", value)", "javascript"));
        assertTrue(ScriptVariableNames.find("vars.put(\"dynamic_$id\", value)", "").isEmpty());
        assertTrue(ScriptVariableNames.find("vars.put(\"dynamic_#{id}\", value)", "ruby").isEmpty());
        assertTrue(ScriptVariableNames.find("vars.put(f'user_{id}', value)", "python").isEmpty());
    }

    @Test
    void toleratesIncompleteScriptsAndIgnoresEmptyNames() {
        String script = "vars.put('valid', value); vars.put('', value); vars.put('unfinished";
        assertEquals(Set.of("valid"), ScriptVariableNames.find(script, "groovy"));
        assertTrue(ScriptVariableNames.find("/* vars.put('comment', value)", "groovy").isEmpty());
    }

    @Test
    void handlesEscapedQuotesWithoutTreatingStringContentsAsCalls() {
        assertEquals(Set.of("user'name"), ScriptVariableNames.find("vars.put('user\\'name', value)", "groovy"));
        assertTrue(ScriptVariableNames.find("def example = 'text \\' vars.put(\"fake\", value)'", "groovy").isEmpty());
    }
}
