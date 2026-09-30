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

package org.apache.jmeter.gui.util

import org.apache.jmeter.control.GenericController
import org.apache.jmeter.control.TestFragmentController
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.scenario.TestFragmentsSection
import org.apache.jmeter.threads.ThreadGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MenuFactoryTest : JMeterTestCase() {

    @Test
    fun `ensure each menu has something in it`() {
        assertEquals(12, MenuFactory.getMenuMap().size, "MenuFactory.getMenuMap().size")
        MenuFactory.getMenuMap().forEach { (group, items) ->
            assertNotEquals(0, items.size, "MenuFactory.getMenuMap()[$group].size")
        }
    }

    @Test
    fun `default add menu has expected item count`() {
        assertEquals(6 + 3, MenuFactory.createDefaultAddMenu().itemCount, "items + separators")
    }

    @Test
    fun `test fragments section holds controllers and one level of test fragment groups`() {
        val model = JMeterTreeModel()
        val fragments = model.getNodesOfType(TestFragmentsSection::class.java).single()
        assertTrue(MenuFactory.canAddTo(fragments, GenericController()))
        assertTrue(MenuFactory.canAddTo(fragments, TestFragmentController()))
        val group = model.addComponent(TestFragmentController(), fragments)
        assertSame(fragments, group.parent)
        assertTrue(MenuFactory.canAddTo(group, GenericController()))
        assertFalse(MenuFactory.canAddTo(group, TestFragmentController())) {
            "Test Fragment groups are one level deep"
        }
    }

    @Test
    fun `a thread group copied together with a test fragment cannot go into test fragments`() {
        val model = JMeterTreeModel()
        val fragments = model.getNodesOfType(TestFragmentsSection::class.java).single()
        val nodes = arrayOf(JMeterTreeNode(TestFragmentController(), null), JMeterTreeNode(ThreadGroup(), null))
        assertFalse(MenuFactory.canAddTo(fragments, nodes))
        assertTrue(MenuFactory.canAddTo(fragments, arrayOf(nodes[0], JMeterTreeNode(GenericController(), null))))
    }
}
