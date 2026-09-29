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
import org.apache.jmeter.scenario.Scenario
import org.apache.jmeter.scenario.ScenarioPlanMigration
import org.apache.jmeter.scenario.TestPlanSection
import org.apache.jmeter.scenario.ThreadGroupsSection
import org.apache.jmeter.scenario.gui.UniqueNames
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.property.CollectionProperty
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jorphan.collections.HashTree
import org.apache.jorphan.collections.ListedHashTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    /** A sectioned plan saved with two test fragments of the same name, the Module Controller using the second */
    private fun sectionedPlanWithDuplicateFragments(): HashTree {
        val migrated = ScenarioPlanMigration.migrate(legacyPlanWithDuplicateFragments())
        // Undo the renaming migration does, as a hand-edited or generated file could have it
        val planTree = migrated.getTree(migrated.array[0])
        val fragments = planTree.list().first { it is org.apache.jmeter.scenario.TestFragmentsSection }
        planTree.getTree(fragments).list().forEach { (it as TestFragmentController).name = "Workers" }
        val module = modules(migrated).single()
        val path = (module.getProperty("ModuleController.node_path") as CollectionProperty).map { it.stringValue }
        module.setProperty(CollectionProperty("ModuleController.node_path", path.map { if (it == "Workers (2)") "Workers" else it }))
        return migrated
    }

    private fun modules(tree: HashTree): List<ModuleController> {
        val found = mutableListOf<ModuleController>()
        fun walk(tree: HashTree) {
            for (element in tree.list()) {
                if (element is ModuleController) {
                    found.add(element)
                }
                walk(tree.getTree(element))
            }
        }
        walk(tree)
        return found
    }

    @Test
    fun `module controllers keep their target when a loaded plan has duplicate names`() {
        val tree = sectionedPlanWithDuplicateFragments()
        ScenarioPlanMigration.makeNamesUnique(tree, HashMap(), HashSet())
        assertEquals("Second flow", resolvedTarget(tree))
    }

    @Test
    fun `merging a plan adds to the sections of the open plan and keeps its references`() {
        val model = JMeterTreeModel()
        val plan = (model.root as JMeterTreeNode).getChildAt(0) as JMeterTreeNode
        val threadGroups = model.getNodesOfType(ThreadGroupsSection::class.java).single()
        model.insertNodeInto(
            JMeterTreeNode(ThreadGroup().apply { name = "Caller"; threadGroupId = "caller" }, model),
            threadGroups, 0
        )
        val merged = ScenarioPlanMigration.migrate(legacyPlanWithDuplicateFragments())

        // As File > Merge does
        ScenarioPlanMigration.makeNamesUnique(merged, UniqueNames.usedNames(model), UniqueNames.usedThreadGroupIds(model))
        model.addSubTree(merged, plan, false)

        val sections = (0 until plan.childCount).map { (plan.getChildAt(it) as JMeterTreeNode).userObject }
            .filterIsInstance<TestPlanSection>()
        assertEquals(sections.map { it.javaClass }.distinct().size, sections.size) { "No section twice: $sections" }
        val callers = model.getNodesOfType(ThreadGroup::class.java).map { it.testElement as ThreadGroup }
        assertEquals(listOf("Caller", "Caller (2)"), callers.map { it.name })
        val scenarios = model.getNodesOfType(Scenario::class.java).map { it.testElement as Scenario }
        assertEquals(2, scenarios.size)
        assertTrue(scenarios[0].isEnabled)
        assertFalse(scenarios[1].isEnabled) { "The merged scenario does not replace the active one" }
        assertEquals(listOf(callers[1].threadGroupId), scenarios[1].workloads.map { it.threadGroupId }) {
            "The merged row runs the merged thread group, which got a new id"
        }
        val module = model.getNodesOfType(ModuleController::class.java).single().testElement as ModuleController
        module.resolveReplacementSubTree(model.root as JMeterTreeNode)
        assertEquals("Second flow", module.selectedNode?.name) { "The merged Module Controller still finds its target" }
    }
}
