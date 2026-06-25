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

package org.apache.jmeter.gui.util;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;

import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JFactory;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;

/**
 * Search toolbar associated to {@link JSyntaxTextArea}
 * @since 5.0
 */
public final class JSyntaxSearchToolBar implements ActionListener {
    public static final Color LIGHT_RED = new Color(0xFF, 0x80, 0x80);

    public static final String FIND_ACTION = "Find";

    public static final String DIFF_ACTION = "Diff";

    private static final Color DIFF_RECORDED_LINE = new Color(0xFF, 0xE3, 0xE3);

    private static final Color DIFF_REPLAYED_LINE = new Color(0xE2, 0xF6, 0xE7);

    private static final Color DIFF_CHARACTER = new Color(0xFF, 0xF1, 0x8A);

    private JToolBar toolBar;

    private JTextField searchField;

    private JCheckBox regexCB;

    private JCheckBox matchCaseCB;

    private JButton diffButton;

    private Supplier<DiffContent> diffContentSupplier;

    /**
     * The component where we Search
     */
    private final JSyntaxTextArea dataField;

    /**
     * @param dataField {@link JSyntaxTextArea} to use for searching
     */
    public JSyntaxSearchToolBar(JSyntaxTextArea dataField) {
        this.dataField = dataField;
        init();
    }

    private void init() {
        this.searchField = new JTextField(30);
        JFactory.small(searchField);
        final JButton findButton = new JButton(JMeterUtils.getResString("search_text_button_find"));
        JFactory.small(findButton);
        findButton.setActionCommand(FIND_ACTION);
        findButton.addActionListener(this);
        regexCB = new JCheckBox(JMeterUtils.getResString("search_text_chkbox_regexp"));
        JFactory.small(regexCB);

        matchCaseCB = new JCheckBox(JMeterUtils.getResString("search_text_chkbox_case"));
        JFactory.small(matchCaseCB);

        this.toolBar = new JToolBar();
        toolBar.setFloatable(false);
        JFactory.small(toolBar);
        toolBar.add(searchField);
        toolBar.add(findButton);
        toolBar.add(matchCaseCB);
        toolBar.add(regexCB);
        diffButton = new JButton(JMeterUtils.getResString("search_text_button_diff")); // $NON-NLS-1$
        JFactory.small(diffButton);
        diffButton.setActionCommand(DIFF_ACTION);
        diffButton.addActionListener(this);
        diffButton.setVisible(false);
        diffButton.setEnabled(false);
        toolBar.add(diffButton);
        searchField.addActionListener(e -> findButton.doClick(0));
    }

    public JToolBar getToolBar() {
        return toolBar;
    }

    public void setDiffContentSupplier(Supplier<DiffContent> diffContentSupplier) {
        this.diffContentSupplier = diffContentSupplier;
        setDiffButtonVisible(diffContentSupplier != null);
    }

    public void setDiffButtonVisible(boolean visible) {
        if (diffButton != null) {
            diffButton.setVisible(visible);
            diffButton.setEnabled(visible);
        }
    }

    @Override
    public void actionPerformed(ActionEvent evt) {
        if (DIFF_ACTION.equals(evt.getActionCommand())) {
            showDiff();
            return;
        }

        String text = searchField.getText();
        toggleSearchField(searchField, true);

        if (!text.isEmpty()) {
            SearchContext context = createSearchContext(
                    text, true, matchCaseCB.isSelected(), regexCB.isSelected());
            boolean found = SearchEngine.find(dataField, context).wasFound();
            toggleSearchField(searchField, found);
            if(!found) {
                dataField.setCaretPosition(0);
            }
        }
    }

    private void showDiff() {
        if (diffContentSupplier == null) {
            return;
        }
        DiffContent diffContent = diffContentSupplier.get();
        if (diffContent == null) {
            return;
        }
        DiffView diffView = createDiffView(diffContent.recorded(), diffContent.replayed());
        dataField.setText(diffView.text());
        dataField.setSyntaxEditingStyle("text/plain"); // $NON-NLS-1$
        dataField.removeAllLineHighlights();
        dataField.getHighlighter().removeAllHighlights();
        applyDiffHighlights(diffView);
        dataField.setCaretPosition(0);
    }

