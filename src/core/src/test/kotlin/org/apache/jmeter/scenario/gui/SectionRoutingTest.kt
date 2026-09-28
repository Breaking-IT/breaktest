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

import org.apache.jmeter.ai.knowledge.BreakTestAiKnowledge
import org.apache.jmeter.config.Arguments
import org.apache.jmeter.config.ConfigTestElement
import org.apache.jmeter.control.TestFragmentController
import org.apache.jmeter.gui.action.UndoCommand
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.reporters.ResultCollector
import org.apache.jmeter.scenario.ListenersSection
import org.apache.jmeter.scenario.NonTestElementsSection
import org.apache.jmeter.scenario.Profile
import org.apache.jmeter.scenario.ProfilesSection
import org.apache.jmeter.scenario.Scenario
import org.apache.jmeter.scenario.ScenariosSection
import org.apache.jmeter.scenario.SharedProfile
import org.apache.jmeter.scenario.TestFragmentsSection
import org.apache.jmeter.scenario.ThreadGroupsSection
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jorphan.collections.HashTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class SectionRoutingTest : JMeterTestCase() {
    private val model = JMeterTreeModel()
    private val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode

    private fun parentAfterAdding(element: TestElement): KClass<*> =
        ((model.addComponent(element, plan).parent as JMeterTreeNode).userObject)::class

    @Test
    fun `elements added to the test plan go into their section`() {
        assertEquals(ThreadGroupsSection::class, parentAfterAdding(ThreadGroup()))
        assertEquals(ListenersSection::class, parentAfterAdding(ResultCollector()))
        assertEquals(SharedProfile::class, parentAfterAdding(Arguments()))
        assertEquals(SharedProfile::class, parentAfterAdding(ConfigTestElement()))
        assertEquals(ProfilesSection::class, parentAfterAdding(Profile("acceptance")))
        assertEquals(ScenariosSection::class, parentAfterAdding(Scenario()))
        assertEquals(TestFragmentsSection::class, parentAfterAdding(TestFragmentController()))
    }

    @Test
    fun `restoring a saved tree does not add a second set of sections`() {
        // Snapshot the way undo history does
        val nodes = model.getCurrentSubTree(model.root as JMeterTreeNode)
        val saved = UndoCommand.convertAndCloneSubTree(nodes.getTree(nodes.array[0]).clone() as HashTree)
        model.clearTestPlan()
        model.addSubTree(saved, model.root as JMeterTreeNode)
        assertEquals(1, model.getNodesOfType(ScenariosSection::class.java).size) {
            "Undo and redo restore the saved sections; only a new test plan gets default sections"
        }
    }

    @Test
    fun `non-test elements section is created by the first non-test element`() {
        fun sections() = (0 until plan.childCount).map { (plan.getChildAt(it) as JMeterTreeNode).userObject::class }
        assertEquals(
            listOf(ListenersSection::class, ScenariosSection::class, ProfilesSection::class, TestFragmentsSection::class, ThreadGroupsSection::class),
            sections()
        )
        assertEquals(NonTestElementsSection::class, parentAfterAdding(BreakTestAiKnowledge()))
        assertEquals(NonTestElementsSection::class, parentAfterAdding(BreakTestAiKnowledge()))
        assertEquals(NonTestElementsSection::class, sections().last())
        assertEquals(6, sections().size) { "The section is created only once" }
    }
}
