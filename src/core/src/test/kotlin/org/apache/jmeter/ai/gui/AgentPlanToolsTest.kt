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

package org.apache.jmeter.ai.gui

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.jmeter.config.Arguments
import org.apache.jmeter.config.ConfigTestElement
import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.tree.JMeterTreeListener
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.property.TestElementProperty
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.swing.JTree

class AgentPlanToolsTest : JMeterTestCase() {
    private val json = ObjectMapper()
    private lateinit var model: JMeterTreeModel
    private lateinit var group: JMeterTreeNode
    private lateinit var variables: JMeterTreeNode
    private lateinit var request: JMeterTreeNode

    @BeforeEach
    fun openPlan(@TempDir directory: Path) {
        model = JMeterTreeModel(TestPlan("Plan"))
        GuiPackage.initInstance(JMeterTreeListener(model).apply { setJTree(JTree(model)) }, model)
        // Edits back the plan up next to its file; keep that inside the test folder.
        GuiPackage::class.java.getDeclaredField("testPlanFile").apply { isAccessible = true }
            .set(GuiPackage.getInstance(), directory.resolve("plan.jmx").toString())
        val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        group = add(ThreadGroup().apply { name = "Shoppers" }, plan)
        variables = add(Arguments().apply { name = "Variables"; addArgument("host", "shop.example") }, group)
        request = add(ConfigTestElement().apply { name = "Open cart"; setProperty("endpoint", "/cart/42") }, group)
    }

    @AfterEach
    fun resetGuiPackage() {
        GuiPackage::class.java.getDeclaredField("guiPack").apply { isAccessible = true }.set(null, null)
    }

    private fun add(element: TestElement, parent: JMeterTreeNode): JMeterTreeNode =
        JMeterTreeNode(element, model).also { model.insertNodeInto(it, parent, parent.childCount) }

    private fun call(tool: String, arguments: String = "{}"): JsonNode =
        json.valueToTree(BreakTestAgentGuiService.handleTool(tool, json.readTree(arguments)))

    @Test
    fun `outline lists one indented line per node with ids and disabled marker`() {
        request.isEnabled = false
        val outline = call("get_plan_outline")
        val lines = outline.path("outline").asText().lines()
        assertEquals(4, outline.path("nodeCount").asInt())
        assertTrue(lines[0].matches(Regex("""n\d+ TestPlan Plan""")), lines[0])
        assertTrue(lines[1].matches(Regex("""  n\d+ ThreadGroup Shoppers""")), lines[1])
        assertTrue(lines[3].matches(Regex("""    n\d+ ConfigTestElement Open cart \[disabled]""")), lines[3])

        val shallow = call("get_plan_outline", """{"maxDepth":1}""").path("outline").asText().lines()
        assertEquals(2, shallow.size)
        assertTrue(shallow[1].endsWith("(+2 children)"), shallow[1])
    }

    @Test
    fun `element read returns table rows children and raw properties`() {
        val variablesRead = call("get_element_open_plan", """{"targetNodePath":"Plan / Shoppers / Variables"}""")
        assertEquals("shop.example", variablesRead.path("tables").path("arguments")[0].path("value").asText())
        assertEquals("host", variablesRead.path("tables").path("arguments")[0].path("name").asText())

        val groupRead = call("get_element_open_plan", """{"targetNodePath":"Plan / Shoppers","raw":true}""")
        assertEquals(2, groupRead.path("children").size())
        assertEquals("Shoppers", groupRead.path("rawProperties").path("TestElement.name").asText())
    }

