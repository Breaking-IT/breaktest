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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.RemovableRow;
import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.RowField;
import org.apache.jmeter.gui.SearchArea;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElementSchema;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.timers.Timer;
import org.junit.jupiter.api.Test;

class SearchTreeDialogTest {

    @Test
    void rowMatchingRespectsThreadGroupAreaAndTextWithoutRemovingOwners() {
        JMeterTreeNode root = new JMeterTreeNode(new TestPlan("Plan"), null);
        JMeterTreeNode firstGroup = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode otherGroup = new JMeterTreeNode(new ThreadGroup(), null);
        root.add(firstGroup);
        root.add(otherGroup);
        java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger();
        RemovableRow header = new RemovableRow(SearchArea.HEADERS, 1, List.of("X-Trace", "VALUE"), () -> {
            removed.incrementAndGet();
            return true;
        });
        RemovableRow parameter = new RemovableRow(SearchArea.PARAMETERS, 1, List.of("parameter", "VALUE"), () -> false);
        JMeterTreeNode first = new JMeterTreeNode(new RowElement(List.of(header, parameter)), null);
        JMeterTreeNode other = new JMeterTreeNode(new RowElement(List.of(header)), null);
        firstGroup.add(first);
        otherGroup.add(other);
        var matches = SearchTreeDialog.matchingRows(List.of(first, other), new SearchTreeDialog.SearchScope(firstGroup),
                Set.of(SearchArea.HEADERS), new RawTextSearcher(false, "value"));
        assertEquals(1, matches.size());
        assertSame(first, matches.get(0).node());
        assertTrue(SearchTreeDialog.matchingRows(List.of(first), new SearchTreeDialog.SearchScope(firstGroup),
                Set.of(SearchArea.HEADERS), new RawTextSearcher(true, "value")).isEmpty());
        assertEquals(2, SearchTreeDialog.matchingRows(List.of(first), new SearchTreeDialog.SearchScope(firstGroup),
                Set.of(SearchArea.HEADERS, SearchArea.PARAMETERS), new RegexpSearcher(true, "^VALUE$")).size());
        assertEquals(1, SearchTreeDialog.removeRows(matches));
        assertEquals(1, removed.get());
        assertSame(firstGroup, first.getParent());
        assertSame(otherGroup, other.getParent());
    }

    @Test
    void previewSelectionControlsUpdateCountAndPreventEmptyRemoval() {
        java.util.Locale previous = org.apache.jmeter.util.JMeterUtils.getLocale();
        org.apache.jmeter.util.JMeterUtils.setLocale(java.util.Locale.ENGLISH);
        try {
            List<JCheckBox> boxes = List.of(new JCheckBox("First", true), new JCheckBox("Second", true));
            JButton remove = new JButton();
            JPanel controls = SearchTreeDialog.removalSelectionControls(boxes, remove);
            JLabel count = (JLabel) controls.getComponent(2);
            assertEquals("2 of 2 selected", count.getText());
            ((JButton) controls.getComponent(1)).doClick();
            assertFalse(remove.isEnabled());
            assertEquals("0 of 2 selected", count.getText());
            boxes.get(1).doClick();
            assertTrue(remove.isEnabled());
            assertEquals("1 of 2 selected", count.getText());
            ((JButton) controls.getComponent(0)).doClick();
            assertTrue(boxes.stream().allMatch(JCheckBox::isSelected));
        } finally {
            if (previous != null) {
                org.apache.jmeter.util.JMeterUtils.setLocale(previous);
            }
        }
    }

