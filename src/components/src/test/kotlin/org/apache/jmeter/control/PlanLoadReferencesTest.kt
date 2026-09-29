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

import org.apache.jmeter.JMeter
import org.apache.jmeter.config.Arguments
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.scenario.Profile
import org.apache.jmeter.scenario.ProfilesSection
import org.apache.jmeter.scenario.Scenario
import org.apache.jmeter.scenario.ScenarioPlanMigration
import org.apache.jmeter.scenario.ScenarioResolver
import org.apache.jmeter.scenario.ScenarioWorkload
import org.apache.jmeter.scenario.ScenariosSection
import org.apache.jmeter.scenario.SharedProfile
import org.apache.jmeter.scenario.TestFragmentsSection
import org.apache.jmeter.scenario.TestPlanSection
import org.apache.jmeter.scenario.ThreadGroupsSection
import org.apache.jmeter.scenario.gui.UniqueNames
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.property.CollectionProperty
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jorphan.collections.ListedHashTree
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PlanLoadReferencesTest : JMeterTestCase() {
    private fun section(type: Class<out TestPlanSection>) = ScenarioPlanMigration.newSection(type)
    private fun group(name: String, id: String) = ThreadGroup().apply {
        this.name = name
        threadGroupId = id
    }
    private fun row(id: String, name: String, profileName: String = "") = ScenarioWorkload().apply {
        threadGroupId = id
        this.name = name
        profile = profileName
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `merge reserves ids of later incoming thread groups`(collision: Boolean) {
        val model = JMeterTreeModel()
        val target = model.getNodesOfType(ThreadGroupsSection::class.java).single()
        if (collision) {
            model.insertNodeInto(JMeterTreeNode(group("Browse", "browse"), model), target, 0)
        }
        val incoming = ListedHashTree()
        val plan = incoming.add(TestPlan().apply { name = "Test Plan" })
        val first = group("Browse", "browse").apply { setProperty("test.script", "first") }
        val second = group("Browse (2)", "browse-2").apply { setProperty("test.script", "second") }
        val groups = plan.add(section(ThreadGroupsSection::class.java))
        groups.add(first)
        groups.add(second)
        val scenario = Scenario("Imported").apply { workloads = listOf(row("browse", "First"), row("browse-2", "Second")) }
        plan.add(section(ScenariosSection::class.java)).add(scenario)
        val anchored = Arguments().apply {
            setProperty(CollectionProperty(SharedProfile.AFTER_THREAD_GROUPS, listOf("browse", "browse-2")))
        }
        plan.add(section(ProfilesSection::class.java)).add(ScenarioPlanMigration.newSharedProfile()).add(anchored)
        ScenarioPlanMigration.makeNamesUnique(incoming, UniqueNames.usedNames(model), UniqueNames.usedThreadGroupIds(model))
        assertEquals(listOf(first.threadGroupId, second.threadGroupId), SharedProfile.threadGroupsBefore(anchored))
        model.addSubTree(incoming, model.root as JMeterTreeNode, false)
        assertEquals(first.threadGroupId, scenario.workloads[0].threadGroupId)
        val run = ScenarioResolver.resolve(JMeter.convertSubTree(model.testPlan, false), scenario)
        val scripts = run.getTree(run.array[0]).list().filterIsInstance<ThreadGroup>().map { it.getPropertyAsString("test.script") }
        assertAll(
            { assertEquals(second.threadGroupId, scenario.workloads[1].threadGroupId, "The Second row must still run the second imported script") },
            { assertEquals(listOf("first", "second"), scripts, "The resolved workload must execute both imported scripts") }
        )
    }

    private fun duplicateProfiles(normalize: Boolean, firstEnabled: Boolean, merging: Boolean): String? {
        val tree = ListedHashTree()
        val plan = tree.add(TestPlan())
        plan.add(section(ThreadGroupsSection::class.java)).add(group("Workers", "workers"))
        plan.add(section(ScenariosSection::class.java)).add(
            Scenario("Run").apply { workloads = listOf(row("workers", "Run", "Environment")) }
        )
        val profiles = plan.add(section(ProfilesSection::class.java))
        profiles.add(Profile("Environment").apply { isEnabled = firstEnabled }).add(Arguments().apply { addArgument("host", "acceptance.example") })
        profiles.add(Profile("Environment")).add(Arguments().apply { addArgument("host", "production.example") })
        if (normalize) {
            val names = HashMap<Class<out TestPlanSection>, MutableSet<String>>()
            if (merging) names[ProfilesSection::class.java] = hashSetOf("Environment")
            ScenarioPlanMigration.makeNamesUnique(tree, names, HashSet())
        }
        val flat = ScenarioResolver.resolve(JMeter.convertSubTree(tree, false))
        return flat.getTree(flat.array[0]).list().filterIsInstance<ThreadGroup>().single().profileVariables["host"]
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `duplicate profile names preserve the first enabled match`(firstEnabled: Boolean) {
        val expected = if (firstEnabled) "acceptance.example" else "production.example"
        assertEquals(expected, duplicateProfiles(false, firstEnabled, false), "Original resolver control")
        assertEquals(expected, duplicateProfiles(true, firstEnabled, false), "Loading keeps the environment")
        assertEquals(expected, duplicateProfiles(true, firstEnabled, true), "Merging keeps the environment")
    }

    @Test
    fun `renaming an empty profile name preserves use default rows`() {
        val tree = ListedHashTree()
        val plan = tree.add(TestPlan())
        plan.add(section(ProfilesSection::class.java)).add(Profile(""))
        val scenario = Scenario("Run").apply { workloads = listOf(row("workers", "Default")) }
        plan.add(section(ScenariosSection::class.java)).add(scenario)
        val names = hashMapOf<Class<out TestPlanSection>, MutableSet<String>>(ProfilesSection::class.java to hashSetOf(""))
        ScenarioPlanMigration.makeNamesUnique(tree, names, HashSet())
        assertEquals("", scenario.workloads.single().profile)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `duplicate full module paths preserve original last match`(nested: Boolean) {
        val tree = ListedHashTree()
        val plan = tree.add(TestPlan().apply { name = "Plan" })
        val fragmentsSection = section(TestFragmentsSection::class.java)
        val fragments = plan.add(fragmentsSection)
        val first = TestFragmentController().apply { name = "Flow" }
        val second = TestFragmentController().apply { name = "Flow" }
        val firstTree = fragments.add(first)
        val secondTree = fragments.add(second)
        val firstTarget = if (nested) GenericController().apply { name = "Step" } else first
        val secondTarget = if (nested) GenericController().apply { name = "Step" } else second
        if (nested) {
            firstTree.add(firstTarget)
            secondTree.add(secondTarget)
        }
        val caller = group("Caller", "caller")
        val path = listOf("root", "Plan", fragmentsSection.name, "Flow") + if (nested) listOf("Step") else emptyList()
        val module = ModuleController().apply {
            name = "Call flow"
            setProperty(CollectionProperty("ModuleController.node_path", path))
        }
        plan.add(section(ThreadGroupsSection::class.java)).add(caller).add(module)
        // Raw model verifies the original ModuleController resolution without insertion-time renaming.
        val originalModel = JMeterTreeModel(TestPlan().apply { name = "Plan" })
        val root = originalModel.root as JMeterTreeNode
        val originalPlan = root.getChildAt(0) as JMeterTreeNode
        val sectionNode = JMeterTreeNode(fragmentsSection, originalModel)
        originalPlan.add(sectionNode)
        val firstNode = JMeterTreeNode(first, originalModel)
        val secondNode = JMeterTreeNode(second, originalModel)
        sectionNode.add(firstNode)
        sectionNode.add(secondNode)
        if (nested) {
            firstNode.add(JMeterTreeNode(firstTarget, originalModel))
            secondNode.add(JMeterTreeNode(secondTarget, originalModel))
        }
        module.resolveReplacementSubTree(root)
        assertSame(secondTarget, module.selectedNode?.testElement, "Original controller chooses the last complete path")
        val freshModule = ModuleController().apply { setProperty(module.getProperty("ModuleController.node_path").clone()) }
        plan.getTree(plan.list().first { it is ThreadGroupsSection }).getTree(caller).remove(module)
        plan.getTree(plan.list().first { it is ThreadGroupsSection }).getTree(caller).add(freshModule)
        ScenarioPlanMigration.makeNamesUnique(tree, HashMap(), HashSet())
        val loadedModel = JMeterTreeModel(TestPlan())
        loadedModel.addSubTreeForExecution(tree, loadedModel.root as JMeterTreeNode)
        freshModule.resolveReplacementSubTree(loadedModel.root as JMeterTreeNode)
        assertSame(secondTarget, freshModule.selectedNode?.testElement, "Name repair must keep the original target")
    }
}
