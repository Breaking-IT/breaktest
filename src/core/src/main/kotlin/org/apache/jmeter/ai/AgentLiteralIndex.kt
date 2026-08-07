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

/**
 * Finds where each of many literals first occurs in a text, reading the text once.
 *
 * The repair planner asks "which earlier response contains this value?" for every
 * candidate of every recorded request. Answering that with one `indexOf` per
 * (literal, response) pair is quadratic in the recording size and dominates
 * planning time on real HARs. This is a plain Aho-Corasick automaton: build it
 * once from every literal of interest, then each response costs a single pass
 * regardless of how many literals are being looked for.
 *
 * Results are exact. [firstOccurrences] returns the same index that
 * `text.indexOf(literal)` would, for every literal that occurs.
 */
internal class AgentLiteralIndex private constructor(
    private val patterns: List<String>,
    /**
     * Trie edges for the whole automaton in one map keyed by [edge], rather than a
     * map per node: a planning run indexes thousands of mostly prefix-free
     * literals, so per-node maps would cost far more than the edges themselves.
     */
    private val edges: HashMap<Long, Int>,
    private val fail: IntArray,
    private val terminal: IntArray,
    private val outputLink: IntArray,
) {

    /**
     * Maps each indexed literal that occurs in [text] to the index of its first
     * occurrence. Literals that do not occur are absent from the result.
     */
    fun firstOccurrences(text: String): Map<String, Int> {
        if (patterns.isEmpty() || text.isEmpty()) {
            return emptyMap()
        }
        val firstIndex = IntArray(patterns.size) { -1 }
        var found = 0
        var node = ROOT
        for (position in text.indices) {
            val character = text[position]
            // Follow failure links until a node can consume this character.
            while (node != ROOT && !edges.containsKey(edge(node, character))) {
                node = fail[node]
            }
            node = edges[edge(node, character)] ?: ROOT
            // Every pattern ending here is this node or one of its suffix links.
            var output = if (terminal[node] >= 0) node else outputLink[node]
            while (output >= 0) {
                val pattern = terminal[output]
                if (firstIndex[pattern] < 0) {
                    firstIndex[pattern] = position - patterns[pattern].length + 1
                    found++
                    if (found == patterns.size) {
                        return collect(firstIndex)
                    }
                }
                output = outputLink[output]
            }
        }
        return collect(firstIndex)
    }

    private fun collect(firstIndex: IntArray): Map<String, Int> {
        val occurrences = HashMap<String, Int>()
        for (pattern in patterns.indices) {
            if (firstIndex[pattern] >= 0) {
                occurrences[patterns[pattern]] = firstIndex[pattern]
            }
        }
        return occurrences
    }

    companion object {
        private const val ROOT = 0

        private fun edge(node: Int, character: Char): Long =
            (node.toLong() shl 32) or character.code.toLong()

        /** Builds an automaton for [literals]; blank literals and duplicates are dropped. */
        fun build(literals: Collection<String>): AgentLiteralIndex {
            val patterns = literals.filter { it.isNotEmpty() }.distinct()
            val edges = HashMap<Long, Int>()
            val childrenByNode = mutableListOf(mutableListOf<Char>())
            val terminalByNode = mutableListOf(-1)
            for ((pattern, literal) in patterns.withIndex()) {
                var node = ROOT
                for (character in literal) {
                    node = edges.getOrPut(edge(node, character)) {
                        childrenByNode[node] += character
                        childrenByNode += mutableListOf<Char>()
                        terminalByNode += -1
                        childrenByNode.size - 1
                    }
                }
                // A duplicate-free pattern list means one terminal pattern per node.
                terminalByNode[node] = pattern
            }

            val nodeCount = childrenByNode.size
            val fail = IntArray(nodeCount)
            val outputLink = IntArray(nodeCount) { -1 }
            val queue = ArrayDeque<Int>()
            for (character in childrenByNode[ROOT]) {
                val child = edges.getValue(edge(ROOT, character))
                fail[child] = ROOT
                queue += child
            }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                // The longest proper suffix of this node that is also a prefix of
                // some pattern; outputLink then chains to the nearest terminal one.
                outputLink[node] = if (terminalByNode[fail[node]] >= 0) fail[node] else outputLink[fail[node]]
                for (character in childrenByNode[node]) {
                    val child = edges.getValue(edge(node, character))
                    var candidate = fail[node]
                    while (candidate != ROOT && !edges.containsKey(edge(candidate, character))) {
                        candidate = fail[candidate]
                    }
                    fail[child] = edges[edge(candidate, character)]?.takeIf { it != child } ?: ROOT
                    queue += child
                }
            }
            return AgentLiteralIndex(patterns, edges, fail, terminalByNode.toIntArray(), outputLink)
        }
    }
}
