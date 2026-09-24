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

@file:JvmName("OpenModelScheduleMigration")

package org.apache.jmeter.threads.openmodel

import org.apache.jmeter.threads.openmodel.ThreadScheduleStep.ArrivalType
import org.apache.jmeter.threads.openmodel.ThreadScheduleStep.ArrivalsStep
import org.apache.jmeter.threads.openmodel.ThreadScheduleStep.RateStep
import java.math.BigDecimal

private val COMMENTS = Regex("/\\*.*?\\*/|//[^\\r\\n]*", RegexOption.DOT_MATCHES_ALL)

/**
 * Converts literal legacy schedules to self-contained rate windows. Unresolved expressions,
 * invalid schedules, and workloads that cannot be represented exactly are left untouched.
 * Comments are retained in order alongside the window containing their original expression.
 */
public fun migrateOpenModelSchedule(source: String): String {
    return try {
        migrateLiteralSchedule(source)
    } catch (_: ParserException) {
        source
    } catch (_: TokenizerException) {
        source
    } catch (_: NumberFormatException) {
        source
    }
}

private data class RateWindow(val from: Double, val to: Double, val arrivals: ArrivalsStep)

private fun windows(steps: List<ThreadScheduleStep>): List<RateWindow> {
    var rate = 0.0
    return buildList {
        steps.forEachIndexed { index, step ->
            when (step) {
                is RateStep -> rate = step.rate
                is ArrivalsStep -> add(RateWindow(rate, (steps.getOrNull(index + 1) as? RateStep)?.rate ?: rate, step))
            }
        }
    }
}

private fun number(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private fun migrateLiteralSchedule(source: String): String {
    val parser = ScheduleParser(source)
    // Preserve already explicit modern windows byte-for-byte, including formatting and comments.
    val expressions = parser.tokens.windowed(2).filter { it[1].token == Tokenizer.OpenParenthesisToken }
    if (expressions.isEmpty()) {
        return source
    }
    val modernOnly = expressions.all {
        it[0].token.image.equals("constantThreadsPerMinDuring", ignoreCase = true) ||
            it[0].token.image.equals("rampThreadsPerMinDuring", ignoreCase = true)
    }
    val flags = parser.tokens.count {
        it.token == Tokenizer.IdentifierToken("true") || it.token == Tokenizer.IdentifierToken("false")
    }
    if (modernOnly && flags == expressions.size) {
        return source
    }
    val ends = mutableListOf<Int>()
    val schedule = parser.parse { end, steps ->
        steps.filterIsInstance<ArrivalsStep>().forEach { ends += end }
    }
    val windows = windows(schedule.steps)
    if (windows.isEmpty()) {
        return source
    }
    val comments = COMMENTS.findAll(source).iterator()
    var comment = if (comments.hasNext()) comments.next() else null
    val lines = mutableListOf<String>()
    windows.forEachIndexed { index, window ->
        while (comment != null && comment!!.range.first < ends[index]) {
            lines += comment!!.value
            comment = if (comments.hasNext()) comments.next() else null
        }
        val from = number(window.from * 60)
        val duration = number(window.arrivals.duration)
        val random = window.arrivals.type == ArrivalType.RANDOM
        lines += if (window.from == window.to) {
            "constantThreadsPerMinDuring($from, $duration, $random)"
        } else {
            "rampThreadsPerMinDuring($from, ${number(window.to * 60)}, $duration, $random)"
        }
    }
    while (comment != null) {
        lines += comment!!.value
        comment = if (comments.hasNext()) comments.next() else null
    }
    val migrated = lines.joinToString("\n")
    // Unit conversion must not change rounding, event counts, or seeded event times.
    return if (windows(ThreadSchedule(migrated).steps) == windows) migrated else source
}
