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

import java.awt.Component;
import java.awt.Container;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.JTextComponent;

/** Reveals an editor and highlights an exact range even while a review dialog owns focus. */
public final class EditorMatchHighlighter {
    private EditorMatchHighlighter() { }

    public static <T extends Component> T find(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public static Runnable field(Component root, String name, int row, String expected, int start, int end) {
        Runnable explicit = providedField(root, name, row, expected, start, end);
        if (explicit != null) {
            return explicit;
        }
        return matchingText(root, expected, start, end);
    }

    private static Runnable providedField(Component root, String name, int row, String expected, int start, int end) {
        if (root instanceof ReviewableEditor editor) {
            Runnable result = editor.highlightReviewField(name, row, expected, start, end);
            if (result != null) {
                return result;
            }
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                Runnable result = providedField(child, name, row, expected, start, end);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static Runnable matchingText(Component root, String expected, int start, int end) {
        java.util.List<JTextComponent> candidates = new java.util.ArrayList<>();
        matchingTextComponents(root, expected, candidates);
        // Ambiguous identical values must never highlight a different field from the one being edited.
        return candidates.size() == 1 ? text(candidates.get(0), expected, start, end) : null;
    }

    private static void matchingTextComponents(Component root, String expected, java.util.List<JTextComponent> result) {
        if (root instanceof JTextComponent text && text.isEditable() && expected.equals(text.getText())) {
            result.add(text);
        } else if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                matchingTextComponents(child, expected, result);
            }
        }
    }

    public static void reveal(Component component) {
        if (component.getParent() != null) {
            reveal(component.getParent());
            if (component.getParent() instanceof JTabbedPane tabs && tabs.indexOfComponent(component) >= 0) {
                tabs.setSelectedComponent(component);
            }
        }
        if (component instanceof JComponent target) {
            target.scrollRectToVisible(target.getVisibleRect());
        }
    }

    public static Runnable text(Component scope, String expected, int start, int end) {
        JTextComponent text = find(scope, JTextComponent.class);
        if (text == null || !expected.equals(text.getText()) || start < 0 || end < start || end > expected.length()) {
            return null;
        }
        reveal(text);
        try {
            Object tag = text.getHighlighter().addHighlight(start, end,
                    new DefaultHighlighter.DefaultHighlightPainter(java.awt.Color.YELLOW));
            text.setCaretPosition(end);
            text.moveCaretPosition(start);
            scrollToMatch(text, start);
            // Newly selected tabs can be laid out only on the next event turn.
            SwingUtilities.invokeLater(() -> {
                for (var highlight : text.getHighlighter().getHighlights()) {
                    if (highlight == tag) {
                        scrollToMatch(text, start);
                        break;
                    }
                }
            });
            return () -> text.getHighlighter().removeHighlight(tag);
        } catch (BadLocationException ex) {
            return null;
        }
    }

    private static void scrollToMatch(JTextComponent text, int start) {
        try {
            var bounds = text.modelToView2D(start);
            if (bounds != null) {
                text.scrollRectToVisible(bounds.getBounds());
            }
        } catch (BadLocationException ignored) {
            // The editor may have been refreshed or disposed since the tab was selected.
        }
    }

    public static Runnable table(Component scope, int row, int column, String expected, int start, int end) {
        JTable table = find(scope, JTable.class);
        if (table == null || row < 0 || row >= table.getModel().getRowCount()) {
            return null;
        }
        row = table.convertRowIndexToView(row);
        column = table.convertColumnIndexToView(column);
        if (row < 0 || column < 0 || !expected.equals(table.getValueAt(row, column))) {
            return null;
        }
        reveal(table);
        table.changeSelection(row, column, false, false);
        table.scrollRectToVisible(table.getCellRect(row, column, true));
        if (!table.editCellAt(row, column)) {
            return null;
        }
        Runnable clear = text(table.getEditorComponent(), expected, start, end);
        if (clear == null) {
            table.getCellEditor().cancelCellEditing();
            return null;
        }
        return () -> {
            clear.run();
            if (table.isEditing()) {
                table.getCellEditor().cancelCellEditing();
            }
        };
    }
}
