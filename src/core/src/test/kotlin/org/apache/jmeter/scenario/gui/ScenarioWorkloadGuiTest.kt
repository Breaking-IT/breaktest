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

package org.apache.jmeter.scenario.gui

import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.scenario.ScenarioWorkload
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JTable
import javax.swing.SwingUtilities

class ScenarioWorkloadGuiTest : JMeterTestCase() {
    private fun tables(container: Container): List<JTable> =
        container.components.flatMap { component ->
            when (component) {
                is JTable -> listOf(component)
                is Container -> tables(component)
                else -> listOf()
            }
        }

    /** Lays out the whole component tree, which Swing only does by itself once it is shown in a window. */
    private fun layOut(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layOut)
    }

    @Test
    fun `schedule table keeps room for its rows below the scenario summary`() {
        SwingUtilities.invokeAndWait {
            val gui = ScenarioWorkloadGui()
            val workload = ScenarioWorkload().apply {
                copyWorkloadFrom(
                    ThreadGroup().apply {
                        setThreadGroupModel(ThreadGroup.MODEL_OPEN)
                        openModelSchedule = "rate(1/min) random_arrivals(10 min)"
                    }
                )
            }
            gui.configure(workload)
            gui.setSize(gui.preferredSize)
            layOut(gui)

            val schedule = tables(gui).single { table ->
                generateSequence<Container>(table) { it.parent }.takeWhile { it !== gui }.all { it.isVisible }
            }
            val viewport = schedule.parent
            assertTrue(viewport.height >= schedule.rowHeight * 2) {
                "Schedule table viewport is ${viewport.height}px high, rows are ${schedule.rowHeight}px"
            }
        }
    }
}
