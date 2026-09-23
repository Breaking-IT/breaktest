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

import java.awt.Component;
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
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.DefaultCellEditor;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.LookAndFeel;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.jmeter.gui.util.ParameterCompletionCatalog.Suggestion;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.SystemInfo;

/** Exercises JTable's actual editing and focus transfer with real Swing focus, without OS key synthesis. */
@Isolated
class ParameterCompletionTableTest {
    @ParameterizedTest
    @ValueSource(ints = {KeyEvent.VK_ENTER, KeyEvent.VK_TAB})
    void typingIntoSelectedCellTransfersFocusAndAcceptsCompletion(int acceptKey) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a display for real Swing focus");
        AtomicReference<JFrame> frame = new AtomicReference<>();
        AtomicReference<JTable> table = new AtomicReference<>();
        AtomicReference<JTextField> editor = new AtomicReference<>();
        AtomicReference<LookAndFeel> originalLookAndFeel = new AtomicReference<>();
        CountDownLatch tableFocused = new CountDownLatch(1);
        CountDownLatch editorFocused = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                originalLookAndFeel.set(UIManager.getLookAndFeel());
                assertDoesNotThrow(() -> UIManager.setLookAndFeel(
                        SystemInfo.isMacOS ? new FlatMacLightLaf() : new FlatLightLaf()));
                JTable cells = new JTable(new Object[][] {{""}}, new String[] {"Value"});
                JTextField field = new JTextField();
                cells.setDefaultEditor(Object.class, new DefaultCellEditor(field));
                cells.addFocusListener(focusLatch(tableFocused));
                field.addFocusListener(focusLatch(editorFocused));
                ParameterCompletion.install(cells, () -> List.of(Suggestion.variable("username")));
                JFrame window = new JFrame("Parameter completion table test");
                frame.set(window);
                table.set(cells);
                editor.set(field);
                window.add(new JScrollPane(cells));
                window.setSize(400, 200);
                window.addWindowFocusListener(new WindowAdapter() {
                    @Override
                    public void windowGainedFocus(WindowEvent event) {
                        if (!cells.isEditing()) {
                            cells.requestFocusInWindow();
                        }
                    }
                });
                cells.changeSelection(0, 0, false, false);
                window.setVisible(true);
                window.toFront();
                cells.requestFocusInWindow();
            });
            assertTrue(tableFocused.await(5, TimeUnit.SECONDS), "Table must own real focus before typing");
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(table.get().isFocusOwner());
                type('$');
            });
            assertTrue(editorFocused.await(5, TimeUnit.SECONDS), "Typing must transfer real focus to the cell editor");
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(editor.get().isFocusOwner());
                type('{');
                type('u');
            });
            // Drain the deferred document/caret refresh before checking the real popup and key routing.
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("${u", editor.get().getText());
                assertTrue(MenuSelectionManager.defaultManager().getSelectedPath().length > 0);
                assertTrue(editor.get().isFocusOwner());
                press(acceptKey);
                assertEquals("${username}", editor.get().getText());
                assertTrue(table.get().isEditing(), "Completion must not commit or navigate the cell");
                assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
                press(KeyEvent.VK_ENTER);
                assertFalse(table.get().isEditing());
                assertEquals("${username}", table.get().getValueAt(0, 0));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                if (frame.get() != null) {
                    frame.get().dispose();
                }
                assertDoesNotThrow(() -> UIManager.setLookAndFeel(originalLookAndFeel.get()));
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
