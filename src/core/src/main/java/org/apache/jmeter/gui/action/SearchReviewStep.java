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

import java.awt.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.tree.TreeNode;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.RemovableRow;
import org.apache.jmeter.gui.ReplaceableField;
import org.apache.jmeter.gui.RowField;
import org.apache.jmeter.gui.SearchArea;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.EditorMatchHighlighter;
import org.apache.jmeter.gui.util.ReviewStep;
import org.apache.jmeter.util.JMeterUtils;

/** Stable snapshots: editor navigation can rebuild the underlying argument and header objects. */
final class SearchReviewStep implements ReviewStep {
    enum Kind { REPLACE, ROW, ELEMENT }

    private final JMeterTreeNode node;
    private final TreeNode root;
    private final Kind kind;
    private final String field;
    private int row;
    private String expected;
    private int start;
    private int end;
    private final String replacement;
    private final String original;
    private final RowGroup rows;
    private State state = State.PENDING;

    private static final class RowGroup {
        private final JMeterTreeNode node;
        private final SearchArea area;
        private final List<List<String>> expected;

        private RowGroup(JMeterTreeNode node, SearchArea area) {
            this.node = node;
            this.area = area;
            expected = new ArrayList<>(live().stream().map(RemovableRow::tokens).toList());
        }

        private List<RemovableRow> live() {
            return RemovableRow.forElement(node.getTestElement()).stream().filter(row -> row.area() == area).toList();
        }

        private boolean current() {
            return expected.equals(live().stream().map(RemovableRow::tokens).toList());
        }
    }

    private SearchReviewStep(JMeterTreeNode node, Kind kind, String field, int row, String expected,
            int start, int end, String replacement, RowGroup rows) {
        this.node = node;
        root = node.getRoot();
        this.kind = kind;
        this.field = field;
        this.row = row;
        this.expected = expected;
        this.start = start;
        this.end = end;
        this.replacement = replacement;
        original = expected;
        this.rows = rows;
    }

    static List<SearchReviewStep> replacements(List<JMeterTreeNode> nodes, Pattern pattern,
            String replacement, boolean regex, Set<SearchArea> areas, RowField column) {
        List<SearchReviewStep> steps = new ArrayList<>();
        for (JMeterTreeNode node : nodes) {
            Map<String, Integer> indexes = new HashMap<>();
            for (ReplaceableField field : SearchTreeDialog.replaceableFields(node)) {
                int row = indexes.merge(field.name(), 1, Integer::sum) - 1;
                if (!areas.contains(field.area()) || (column != RowField.ALL
                        && (field.area() == SearchArea.PARAMETERS || field.area() == SearchArea.HEADERS)
                        && field.rowField() != column) || field.value().isEmpty()) {
                    continue;
                }
                Matcher matcher = pattern.matcher(field.value());
                StringBuilder expanded = new StringBuilder();
                int previousEnd = 0;
                while (matcher.find()) {
                    int prefixLength = expanded.length() + matcher.start() - previousEnd;
                    matcher.appendReplacement(expanded, regex ? replacement : Matcher.quoteReplacement(replacement));
                    String value = expanded.substring(prefixLength);
                    steps.add(new SearchReviewStep(node, Kind.REPLACE, field.name(), row,
                            field.value(), matcher.start(), matcher.end(), value, null));
                    previousEnd = matcher.end();
                }
            }
        }
        return steps;
    }

    static List<SearchReviewStep> rows(List<SearchTreeDialog.RowMatch> matches, Pattern pattern, RowField column) {
        List<SearchReviewStep> steps = new ArrayList<>();
        Map<JMeterTreeNode, Map<SearchArea, RowGroup>> groups = new HashMap<>();
        for (var match : matches) {
            RowGroup group = groups.computeIfAbsent(match.node(), ignored -> new HashMap<>())
                    .computeIfAbsent(match.row().area(), area -> new RowGroup(match.node(), area));
            List<String> tokens = match.row().tokens();
            for (int c = 0; c < tokens.size(); c++) {
                if (column == RowField.NAME && c != 0 || column == RowField.VALUE && c != 1) {
                    continue;
                }
                Matcher matcher = pattern.matcher(tokens.get(c));
                if (matcher.find()) {
                    String field = (match.row().area() == SearchArea.HEADERS ? "Header " : "Parameter ")
                            + (c == 0 ? "name" : c == 1 ? "value" : "description");
                    steps.add(new SearchReviewStep(match.node(), Kind.ROW, field, match.row().number() - 1,
                            tokens.get(c), matcher.start(), matcher.end(), "", group));
                    break;
                }
            }
        }
        return steps;
    }

