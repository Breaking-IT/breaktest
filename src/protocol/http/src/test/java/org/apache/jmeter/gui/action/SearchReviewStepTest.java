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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.RowField;
import org.apache.jmeter.gui.SearchArea;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.ReviewStep;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.junit.jupiter.api.Test;

class SearchReviewStepTest extends JMeterTestCase {
    private static final Set<SearchArea> ALL = EnumSet.allOf(SearchArea.class);

    private static JMeterTreeNode node(HTTPSamplerProxy sampler) {
        var root = new JMeterTreeNode(new TestPlan(), null);
        var node = new JMeterTreeNode(sampler, null);
        root.add(node);
        return node;
    }

    private static HTTPSamplerProxy sampler() {
        var sampler = new HTTPSamplerProxy();
        sampler.setName("Request");
        sampler.setMethod("POST");
        sampler.setPath("/send");
        sampler.setProperty(TestElement.GUI_CLASS, HttpTestSampleGui.class.getName());
        return sampler;
    }

    @Test
    void regexGroupsAreExpandedPerOccurrenceAndRejectedMatchesSurviveAcceptAll() {
        var sampler = sampler();
        sampler.addArgument("first", "a1 b22 c333");
        var steps = SearchReviewStep.replacements(List.of(node(sampler)), Pattern.compile("([a-z])(\\d+)"),
                "$2-$1", true, ALL, RowField.ALL);
        assertEquals(3, steps.size());
        steps.get(1).reject();
        for (var step : steps) {
            if (step.state() == ReviewStep.State.PENDING) {
                assertTrue(step.apply(null, steps));
            }
        }
        assertEquals("1-a b22 333-c", sampler.getArguments().getArgument(0).getValue());
        assertEquals(ReviewStep.State.REJECTED, steps.get(1).state());
    }

    @Test
    void replacementsHonorAreaColumnAndLiteralReplacementAndReResolveClonedFields() {
        var sampler = sampler();
        sampler.addArgument("token", "token token");
        sampler.setNativeHeaders(List.of(new Header("X-Token", "token")));
        var steps = SearchReviewStep.replacements(List.of(node(sampler)), Pattern.compile("token"),
                "$1", false, Set.of(SearchArea.PARAMETERS), RowField.VALUE);
        assertEquals(2, steps.size());
        sampler.setArguments((Arguments) sampler.getArguments().clone());
        assertTrue(steps.get(1).apply(null, steps));
        assertTrue(steps.get(0).apply(null, steps));
        assertEquals("$1 $1", sampler.getArguments().getArgument(0).getValue());
        assertEquals("token", sampler.getArguments().getArgument(0).getName());
        assertEquals("token", sampler.getNativeHeaderList().get(0).getValue());
    }

    private static List<SearchReviewStep> rows(JMeterTreeNode node, SearchArea area) {
        var matches = SearchTreeDialog.matchingRows(List.of(node), new SearchTreeDialog.SearchScope(node),
                Set.of(area), new RawTextSearcher(true, "same"), RowField.VALUE);
        return SearchReviewStep.rows(matches, Pattern.compile("same"), RowField.VALUE);
    }

    @Test
    void deletesOnlyAcceptedDuplicateRowsAfterNavigationRebuildsTheirCollections() {
        var sampler = sampler();
        sampler.setNativeHeaders(List.of(new Header("X-Duplicate", "same"),
                new Header("X-Duplicate", "same"), new Header("X-Duplicate", "same")));
        var steps = rows(node(sampler), SearchArea.HEADERS);
        assertEquals(3, steps.size());
        steps.get(1).reject();
        sampler.setNativeHeaders(sampler.getNativeHeaderList().stream().map(header -> (Header) header.clone()).toList());
        assertTrue(steps.get(0).apply(null, steps));
        assertTrue(steps.get(2).apply(null, steps));
        assertEquals(1, sampler.getNativeHeaderList().size());
        assertTrue(steps.get(1).current());
        assertEquals(ReviewStep.State.REJECTED, steps.get(1).state());
        assertFalse(steps.get(1).apply(null, steps));
    }

    @Test
    void refusesDeletionIfTheRowCollectionChangedSinceReview() {
        var sampler = sampler();
        sampler.addArgument("one", "same");
        sampler.addArgument("two", "same");
        var steps = rows(node(sampler), SearchArea.PARAMETERS);
        sampler.getArguments().getArgument(0).setValue("edited");
        assertFalse(steps.get(1).apply(null, steps));
        assertEquals(2, sampler.getArguments().getArgumentCount());
        assertEquals(ReviewStep.State.STALE, steps.get(1).state());
    }

    @Test
    void highlightsBodyMatchesInTheLiveBodyEditor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var sampler = sampler();
            sampler.setPostBodyRaw(true);
            sampler.addArgument("", "first\r\nsame");
            var steps = SearchReviewStep.replacements(List.of(node(sampler)), Pattern.compile("same"),
                    "updated", false, ALL, RowField.ALL);
            var editor = new HttpTestSampleGui();
            editor.configure(sampler);
            Runnable clear = steps.get(0).highlight(editor);
            assertNotNull(clear);
            clear.run();
        });
    }

    @Test
    void batchEditsSurviveOpenEditorWriteBackAndRejectedChildrenProtectTheirParent() throws Exception {
        var singleton = GuiPackage.class.getDeclaredField("guiPack");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        try {
            SwingUtilities.invokeAndWait(() -> {
                var model = new JMeterTreeModel(new TestPlan("Plan"));
                var listener = new JMeterTreeListener(model);
                listener.setJTree(new JTree(model));
                GuiPackage.initInstance(listener, model);
                var gui = GuiPackage.getInstance();
                var plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
                var parent = new JMeterTreeNode(new GenericController(), model);
                model.insertNodeInto(parent, plan, 0);
                var sampler = sampler();
                sampler.addArgument("one", "same same");
                var node = new JMeterTreeNode(sampler, model);
                model.insertNodeInto(node, parent, 0);
                listener.setSelectionPathWithoutEdit(new TreePath(node.getPath()));
                gui.refreshCurrentGui();
                var steps = SearchReviewStep.replacements(List.of(node), Pattern.compile("same"), "new",
                        false, ALL, RowField.ALL);
                assertEquals(2, SearchReviewStep.apply(gui, steps, steps));
                gui.updateCurrentNode();
                assertEquals("new new", sampler.getArguments().getArgument(0).getValue());
                var deletions = SearchReviewStep.elements(List.of(parent, node), null, ALL, RowField.ALL);
                deletions.get(1).reject();
                assertFalse(deletions.get(0).apply(gui, deletions));
                assertEquals(1, parent.getChildCount());
            });
        } finally {
            singleton.set(null, previous);
        }
    }
}
