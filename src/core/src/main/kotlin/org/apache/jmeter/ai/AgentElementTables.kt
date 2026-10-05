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

import com.fasterxml.jackson.databind.JsonNode
import org.apache.jmeter.config.Argument
import org.apache.jmeter.config.Arguments
import org.apache.jmeter.testelement.TestElement

/** Adapters for native table models. Protocol types are resolved only when installed, avoiding a core dependency. */
internal object AgentElementTables {
    private const val HTTP_SAMPLER = "org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase"
    private const val WS_CONNECT = "org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler"
    private const val HEADER_MANAGER = "org.apache.jmeter.protocol.http.control.HeaderManager"
    private const val HEADER = "org.apache.jmeter.protocol.http.control.Header"
    private const val HTTP_ARGUMENT = "org.apache.jmeter.protocol.http.util.HTTPArgument"

    private fun isType(element: TestElement, name: String): Boolean =
        generateSequence(element.javaClass as Class<*>?) { it.superclass }.any { it.name == name }

    private fun rowTypes(element: TestElement): Map<String, String> = when {
        isType(element, WS_CONNECT) || isType(element, HEADER_MANAGER) -> mapOf("headers" to HEADER)
        isType(element, HTTP_SAMPLER) -> mapOf("arguments" to HTTP_ARGUMENT)
        element is Arguments -> mapOf("arguments" to Argument::class.java.name)
        else -> emptyMap()
    }

    private fun newRow(type: String): TestElement = Class.forName(type).getDeclaredConstructor().newInstance() as TestElement

    fun describe(element: TestElement): List<Map<String, Any?>> = rowTypes(element).map { (name, type) ->
        mapOf(
            "name" to name, "type" to "array", "row" to AgentElementCatalog.describe(newRow(type)),
            "semantics" to "Replaces all rows in this table; use [] to clear. Rows contain scalar properties from the row schema."
        )
    }

    fun configure(element: TestElement, tables: JsonNode) {
        require(tables.isObject) { "tables must be an object" }
        val types = rowTypes(element)
        for ((name, values) in tables.properties()) {
            val type = types[name] ?: error("Unknown table '$name'; inspect the element schema first")
            require(values.isArray) { "$name must be an array of row objects" }
            val rows = values.map { AgentElementCatalog.configured(newRow(type), it) }
            when {
                isType(element, WS_CONNECT) -> element.javaClass.getMethod("setHeaders", List::class.java).invoke(element, rows)
                isType(element, HEADER_MANAGER) -> {
                    val headers = element.javaClass.getMethod("getHeaders").invoke(element) as org.apache.jmeter.testelement.property.CollectionProperty
                    headers.clear()
                    rows.forEach { element.javaClass.getMethod("add", Class.forName(HEADER)).invoke(element, it) }
                }
                else -> {
                    val arguments = Arguments()
                    rows.forEach { arguments.addArgument(it as Argument) }
                    if (element is Arguments) element.setArguments(rows.map { it as Argument })
                    else element.javaClass.getMethod("setArguments", Arguments::class.java).invoke(element, arguments)
                }
            }
        }
    }
}