    @Test
    void defaultsRowsSupportColumnFiltersAndSkipChangedPreviewRows() throws Exception {
        ConfigTestElement defaults = new ConfigTestElement();
        Arguments args = new Arguments();
        args.addArgument("target", "keep");
        args.addArgument("keep", "target");
        defaults.setProperty(new TestElementProperty("HTTPsampler.Arguments", args));
        JMeterTreeNode parent = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode node = new JMeterTreeNode(defaults, null);
        parent.add(node);
        var scope = new SearchTreeDialog.SearchScope(null);
        var names = SearchTreeDialog.matchingRows(List.of(node), scope, Set.of(SearchArea.PARAMETERS),
                new RawTextSearcher(true, "target"), RowField.NAME);
        var values = SearchTreeDialog.matchingRows(List.of(node), scope, Set.of(SearchArea.PARAMETERS),
                new RawTextSearcher(true, "target"), RowField.VALUE);
        assertEquals(1, names.size());
        assertEquals(1, names.get(0).row().number());
        assertEquals(1, values.size());
        assertEquals(2, values.get(0).row().number());
        assertEquals(List.of("target", "keep"),
                SearchTreeDialog.searchableTokens(node, null, Set.of(SearchArea.PARAMETERS), RowField.NAME));
        args.getArgument(0).setValue("changed after preview");
        var result = SearchTreeDialog.removeRowsWithResult(List.of(names.get(0), values.get(0)));
        assertEquals(1, result.removed());
        assertEquals(1, result.stale());
        assertEquals(0, result.busy());
        assertEquals("changed after preview", args.getArgument(0).getValue());
        defaults.setProperty("HTTPSampler.postBodyRaw", true);
        assertTrue(RemovableRow.forElement(defaults).isEmpty());
        defaults.setProperty("HTTPSampler.postBodyRaw", false);
        args.addArgument("", "unnamed value");
        assertEquals(2, RemovableRow.forElement(defaults).size());
        args.removeArgument(0);
        assertTrue(RemovableRow.forElement(defaults).isEmpty());
    }

    @Test
    void rowRemovalReportsBusyAndDetachedOwners() {
        RemovableRow row = new RemovableRow(SearchArea.HEADERS, 1, List.of("X-Trace", "value"), () -> {
            throw new AssertionError("Unavailable rows must not be removed");
        });
        JMeterTreeNode busy = new JMeterTreeNode(new RowElement(List.of(row)) {
            @Override
            public boolean canRemove() {
                return false;
            }
        }, null);
        JMeterTreeNode detached = new JMeterTreeNode(new RowElement(List.of(row)), null);
        JMeterTreeNode parent = new JMeterTreeNode(new ThreadGroup(), null);
        parent.add(busy);
        parent.add(detached);
        var matches = SearchTreeDialog.matchingRows(List.of(busy, detached), new SearchTreeDialog.SearchScope(null),
                Set.of(SearchArea.HEADERS), new RawTextSearcher(true, "value"));
        parent.remove(detached);
        var result = SearchTreeDialog.removeRowsWithResult(matches);
        assertEquals(0, result.removed());
        assertEquals(1, result.busy());
        assertEquals(1, result.stale());
        assertEquals(2, result.skipped());
    }

