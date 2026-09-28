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

package org.apache.jmeter.control

import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.scenario.ScenarioPlanMigration
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.property.CollectionProperty
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jorphan.collections.HashTree
import org.apache.jorphan.collections.ListedHashTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleControllerMigrationTest : JMeterTestCase() {
    private fun legacyPlanWithDuplicateFragments(): HashTree {
        val tree = ListedHashTree()
        val plan = tree.add(TestPlan().apply { name = "Plan" })
        plan.add(TestFragmentController().apply { name = "Workers" })
            .add(GenericController().apply { name = "First flow" })
        plan.add(TestFragmentController().apply { name = "Workers" })
            .add(GenericController().apply { name = "Second flow" })
        plan.add(ThreadGroup().apply { name = "Caller" })
            .add(
                ModuleController().apply {
                    name = "Run second flow"
                    setProperty(CollectionProperty("ModuleController.node_path", listOf("root", "Plan", "Workers", "Second flow")))
                }
            )
        return tree
    }

    /** Loads the tree the way a test run does, and returns the name of the Module Controller's target. */
    private fun resolvedTarget(tree: HashTree): String? {
        val model = JMeterTreeModel(TestPlan())
        model.addSubTreeForExecution(tree, model.root as JMeterTreeNode)
        val module = model.getNodesOfType(ModuleController::class.java).single().testElement as ModuleController
        module.resolveReplacementSubTree(model.root as JMeterTreeNode)
        return module.selectedNode?.name
    }

    @Test
    fun `module controllers keep their target when test fragments with the same name are renamed`() {
        assertEquals("Second flow", resolvedTarget(legacyPlanWithDuplicateFragments())) { "The old plan" }
        assertEquals("Second flow", resolvedTarget(ScenarioPlanMigration.migrate(legacyPlanWithDuplicateFragments())))
    }
}
