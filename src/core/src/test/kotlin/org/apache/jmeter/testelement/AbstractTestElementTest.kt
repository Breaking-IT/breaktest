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

package org.apache.jmeter.testelement

import io.mockk.mockk
import io.mockk.spyk
import org.apache.jmeter.testelement.property.CollectionProperty
import org.apache.jmeter.testelement.property.JMeterProperty
import org.apache.jmeter.testelement.property.StringProperty
import org.apache.jmeter.testelement.property.TestElementProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AbstractTestElementTest {
    @Test
    fun `clone can remove properties`() {
        class ElementWithDefaultComment : AbstractTestElement() {
            init {
                set(TestElementSchema.comments, "initialized in constructor")
            }
        }

        val source = ElementWithDefaultComment().apply {
            removeProperty(TestElementSchema.comments.name)
        }

        val cloned = source.clone() as ElementWithDefaultComment

        cloned.propertyIterator().asSequence().toList()

        assertEquals(
            source.propertyIterator().asSequence().toList().joinToString("\n") { it.name + " = " + it.stringValue },
            cloned.propertyIterator().asSequence().toList().joinToString("\n") { it.name + " = " + it.stringValue },
        ) {
            "The properties after cloning the element should match the original properites. " +
                "Note that comments is added in constructor, however, we remove <<comments>> property before cloning"
        }

        assertEquals(source, cloned) {
            "The cloned element should be be equal the original element. " +
                "Note that comments is added in constructor, however, we remove <<comments>> property before cloning"
        }
    }

    @Test
    fun `set outer properties as temporary when using a TestElementProperty`() {
        val sut = spyk<AbstractTestElement>()
        val outerElement = mockk<TestElement>(relaxed = true)
        val innerElement = mockk<TestElement>(relaxed = true)
        val outerProp = TestElementProperty("outerProp", outerElement)
        val innerProp = TestElementProperty("innerProp", innerElement)
        outerProp.addProperty(innerProp)

        sut.setTemporary(outerProp)

        assertTrue(sut.isTemporary(outerProp)) {
            "isTemporary($outerProp)"
        }
        assertFalse(sut.isTemporary(innerProp)) {
            "isTemporary($innerProp)"
        }
    }

    @Test
    fun `set all properties as temporary when using a MultiProperty`() {
        val sut = spyk<AbstractTestElement>()
        val outerProp = CollectionProperty()
        val innerProp = CollectionProperty()

        outerProp.addProperty(innerProp)
        sut.setTemporary(outerProp)

        assertTrue(sut.isTemporary(outerProp)) {
            "isTemporary($outerProp)"
        }
        assertTrue(sut.isTemporary(innerProp)) {
            "isTemporary($innerProp)"
        }
    }

    @Test
    fun `simple temporary properties keep value semantics`() {
        val sut = spyk<AbstractTestElement>()
        val prop = StringProperty("prop", "value")
        val equalProp = StringProperty("prop", "value")

        sut.setTemporary(prop)

        assertTrue(sut.isTemporary(equalProp)) {
            "isTemporary($equalProp)"
        }
    }

    @Test
    fun `shallow multi temporary properties use identity semantics`() {
        val sut = spyk<AbstractTestElement>()
        val prop = CollectionProperty("prop", listOf(StringProperty("child", "value")))
        val equalProp = CollectionProperty("prop", listOf(StringProperty("child", "value")))

        sut.setTemporary(prop)

        assertFalse(sut.isTemporary(equalProp)) {
            "isTemporary($equalProp)"
        }
    }

    @Test
    fun `test element temporary properties use identity semantics`() {
        class Element : AbstractTestElement()
        val sut = spyk<AbstractTestElement>()
        val element = Element().apply {
            setProperty(StringProperty("child", "value"))
        }
        val equalElement = Element().apply {
            setProperty(StringProperty("child", "value"))
        }
        val prop = TestElementProperty("testvar", element)
        val equalProp = TestElementProperty("testvar", equalElement)

        sut.setTemporary(prop)

        assertFalse(sut.isTemporary(equalProp)) {
            "isTemporary($equalProp)"
        }
    }

    @Test
    fun `http sampler nested temporary properties use identity semantics`() {
        class Element : AbstractTestElement()

        for (propertyName in listOf("HTTPsampler.Arguments", "HTTPSampler.cookie_manager")) {
            val sut = spyk<AbstractTestElement>()
            val element = Element().apply {
                setProperty(StringProperty("child", "value"))
            }
            val equalElement = Element().apply {
                setProperty(StringProperty("child", "value"))
            }
            val prop = TestElementProperty(propertyName, element)
            val equalProp = TestElementProperty(propertyName, equalElement)

            sut.setTemporary(prop)

            assertFalse(sut.isTemporary(equalProp)) {
                "isTemporary($equalProp)"
            }
        }
    }

    @Test
    fun `merged http sampler nested property is marked temporary by identity`() {
        class Element : AbstractTestElement()
        class MutableElement : AbstractTestElement() {
            fun add(property: TestElementProperty) = addProperty(property)
        }
        val sut = MutableElement()
        val existingElement = Element().apply {
            setProperty(StringProperty("existing", "value"))
        }
        val existingProp = TestElementProperty("HTTPSampler.header_manager", existingElement)
        val incomingElement = Element().apply {
            setProperty(StringProperty("incoming", "value"))
        }
        val incomingProp = TestElementProperty("HTTPSampler.header_manager", incomingElement)
        val equalElement = Element().apply {
            setProperty(StringProperty("existing", "value"))
            setProperty(StringProperty("incoming", "value"))
        }

        sut.setProperty(existingProp)
        sut.isRunningVersion = true
        sut.add(incomingProp)

        assertTrue(sut.isTemporary(existingProp)) {
            "isTemporary($existingProp)"
        }
        assertFalse(sut.isTemporary(TestElementProperty("HTTPSampler.header_manager", equalElement))) {
            "temporary HTTP nested properties should use identity semantics"
        }
    }

    @Test
    fun `merged collection property drops merged items but keeps own items on recover`() {
        class MutableElement : AbstractTestElement() {
            fun add(property: JMeterProperty) = addProperty(property)
        }
        val sut = MutableElement()
        val ownItem = StringProperty("own", "value")
        val ownProp = CollectionProperty("items", listOf(ownItem))
        sut.setProperty(ownProp)
        sut.isRunningVersion = true

        val mergedItem = StringProperty("merged", "value")
        val incomingProp = CollectionProperty("items", listOf(mergedItem))
        sut.add(incomingProp)

        assertTrue(sut.isTemporary(mergedItem)) {
            "merged enclosed property should be temporary: isTemporary($mergedItem)"
        }
        assertFalse(sut.isTemporary(ownProp)) {
            "the merged-into collection is the element's own property and must not be temporary, " +
                "otherwise recoverRunningVersion() would remove it: isTemporary($ownProp)"
        }
        assertEquals(2, ownProp.size()) { "collection should contain own + merged items before recover" }

        sut.recoverRunningVersion()

        val recovered = sut.getProperty("items") as CollectionProperty
        assertEquals(
            listOf("own"),
            recovered.iterator().asSequence().map { it.name }.toList(),
        ) {
            "recoverRunningVersion() should remove the merged items and keep the element's own items"
        }
    }

    @Test
    fun `repeated collection merges do not accumulate items across iterations`() {
        class MutableElement : AbstractTestElement() {
            fun add(property: JMeterProperty) = addProperty(property)
        }
        val sut = MutableElement()
        sut.setProperty(CollectionProperty("items", listOf(StringProperty("own", "value"))))
        sut.isRunningVersion = true

        // Simulates TestCompiler.configureSampler + done(): a config element merges the
        // same collection into the sampler on every iteration
        val configItem = StringProperty("merged", "value")
        val configProp = CollectionProperty("items", listOf(configItem))
        repeat(3) {
            sut.add(configProp)
            sut.recoverRunningVersion()
        }

        val recovered = sut.getProperty("items") as CollectionProperty
        assertEquals(
            listOf("own"),
            recovered.iterator().asSequence().map { it.name }.toList(),
        ) {
            "per-iteration merge/recover cycles must be stable and must not leak merged items"
        }
    }

    @Test
    fun `merging two config collections does not pollute the first config's own property`() {
        class MutableElement : AbstractTestElement() {
            fun add(property: JMeterProperty) = addProperty(property)
        }
        // Simulates TestCompiler.configureSampler with two config elements (e.g. two
        // Arguments/User Defined Variables) contributing the same collection property to a
        // sampler that has none of its own. The first config's container must not capture
        // the second config's items, and repeated iterations must not accumulate anything.
        val sut = MutableElement()
        sut.isRunningVersion = true

        val firstConfigProp = CollectionProperty("items", listOf(StringProperty("first", "value")))
        val secondConfigProp = CollectionProperty("items", listOf(StringProperty("second", "value")))
        repeat(3) {
            sut.add(firstConfigProp)
            sut.add(secondConfigProp)

            val merged = sut.getProperty("items") as CollectionProperty
            assertEquals(
                listOf("first", "second"),
                merged.iterator().asSequence().map { it.name }.toList(),
            ) {
                "during the sample the element should see both configs' items"
            }

            sut.recoverRunningVersion()
        }

        assertEquals(
            listOf("first"),
            firstConfigProp.iterator().asSequence().map { it.name }.toList(),
        ) {
            "the first config element's own collection must not accumulate the second config's items"
        }
        assertEquals(
            listOf("second"),
            secondConfigProp.iterator().asSequence().map { it.name }.toList(),
        ) {
            "the second config element's own collection must stay untouched"
        }
        assertTrue(sut.getProperty("items") is org.apache.jmeter.testelement.property.NullProperty) {
            "the merged collection was pure config data and should be gone after recover"
        }
    }

    @Test
    fun `http header manager temporary property does not change simple property semantics`() {
        class Element : AbstractTestElement()
        val sut = spyk<AbstractTestElement>()
        val prop = StringProperty("prop", "value")
        val equalProp = StringProperty("prop", "value")
        val headerProp = TestElementProperty(
            "HTTPSampler.header_manager",
            Element().apply {
                setProperty(StringProperty("child", "value"))
            },
        )

        sut.setTemporary(prop)
        sut.setTemporary(headerProp)

        assertTrue(sut.isTemporary(equalProp)) {
            "isTemporary($equalProp)"
        }
    }
}
