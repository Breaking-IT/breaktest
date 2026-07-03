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

package org.apache.jmeter.ai

import org.apache.oro.text.regex.MalformedPatternException
import org.apache.oro.text.regex.Perl5Compiler
import org.apache.oro.text.regex.Perl5Matcher

/**
 * Regex helpers for patterns that must run inside JMeter's Regex Extractor,
 * which uses the ORO/Perl5 engine — NOT java.util.regex. In particular ORO has
 * no `\Q...\E` quoting, so `Regex.escape()`/`Pattern.quote()` output silently
 * never matches and fail-on-missing extractors then mark green samples failed.
 */
public object AgentRegexSupport {
    private val ORO_SPECIAL = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')

    /** Escapes a literal for ORO by backslash-escaping each metacharacter. */
    public fun oroEscape(literal: String): String =
        buildString(literal.length + 8) {
            for (character in literal) {
                if (character in ORO_SPECIAL) {
                    append('\\')
                }
                append(character)
            }
        }

    /**
     * Returns null when the regex is usable by JMeter's ORO engine, or a short
     * human-readable problem description otherwise.
     */
    public fun oroProblem(regex: String): String? {
        if (regex.contains("\\Q") || regex.contains("\\E")) {
            return "uses \\Q...\\E quoting, which JMeter's ORO/Perl5 regex engine does not support; " +
                "escape literal metacharacters with single backslashes instead"
        }
        return try {
            Perl5Compiler().compile(regex)
            null
        } catch (e: MalformedPatternException) {
            "does not compile in JMeter's ORO/Perl5 regex engine: ${e.message}"
        }
    }

    /** Matches with the same engine the Regex Extractor will use at runtime. */
    public fun oroMatches(regex: String, text: String): Boolean =
        try {
            Perl5Matcher().contains(text, Perl5Compiler().compile(regex))
        } catch (e: MalformedPatternException) {
            false
        }
}
