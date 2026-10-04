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

import org.apache.jmeter.control.LoopController
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.scenario.Scenario
import org.apache.jmeter.scenario.ScenarioWorkload
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jmeter.util.JMeterUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SwingUtilities

class ScenarioGuiTest : JMeterTestCase() {
    private fun descendants(container: Container): Sequence<Component> =
        container.components.asSequence().flatMap { sequenceOf(it) + ((it as? Container)?.let(::descendants) ?: emptySequence()) }

    /** The name field comes first in every editor, in its title panel */
    private fun nameField(gui: Container) = descendants(gui).filterIsInstance<JTextField>().first()

    private fun workload(name: String) = ScenarioWorkload().apply {
        this.name = name
        copyWorkloadFrom(ThreadGroup().apply { setSamplerController(LoopController().apply { loops = 1 }) })
    }

    @Test
    fun `table follows edits of the selected thread group while typing`() {
        SwingUtilities.invokeAndWait {
            val gui = ScenarioGui()
            gui.configure(Scenario("Load").apply { setWorkloads(listOf(workload("Browse"))) })
            val table = descendants(gui).filterIsInstance<JTable>().first { it.name == "scenarioThreadGroups" }
            val editor = descendants(gui).filterIsInstance<ScenarioWorkloadGui>().single()
            val nameField = nameField(editor)

            nameField.text = "Browse at peak"

            assertEquals("Browse at peak", table.getValueAt(0, 1)) { "The row must update without leaving the field" }
        }
    }

    @Test
    fun `loop count edits stay synchronous across row changes and reopening the scenario`() {
        SwingUtilities.invokeAndWait {
            val scenario = Scenario("Load").apply {
                setWorkloads(listOf(workload("Browse"), workload("Checkout")))
            }
            val gui = ScenarioGui()
            gui.configure(scenario)
            val table = descendants(gui).filterIsInstance<JTable>().first { it.name == "scenarioThreadGroups" }
            val editor = descendants(gui).filterIsInstance<ScenarioWorkloadGui>().single()
            val loops = descendants(editor).filterIsInstance<JTextField>().single { it.name == "Loops Field" }
            var rowUpdates = 0
            table.model.addTableModelListener { rowUpdates++ }

            repeat(3) { visit ->
                loops.document.remove(0, loops.document.length)
                assertEquals("", loops.text)
                val updatesAfterDelete = rowUpdates
                loops.document.insertString(0, "${visit + 2}", null)
                assertTrue(rowUpdates > updatesAfterDelete, "Summary must update during the edit")
                loops.selectAll()
                loops.replaceSelection("${visit + 12}")
                assertEquals("${visit + 12}", loops.text)
                table.setRowSelectionInterval(1, 1)
                assertEquals("1", loops.text)
                loops.text = "27"
                gui.modifyTestElement(scenario)
                assertEquals("${visit + 12}", savedLoops(scenario.workloads[0]))
                assertEquals("27", savedLoops(scenario.workloads[1]))

                // Simulate leaving this tree node and returning to the cached editor.
                gui.clearGui()
                gui.configure(scenario)
                assertEquals("${visit + 12}", loops.text)
                table.setRowSelectionInterval(1, 1)
                loops.text = "1"
                table.setRowSelectionInterval(0, 0)
            }
            loops.text = "${'$'}{iterations}"
            gui.modifyTestElement(scenario)
            assertEquals("${'$'}{iterations}", savedLoops(scenario.workloads[0]))
            loops.text = "   "
            gui.modifyTestElement(scenario)
            assertEquals("   ", loops.text)
            assertEquals("1", savedLoops(scenario.workloads[0]))
        }
    }

    @Test
    fun `other workload fields retain edits under each duration policy`() {
        SwingUtilities.invokeAndWait {
            for (policy in listOf("loops", "duration", "unlimited")) {
                val workload = workload("Browse").apply {
                    copyWorkloadFrom(
                        ThreadGroup().apply {
                            setSamplerController(
                                LoopController().apply {
                                    loops = if (policy == "unlimited") LoopController.INFINITE_LOOP_COUNT else 3
                                }
                            )
                            scheduler = policy == "duration"
                            setDuration(30)
                        }
                    )
                }
                val scenario = Scenario("Load").apply { setWorkloads(listOf(workload)) }
                val gui = ScenarioGui()
                gui.configure(scenario)
                val editor = descendants(gui).filterIsInstance<ScenarioWorkloadGui>().single()
                val table = descendants(gui).filterIsInstance<JTable>().first { it.name == "scenarioThreadGroups" }
                val fields = listOf(
                    "number_of_threads" to "ThreadGroup.num_threads",
                    "ramp_up" to "ThreadGroup.ramp_time",
                    "duration" to "ThreadGroup.duration",
                    "delay" to "ThreadGroup.delay"
                )
                for ((label, property) in fields) {
                    val field = descendants(editor).filterIsInstance<JLabel>()
                        .single { it.text == JMeterUtils.getResString(label) }.labelFor as JTextField
                    field.text = ""
                    assertEquals("", field.text)
                    field.text = "42"
                    assertEquals("42", field.text)
                    if (label == "number_of_threads") {
                        assertEquals("42", table.getValueAt(0, 5), "Thread summary must update immediately")
                    }
                    gui.modifyTestElement(scenario)
                    assertEquals("42", scenario.workloads.single().getPropertyAsString(property))
                    assertEquals(policy == "duration", scenario.workloads.single().getPropertyAsBoolean("ThreadGroup.scheduler"))
                    assertEquals(if (policy == "loops") "3" else "-1", savedLoops(scenario.workloads.single()))
                }
            }
        }
    }

    private fun savedLoops(workload: ScenarioWorkload): String =
        (workload.getProperty("ThreadGroup.main_controller").objectValue as LoopController).loopString

    @Test
    fun `fixed nodes cannot be renamed`() {
        SwingUtilities.invokeAndWait {
            for (gui in listOf(ScenariosSectionGui(), ThreadGroupsSectionGui(), ProfilesSectionGui(), SharedProfileGui())) {
                val nameField = nameField(gui)
                assertFalse(nameField.isEditable) { "${gui.javaClass.simpleName} name must be fixed" }
            }
            val scenarioName = nameField(ScenarioGui())
            assertTrue(scenarioName.isEditable) { "Scenarios are named by the user" }
        }
    }
}
