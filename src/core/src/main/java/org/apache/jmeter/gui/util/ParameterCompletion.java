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

import java.awt.AWTKeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.util.ParameterCompletionCatalog.Suggestion;

/** In-place ${...} completion for text fields, text areas and temporary table editors. */
public final class ParameterCompletion {
    private static final String INSTALLED = ParameterCompletion.class.getName();
    private static final String EXCLUDED = INSTALLED + ".excluded";
    private final JTextComponent editor;
    private final Supplier<List<Suggestion>> source;
    private final JPopupMenu popup;
    private final JList<Suggestion> list = new JList<>();
    private final Map<KeyStroke, Object> savedBindings = new LinkedHashMap<>();
    private Set<AWTKeyStroke> savedTraversalKeys;
    private List<Suggestion> variables = List.of();
    private boolean pending;
    private boolean refreshOnFocus;
    private boolean accepting;
    private int expressionStart = -1;

    /** Excludes a component subtree from subsequent completion installation. */
    public static void exclude(JComponent component) {
        component.putClientProperty(EXCLUDED, true);
    }

    /** Installs once, including on children added later (for example JTable cell editors). */
    public static void install(Component component) {
        install(component, () -> {
            GuiPackage gui = GuiPackage.getInstance();
            return gui == null ? List.of() : ParameterCompletionCatalog.variables(gui.getCurrentNode());
        });
    }

