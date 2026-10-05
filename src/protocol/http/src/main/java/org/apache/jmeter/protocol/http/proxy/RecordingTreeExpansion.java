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

package org.apache.jmeter.protocol.http.proxy;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JTree;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.threads.AbstractThreadGroup;

/** Reveal recording destinations without collapsing any existing expansion state. */
final class RecordingTreeExpansion {
    private RecordingTreeExpansion() {
    }

    static void expand(List<RecordedSampler> samples) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null && gui.getMainFrame() != null) {
            expand(gui.getMainFrame().getTree(), samples.stream().map(sample -> sample.target).toList());
        }
    }

    static void expand(JTree tree, Collection<JMeterTreeNode> targets) {
        Set<JMeterTreeNode> groups = new LinkedHashSet<>();
        for (JMeterTreeNode target : targets) {
            for (JMeterTreeNode node = target; node != null; node = (JMeterTreeNode) node.getParent()) {
                if (node.getTestElement() instanceof AbstractThreadGroup) {
                    groups.add(node);
                    tree.expandPath(new TreePath(node.getPath()));
                    tree.expandPath(new TreePath(target.getPath()));
                    break;
                }
            }
        }
        if (!groups.isEmpty()) {
            tree.scrollPathToVisible(new TreePath(groups.iterator().next().getPath()));
        }
    }
}
