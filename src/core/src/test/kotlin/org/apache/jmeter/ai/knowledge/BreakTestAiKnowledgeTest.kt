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
import org.apache.jmeter.control.gui.TestPlanGui
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.save.SaveService
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.TestPlan
import org.apache.jorphan.collections.HashTree
import org.apache.jorphan.collections.ListedHashTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

class BreakTestAiKnowledgeTest : JMeterTestCase() {
    private val mapper = ObjectMapper()

    @Test
    fun `default knowledge is valid structured json`() {
        val knowledge = BreakTestAiKnowledge()
        val parsed = mapper.readTree(knowledge.knowledgeJson)

        assertEquals(BreakTestAiKnowledge.DEFAULT_NAME, knowledge.name)
        assertEquals(1, parsed.path("schemaVersion").asInt())
        assertEquals(true, parsed.path("correlationPatterns").isArray)
        assertEquals(true, parsed.path("assertionPatterns").isArray)
    }

    @Test
    fun `knowledge element survives jmx save load round trip`() {
        val originalJson = """
            {
              "schemaVersion": 1,
              "correlationPatterns": [
                {
                  "name": "csrf header",
                  "provedBy": "ThreadGroup_01 / Login"
                }
              ]
            }
        """.trimIndent()
        val knowledge = BreakTestAiKnowledge().apply {
            knowledgeJson = originalJson
            setProperty(TestElement.GUI_CLASS, BreakTestAiKnowledgeGui::class.java.name)
            setProperty(TestElement.TEST_CLASS, BreakTestAiKnowledge::class.java.name)
        }
        val plan = TestPlan("Plan").apply {
            setProperty(TestElement.GUI_CLASS, TestPlanGui::class.java.name)
            setProperty(TestElement.TEST_CLASS, TestPlan::class.java.name)
        }
        val tree = ListedHashTree()
        tree.add(plan).add(knowledge)
        val tempFile = Files.createTempFile("breaktest-ai-knowledge", ".jmx")

        tempFile.toFile().outputStream().use { SaveService.saveTree(tree, it) }
        val loaded = SaveService.loadTree(tempFile.toFile())
        val loadedKnowledge = findKnowledge(loaded)

        assertNotNull(loadedKnowledge)
        assertEquals(
            "csrf header",
            mapper.readTree(loadedKnowledge!!.knowledgeJson)
                .path("correlationPatterns")
                .path(0)
                .path("name")
                .asText(),
        )
    }

    private fun findKnowledge(tree: HashTree): BreakTestAiKnowledge? {
        for (node in tree.list()) {
            if (node is BreakTestAiKnowledge) {
                return node
            }
            val found = findKnowledge(tree.getTree(node))
            if (found != null) {
                return found
            }
        }
        return null
    }
}
