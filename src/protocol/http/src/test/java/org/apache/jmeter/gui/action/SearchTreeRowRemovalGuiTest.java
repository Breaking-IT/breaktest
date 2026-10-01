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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Set;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.SearchArea;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

class SearchTreeRowRemovalGuiTest {
    @Test
    void removesRowsFromOpenEditorAndOtherRequestWithoutRestoringThemOnSave() throws Exception {
        var instance = GuiPackage.class.getDeclaredField("guiPack");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        try {
            SwingUtilities.invokeAndWait(() -> {
                JMeterTreeModel model = new JMeterTreeModel(new TestPlan("Plan"));
                JMeterTreeListener listener = new JMeterTreeListener(model);
                listener.setJTree(new JTree(model));
                GuiPackage.initInstance(listener, model);
                GuiPackage gui = GuiPackage.getInstance();
                JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
                JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), model);
                model.insertNodeInto(group, plan, 0);
                HTTPSamplerProxy open = sampler("Open request");
                HTTPSamplerProxy other = sampler("Other request");
                JMeterTreeNode openNode = new JMeterTreeNode(open, model);
                JMeterTreeNode otherNode = new JMeterTreeNode(other, model);
                model.insertNodeInto(openNode, group, 0);
                model.insertNodeInto(otherNode, group, 1);
                listener.setSelectionPathWithoutEdit(new TreePath(openNode.getPath()));
                gui.refreshCurrentGui();
                gui.updateCurrentNode();
                // A refreshed editor is eligible for write-back by the next routed action.
                gui.refreshCurrentGui();
                var matches = SearchTreeDialog.matchingRows(List.of(openNode, otherNode),
                        new SearchTreeDialog.SearchScope(group), Set.of(SearchArea.HEADERS, SearchArea.PARAMETERS),
                        new RawTextSearcher(true, "remove-me"));
                assertEquals(4, matches.size());
                openNode.setMarkedBySearch(true);
                ResetSearchCommand.clearSearchMarks(gui);
                assertFalse(openNode.isMarkedBySearch());
                assertEquals(4, SearchTreeDialog.removeRows(matches));
                gui.refreshCurrentGui();
                // Saving/navigating away must not put the visible editor's old rows back.
                gui.updateCurrentNode();
                for (HTTPSamplerProxy sampler : List.of(open, other)) {
                    assertEquals(1, sampler.getArguments().getArgumentCount());
                    assertEquals("keep", sampler.getArguments().getArgument(0).getName());
                    assertEquals(1, sampler.getNativeHeaderList().size());
                    assertEquals("X-Keep", sampler.getNativeHeaderList().get(0).getName());
                }
            });
        } finally {
            instance.set(null, previous);
        }
    }

    private static HTTPSamplerProxy sampler(String name) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName(name);
        sampler.setProperty(TestElement.GUI_CLASS, HttpTestSampleGui.class.getName());
        sampler.setDomain("example.test");
        sampler.setPath("/resources");
        sampler.addArgument("remove-me", "value");
        sampler.addArgument("keep", "value");
        sampler.setNativeHeaders(List.of(new Header("X-Remove", "remove-me"), new Header("X-Keep", "value")));
        return sampler;
    }
}
