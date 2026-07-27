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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.UUID

class AgentLiteralIndexTest {

    /** What the planner used to do: one indexOf per literal. */
    private fun bruteForce(text: String, literals: Collection<String>): Map<String, Int> =
        literals.filter { it.isNotEmpty() }
            .distinct()
            .mapNotNull { literal -> text.indexOf(literal).takeIf { it >= 0 }?.let { literal to it } }
            .toMap()

    private fun assertMatchesBruteForce(text: String, literals: Collection<String>) {
        assertEquals(
            bruteForce(text, literals),
            AgentLiteralIndex.build(literals).firstOccurrences(text),
            "index disagrees with indexOf for text of length ${text.length}",
        )
    }

    @Test
    fun `reports the same first index as indexOf`() {
        val text = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=abc123\r\n\r\n{\"pageId\":\"abc123\",\"n\":7}"
        assertMatchesBruteForce(text, listOf("abc123", "pageId", "Set-Cookie", "missing", "7"))
    }

    @Test
    fun `handles literals that are suffixes prefixes and substrings of each other`() {
        // The classic Aho-Corasick trap: output links must report every pattern
        // ending at a position, not just the longest one.
        val text = "xabcabcabx"
        assertMatchesBruteForce(text, listOf("a", "ab", "abc", "bc", "c", "cab", "abcabc", "bx", "xabc"))
    }

    @Test
    fun `handles overlapping and repeated patterns`() {
        assertMatchesBruteForce("aaaaaa", listOf("a", "aa", "aaa", "aaaa", "aaaaaaa"))
        assertMatchesBruteForce("abababab", listOf("abab", "baba", "bab", "aba"))
    }

    @Test
    fun `handles empty and degenerate input`() {
        assertEquals(emptyMap<String, Int>(), AgentLiteralIndex.build(emptyList()).firstOccurrences("abc"))
        assertEquals(emptyMap<String, Int>(), AgentLiteralIndex.build(listOf("a")).firstOccurrences(""))
        assertEquals(emptyMap<String, Int>(), AgentLiteralIndex.build(listOf("")).firstOccurrences("abc"))
        assertEquals(mapOf("a" to 0), AgentLiteralIndex.build(listOf("a", "a", "")).firstOccurrences("abc"))
    }

    @Test
    fun `matches indexOf on randomised small alphabets`() {
        // A tiny alphabet maximises overlap between patterns, which is where a
        // wrong failure or output link shows up.
        val random = Random(20260727L)
        repeat(300) {
            val text = (1..random.nextInt(200)).map { "abc"[random.nextInt(3)] }.joinToString("")
            val literals = (1..random.nextInt(12) + 1).map { _ ->
                (1..random.nextInt(5) + 1).map { "abc"[random.nextInt(3)] }.joinToString("")
            }
            assertMatchesBruteForce(text, literals)
        }
    }

    @Test
    fun `matches indexOf on realistic har-shaped content`() {
        val random = Random(4242L)
        repeat(20) {
            val tokens = (1..20).map { UUID.randomUUID().toString() }
            val body = buildString {
                append("HTTP/1.1 200 OK\r\nSet-Cookie: sid=").append(tokens[0]).append("\r\n\r\n")
                repeat(200) {
                    append("{\"id\":\"").append(tokens[random.nextInt(tokens.size)])
                    append("\",\"v\":").append(random.nextInt()).append("},")
                }
            }
            // half the literals are present, half are not
            val literals = tokens + (1..20).map { UUID.randomUUID().toString() }
            assertMatchesBruteForce(body, literals)
        }
    }

    @Test
    fun `reads the text once regardless of literal count`() {
        val text = (1..50_000).joinToString("") { "abcdefgh" }
        val literals = (1..2_000).map { "zzz-absent-$it" }
        val started = System.nanoTime()
        assertEquals(emptyMap<String, Int>(), AgentLiteralIndex.build(literals).firstOccurrences(text))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        // 2000 separate indexOf passes over 400KB would be far slower than this.
        assertTrue(elapsedMillis < 2_000, "single-pass search took ${elapsedMillis}ms")
    }
}
