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
import org.apache.jmeter.gui.JMeterGUIComponent
import org.apache.jmeter.gui.util.MenuFactory
import org.apache.jmeter.testbeans.TestBean
import org.apache.jmeter.testbeans.TestBeanHelper
import org.apache.jmeter.testbeans.gui.TestBeanGUI
import org.apache.jmeter.testelement.TestElement
import java.beans.Introspector
import java.beans.PropertyDescriptor

/** Discovers the installed GUI elements rather than maintaining a second sampler registry. Run on the EDT. */
public object AgentElementCatalog {
    private val excluded = setOf("runningVersion", "temporary", "threadContext", "threadName", "first", "done")

    public fun list(query: String): List<Map<String, Any?>> = MenuFactory.availableElements().flatMap { (category, items) ->
        items.map { mapOf("elementId" to it.className, "label" to it.label, "category" to category) }
    }.filter { it.values.any { value -> value.toString().contains(query, ignoreCase = true) } }
        .distinctBy { it["elementId"] }.sortedBy { it["label"].toString() }

    public fun create(elementId: String): TestElement {
        require(MenuFactory.availableElements().values.flatten().any { it.className == elementId }) {
            "Unknown elementId; choose an installed element from list_available_elements"
        }
        val type = Class.forName(elementId)
        val gui = if (TestBean::class.java.isAssignableFrom(type)) TestBeanGUI(type)
        else type.getDeclaredConstructor().newInstance() as JMeterGUIComponent
        gui.clearGui()
        return gui.createTestElement().also(TestBeanHelper::prepare)
    }

    private fun properties(element: TestElement): List<PropertyDescriptor> =
        Introspector.getBeanInfo(
            element.javaClass,
            if (element is TestBean) Introspector.USE_ALL_BEANINFO else Introspector.IGNORE_ALL_BEANINFO,
        ).propertyDescriptors.filter {
            !it.isHidden && it.name !in excluded && it.readMethod != null && it.writeMethod != null &&
                (
                    it.propertyType == String::class.java || it.propertyType == Boolean::class.javaPrimitiveType ||
                        it.propertyType == Int::class.javaPrimitiveType || it.propertyType == Long::class.javaPrimitiveType ||
                        it.propertyType == Double::class.javaPrimitiveType || it.propertyType == Float::class.javaPrimitiveType ||
                        it.propertyType.isEnum
                    )
        }

    public fun describe(source: TestElement): Map<String, Any?> {
        val element = (source.clone() as TestElement).also(TestBeanHelper::prepare)
        return mapOf(
            "className" to element.javaClass.name,
            "properties" to properties(element).map { property ->
                mapOf(
                    "name" to property.name, "label" to property.displayName,
                    "description" to property.shortDescription, "type" to property.propertyType.simpleName,
                    "value" to property.readMethod.invoke(element)?.let { if (it is Enum<*>) it.name else it },
                    "choices" to (
                        property.propertyType.enumConstants?.map { (it as Enum<*>).name }
                            ?: (property.getValue("tags") as? Array<*>)?.toList()
                        ),
                    "acceptsExpression" to acceptsString(element, property)
                )
            },
            "tables" to AgentElementTables.describe(element),
            "configurationNote" to "Use properties for listed scalar settings and tables for listed arrays of row objects. A supplied table replaces all rows; omitted tables are preserved. Other nested objects require dedicated tools or child elements; do not invent property keys.",
            "constraints" to constraints(element.javaClass.simpleName),
        )
    }

    private fun acceptsString(element: TestElement, property: PropertyDescriptor): Boolean =
        runCatching { element.javaClass.getMethod(property.writeMethod.name, String::class.java) }.isSuccess

    /** Return a fully configured clone; failed setters never partially mutate the live element. */
    public fun configured(element: TestElement, values: JsonNode, tables: JsonNode? = null): TestElement {
        require(values.isObject) { "properties must be an object" }
        val copy = (element.clone() as TestElement).also(TestBeanHelper::prepare)
        val descriptors = properties(copy).associateBy { it.name }
        for ((name, value) in values.properties()) {
            val property = descriptors[name] ?: error("Unknown or unsupported setting '$name'; inspect the element schema first")
            val type = property.propertyType
            val tags = property.getValue("tags") as? Array<*>
            if (tags != null && property.getValue("notOther") == true) {
                require(value.isTextual && value.asText() in tags) { "Invalid choice for $name" }
            }
            if (value.isTextual && acceptsString(copy, property)) {
                copy.javaClass.getMethod(property.writeMethod.name, String::class.java).invoke(copy, value.asText())
                persistBeanProperty(copy, property, value.asText())
                continue
            }
            val converted: Any = when {
                type == Boolean::class.javaPrimitiveType -> { require(value.isBoolean) { "$name must be boolean" }; value.asBoolean() }
                type == Int::class.javaPrimitiveType -> { require(value.isIntegralNumber && value.canConvertToInt()) { "$name must be an integer" }; value.asInt() }
                type == Long::class.javaPrimitiveType -> { require(value.isIntegralNumber && value.canConvertToLong()) { "$name must be an integer" }; value.asLong() }
                type == Double::class.javaPrimitiveType -> {
                    require(value.isNumber && value.asDouble().isFinite()) { "$name must be a finite number" }
                    value.asDouble()
                }
                type == Float::class.javaPrimitiveType -> {
                    require(value.isNumber && value.asDouble().toFloat().isFinite()) { "$name must be a finite number" }
                    value.asDouble().toFloat()
                }
                type.isEnum -> {
                    require(value.isTextual) { "$name must be an enum name" }
                    type.enumConstants.firstOrNull { (it as Enum<*>).name == value.asText() }
                        ?: error("Invalid choice for $name")
                }
                else -> error("$name must be text")
            }
            property.writeMethod.invoke(copy, converted)
            persistBeanProperty(copy, property, converted)
        }
        if (tables != null) AgentElementTables.configure(copy, tables)
        return copy
    }

    // Many TestBeans keep setter values only in fields; JMX and GUI edits use the property map.
    private fun persistBeanProperty(element: TestElement, descriptor: PropertyDescriptor, value: Any) {
        if (element is TestBean && !TestBeanHelper.isDescriptorIgnored(descriptor)) {
            val property = org.apache.jmeter.testelement.property.AbstractProperty.createProperty(value)
            property.name = descriptor.name
            element.setProperty(property)
        }
    }

    private fun constraints(type: String): List<String> = when (type) {
        "WebSocketConnectSampler" -> listOf(
            "Connect before sending; all steps for a connection use the same sessionName.",
            "Match handlers belong directly under Connect; Connect's response is the HTTP handshake, not application messages."
        )
        "WebSocketSendWaitSampler" -> listOf(
            "Only one active Send and Wait is supported per session. Match replies by request identity when unsolicited messages or heartbeats can arrive.",
            "Use Send only plus a Match handler for a concurrent heartbeat producer. Binary payload variables must expand to valid hex."
        )
        "ForkController" -> listOf(
            "The branch runs asynchronously in the same virtual user. Bound its loop and arrange termination before closing shared resources.",
            "Use an explicit shared stop/completion signal or finite loop; a timer belongs inside the heartbeat branch, not on the main flow."
        )
        "LoopController", "WhileController" -> listOf("Bound generated loops and add pacing. Ensure background loops can terminate on failure and at test end.")
        else -> emptyList()
    }
}
