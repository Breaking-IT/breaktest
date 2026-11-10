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

import org.apache.jmeter.gui.util.MenuFactory
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.testelement.property.CollectionProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

/**
 * Measures how well an agent can set the installed elements. Scalar settings are always
 * reachable (typed properties or rawProperties) and nested elements by rawProperties path. Lists
 * have typed table support for some elements; the others need the untyped rawLists. This test
 * pins those others, so a new element with such a list shows up here instead of going unnoticed.
 */
class AgentElementCoverageTest : JMeterTestCase() {
    @Test
    fun `every installed element is creatable and row-less lists are the known ones`() {
        val failures = sortedMapOf<String, String>()
        val uncoveredLists = sortedSetOf<String>()
        var elements = 0
        SwingUtilities.invokeAndWait {
            for (item in MenuFactory.availableElements().values.flatten().distinctBy { it.className }) {
                val element = try {
                    AgentElementCatalog.create(item.className)
                } catch (e: Exception) {
                    failures[item.className] = e.cause?.toString() ?: e.toString()
                    continue
                }
                elements++
                val tables = AgentElementCatalog.describe(element)["tables"] as List<*>
                val lists = element.propertyIterator().asSequence().filterIsInstance<CollectionProperty>().map { it.name }.toList()
                if (lists.isNotEmpty() && tables.isEmpty()) {
                    uncoveredLists += "${element.javaClass.simpleName}: ${lists.joinToString()}"
                }
            }
        }
        println("Agent element coverage: $elements elements, ${uncoveredLists.size} with lists that have no table support")
        uncoveredLists.forEach { println("  $it") }
        failures.forEach { (name, reason) -> println("  cannot create $name: $reason") }
        assertTrue(elements > 100, "expected the full element catalog, found $elements")
        assertEquals(emptyMap<String, String>(), failures, "elements the agent cannot create")
        assertEquals(KNOWN_UNCOVERED_LISTS, uncoveredLists.toSet(), "lists that only rawLists can fill")
    }

    @Test
    fun `request shortcut splits a url and raw lists fill an authorization table`(@org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path) {
        val json = com.fasterxml.jackson.databind.ObjectMapper()
        SwingUtilities.invokeAndWait {
            val model = org.apache.jmeter.gui.tree.JMeterTreeModel()
            val listener = org.apache.jmeter.gui.tree.JMeterTreeListener(model).apply { setJTree(javax.swing.JTree(model)) }
            org.apache.jmeter.gui.GuiPackage.initInstance(listener, model)
            val gui = org.apache.jmeter.gui.GuiPackage.getInstance()
            org.apache.jmeter.gui.GuiPackage::class.java.getDeclaredField("testPlanFile").apply { isAccessible = true }
                .set(gui, directory.resolve("new.jmx").toString())
            fun call(tool: String, arguments: String): com.fasterxml.jackson.databind.JsonNode = json.valueToTree(
                org.apache.jmeter.ai.gui.BreakTestAgentGuiService.handleToolInProcess(tool, json.readTree(arguments)),
            )
            try {
                val group = call("add_element_open_plan", """{"elementId":"org.apache.jmeter.threads.gui.ThreadGroupGui","name":"G"}""").path("nodeId").asText()
                val request = call(
                    "add_http_request",
                    """{"url":"https://shop.example:8443/api/products?category=${'$'}{slug}&limit=32","targetNodeId":"$group","headers":{"Accept":"application/json"}}""",
                )
                val read = call("get_element_open_plan", """{"targetNodeId":"${request.path("nodeId").asText()}"}""")
                val properties = read.path("properties")
                assertEquals("shop.example", properties.path("domain").asText())
                assertEquals("https", properties.path("protocol").asText())
                assertEquals(8443, properties.path("port").asInt())
                assertEquals("/api/products", properties.path("path").asText())
                assertEquals("\${slug}", read.path("tables").path("arguments")[0].path("value").asText())
                assertEquals("application/json", read.path("tables").path("headers")[0].path("value").asText())

                // Adding a header to every request keeps the headers each request already has.
                call("add_http_request", """{"url":"https://shop.example/api/cart","targetNodeId":"$group"}""")
                val batch = call(
                    "batch_open_plan",
                    """{"steps":[{"tool":"configure_element_open_plan","forEach":{"type":"sampler","underNodeId":"$group"},
                       "arguments":{"tableMode":"upsert","tables":{"headers":[{"name":"X-Tenant","value":"{{node.name}}"}]}}}]}""",
                ).path("results")[0].path("result")
                assertEquals(2, batch.path("succeeded").asInt(), batch.toString())
                val first = call("get_element_open_plan", """{"targetNodeId":"${request.path("nodeId").asText()}"}""")
                    .path("tables").path("headers").map { it.path("name").asText() to it.path("value").asText() }
                assertEquals(listOf("Accept" to "application/json", "X-Tenant" to "/api/products"), first)

                // Query parameters stay in the URL even for methods without an explicit body.
                for (method in listOf("GET", "DELETE", "OPTIONS", "POST", "PUT", "PATCH", "HEAD")) {
                    call(
                        "add_http_request",
                        """{"url":"https://shop.example/action?token=abc%2Fdef&empty=&flag","method":"$method","name":"query-$method","targetNodeId":"$group"}""",
                    )
                    val sampler = model.getNodesOfType(org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy::class.java)
                        .single { it.testElement.name == "query-$method" }.testElement
                        as org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy
                    assertEquals("https://shop.example/action?token=abc%2Fdef&empty=&flag", sampler.url.toString(), method)
                    if (method in listOf("POST", "PUT", "PATCH", "HEAD")) {
                        assertEquals(0, sampler.arguments.argumentCount, method)
                    }
                }

                val auth = call(
                    "add_element_open_plan",
                    """{"elementId":"org.apache.jmeter.protocol.http.gui.AuthPanel","targetNodeId":"$group","rawLists":{"AuthManager.auth_list":[
                        {"testclass":"org.apache.jmeter.protocol.http.control.Authorization","Authorization.url":"https://shop.example/","Authorization.username":"u"}]}}""",
                )
                val manager = model.getNodesOfType(org.apache.jmeter.protocol.http.control.AuthManager::class.java).single().testElement
                    as org.apache.jmeter.protocol.http.control.AuthManager
                assertEquals("u", manager.getAuthObjectAt(0).user)
                assertTrue(auth.path("nodeId").asText().isNotEmpty())

                val functions = call("list_functions", """{"query":"Random"}""").path("functions").asText()
                assertTrue(functions.lines().any { it.startsWith("__Random(") }, functions)
            } finally {
                org.apache.jmeter.gui.GuiPackage::class.java.getDeclaredField("guiPack").apply { isAccessible = true }.set(null, null)
            }
        }
    }

    private companion object {
        val KNOWN_UNCOVERED_LISTS: Set<String> = setOf(
            "AuthManager: AuthManager.auth_list",
            "CompareAssertion: stringsToSkip",
            "CookieManager: CookieManager.cookies",
            "DNSCacheManager: DNSCacheManager.servers, DNSCacheManager.hosts",
            "IfController: IfController.conditions",
            "ProxyControl: ProxyControlGui.exclude_list, ProxyControlGui.include_list",
            "ResponseAssertion: Asserion.test_strings",
            "SmtpSampler: SMTPSampler.headerFields",
            "UserParameters: UserParameters.names, UserParameters.thread_values",
            "WhileController: WhileController.conditions",
        )

        const val HTTP_SAMPLER = "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"
    }
}
