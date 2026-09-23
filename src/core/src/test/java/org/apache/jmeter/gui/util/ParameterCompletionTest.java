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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AWTKeyStroke;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractAction;
import javax.swing.InputMap;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.PlainDocument;

import org.apache.jmeter.gui.util.ParameterCompletionCatalog.Suggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ParameterCompletionTest {
    @Test
    void filtersCaseSensitivelyAndOnlyOffersFunctionsAfterUnderscore() {
        List<Suggestion> variables = List.of(Suggestion.variable("username"), Suggestion.variable("userid"),
                Suggestion.variable("password"), Suggestion.variable("User"));
        List<Suggestion> functions = List.of(Suggestion.function("__time", true));
        assertEquals(4, ParameterCompletion.suggestions("", variables, functions).size());
        assertEquals(List.of("userid", "username"), ParameterCompletion.suggestions("u", variables, functions)
                .stream().map(Suggestion::name).toList());
        assertEquals(functions, ParameterCompletion.suggestions("_", variables, functions));
        assertTrue(ParameterCompletion.suggestions("__x", variables, functions).isEmpty());
    }

    @Test
    void recognizesOnlyOpenUnescapedExpressionsIncludingNestedFunctionArguments() {
        assertEquals("u", ParameterCompletion.expression("${u", 3).prefix());
        assertNull(ParameterCompletion.expression("${user}", 7));
        assertNull(ParameterCompletion.expression("${__time(", 9));
        assertNull(ParameterCompletion.expression("\\${u", 4));
        assertEquals("u", ParameterCompletion.expression("\\\\${u", 5).prefix());
        String nested = "${__P(key,${u";
        assertEquals(10, ParameterCompletion.expression(nested, nested.length()).start());
        assertNull(ParameterCompletion.expression("plain text", 5));
        assertNull(ParameterCompletion.expression("${u", 1));
    }

    @Test
    void insertsInMiddleAndReplacesExistingClosingBraceAndSuffix() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField editor = new JTextField("/users/${user}/details");
            editor.setCaretPosition(10);
            ParameterCompletion helper = new ParameterCompletion(editor, List::of);
            helper.accept(Suggestion.variable("userid"));
            assertEquals("/users/${userid}/details", editor.getText());
            assertEquals(16, editor.getCaretPosition());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ",\"|\":\"\"}}",
        "{\"outer\":{\"|\":\"\"}}",
        "{\"outer\":{\"token\":\"|\"}}",
        "{\"outer\":{\"token\":\"prefix | suffix\"}}",
        "{\"items\":[\"|\"]}",
        "<root value='|' other='}'/>",
        "Hello | text ends here }"
    })
    void preservesSurroundingSyntaxWhenInsertingAnUnclosedExpression(String template) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            int insertion = template.indexOf('|');
            for (String trigger : List.of("${", "${cred")) {
                JTextArea editor = new JTextArea(template.replace("|", trigger));
                editor.setCaretPosition(insertion + trigger.length());
                new ParameterCompletion(editor, List::of).accept(Suggestion.variable("credential"));
                assertEquals(template.replace("|", "${credential}"), editor.getText());
                assertEquals(insertion + "${credential}".length(), editor.getCaretPosition());
            }
        });
    }

    @Test
    void reusesTheVariableClosingBraceInsideJson() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String template = "{\"outer\":{\"${cre|dential}\":\"\"}}";
            JTextArea editor = new JTextArea(template.replace("|", ""));
            editor.setCaretPosition(template.indexOf('|'));
            new ParameterCompletion(editor, List::of).accept(Suggestion.variable("username"));
            assertEquals("{\"outer\":{\"${username}\":\"\"}}", editor.getText());
        });
    }

    @Test
    void preservesOtherExpressionsAndMultilineContent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea editor = new JTextArea("before\n${u and ${password}\nafter");
            editor.setCaretPosition(10);
            new ParameterCompletion(editor, List::of).accept(Suggestion.variable("username"));
            assertEquals("before\n${username} and ${password}\nafter", editor.getText());
        });
    }

    @Test
    void selectsUsableIndexAndPositionsFunctionArguments() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField editor = new JTextField("${product");
            ParameterCompletion helper = new ParameterCompletion(editor, List::of);
            editor.setCaretPosition(editor.getText().length());
            helper.accept(Suggestion.indexed("productIds"));
            assertEquals("${productIds_1}", editor.getText());
            assertEquals("1", editor.getSelectedText());
            editor.replaceSelection("3");
            assertEquals("${productIds_3}", editor.getText());
            editor.setText("${_t}");
            editor.setCaretPosition(4);
            helper.accept(Suggestion.function("__time", true));
            assertEquals("${__time()}", editor.getText());
            assertEquals(9, editor.getCaretPosition());
        });
    }

    @Test
    void installsOnceAndHandlesDynamicallyAddedEditorsAndDocumentReplacement() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel panel = new JPanel();
            ParameterCompletion.install(panel);
            JTextField editor = new JTextField();
            panel.add(editor);
            int listeners = editor.getFocusListeners().length;
            ParameterCompletion.install(panel);
            ParameterCompletion.install(editor);
            assertEquals(listeners, editor.getFocusListeners().length);
            PlainDocument old = (PlainDocument) editor.getDocument();
            int oldListeners = old.getDocumentListeners().length;
            PlainDocument replacement = new PlainDocument();
            editor.setDocument(replacement);
            assertTrue(old.getDocumentListeners().length < oldListeners);
            assertTrue(replacement.getDocumentListeners().length > 0);
        });
    }

    @ParameterizedTest
    @CsvSource({"ENTER,true", "TAB,true", "ENTER,false", "TAB,false"})
    void popupKeysInsertAndDismissWithoutLeakingIntoTheNormalEditorActions(String acceptKey, boolean inheritTraversal)
            throws Exception {
        AtomicReference<JTextField> field = new AtomicReference<>();
        AtomicReference<JPopupMenu> menu = new AtomicReference<>();
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicReference<Object> originalEnter = new AtomicReference<>();
        AtomicReference<Object> originalTab = new AtomicReference<>();
        AtomicReference<Set<AWTKeyStroke>> originalForward = new AtomicReference<>();
        AtomicReference<Set<AWTKeyStroke>> originalBackward = new AtomicReference<>();
        int forward = KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS;
        int backward = KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS;
        SwingUtilities.invokeAndWait(() -> {
            JTextField editor = new JTextField() {
                @Override
                public boolean isFocusOwner() {
                    return true;
                }

                @Override
                public boolean isShowing() {
                    return true;
                }

                @Override
                public Rectangle2D modelToView2D(int position) {
                    return new Rectangle2D.Double(0, 0, 1, 20);
                }
            };
            // Exercise the real popup lifecycle and editor bindings without a native desktop.
            JPopupMenu popup = new TestPopup();
            editor.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "original-enter");
            editor.getActionMap().put("original-enter", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    submissions.incrementAndGet();
                }
            });
            originalEnter.set(editor.getInputMap().get(KeyStroke.getKeyStroke("ENTER")));
            originalTab.set(editor.getInputMap().get(KeyStroke.getKeyStroke("TAB")));
            editor.setFocusTraversalKeys(forward, inheritTraversal ? null
                    : Set.of(KeyStroke.getKeyStroke("TAB"), KeyStroke.getKeyStroke("ctrl TAB")));
            originalForward.set(editor.getFocusTraversalKeys(forward));
            originalBackward.set(editor.getFocusTraversalKeys(backward));
            new ParameterCompletion(editor, () -> {
                snapshots.incrementAndGet();
                return List.of(Suggestion.variable("userid"), Suggestion.variable("username"));
            }, popup);
            field.set(editor);
            menu.set(popup);
            editor.setText("${u");
        });
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(menu.get().isVisible());
            assertFalse(field.get().getFocusTraversalKeys(forward).contains(KeyStroke.getKeyStroke("TAB")));
            assertTrue(field.get().getFocusTraversalKeys(forward).contains(KeyStroke.getKeyStroke("ctrl TAB")));
            assertEquals(originalBackward.get(), field.get().getFocusTraversalKeys(backward));
            press(field.get(), "DOWN");
            press(field.get(), acceptKey);
            assertEquals("${username}", field.get().getText());
            assertEquals(0, submissions.get());
            assertEquals(originalTab.get(), field.get().getInputMap().get(KeyStroke.getKeyStroke("TAB")));
            assertEquals(originalForward.get(), field.get().getFocusTraversalKeys(forward));
            assertEquals(!inheritTraversal, field.get().areFocusTraversalKeysSet(forward));
            assertEquals(originalEnter.get(), field.get().getInputMap().get(KeyStroke.getKeyStroke("ENTER")));
            press(field.get(), "ENTER");
            assertEquals(1, submissions.get());
            field.get().setText("${u");
        });
        SwingUtilities.invokeAndWait(() -> field.get().replaceSelection("s"));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(2, snapshots.get(), "Filtering reuses the expression's in-memory snapshot");
            assertTrue(menu.get().isVisible());
            press(field.get(), "ESCAPE");
            assertEquals("${us", field.get().getText());
            assertEquals(originalTab.get(), field.get().getInputMap().get(KeyStroke.getKeyStroke("TAB")));
            assertEquals(originalForward.get(), field.get().getFocusTraversalKeys(forward));
            assertEquals(!inheritTraversal, field.get().areFocusTraversalKeysSet(forward));
            assertFalse(menu.get().isVisible());
            assertEquals(originalEnter.get(), field.get().getInputMap().get(KeyStroke.getKeyStroke("ENTER")));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void focusWithoutTypingDoesNotOpenCompletion(boolean loadAfterInstallation) throws Exception {
        AtomicBoolean focused = new AtomicBoolean();
        AtomicReference<JPopupMenu> menu = new AtomicReference<>();
        AtomicInteger snapshots = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            JTextField editor = new JTextField(loadAfterInstallation ? "" : "${username}") {
                @Override
                public boolean isFocusOwner() {
                    return focused.get();
                }

                @Override
                public boolean isShowing() {
                    return true;
                }

                @Override
                public Rectangle2D modelToView2D(int position) {
                    return new Rectangle2D.Double(0, 0, 1, 20);
                }
            };
            TestPopup popup = new TestPopup();
            menu.set(popup);
            new ParameterCompletion(editor, () -> {
                snapshots.incrementAndGet();
                return List.of(Suggestion.variable("username"), Suggestion.variable("userId"));
            }, popup);
            if (loadAfterInstallation) {
                editor.setText("${username}");
            }
            editor.setCaretPosition(6);
            focused.set(true);
            FocusEvent event = new FocusEvent(editor, FocusEvent.FOCUS_GAINED);
            for (var listener : editor.getFocusListeners()) {
                listener.focusGained(event);
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(menu.get().isVisible(), "Loading or focusing an existing value must not open completion");
            assertEquals(0, snapshots.get());
        });
    }

    @Test
    void enablesFocusTransferForTablesAddedAfterInstallation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel panel = new JPanel();
            ParameterCompletion.install(panel);
            JTable table = new JTable(1, 1);
            assertFalse(table.getSurrendersFocusOnKeystroke());
            panel.add(table);
            assertTrue(table.getSurrendersFocusOnKeystroke());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "dismiss", "cancel"})
    void restoresLocalBindingsWithoutCopyingThemeDefaults(String close) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField editor = new JTextField("${u");
            editor.setCaretPosition(3);
            InputMap local = editor.getInputMap();
            local.clear();
            InputMap oldTheme = new InputMap();
            InputMap newTheme = new InputMap();
            for (String key : List.of("ENTER", "TAB", "UP", "DOWN", "ESCAPE")) {
                oldTheme.put(KeyStroke.getKeyStroke(key), "old-" + key);
                newTheme.put(KeyStroke.getKeyStroke(key), "new-" + key);
            }
            local.setParent(oldTheme);
            local.put(KeyStroke.getKeyStroke("DOWN"), "custom-down");
            TestPopup popup = new TestPopup();
            ParameterCompletion helper = new ParameterCompletion(editor, List::of, popup);
            popup.setVisible(true);
            local.setParent(newTheme);
            switch (close) {
            case "accept":
                helper.accept(Suggestion.variable("username"));
                break;
            case "cancel":
                popup.cancel();
                popup.setVisible(false);
                break;
            default:
                press(editor, "ESCAPE");
                break;
            }
            assertEquals(1, local.size());
            assertEquals("custom-down", local.get(KeyStroke.getKeyStroke("DOWN")));
            for (String key : List.of("ENTER", "TAB", "UP", "ESCAPE")) {
                assertEquals("new-" + key, local.get(KeyStroke.getKeyStroke(key)), key);
            }
        });
    }

    private static void press(JTextField editor, String key) {
        Object action = editor.getInputMap().get(KeyStroke.getKeyStroke(key));
        editor.getActionMap().get(action).actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, key));
    }

    private static final class TestPopup extends JPopupMenu {
        void cancel() {
            firePopupMenuCanceled();
        }

        private boolean showing;

        @Override
        public boolean isVisible() {
            return showing;
        }

        @Override
        public void show(Component invoker, int x, int y) {
            setVisible(true);
        }

        @Override
        public void pack() {
        }

        @Override
        public void setVisible(boolean visible) {
            if (showing == visible) {
                return;
            }
            if (visible) {
                firePopupMenuWillBecomeVisible();
            } else {
                firePopupMenuWillBecomeInvisible();
            }
            showing = visible;
        }
    }

}