    private void applyDiffHighlights(DiffView diffView) {
        for (LineHighlight highlight : diffView.lineHighlights()) {
            try {
                dataField.addLineHighlight(highlight.line(), highlight.color());
            } catch (BadLocationException ignored) {
                // The diff view changed between render and highlight; ignore the stale range.
            }
        }
        DefaultHighlighter.DefaultHighlightPainter painter =
                new DefaultHighlighter.DefaultHighlightPainter(DIFF_CHARACTER);
        for (TextHighlight highlight : diffView.textHighlights()) {
            try {
                dataField.getHighlighter().addHighlight(highlight.start(), highlight.end(), painter);
            } catch (BadLocationException ignored) {
                // The diff view changed between render and highlight; ignore the stale range.
            }
        }
    }

    void toggleSearchField(JTextField textToFindField, boolean matchFound) {
        if(!matchFound) {
            textToFindField.setBackground(LIGHT_RED);
            textToFindField.setForeground(Color.WHITE);
        } else {
            textToFindField.setBackground(Color.WHITE);
            textToFindField.setForeground(Color.BLACK);
        }
    }

    private static SearchContext createSearchContext(String text, boolean forward, boolean matchCase,
            boolean isRegex) {
        SearchContext context = new SearchContext();
        context.setSearchFor(text);
        context.setMatchCase(matchCase);
        context.setRegularExpression(isRegex);
        context.setSearchForward(forward);
        context.setMarkAll(false);
        context.setSearchSelectionOnly(false);
        context.setWholeWord(false);
        return context;
    }

    static DiffView createDiffView(String recorded, String replayed) {
        List<String> recordedLines = splitLines(recorded);
        List<String> replayedLines = splitLines(replayed);
        int[][] lcs = lcs(recordedLines, replayedLines);
        List<DiffOp> ops = diffOperations(recordedLines, replayedLines, lcs);

        StringBuilder text = new StringBuilder();
        List<LineHighlight> lineHighlights = new ArrayList<>();
        List<TextHighlight> textHighlights = new ArrayList<>();
        text.append("Recorded vs Replayed Diff\n"); // $NON-NLS-1$
        text.append("- recorded\n"); // $NON-NLS-1$
        text.append("+ replayed\n\n"); // $NON-NLS-1$
        int lineNumber = 4;
        int index = 0;
        while (index < ops.size()) {
            DiffOp op = ops.get(index);
            if (op.type() == DiffType.EQUAL) {
                appendDiffLine(text, lineNumber++, "  ", op.text(), null, lineHighlights); // $NON-NLS-1$
                index++;
                continue;
            }
            if (op.type() == DiffType.DELETE) {
                List<String> deleted = new ArrayList<>();
                while (index < ops.size() && ops.get(index).type() == DiffType.DELETE) {
                    deleted.add(ops.get(index++).text());
                }
                List<String> inserted = new ArrayList<>();
                while (index < ops.size() && ops.get(index).type() == DiffType.INSERT) {
                    inserted.add(ops.get(index++).text());
                }
                lineNumber = appendChangedLines(text, lineNumber, deleted, inserted, lineHighlights, textHighlights);
                continue;
            }
            if (op.type() == DiffType.INSERT) {
                int start = text.length();
                appendDiffLine(text, lineNumber, "+ ", op.text(), DIFF_REPLAYED_LINE, lineHighlights); // $NON-NLS-1$
                addWholeLineTextHighlight(textHighlights, start + 2, op.text());
                lineNumber++;
            }
            index++;
        }
        return new DiffView(text.toString(), lineHighlights, textHighlights);
    }

