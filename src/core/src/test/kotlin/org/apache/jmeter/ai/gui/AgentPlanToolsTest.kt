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
        request = add(
            ConfigTestElement().apply {
                name = "Open cart"
                setProperty("endpoint", "/cart/42")
                // Saved plans name the editor and class of every element.
                setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.config.gui.SimpleConfigGui")
                setProperty(TestElement.TEST_CLASS, ConfigTestElement::class.java.name)
            },
            group,
        )
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
    fun `export and import round trip replaces a node from edited text`() {
        val exported = call("export_subtree", """{"targetNodePath":"Plan / Shoppers / Open cart"}""").path("xml").asText()
        assertTrue(exported.contains("/cart/42"), exported)
        val edited = json.createObjectNode()
            .put("xml", exported.replace("/cart/42", "/cart/99").replace("Open cart", "Open other cart"))
            .put("replaceNodeId", call("find_open_plan_nodes", """{"name":"Open cart"}""").path("matches")[0].path("nodeId").asText())
        val imported = json.valueToTree<JsonNode>(BreakTestAgentGuiService.handleTool("import_subtree", edited))
        assertTrue(imported.path("replaced").asBoolean())
        assertEquals(2, group.childCount)
        val replacement = group.getChildAt(1) as JMeterTreeNode
        assertEquals("Open other cart", replacement.testElement.name)
        assertEquals("/cart/99", replacement.testElement.getPropertyAsString("endpoint"))
    }

    @Test
    fun `raw property paths reach nested elements and raw read shows them`() {
        (group.testElement as ThreadGroup).setSamplerController(org.apache.jmeter.control.LoopController().apply { loops = 1 })
        call(
            "configure_element_open_plan",
            """{"targetNodePath":"Plan / Shoppers","rawProperties":{"ThreadGroup.main_controller/LoopController.loops":"7"}}""",
        )
        val raw = call("get_element_open_plan", """{"targetNodePath":"Plan / Shoppers","raw":true}""").path("rawProperties")
        assertEquals("7", raw.path("ThreadGroup.main_controller").path("LoopController.loops").asText())
        call(
            "configure_element_open_plan",
            """{"targetNodePath":"Plan / Shoppers / Variables","rawProperties":{"Arguments.arguments/0/Argument.value":"other.example"}}""",
        )
        assertEquals("other.example", (variables.testElement as Arguments).getArgument(0).value)
    }

    @Test
    fun `batch returns brief edit results unless full detail is asked`() {
        val steps = """[{"tool":"set_enabled_open_plan","arguments":{"targetNodePath":"Plan / Shoppers / Open cart","enabled":false}},
            {"tool":"get_plan_outline"}]"""
        val brief = call("batch_open_plan", """{"steps":$steps}""").path("results")
        assertEquals(setOf("nodeId", "nodePath"), brief[0].path("result").fieldNames().asSequence().toSet())
        assertTrue(brief[1].path("result").has("outline"), "read steps keep their result")
        val full = call("batch_open_plan", """{"detail":"full","steps":$steps}""").path("results")
        assertTrue(full[0].path("result").has("changed"))
    }

    @Test
    fun `permission setting blocks edits and runs but not reads`() {
        org.apache.jmeter.util.JMeterUtils.setProperty("breaktest.agent.permissions", "read")
        try {
            BreakTestAgentGuiService.handleToolInProcess("get_plan_outline", json.createObjectNode())
            val edit = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                BreakTestAgentGuiService.handleToolInProcess(
                    "set_enabled_open_plan", json.readTree("""{"targetNodePath":"Plan / Shoppers","enabled":false}"""),
                )
            }
            assertTrue(edit.message!!.contains("needs 'edit'"), edit.message)
            val batch = json.valueToTree<JsonNode>(
                BreakTestAgentGuiService.handleToolInProcess(
                    "batch_open_plan",
                    json.readTree("""{"steps":[{"tool":"set_enabled_open_plan","arguments":{"targetNodePath":"Plan / Shoppers","enabled":false}}]}"""),
                ),
            )
            assertEquals(1, batch.path("failed").asInt())
            assertTrue(group.isEnabled)
            org.apache.jmeter.util.JMeterUtils.setProperty("breaktest.agent.permissions", "edit")
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                BreakTestAgentGuiService.handleToolInProcess("start_test", json.createObjectNode())
            }
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties().remove("breaktest.agent.permissions")
        }
    }

    @Test
    fun `scenario can be added enabled and variables are listed with their node`() {
        val added = call("add_scenario", """{"name":"Peak","enable":true}""")
        assertTrue(added.path("enabled").asBoolean())
        val listed = call("list_variables").path("variables").asText()
        assertTrue(Regex("""host <- n\d+ Arguments Variables""").containsMatchIn(listed), listed)
    }

    @Test
    fun `run statistics report counts errors and percentiles per label`() {
        org.apache.jmeter.ai.AgentRunStats.reset()
        repeat(100) { index ->
            val result = org.apache.jmeter.samplers.SampleResult(1000L + index, (index + 1).toLong())
            result.sampleLabel = "home"
            result.isSuccessful = index % 10 != 0
            result.responseCode = if (result.isSuccessful) "200" else "500"
            org.apache.jmeter.ai.AgentRunStats.record(result, false)
        }
        val report = json.valueToTree<JsonNode>(org.apache.jmeter.ai.AgentRunStats.snapshot(10, 2))
        assertEquals(100, report.path("samples").asInt())
        assertEquals(10, report.path("errors").asInt())
        val cells = report.path("rows").asText().split(" | ")
        assertEquals(listOf("R", "home", "100", "10"), cells.take(4))
        assertTrue(cells[5].toLong() in 85..100, "p90 of 1..100 ms, was ${cells[5]}")
        assertEquals("100", cells[7])
        assertEquals(2, report.path("errorSamples").size())
        assertTrue(report.path("errorKinds")[0].asText().startsWith("10 x home | 500"))
    }

    @Test
    fun `full request and response of run samples can be listed and read in windows`() {
        org.apache.jmeter.ai.AgentRunStats.reset()
        val body = "x".repeat(500) + """{"token":"abc123"}""" + "y".repeat(30_000)
        for ((label, success) in listOf("login" to true, "login" to true, "search" to false)) {
            val result = org.apache.jmeter.samplers.SampleResult(1000L, 20L)
            result.sampleLabel = label
            result.isSuccessful = success
            result.responseCode = if (success) "200" else "503"
            result.samplerData = "POST https://shop.example/$label\n\nPOST data:\nuser=jo"
            result.requestHeaders = "Content-Type: application/x-www-form-urlencoded\n"
            result.responseHeaders = "HTTP/1.1 ${result.responseCode}\nSet-Cookie: sid=9\n"
            result.setResponseData(body, "UTF-8")
            result.dataType = org.apache.jmeter.samplers.SampleResult.TEXT
            org.apache.jmeter.ai.AgentRunStats.record(result, false)
        }
        val rows = call("list_samples", """{"source":"run"}""").path("rows").asText().lines()
        assertEquals(2, rows.size, "one passing login and the failed search; the second login is not copied")
        assertTrue(rows[1].startsWith("1 | search | FAILED | 503"), rows[1])

        val login = call("get_sample", """{"source":"run","label":"login","find":"\"token\""}""")
        assertTrue(login.path("request").asText().startsWith("POST https://shop.example/login"))
        assertTrue(login.path("requestHeaders").asText().contains("Content-Type"))
        assertTrue(login.path("responseHeaders").asText().contains("Set-Cookie: sid=9"))
        assertTrue(login.path("responseBody").asText().contains("""{"token":"abc123"}"""))
        assertEquals(301, login.path("window").path("offset").asInt(), "200 characters before the match at 501")
        assertEquals(20_301, login.path("window").path("nextOffset").asInt())

        val headersOnly = call("get_sample", """{"source":"run","index":1,"parts":["responseHeaders"]}""")
        assertFalse(headersOnly.has("responseBody"))
        assertFalse(headersOnly.path("success").asBoolean())
    }

    @Test
    fun `agent variables go to a variables element under the shared profile`() {
        val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        (plan.testElement as TestPlan).addParameter("password", "old-on-plan")
        val profile = add(org.apache.jmeter.scenario.SharedProfile().apply { name = "Shared Profile" }, plan)

        val first = call("set_user_defined_variable_open_plan", """{"name":"password","value":"s3cret"}""")
        call("set_user_defined_variable_open_plan", """{"name":"username","value":"jo"}""")
        assertEquals("Plan / Shared Profile / User Defined Variables", first.path("nodePath").asText())
        assertEquals(1, profile.childCount, "both variables share one element")
        val stored = (profile.getChildAt(0) as JMeterTreeNode).testElement as Arguments
        assertEquals(mapOf("password" to "s3cret", "username" to "jo"), stored.argumentsAsMap)
        assertTrue((plan.testElement as TestPlan).userDefinedVariables.isEmpty(), "the plan-level copy moved along")

        request.testElement.setProperty("endpoint", "/login?p=s3cret")
        call(
            "replace_literal_open_plan",
            """{"literal":"s3cret","replacement":"${'$'}{password}","allowWholePlan":true,"excludeUserDefinedVariables":true}""",
        )
        assertEquals("/login?p=\${password}", request.testElement.getPropertyAsString("endpoint"))
        assertEquals("s3cret", stored.argumentsAsMap["password"], "the variable keeps its original value")
        val leftovers = call("search_open_plan_values", """{"literal":"s3cret","excludeUserDefinedVariables":true}""")
        assertEquals(0, leftovers.path("matchCount").asInt())
    }

    @Test
    fun `agent variables stay on the test plan when there is no shared profile`() {
        val result = call("set_user_defined_variable_open_plan", """{"name":"tenant","value":"acme"}""")
        assertEquals("Plan", result.path("nodePath").asText())
        val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        assertEquals("acme", (plan.testElement as TestPlan).userDefinedVariables["tenant"])
    }

    @Test
    fun `keeping a copy adds one disabled copy after the thread group and replaces an older one`() {
        val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        val originalId = (group.testElement as ThreadGroup).orCreateThreadGroupId

        assertEquals("Shoppers (before AI repair)", BreakTestAgentGuiService.keepThreadGroupCopy("Plan / Shoppers"))
        request.testElement.setProperty("endpoint", "/cart/changed-by-repair")
        BreakTestAgentGuiService.keepThreadGroupCopy("Plan / Shoppers")

        assertEquals(2, plan.childCount, "the second copy replaced the first")
        assertEquals(group, plan.getChildAt(0), "the original stays first and is the one that is edited")
        assertTrue(group.isEnabled)
        assertEquals(originalId, (group.testElement as ThreadGroup).threadGroupId)
        val copy = plan.getChildAt(1) as JMeterTreeNode
        assertEquals("Shoppers (before AI repair)", copy.testElement.name)
        assertFalse(copy.isEnabled)
        assertTrue((copy.testElement as ThreadGroup).threadGroupId != originalId, "scenarios keep pointing at the original")
        assertEquals(2, copy.childCount)
        assertEquals(
            "/cart/changed-by-repair", (copy.getChildAt(1) as JMeterTreeNode).testElement.getPropertyAsString("endpoint"),
            "the newer copy holds the newer state",
        )
        assertTrue(AiAutoScriptingLogWindow.changes().none { it["summary"].orEmpty().contains("before AI repair") })
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