    @Test
    fun `batch feeds an earlier result into later steps and stops at the first failure`() {
        val result = call(
            "batch_open_plan",
            """
            {"steps":[
              {"tool":"clone_node_open_plan","as":"copy","arguments":
                {"sourceNodePath":"Plan / Shoppers / Open cart","targetNodePath":"Plan / Shoppers","position":"last_child"}},
              {"tool":"set_enabled_open_plan","arguments":{"targetNodeId":"@copy","enabled":false}},
              {"tool":"get_element_open_plan","arguments":{"targetNodeId":"@copy.clonedNodeId"}},
              {"tool":"no_such_tool"},
              {"tool":"set_enabled_open_plan","arguments":{"targetNodeId":"@copy","enabled":true}}
            ]}
            """.trimIndent(),
        )
        assertEquals(3, result.path("succeeded").asInt(), result.toString())
        assertEquals(1, result.path("failed").asInt())
        assertTrue(result.path("stoppedEarly").asBoolean())
        assertEquals(3, group.childCount)
        val copy = group.getChildAt(2) as JMeterTreeNode
        assertEquals("Open cart", copy.testElement.name)
        assertFalse(copy.isEnabled, "the step after the failure must not have re-enabled the copy")
        assertFalse(result.path("results")[2].path("result").path("enabled").asBoolean())
    }

    @Test
    fun `text that only looks like a reference is passed through unchanged`() {
        val result = call(
            "batch_open_plan",
            """{"steps":[{"tool":"search_open_plan_values","arguments":{"query":"@unknown"}}]}""",
        )
        assertEquals(1, result.path("succeeded").asInt(), result.toString())
        assertEquals("@unknown", result.path("results")[0].path("result").path("literalVariantsSearched")[0].asText())
    }

    @Test
    fun `scenario workload is added updated listed and removed`() {
        val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        val scenario = org.apache.jmeter.scenario.Scenario("Peak")
        add(scenario, plan)

        call("set_scenario_workload", """{"threadGroupName":"Shoppers","threads":"5","rampUpSeconds":"10"}""")
        val updated = call("set_scenario_workload", """{"threadGroupName":"Shoppers","threads":25,"durationSeconds":"60"}""")
        val workload = scenario.workloads.single()
        assertEquals("25", workload.threads)
        assertEquals("10", workload.rampUp)
        assertEquals("60", workload.duration)
        assertEquals((group.testElement as ThreadGroup).threadGroupId, workload.threadGroupId)
        assertEquals("Shoppers", updated.path("workloads")[0].path("threadGroup").asText())

        val read = call("get_element_open_plan", """{"targetNodePath":"Plan / Peak"}""")
        assertEquals("25", read.path("workloads")[0].path("threads").asText())

        call("set_scenario_workload", """{"threadGroupName":"Shoppers","remove":true}""")
        assertTrue(scenario.workloads.isEmpty())
    }

    @Test
    fun `scoped search reports node names rooted at the plan`() {
        val match = call("search_open_plan_values", """{"query":"/cart/42","scopeNodePath":"Plan / Shoppers"}""")
            .path("matches").single()
        assertEquals("Plan / Shoppers / Open cart", match.path("nodePath").asText())
    }

    @Test
    fun `http argument listing reads rows of a nested arguments element`() {
        val sampler = ConfigTestElement().apply {
            name = "Search"
            setProperty(TestElementProperty("HTTPsampler.Arguments", Arguments().apply { addArgument("q", "lamp") }))
        }
        val method = BreakTestAgentGuiService::class.java.getDeclaredMethod("httpArguments", TestElement::class.java)
        method.isAccessible = true
        val rows = method.invoke(BreakTestAgentGuiService, sampler) as List<*>
        assertEquals(1, rows.size)
        assertTrue(rows.single().toString().contains("name=q") && rows.single().toString().contains("value=lamp"), rows.toString())
    }

    @Test
    fun `raw properties set scalar keys and refuse nested ones`() {
        call(
            "configure_element_open_plan",
            """{"targetNodePath":"Plan / Shoppers / Open cart","rawProperties":{"endpoint":"/cart/7"}}""",
        )
        assertEquals("/cart/7", request.testElement.getPropertyAsString("endpoint"))
        val failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            call(
                "configure_element_open_plan",
                """{"targetNodePath":"Plan / Shoppers / Variables","rawProperties":{"Arguments.arguments":"x"}}""",
            )
        }
        assertTrue(failure.message!!.contains("nested property"), failure.message)
    }
}