    private static int appendChangedLines(StringBuilder text, int lineNumber, List<String> deleted,
            List<String> inserted, List<LineHighlight> lineHighlights, List<TextHighlight> textHighlights) {
        int pairCount = Math.min(deleted.size(), inserted.size());
        for (int i = 0; i < pairCount; i++) {
            String recordedLine = deleted.get(i);
            String replayedLine = inserted.get(i);
            ChangedRange changedRange = changedRange(recordedLine, replayedLine);
            int recordedStart = text.length();
            appendDiffLine(text, lineNumber++, "- ", recordedLine, DIFF_RECORDED_LINE, lineHighlights); // $NON-NLS-1$
            addTextHighlight(textHighlights, recordedStart + 2 + changedRange.recordedStart(),
                    recordedStart + 2 + changedRange.recordedEnd());

            int replayedStart = text.length();
            appendDiffLine(text, lineNumber++, "+ ", replayedLine, DIFF_REPLAYED_LINE, lineHighlights); // $NON-NLS-1$
            addTextHighlight(textHighlights, replayedStart + 2 + changedRange.replayedStart(),
                    replayedStart + 2 + changedRange.replayedEnd());
        }
        for (int i = pairCount; i < deleted.size(); i++) {
            int start = text.length();
            appendDiffLine(text, lineNumber++, "- ", deleted.get(i), DIFF_RECORDED_LINE, lineHighlights); // $NON-NLS-1$
            addWholeLineTextHighlight(textHighlights, start + 2, deleted.get(i));
        }
        for (int i = pairCount; i < inserted.size(); i++) {
            int start = text.length();
            appendDiffLine(text, lineNumber++, "+ ", inserted.get(i), DIFF_REPLAYED_LINE, lineHighlights); // $NON-NLS-1$
            addWholeLineTextHighlight(textHighlights, start + 2, inserted.get(i));
        }
        return lineNumber;
    }

    private static void appendDiffLine(StringBuilder text, int lineNumber, String prefix, String line, Color color,
            List<LineHighlight> lineHighlights) {
        if (color != null) {
            lineHighlights.add(new LineHighlight(lineNumber, color));
        }
        text.append(prefix).append(line).append('\n');
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        String[] lines = text.split("\\R", -1); // $NON-NLS-1$
        int length = lines.length;
        if (length > 0 && lines[length - 1].isEmpty()) {
            length--;
        }
        List<String> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            result.add(lines[i]);
        }
        return result;
    }

    private static int[][] lcs(List<String> recorded, List<String> replayed) {
        int[][] lcs = new int[recorded.size() + 1][replayed.size() + 1];
        for (int i = recorded.size() - 1; i >= 0; i--) {
            for (int j = replayed.size() - 1; j >= 0; j--) {
                if (recorded.get(i).equals(replayed.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }
        return lcs;
    }

    private static List<DiffOp> diffOperations(List<String> recorded, List<String> replayed, int[][] lcs) {
        List<DiffOp> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < recorded.size() && j < replayed.size()) {
            if (recorded.get(i).equals(replayed.get(j))) {
                ops.add(new DiffOp(DiffType.EQUAL, recorded.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new DiffOp(DiffType.DELETE, recorded.get(i++)));
            } else {
                ops.add(new DiffOp(DiffType.INSERT, replayed.get(j++)));
            }
        }
        while (i < recorded.size()) {
            ops.add(new DiffOp(DiffType.DELETE, recorded.get(i++)));
        }
        while (j < replayed.size()) {
            ops.add(new DiffOp(DiffType.INSERT, replayed.get(j++)));
        }
        return ops;
    }

    private static ChangedRange changedRange(String recorded, String replayed) {
        int prefix = 0;
        int maxPrefix = Math.min(recorded.length(), replayed.length());
        while (prefix < maxPrefix && recorded.charAt(prefix) == replayed.charAt(prefix)) {
            prefix++;
        }
        int recordedSuffix = recorded.length();
        int replayedSuffix = replayed.length();
        while (recordedSuffix > prefix && replayedSuffix > prefix
                && recorded.charAt(recordedSuffix - 1) == replayed.charAt(replayedSuffix - 1)) {
            recordedSuffix--;
            replayedSuffix--;
        }
        return new ChangedRange(prefix, recordedSuffix, prefix, replayedSuffix);
    }

    private static void addWholeLineTextHighlight(List<TextHighlight> textHighlights, int start, String line) {
        addTextHighlight(textHighlights, start, start + line.length());
    }

    private static void addTextHighlight(List<TextHighlight> textHighlights, int start, int end) {
        if (end > start) {
            textHighlights.add(new TextHighlight(start, end));
        }
    }

    public record DiffContent(String recorded, String replayed) {}

    record DiffView(String text, List<LineHighlight> lineHighlights, List<TextHighlight> textHighlights) {}

    record LineHighlight(int line, Color color) {}

    record TextHighlight(int start, int end) {}

    private record DiffOp(DiffType type, String text) {}

    private record ChangedRange(int recordedStart, int recordedEnd, int replayedStart, int replayedEnd) {}

    private enum DiffType {
        EQUAL,
        DELETE,
        INSERT
    }
}
