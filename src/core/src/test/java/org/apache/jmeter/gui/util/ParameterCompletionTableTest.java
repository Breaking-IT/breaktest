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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.DefaultCellEditor;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.LookAndFeel;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.jmeter.gui.util.ParameterCompletionCatalog.Suggestion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.SystemInfo;

/** Exercises JTable's actual editing and focus transfer with real Swing focus, without OS key synthesis. */
@Isolated
class ParameterCompletionTableTest {
    private static JFrame frame;
    private static LookAndFeel originalLookAndFeel;

    @BeforeAll
    static void openWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a display for real Swing focus");
        SwingUtilities.invokeAndWait(() -> {
            originalLookAndFeel = UIManager.getLookAndFeel();
            assertDoesNotThrow(() -> UIManager.setLookAndFeel(
                    SystemInfo.isMacOS ? new FlatMacLightLaf() : new FlatLightLaf()));
            frame = new JFrame("Parameter completion focus test");
            frame.setSize(400, 200);
        });
    }

    @AfterAll
    static void closeWindow() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(() -> {
                frame.dispose();
                assertDoesNotThrow(() -> UIManager.setLookAndFeel(originalLookAndFeel));
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"10,false", "9,false", "10,true", "9,true"})
    void typingIntoSelectedCellTransfersFocusAndAcceptsCompletion(int acceptKey, boolean typeBeforeFocus)
            throws Exception {
        try (EditorWindow window = new EditorWindow("", false)) {
            window.awaitTableFocus();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(window.table.isFocusOwner());
                type('$');
                if (typeBeforeFocus) {
                    type('{');
                    type('u');
                    assertTrue(window.table.isFocusOwner(), "Exercise typing before focus transfer finishes");
                }
            });
            window.awaitEditorFocus();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(window.editor.isFocusOwner());
                if (!typeBeforeFocus) {
                    type('{');
                    type('u');
                }
            });
            // Drain the deferred document/caret refresh before checking the real popup and key routing.
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("${u", window.editor.getText());
                assertTrue(MenuSelectionManager.defaultManager().getSelectedPath().length > 0);
                assertTrue(window.editor.isFocusOwner());
                press(acceptKey);
                assertEquals("${userId}", window.editor.getText());
                assertTrue(window.table.isEditing(), "Completion must not commit or navigate the cell");
                assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
                press(KeyEvent.VK_ENTER);
                assertFalse(window.table.isEditing());
                assertEquals("${userId}", window.table.getValueAt(0, 0));
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"10,false", "9,false", "10,true", "9,true"})
    void focusingExistingExpressionPreservesTextAndNormalKeys(int key, boolean standalone) throws Exception {
        try (EditorWindow window = new EditorWindow("${username}", standalone)) {
            window.awaitTableFocus();
            AtomicInteger submissions = new AtomicInteger();
            CountDownLatch editorLostFocus = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> {
                window.editor.addActionListener(event -> submissions.incrementAndGet());
                window.editor.addFocusListener(new FocusAdapter() {
                    @Override
                    public void focusGained(FocusEvent event) {
                        // Position the caret as a click between 'user' and 'name' would.
                        window.editor.setCaretPosition(6);
                    }

                    @Override
                    public void focusLost(FocusEvent event) {
                        editorLostFocus.countDown();
                    }
                });
                if (!standalone) {
                    assertTrue(window.table.editCellAt(0, 0));
                }
                window.editor.requestFocusInWindow();
            });
            window.awaitEditorFocus();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(window.editor.isFocusOwner());
                assertEquals(6, window.editor.getCaretPosition());
                assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length,
                        "Focus alone must not open completion or intercept Enter/Tab");
                press(key);
                assertEquals("${username}", window.editor.getText());
                if (!standalone) {
                    assertFalse(window.table.isEditing(), "Enter/Tab must commit normally");
                    assertEquals("${username}", window.table.getValueAt(0, 0));
                } else if (key == KeyEvent.VK_ENTER) {
                    assertEquals(1, submissions.get(), "Enter must invoke the normal field action");
                }
            });
            if (standalone && key == KeyEvent.VK_TAB) {
                assertTrue(editorLostFocus.await(5, TimeUnit.SECONDS), "Tab must move focus normally");
            }
        }
    }

    private static final class EditorWindow implements AutoCloseable {
        private JTable table;
        private JTextField editor;
        private WindowAdapter focusListener;
        private final CountDownLatch tableFocused = new CountDownLatch(1);
        private final CountDownLatch editorFocused = new CountDownLatch(1);

        EditorWindow(String initialValue, boolean standalone) throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                table = new JTable(new Object[][] {{initialValue}}, new String[] {"Value"});
                editor = new JTextField(initialValue);
                if (!standalone) {
                    table.setDefaultEditor(Object.class, new DefaultCellEditor(editor));
                }
                table.addFocusListener(focusLatch(tableFocused));
                editor.addFocusListener(focusLatch(editorFocused));
                // Keep the native window alive across cases to avoid desktop activation races.
                frame.setContentPane(new JPanel(new BorderLayout()));
                frame.add(new JScrollPane(table));
                if (standalone) {
                    frame.add(editor, BorderLayout.SOUTH);
                }
                ParameterCompletion.install(frame.getContentPane(), () -> List.of(
                        Suggestion.variable("username"), Suggestion.variable("userId")));
                focusListener = new WindowAdapter() {
                    @Override
                    public void windowGainedFocus(WindowEvent event) {
                        if (!table.isEditing()) {
                            table.requestFocusInWindow();
                        }
                    }
                };
                frame.addWindowFocusListener(focusListener);
                table.changeSelection(0, 0, false, false);
                frame.setVisible(true);
                frame.validate();
                frame.toFront();
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                    Desktop.getDesktop().requestForeground(true);
                }
                frame.requestFocus();
                table.requestFocusInWindow();
            });
        }

        void awaitTableFocus() throws InterruptedException {
            assertTrue(tableFocused.await(5, TimeUnit.SECONDS), "Table must own real focus before typing");
        }

        void awaitEditorFocus() throws InterruptedException {
            assertTrue(editorFocused.await(5, TimeUnit.SECONDS), "Editor must receive real focus");
        }

        @Override
        public void close() throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                frame.removeWindowFocusListener(focusListener);
                frame.setContentPane(new JPanel());
            });
        }
    }

    private static FocusAdapter focusLatch(CountDownLatch latch) {
        return new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                latch.countDown();
            }
        };
    }

    private static void type(char character) {
        int code = KeyEvent.getExtendedKeyCodeForChar(character);
        dispatch(KeyEvent.KEY_PRESSED, code, character);
        dispatch(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, character);
        dispatch(KeyEvent.KEY_RELEASED, code, character);
    }

    private static void press(int code) {
        dispatch(KeyEvent.KEY_PRESSED, code, KeyEvent.CHAR_UNDEFINED);
        dispatch(KeyEvent.KEY_RELEASED, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static void dispatch(int id, int code, char character) {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        focus.dispatchEvent(new KeyEvent(focus, id, System.currentTimeMillis(), 0, code, character));
    }
}