    @Test
    void restoredScopeUsesOccurrenceForIdenticallyNamedGroups() {
        JMeterTreeModel original = new JMeterTreeModel(new TestPlan("Plan"));
        JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) original.getRoot()).getChildAt(0);
        JMeterTreeNode first = new JMeterTreeNode(new ThreadGroup(), original);
        JMeterTreeNode second = new JMeterTreeNode(new ThreadGroup(), original);
        first.getTestElement().setName("Group");
        second.getTestElement().setName("Group");
        plan.add(first);
        plan.add(second);
        var firstKey = SearchTreeDialog.scopeKey(first);
        var secondKey = SearchTreeDialog.scopeKey(second);
        assertEquals(0, firstKey.occurrence());
        assertEquals(1, secondKey.occurrence());
        JMeterTreeModel restored = new JMeterTreeModel(new TestPlan("Plan"));
        JMeterTreeNode restoredPlan = (JMeterTreeNode) ((JMeterTreeNode) restored.getRoot()).getChildAt(0);
        JMeterTreeNode restoredFirst = new JMeterTreeNode((ThreadGroup) first.getTestElement().clone(), restored);
        JMeterTreeNode restoredSecond = new JMeterTreeNode((ThreadGroup) second.getTestElement().clone(), restored);
        restoredPlan.add(restoredFirst);
        restoredPlan.add(restoredSecond);
        assertSame(restoredFirst, SearchTreeDialog.resolveScope(first, firstKey, restored));
        assertSame(restoredSecond, SearchTreeDialog.resolveScope(second, secondKey, restored));
        restoredPlan.remove(restoredSecond);
        assertSame(second, SearchTreeDialog.resolveScope(second, secondKey, restored));
        assertFalse(SearchTreeDialog.isWithinSearchScope(restoredFirst, new SearchTreeDialog.SearchScope(second)));
    }

    private static class RowElement extends ConfigTestElement implements Replaceable {
        private final List<RemovableRow> rows;

        RowElement(List<RemovableRow> rows) {
            this.rows = rows;
        }

        @Override
        public List<RemovableRow> getRemovableRows() {
            return rows;
        }

        @Override
        public int replace(String regex, String replacement, boolean caseSensitive) {
            return 0;
        }
    }

    @Test
    void cleanupIncludesNewlyEmptyNestedControllersButNotThreadGroup() {
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode outer = new JMeterTreeNode(new TransactionController(), null);
        JMeterTreeNode inner = new JMeterTreeNode(new GenericController(), null);
        JMeterTreeNode request = new JMeterTreeNode(new ConfigTestElement(), null);
        group.add(outer);
        outer.add(inner);
        inner.add(request);
        var affected = Set.copyOf(SearchTreeDialog.controllerAncestors(request));
        assertEquals(Set.of(inner, outer), affected);
        inner.remove(request);
        assertEquals(List.of(inner, outer), SearchTreeDialog.emptiedControllers(affected, group));
    }

    @Test
    void cleanupPreservesUnrelatedEmptyControllersAndRemainingChildren() {
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode outer = new JMeterTreeNode(new GenericController(), null);
        JMeterTreeNode emptied = new JMeterTreeNode(new GenericController(), null);
        JMeterTreeNode alreadyEmpty = new JMeterTreeNode(new GenericController(), null);
        group.add(outer);
        outer.add(emptied);
        outer.add(alreadyEmpty);
        assertEquals(List.of(emptied), SearchTreeDialog.emptiedControllers(Set.of(outer, emptied), group));
        emptied.add(new JMeterTreeNode(new ConfigTestElement(), null));
        assertTrue(SearchTreeDialog.emptiedControllers(Set.of(outer, emptied), group).isEmpty());
    }

    @Test
    void cleanupExcludesDetachedSubtreesBusyControllersAndFragmentRoots() {
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode detached = new JMeterTreeNode(new GenericController(), null);
        JMeterTreeNode child = new JMeterTreeNode(new GenericController(), null);
        detached.add(child);
        assertTrue(SearchTreeDialog.emptiedControllers(Set.of(detached, child), group).isEmpty());
        JMeterTreeNode busy = new JMeterTreeNode(new GenericController() {
            @Override
            public boolean canRemove() {
                return false;
            }
        }, null);
        group.add(busy);
        assertTrue(SearchTreeDialog.emptiedControllers(Set.of(busy), group).isEmpty());
        JMeterTreeNode fragment = new JMeterTreeNode(new TestFragmentController(), null);
        fragment.add(child);
        assertTrue(SearchTreeDialog.controllerAncestors(child).isEmpty());
    }

    @Test
    void scopeIncludesOnlyOneSelectedThreadGroupOrWholePlan() {
        JMeterTreeNode plan = new JMeterTreeNode(new TestPlan("Plan"), null);
        JMeterTreeNode first = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode second = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode third = new JMeterTreeNode(new ThreadGroup(), null);
        plan.add(first);
        plan.add(second);
        plan.add(third);
        JMeterTreeNode child = new JMeterTreeNode(new ConfigTestElement(), null);
        second.add(child);
        var selected = new SearchTreeDialog.SearchScope(second);
        assertFalse(SearchTreeDialog.isWithinSearchScope(first, selected));
        assertTrue(SearchTreeDialog.isWithinSearchScope(child, selected));
        assertFalse(SearchTreeDialog.isWithinSearchScope(third, selected));
        assertFalse(SearchTreeDialog.isWithinSearchScope(plan, selected));
        assertTrue(SearchTreeDialog.isWithinSearchScope(plan, new SearchTreeDialog.SearchScope(null)));
    }

    @Test
    void searchAndReplaceAreasKeepIdenticalValuesInSeparateFields() throws Exception {
        ConfigTestElement element = new ConfigTestElement();
        element.setName("same-value");
        element.setComment("same-value");
        JMeterTreeNode node = new JMeterTreeNode(element, null);
        assertEquals(List.of("same-value"), SearchTreeDialog.searchableTokens(node, null, Set.of(SearchArea.NAME)));
        assertTrue(SearchTreeDialog.searchableTokens(node, null, Set.of(SearchArea.OTHER)).contains("same-value"));
        assertTrue(SearchTreeDialog.searchableTokens(node, null, Set.of()).isEmpty());
        var changes = SearchTreeDialog.replacementChanges(node, Pattern.compile("same-value"),
                "new-name", false, Set.of(SearchArea.NAME));
        assertEquals(1, SearchTreeDialog.applyChanges(changes));
        assertEquals("new-name", element.getName());
        assertEquals("same-value", element.getComment());
        assertTrue(SearchTreeDialog.replacementChanges(node, Pattern.compile("same-value"),
                "ignored", false, Set.of(SearchArea.RECORDED_RESPONSE)).isEmpty());
    }

    @Test
    void removalPathExcludesHiddenRootAndPreservesRepeatedVisibleNames() {
        TestPlan plan = new TestPlan("Example Plan");
        JMeterTreeModel model = new JMeterTreeModel(plan);
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        JMeterTreeNode planNode = (JMeterTreeNode) root.getChildAt(0);
        ThreadGroup group = new ThreadGroup();
        group.setName("Example Plan");
        JMeterTreeNode groupNode = new JMeterTreeNode(group, model);
        model.insertNodeInto(groupNode, planNode, 0);
        ConfigTestElement sampler = new ConfigTestElement();
        sampler.setName("Request");
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, model);
        model.insertNodeInto(samplerNode, groupNode, 0);

        assertEquals("Example Plan", SearchTreeDialog.formatNodePath(planNode));
        assertEquals("Example Plan > Example Plan > Request", SearchTreeDialog.formatNodePath(samplerNode));
    }

    @Test
    void matchesSelectedNodeTypes() {
        assertTrue(SearchTreeDialog.matchesAnySelectedNodeType(
                new DummyPreProcessor(), EnumSet.of(SearchTreeDialog.NodeType.PRE_PROCESSOR)));
        assertTrue(SearchTreeDialog.matchesAnySelectedNodeType(
                new DummyPostProcessor(), EnumSet.of(SearchTreeDialog.NodeType.POST_PROCESSOR)));
        assertTrue(SearchTreeDialog.matchesAnySelectedNodeType(
                new DummyAssertion(), EnumSet.of(SearchTreeDialog.NodeType.ASSERTION)));
        assertTrue(SearchTreeDialog.matchesAnySelectedNodeType(
                new DummyTimer(), EnumSet.of(SearchTreeDialog.NodeType.TIMER)));
        assertTrue(SearchTreeDialog.matchesAnySelectedNodeType(
                new ConfigTestElement(), EnumSet.of(SearchTreeDialog.NodeType.CONFIG_ELEMENT)));
    }

    @Test
    void rejectsUnselectedNodeTypes() {
        assertFalse(SearchTreeDialog.matchesAnySelectedNodeType(
                new DummyPreProcessor(), EnumSet.of(SearchTreeDialog.NodeType.POST_PROCESSOR)));
    }

    @Test
    void findsContainingThreadGroupForDefaultScope() {
        JMeterTreeNode threadGroupNode = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode samplerNode = new JMeterTreeNode(new DummySampler(), null);
        threadGroupNode.add(samplerNode);

        assertSame(threadGroupNode, SearchTreeDialog.findThreadGroupScope(samplerNode));
        assertSame(threadGroupNode, SearchTreeDialog.findThreadGroupScope(threadGroupNode));
        assertNull(SearchTreeDialog.findThreadGroupScope(new JMeterTreeNode(new DummySampler(), null)));
    }

    @Test
    void limitsNodesToSelectedThreadGroupScope() {
        JMeterTreeNode firstThreadGroup = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode firstSampler = new JMeterTreeNode(new DummySampler(), null);
        firstThreadGroup.add(firstSampler);
        JMeterTreeNode secondThreadGroup = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode secondSampler = new JMeterTreeNode(new DummySampler(), null);
        secondThreadGroup.add(secondSampler);

        assertTrue(SearchTreeDialog.isWithinScope(firstThreadGroup, firstThreadGroup));
        assertTrue(SearchTreeDialog.isWithinScope(firstSampler, firstThreadGroup));
        assertFalse(SearchTreeDialog.isWithinScope(secondSampler, firstThreadGroup));
        assertTrue(SearchTreeDialog.isWithinScope(secondSampler, null));
    }

    @Test
    void exposesNameAndCommentsAsReplaceableFields() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Checkout transaction");
        threadGroup.setComment("Calls the old checkout endpoint");
        JMeterTreeNode node = new JMeterTreeNode(threadGroup, null);

        var fields = SearchTreeDialog.replaceableFields(node);

        assertTrue(fields.stream().anyMatch(field -> field.name().equals("Name")
                && field.value().equals("Checkout transaction")));
        assertTrue(fields.stream().anyMatch(field -> field.name().equals("Comments")
                && field.value().equals("Calls the old checkout endpoint")));
    }

    @Test
    void replacementCanDeleteCommentText() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Checkout");
        threadGroup.setComment("old old");
        JMeterTreeNode node = new JMeterTreeNode(threadGroup, null);

        var changes = SearchTreeDialog.replacementChanges(
                node, Pattern.compile(Pattern.quote("old")), "", false);

        assertEquals(2, SearchTreeDialog.applyChanges(changes));
        assertEquals(" ", threadGroup.getComment());
    }

    @Test
    void regexReplacementSupportsCaptureGroups() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("checkout-v12");
        JMeterTreeNode node = new JMeterTreeNode(threadGroup, null);

        var changes = SearchTreeDialog.replacementChanges(
                node, Pattern.compile("v(\\d+)"), "version-$1", true);

        assertEquals(1, SearchTreeDialog.applyChanges(changes));
        assertEquals("checkout-version-12", threadGroup.getName());
    }

    @Test
    void broadSearchDoesNotExposeInternalPropertyNames() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setComment("visible comment");

        assertTrue(threadGroup.getSearchableTokens().contains("visible comment"));
        assertFalse(threadGroup.getSearchableTokens().contains("TestElement.comments"));
    }

    @Test
    void addsRecordedExchangeTokensBeforeTestPlanIsFirstSaved() throws Exception {
        RecordedExchangeStore.Archive recording = RecordedExchangeStore.fromHar("""
                {
                  "log": {
                    "entries": [{
                      "startedDateTime": "2021-01-01T00:00:00Z",
                      "_webSocketMessages": [
                        {"type":"send","time":1609459200.1,"opcode":1,"data":"outgoing-message"},
                        {"type":"receive","time":1609459200.2,"opcode":1,"data":"incoming-message"},
                        {"type":"send","time":1609459200.3,"opcode":2,"data":"AP8="},
                        {"type":"receive","time":1609459200.4,"opcode":2,"data":"Af4="},
                        {"type":"send","time":1609459200.5,"opcode":2,"data":"/2FiY2RlZmdoaWprMTI3MzQ3MDIA"}
                      ],
                      "request": {
                        "method": "GET",
                        "url": "https://example.invalid/unsaved-search-value",
                        "httpVersion": "HTTP/1.1",
                        "headers": []
                      },
                      "response": {
                        "status": 200,
                        "statusText": "OK",
                        "httpVersion": "HTTP/1.1",
                        "headers": [],
                        "content": {"text": "unsaved-response-value"}
                      }
                    }]
                  }
                }
                """.getBytes(StandardCharsets.UTF_8), "recording.har");
        JmxArchiveEntryStore.registerBundle(
                recording.manifestEntryName(), recording.checksum(), recording.entries());

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, recording.manifestEntryName());
        threadGroup.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, recording.checksum());
        DummyTestElement sampler = new DummySampler();
        sampler.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, recording.exchangeIds().get(0));
        JMeterTreeNode threadGroupNode = new JMeterTreeNode(threadGroup, null);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, null);
        threadGroupNode.add(samplerNode);

        List<String> searchableTokens = new ArrayList<>();
        SearchTreeDialog.addRecordedExchangeTokens(searchableTokens, samplerNode, null);

        assertTrue(searchableTokens.stream().anyMatch(token -> token.contains("unsaved-search-value")));
        assertTrue(searchableTokens.stream().anyMatch(token -> token.contains("unsaved-response-value")));
        List<String> request = SearchTreeDialog.searchableTokens(samplerNode, null, Set.of(SearchArea.RECORDED_REQUEST));
        List<String> response = SearchTreeDialog.searchableTokens(samplerNode, null, Set.of(SearchArea.RECORDED_RESPONSE));
        assertTrue(searchableTokens.containsAll(List.of("outgoing-message", "incoming-message", "00 ff", "01 fe")));
        assertTrue(request.containsAll(List.of("outgoing-message", "00 ff")));
        assertTrue(new RawTextSearcher(true, "12734702").search(request));
        assertTrue(new RegexpSearcher(true, "1273[0-9]{4}").search(request));
        assertFalse(new RawTextSearcher(true, "12734702").search(response));
        assertFalse(request.contains("incoming-message"));
        assertFalse(request.contains("01 fe"));
        assertTrue(response.containsAll(List.of("incoming-message", "01 fe")));
        assertFalse(response.contains("outgoing-message"));
        assertFalse(response.contains("00 ff"));
        assertTrue(request.stream().anyMatch(token -> token.contains("unsaved-search-value")));
        assertFalse(request.stream().anyMatch(token -> token.contains("unsaved-response-value")));
        assertTrue(response.stream().anyMatch(token -> token.contains("unsaved-response-value")));
        assertFalse(response.stream().anyMatch(token -> token.contains("unsaved-search-value")));
        assertFalse(SearchTreeDialog.searchableTokens(samplerNode, null, Set.of(SearchArea.OTHER))
                .stream().anyMatch(token -> token.contains("unsaved-response-value")));
    }

    private static class DummyPreProcessor extends DummyTestElement implements PreProcessor {
        @Override
        public void process() {
            // NOOP
        }
    }

    private static class DummyPostProcessor extends DummyTestElement implements PostProcessor {
        @Override
        public void process() {
            // NOOP
        }
    }

    private static class DummyAssertion extends DummyTestElement implements Assertion {
        @Override
        public AssertionResult getResult(SampleResult response) {
            return new AssertionResult(getName());
        }
    }

    private static class DummyTimer extends DummyTestElement implements Timer {
        @Override
        public long delay() {
            return 0;
        }
    }

    private static class DummySampler extends DummyTestElement {
    }

    private abstract static class DummyTestElement extends AbstractTestElement {
        @Override
        public TestElementSchema getSchema() {
            return null;
        }
    }
}
