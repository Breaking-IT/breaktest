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

package org.apache.jmeter.ai.gui

import org.apache.jmeter.gui.GuiPackage
import org.apache.jmeter.gui.tree.JMeterTreeListener
import org.apache.jmeter.gui.tree.JMeterTreeModel
import org.apache.jmeter.gui.tree.JMeterTreeNode
import org.apache.jmeter.save.SaveService
import org.apache.jmeter.testelement.TestElement
import org.apache.jmeter.testelement.TestPlan
import java.io.File
import javax.swing.JTree
import javax.swing.SwingUtilities

/**
 * Opens a plan file in a process that has no BreakTest window, so the plan tools can work on it.
 * The tools only need the tree model, which does not need a display.
 */
public object AgentHeadlessPlan {
    @Volatile
    private var open = false

    @JvmStatic
    public fun isOpen(): Boolean = open

    /** Loads [file] as the open plan of this process and returns its node count. */
    @JvmStatic
    public fun open(file: File): Int {
        val tree = SaveService.loadTree(file)
        val plan = tree.array.firstOrNull() as? TestPlan
            ?: throw IllegalArgumentException("${file.path} is not a complete test plan")
        var count = 0
        SwingUtilities.invokeAndWait {
            val model = JMeterTreeModel(TestPlan())
            GuiPackage.initInstance(JMeterTreeListener(model).apply { setJTree(JTree(model)) }, model)
            val gui = GuiPackage.getInstance()
            gui.clearTestPlan(plan as TestElement)
            model.addSubTree(tree, model.root as JMeterTreeNode, false)
            gui.setTestPlanFile(file.path)
            gui.setDirty(false)
            count = BreakTestAgentGuiService.allTreeNodes(model.testPlan).size
        }
        open = true
        return count
    }
}
