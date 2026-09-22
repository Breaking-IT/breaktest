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

package org.apache.jmeter.ai.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.jmeter.ai.knowledge.gui.BreakTestAiKnowledgeGui
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.save.SaveService
import org.apache.jmeter.testelement.TestElement
import org.apache.jorphan.collections.HashTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BreakTestAiKnowledgeTest : JMeterTestCase() {
    private val mapper = ObjectMapper()

    @Test
    fun `legacy GUI preserves notes but is not available in add menus`() {
        val legacy = BreakTestAiKnowledge().apply { knowledgeJson = "legacy-unparsed-notes" }
        val gui = BreakTestAiKnowledgeGui()
        gui.configure(legacy)
        gui.modifyTestElement(legacy)
        assertEquals("legacy-unparsed-notes", legacy.knowledgeJson)
        assertTrue(gui.menuCategories.isEmpty())
        // Paste and drag/drop stream categories without a null check.
        assertEquals(0L, gui.menuCategories.stream().count())
        val field = BreakTestAiKnowledgeGui::class.java.getDeclaredField("knowledgeJson").apply { isAccessible = true }
        val text = field.get(gui) as org.apache.jmeter.gui.util.JSyntaxTextArea
        assertEquals("legacy-unparsed-notes", text.text)
        assertFalse(text.isEditable)
        text.selectAll()
        assertEquals("legacy-unparsed-notes", text.selectedText)
        gui.clearGui()
        assertEquals("", text.text)
    }

    @Test
    fun `default knowledge is valid structured json`() {
        val knowledge = BreakTestAiKnowledge()
        val parsed = mapper.readTree(knowledge.knowledgeJson)

        assertEquals(BreakTestAiKnowledge.DEFAULT_NAME, knowledge.name)
        assertEquals(1, parsed.path("schemaVersion").asInt())
        assertEquals(true, parsed.path("correlationPatterns").isArray)
        assertEquals(true, parsed.path("assertionPatterns").isMissingNode)
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `legacy XML and archive notes load but disappear on save`(zipped: Boolean, qualified: Boolean, @TempDir directory: Path) {
        val tag = if (qualified) BreakTestAiKnowledge::class.java.name else "BreakTestAiKnowledge"
        val xml = """
            <jmeterTestPlan version="1.2" properties="5.0">
              <hashTree>
                <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Plan" enabled="true">
                  <stringProp name="TestPlan.comments">keep this comment</stringProp>
                </TestPlan>
                <hashTree>
                  <GenericController guiclass="LogicControllerGui" testclass="GenericController" testname="Before" enabled="true"/>
                  <hashTree/>
                  <$tag guiclass="BreakTestAiKnowledgeGui" testclass="$tag" testname="Legacy" enabled="true">
                    <stringProp name="BreakTestAiKnowledge.knowledgeJson">legacy-not-json</stringProp>
                  </$tag>
                  <hashTree>
                    <GenericController guiclass="LogicControllerGui" testclass="GenericController" testname="Child" enabled="true"/>
                    <hashTree/>
                  </hashTree>
                  <GenericController guiclass="LogicControllerGui" testclass="GenericController" testname="After" enabled="false"/>
                  <hashTree>
                    <$tag guiclass="BreakTestAiKnowledgeGui" testclass="$tag" testname="Nested legacy" enabled="true"/>
                    <hashTree/>
                  </hashTree>
                </hashTree>
              </hashTree>
            </jmeterTestPlan>
        """.trimIndent()
        val input = directory.resolve("legacy.jmx")
        if (zipped) {
            ZipOutputStream(Files.newOutputStream(input)).use {
                it.putNextEntry(ZipEntry("testplan.jmx"))
                it.write(xml.toByteArray(Charsets.UTF_8))
                it.closeEntry()
            }
        } else Files.writeString(input, xml)
        val loaded = SaveService.loadTree(input.toFile())
        val originalElements = elements(loaded)
        val notes = originalElements.filterIsInstance<BreakTestAiKnowledge>()
        assertEquals(2, notes.size)
        assertEquals("legacy-not-json", notes.first().knowledgeJson)
        val expected = originalElements.filterNot { it is BreakTestAiKnowledge }.map { it.name to it.isEnabled }

        // Exercise both the stream path used by the bridge and atomic GUI file saves.
        val copy = directory.resolve("copy.jmx")
        Files.newOutputStream(copy).use { SaveService.saveTree(loaded, it) }
        SaveService.saveTreeToFile(loaded, input)
        for (saved in listOf(copy, input)) {
            val reloaded = SaveService.loadTree(saved.toFile())
            val actual = elements(reloaded)
            assertFalse(actual.any { it is BreakTestAiKnowledge })
            assertEquals(expected, actual.map { it.name to it.isEnabled })
            val plan = reloaded.array.first() as TestElement
            assertEquals("keep this comment", plan.getPropertyAsString("TestPlan.comments"))
            assertEquals(listOf("Before", "Child", "After"), reloaded.getTree(plan).list().map { (it as TestElement).name })
            ZipFile(saved.toFile()).use { archive ->
                val persistedXml = archive.getInputStream(archive.getEntry("testplan.jmx")).bufferedReader().use { it.readText() }
                assertFalse(persistedXml.contains("BreakTestAiKnowledge"))
                assertFalse(persistedXml.contains("legacy-not-json"))
            }
        }
        // Saving must not mutate the caller's tree, including before a save failure.
        assertEquals(originalElements, elements(loaded))
        assertTrue(elements(loaded).containsAll(notes))
    }

    private fun elements(tree: HashTree): List<TestElement> = tree.list().flatMap { node ->
        listOf(node as TestElement) + elements(tree.getTree(node))
    }
}
