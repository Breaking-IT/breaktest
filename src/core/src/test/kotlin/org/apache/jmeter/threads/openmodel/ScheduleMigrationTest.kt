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

package org.apache.jmeter.threads.openmodel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Random

class ScheduleMigrationTest {
    @Test
    fun migratesMixedArrivalsAndPausesWithoutChangingTheWorkload() {
        assertMigration(
            "rate(1/sec) even_arrival(2 sec) rate(2/sec) random_arrival(3 sec) pause(4 sec) random_arrival(5 sec) rate(3/sec)",
            """
            rampThreadsPerMinDuring(60, 120, 2)
            constantThreadsPerMinDuring(120, 3, true)
            constantThreadsPerMinDuring(0, 4)
            rampThreadsPerMinDuring(120, 180, 5, true)
            """.trimIndent()
        )
    }

    @Test
    fun preservesConsecutiveRateAndArrivalSemantics() {
        assertMigration(
            "rate(1/sec) rate(2/sec) even_arrival(2 sec) rate(3/sec) rate(4/sec) random_arrival(2 sec) rate(5/sec)",
            "rampThreadsPerMinDuring(120, 180, 2)\nrampThreadsPerMinDuring(240, 300, 2, true)"
        )
        assertMigration(
            "rate(1/sec) even_arrival(2 sec) random_arrival(3 sec) rate(2/sec)",
            "constantThreadsPerMinDuring(60, 2)\nrampThreadsPerMinDuring(60, 120, 3, true)"
        )
    }

    @Test
    fun convertsUnitsAndLegacyDefaultFlags() {
        assertMigration(
            "RATE(3600 per hour) RANDOM_ARRIVALS(1 min 500 ms) pause(0)",
            "constantThreadsPerMinDuring(60, 60.5, true)\nconstantThreadsPerMinDuring(0, 0)"
        )
        assertMigration(
            "constantThreadsPerMinDuring(120, 30) rampThreadsPerMinDuring(120, 60, 10)",
            "constantThreadsPerMinDuring(120, 30) rampThreadsPerMinDuring(120, 60, 10)"
        )
        assertMigration(
            "constantThreadsPerMinDuring(120, 3, true) even_arrival(2 sec) rate(3/sec)",
            "constantThreadsPerMinDuring(120, 3, true)\nrampThreadsPerMinDuring(120, 180, 2)"
        )
    }

    @Test
    fun retainsCommentsInOrder() {
        assertMigration(
            "// title\nrate(1/sec) /* steady */ random_arrival(1 min) // cool\npause(2 sec) /* end */",
            """
            // title
            /* steady */
            constantThreadsPerMinDuring(60, 60, true)
            // cool
            constantThreadsPerMinDuring(0, 2)
            /* end */
            """.trimIndent()
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "5",
            "rate(2/sec)",
            "rate(2/sec) invalid(1 min)",
            "rate(${'$'}{rate}/sec) random_arrival(10 sec)",
            "${'$'}{__groovy(props.get('schedule'))}",
            "constantThreadsPerMinDuring(120, 30, maybe)",
            "constantThreadsPerMinDuring(20, 15)",
            "rampThreadsPerMinDuring(10, 20, 30)",
            "rampThreadsPerMinDuring(10, 20, 30, false)",
            " /* keep formatting */ constantThreadsPerMinDuring(120, 30, true)  ",
        ]
    )
    fun leavesUnsupportedOrAlreadyModernSchedulesIntact(source: String) {
        assertEquals(source, migrateOpenModelSchedule(source))
    }

    private fun assertMigration(source: String, expected: String) {
        val migrated = migrateOpenModelSchedule(source)
        assertEquals(expected, migrated)
        assertEquals(migrated, migrateOpenModelSchedule(migrated), "Migration must be idempotent")
        assertEquals(ThreadSchedule(source).totalDuration, ThreadSchedule(migrated).totalDuration)
        for (seed in listOf(0L, 42L)) {
            assertEquals(
                ThreadScheduleProcessGenerator(Random(seed), ThreadSchedule(source)).asSequence().toList(),
                ThreadScheduleProcessGenerator(Random(seed), ThreadSchedule(migrated)).asSequence().toList(),
                "Migration must preserve every arrival with seed $seed"
            )
        }
    }
}
