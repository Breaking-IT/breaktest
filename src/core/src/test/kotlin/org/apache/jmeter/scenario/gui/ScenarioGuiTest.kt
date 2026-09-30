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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
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
