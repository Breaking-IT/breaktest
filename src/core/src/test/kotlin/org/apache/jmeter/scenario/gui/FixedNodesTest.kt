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

import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.scenario.NonTestElementsSection
import org.apache.jmeter.scenario.Profile
import org.apache.jmeter.scenario.ProfilesSection
import org.apache.jmeter.scenario.ScenarioPlanMigration
import org.apache.jmeter.scenario.SharedProfile
import org.apache.jmeter.scenario.ThreadGroupsSection
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixedNodesTest : JMeterTestCase() {
    private val model = JMeterTreeModel()

    private fun node(type: Class<*>) = model.getNodesOfType(type).single()

    private fun add(element: Any, parent: JMeterTreeNode): JMeterTreeNode =
        JMeterTreeNode(element as org.apache.jmeter.testelement.TestElement, model)
            .also { model.insertNodeInto(it, parent, parent.childCount) }

    @Test
    fun `sections and the shared profile cannot be copied or removed`() {
        for (fixed in listOf(node(ThreadGroupsSection::class.java), node(ProfilesSection::class.java), node(SharedProfile::class.java))) {
            assertFalse(FixedNodes.isCopyable(fixed)) { "${fixed.name} must not be copied or duplicated" }
            assertFalse(FixedNodes.isRemovable(fixed)) { "${fixed.name} must not be removed" }
        }
    }

    @Test
    fun `other elements and the on-demand non-test section behave as before`() {
        val threadGroup = add(ThreadGroup(), node(ThreadGroupsSection::class.java))
        val profile = add(Profile("acceptance"), node(ProfilesSection::class.java))
        val nonTest = add(ScenarioPlanMigration.newSection(NonTestElementsSection::class.java), (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode)
        assertTrue(FixedNodes.isCopyable(threadGroup) && FixedNodes.isRemovable(threadGroup))
        assertTrue(FixedNodes.isCopyable(profile) && FixedNodes.isRemovable(profile))
        assertTrue(FixedNodes.isRemovable(nonTest))
        assertFalse(FixedNodes.isCopyable(nonTest))
    }

    @Test
    fun `a second shared profile left by an earlier copy can be removed`() {
        val original = node(SharedProfile::class.java)
        val copy = add(ScenarioPlanMigration.newSharedProfile(), node(ProfilesSection::class.java))
        assertTrue(FixedNodes.isRemovable(copy)) { "Plans with a duplicated Shared profile must be repairable" }
        model.removeNodeFromParent(copy)
        assertFalse(FixedNodes.isRemovable(original)) { "The last Shared profile stays" }
    }
}