    static List<SearchReviewStep> elements(List<JMeterTreeNode> nodes, Pattern pattern,
            Set<SearchArea> areas, RowField column) {
        List<SearchReviewStep> steps = new ArrayList<>();
        for (JMeterTreeNode node : nodes) {
            List<SearchReviewStep> matches = pattern == null ? List.of()
                    : replacements(List.of(node), pattern, "", false, areas, column);
            if (matches.isEmpty()) {
                steps.add(new SearchReviewStep(node, Kind.ELEMENT, "Name", 0,
                        node.getName(), 0, node.getName().length(), "", null));
            } else {
                SearchReviewStep first = matches.get(0);
                steps.add(new SearchReviewStep(node, Kind.ELEMENT, first.field, first.row,
                        first.expected, first.start, first.end, "", null));
            }
        }
        return steps;
    }

    private ReplaceableField resolve() {
        return SearchTreeDialog.replaceableFields(node).stream().filter(value -> value.name().equals(field))
                .skip(row).findFirst().orElse(null);
    }

    @Override
    public boolean current() {
        if (node.getParent() == null || node.getRoot() != root) {
            return false;
        }
        if (kind == Kind.ROW) {
            return row >= 0 && row < rows.expected.size() && rows.current();
        }
        ReplaceableField resolved = resolve();
        return resolved != null && expected.equals(resolved.value());
    }

    boolean apply(GuiPackage gui, List<SearchReviewStep> steps) {
        if (state != State.PENDING || !current()) {
            if (state == State.PENDING) {
                markStale();
            }
            return false;
        }
        if (kind != Kind.REPLACE && !node.getTestElement().canRemove()) {
            return false;
        }
        if (kind == Kind.ELEMENT) {
            // Rejecting a child must not be bypassed by accepting a containing element.
            if (steps.stream().anyMatch(step -> step.state == State.REJECTED && step.node != node
                    && node.isNodeDescendant(step.node))) {
                return false;
            }
            if (!SearchTreeDialog.removeMatchingNode(gui, node)) {
                return false;
            }
        } else if (kind == Kind.ROW) {
            if (!rows.live().get(row).remove()) {
                markStale();
                return false;
            }
            rows.expected.remove(row);
            for (SearchReviewStep other : steps) {
                if (other != this && other.rows == rows && other.row > row) {
                    other.row--;
                }
            }
        } else {
            String updated = expected.substring(0, start) + replacement + expected.substring(end);
            resolve().setValue(updated);
            int delta = replacement.length() - (end - start);
            for (SearchReviewStep other : steps) {
                if (other != this && other.kind == Kind.REPLACE && other.node == node
                        && other.field.equals(field) && other.row == row && other.expected.equals(expected)) {
                    other.expected = updated;
                    if (other.start >= end) {
                        other.start += delta;
                        other.end += delta;
                    } else if (other.end > start) {
                        other.markStale();
                    }
                }
            }
            expected = updated;
            end = start + replacement.length();
        }
        state = State.ACCEPTED;
        return true;
    }

    static int apply(GuiPackage gui, List<SearchReviewStep> requested, List<SearchReviewStep> steps) {
        gui.updateCurrentNode();
        ResetSearchCommand.clearSearchMarks(gui);
        return SearchTreeDialog.editWithUndo(gui, JMeterUtils.getResString("correlation_review_step_by_step"),
                count -> count > 0, () -> {
                    int count = 0;
                    // Children first prevents accepted parents invalidating pending child reviews.
                    for (SearchReviewStep step : requested.stream()
                            .sorted(java.util.Comparator.comparingInt((SearchReviewStep step) -> step.node.getLevel()).reversed()).toList()) {
                        if (step.apply(gui, steps)) {
                            if (step.node.getParent() != null) {
                                gui.getTreeModel().nodeChanged(step.node);
                            }
                            count++;
                        }
                    }
                    return count;
                });
    }

    @Override
    public JMeterTreeNode node() { return node; }

    @Override
    public State state() { return state; }

    @Override
    public void reject() { state = State.REJECTED; }

    @Override
    public void markStale() { state = State.STALE; }

    @Override
    public String description() {
        if (kind == Kind.ELEMENT) {
            return JMeterUtils.getResString("review_delete_element") + " " + node.getName()
                    + "\n" + JMeterUtils.getResString("review_delete_subtree");
        }
        if (kind == Kind.ROW) {
            return JMeterUtils.getResString("review_delete_row") + " — " + node.getName() + "\n"
                    + (state == State.ACCEPTED ? original : String.join(" = ", rows.expected.get(row)));
        }
        return node.getName() + " — " + field + "\n" + original + " → " + replacement;
    }

    @Override
    public Runnable highlight(Component editor) {
        if (state == State.ACCEPTED && kind != Kind.REPLACE) {
            return null;
        }
        Runnable result = EditorMatchHighlighter.field(editor, field, row, expected, start, end);
        return result == null && kind == Kind.ELEMENT ? () -> { } : result;
    }
}