    static void install(Component component, Supplier<List<Suggestion>> source) {
        if (component instanceof JComponent jc) {
            if (Boolean.TRUE.equals(jc.getClientProperty(EXCLUDED)) || jc.getClientProperty(INSTALLED) != null) {
                return;
            }
            jc.putClientProperty(INSTALLED, true);
        }
        if (component instanceof JTable table) {
            // JTable otherwise forwards typing while retaining focus, bypassing the editor's completion bindings.
            table.setSurrendersFocusOnKeystroke(true);
        }
        if (component instanceof JTextComponent text) {
            new ParameterCompletion(text, source);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                install(child, source);
            }
            container.addContainerListener(new ContainerAdapter() {
                @Override
                public void componentAdded(ContainerEvent event) {
                    install(event.getChild(), source);
                }
            });
        }
    }

    ParameterCompletion(JTextComponent editor, Supplier<List<Suggestion>> source) {
        this(editor, source, new JPopupMenu());
    }

    ParameterCompletion(JTextComponent editor, Supplier<List<Suggestion>> source, JPopupMenu popup) {
        this.popup = popup;
        this.editor = editor;
        this.source = source;
        popup.setFocusable(false);
        list.setFocusable(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setFocusable(false);
        popup.add(scroll);
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                // Focus traversal consumes Tab before Swing key bindings can handle it.
                int forward = KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS;
                Set<AWTKeyStroke> traversalKeys = editor.getFocusTraversalKeys(forward);
                savedTraversalKeys = editor.areFocusTraversalKeysSet(forward) ? traversalKeys : null;
                Set<AWTKeyStroke> popupTraversalKeys = new HashSet<>(traversalKeys);
                popupTraversalKeys.remove(KeyStroke.getKeyStroke("TAB"));
                popupTraversalKeys.remove(KeyStroke.getKeyStroke("released TAB"));
                editor.setFocusTraversalKeys(forward, popupTraversalKeys);
                bind("ENTER", () -> accept(list.getSelectedValue()));
                bind("TAB", () -> accept(list.getSelectedValue()));
                bind("UP", () -> move(-1));
                bind("DOWN", () -> move(1));
                bind("ESCAPE", () -> popup.setVisible(false));
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                restoreBindings();
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent event) {
                restoreBindings();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (index >= 0 && list.getCellBounds(index, index).contains(event.getPoint())) {
                    accept(list.getModel().getElementAt(index));
                }
            }
        });
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                documentChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                documentChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                documentChanged();
            }
        };
        editor.getDocument().addDocumentListener(listener);
        editor.addPropertyChangeListener("document", event -> {
            ((javax.swing.text.Document) event.getOldValue()).removeDocumentListener(listener);
            ((javax.swing.text.Document) event.getNewValue()).addDocumentListener(listener);
            popup.setVisible(false);
            expressionStart = -1;
            refreshOnFocus = false;
            pending = false;
        });
        editor.addCaretListener(event -> {
            if (popup.isVisible()) {
                schedule();
            }
        });
        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                expressionStart = -1;
                // The first table-edit keystroke can update the document before focus transfer completes.
                if (refreshOnFocus) {
                    refreshOnFocus = false;
                    schedule();
                }
            }

            @Override
            public void focusLost(FocusEvent event) {
                popup.setVisible(false);
                expressionStart = -1;
                refreshOnFocus = false;
                pending = false;
            }
        });
        editor.addHierarchyListener(event -> {
            if (!editor.isShowing()) {
                popup.setVisible(false);
                refreshOnFocus = false;
                pending = false;
            }
        });
    }

    private void documentChanged() {
        if (editor.isFocusOwner()) {
            schedule();
        } else {
            // Loading a value or clicking into an expression must not arm completion.
            // JTable forwards typed keys to the editor before the focus event is delivered.
            refreshOnFocus = EventQueue.getCurrentEvent() instanceof KeyEvent key
                    && key.getID() == KeyEvent.KEY_TYPED
                    && (key.getSource() == editor
                        || key.getSource() == SwingUtilities.getAncestorOfClass(JTable.class, editor));
        }
    }

    private void schedule() {
        if (pending || accepting) {
            return;
        }
        pending = true;
        SwingUtilities.invokeLater(() -> {
            if (!pending) {
                return;
            }
            pending = false;
            refresh();
        });
    }

    private void refresh() {
        if (!editor.isFocusOwner() || !editor.isShowing() || !editor.isEditable() || !editor.isEnabled()
                || editor.getSelectionStart() != editor.getSelectionEnd()) {
            popup.setVisible(false);
            return;
        }
        Expression expression = expression(editor.getText(), editor.getCaretPosition());
        if (expression == null) {
            popup.setVisible(false);
            expressionStart = -1;
            return;
        }
        if (expressionStart != expression.start()) {
            variables = source.get();
            expressionStart = expression.start();
        }
        List<Suggestion> candidates = suggestions(expression.prefix(), variables,
                expression.prefix().startsWith("_") ? ParameterCompletionCatalog.functions() : List.of());
        if (candidates.isEmpty()) {
            popup.setVisible(false);
            return;
        }
        Suggestion previous = list.getSelectedValue();
        list.setListData(candidates.toArray(Suggestion[]::new));
        list.setSelectedIndex(Math.max(0, candidates.indexOf(previous)));
        list.setVisibleRowCount(Math.min(9, candidates.size()));
        Dimension preferred = list.getPreferredScrollableViewportSize();
        ((JScrollPane) list.getParent().getParent()).setPreferredSize(
                new Dimension(Math.min(600, Math.max(240, preferred.width + 24)), preferred.height + 4));
        try {
            Rectangle2D caret = editor.modelToView2D(editor.getCaretPosition());
            if (caret != null) {
                if (popup.isVisible()) {
                    popup.pack();
                } else {
                    popup.show(editor, (int) caret.getX(), (int) caret.getMaxY());
                }
            }
        } catch (BadLocationException ignored) {
            popup.setVisible(false);
        }
    }

    static List<Suggestion> suggestions(String prefix, List<Suggestion> variables, List<Suggestion> functions) {
        return java.util.stream.Stream.concat(variables.stream(),
                prefix.startsWith("_") ? functions.stream() : java.util.stream.Stream.empty())
                .filter(value -> value.name().startsWith(prefix))
                .sorted(java.util.Comparator.comparing(Suggestion::name))
                .toList();
    }

    record Expression(int start, int end, String prefix) {
    }

    static Expression expression(String text, int caret) {
        int start = text.lastIndexOf("${", caret - 1);
        if (start < 0 || caret < start + 2) {
            return null;
        }
        int slashes = 0;
        for (int i = start - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            slashes++;
        }
        if (slashes % 2 != 0) {
            return null;
        }
        String prefix = text.substring(start + 2, caret);
        if (prefix.chars().anyMatch(c -> "${}(),\n\r".indexOf(c) >= 0)) {
            return null;
        }
        int end = caret;
        for (int i = caret; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '}') {
                end = i + 1;
                break;
            }
            // Only reuse a brace reached through a variable-name suffix. A later
            // JSON/object brace must not consume quotes, values or surrounding text.
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') {
                break;
            }
        }
        return new Expression(start, end, prefix);
    }

    void accept(Suggestion suggestion) {
        Expression expression = expression(editor.getText(), editor.getCaretPosition());
        popup.setVisible(false);
        if (suggestion == null || expression == null) {
            return;
        }
        accepting = true;
        try {
            editor.select(expression.start(), expression.end());
            editor.replaceSelection(suggestion.replacement());
            int start = expression.start() + suggestion.selectionStart();
            editor.select(start, start + suggestion.selectionLength());
        } finally {
            accepting = false;
            expressionStart = -1;
        }
    }

    private void move(int delta) {
        int size = list.getModel().getSize();
        list.setSelectedIndex(Math.floorMod(list.getSelectedIndex() + delta, size));
        list.ensureIndexIsVisible(list.getSelectedIndex());
    }

    private void bind(String key, Runnable action) {
        KeyStroke stroke = KeyStroke.getKeyStroke(key);
        String name = INSTALLED + key;
        KeyStroke[] localKeys = editor.getInputMap().keys();
        savedBindings.put(stroke, localKeys != null && Arrays.asList(localKeys).contains(stroke)
                ? editor.getInputMap().get(stroke) : null);
        editor.getInputMap().put(stroke, name);
        editor.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private void restoreBindings() {
        if (!savedBindings.isEmpty()) {
            editor.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, savedTraversalKeys);
            savedTraversalKeys = null;
        }
        savedBindings.forEach((key, value) -> {
            if (value == null) {
                editor.getInputMap().remove(key);
            } else {
                editor.getInputMap().put(key, value);
            }
            editor.getActionMap().remove(INSTALLED + key.toString().replace("pressed ", ""));
        });
        savedBindings.clear();
    }
}
