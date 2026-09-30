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
import org.apache.jmeter.scenario.Profile
import org.apache.jmeter.scenario.Scenario
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CopyConflictsTest : JMeterTestCase() {
    private val model = JMeterTreeModel()
    private val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode

    /** New plans start with one active scenario */
    private val activeScenario = model.getNodesOfType(Scenario::class.java).single().testElement

    @Test
    fun `a copied active scenario is not active`() {
        val copy = activeScenario.clone() as Scenario
        model.addComponent(copy, plan)
        assertFalse(copy.isEnabled)
        assertTrue(activeScenario.isEnabled)
    }

    @Test
    fun `a copied default profile is not the default`() {
        val original = Profile("acceptance").apply { isDefault = true }
        model.addComponent(original, plan)
        val copy = original.clone() as Profile
        model.addComponent(copy, plan)
        assertTrue(original.isDefault)
        assertFalse(copy.isDefault)
    }

    @Test
    fun `a copied thread group gets its own id`() {
        val original = ThreadGroup().apply { threadGroupId = "browse-id" }
        model.addComponent(original, plan)
        val copy = original.clone() as ThreadGroup
        model.addComponent(copy, plan)
        assertEquals("browse-id", original.threadGroupId)
        assertNotEquals("browse-id", copy.threadGroupId)
    }

    @Test
    fun `a moved thread group keeps its id`() {
        val original = ThreadGroup().apply { threadGroupId = "browse-id" }
        val node = model.addComponent(original, plan)
        model.removeNodeFromParent(node)
        model.addComponent(original, plan)
        assertEquals("browse-id", original.threadGroupId) { "Cut and paste must keep scenario references working" }
    }
}
