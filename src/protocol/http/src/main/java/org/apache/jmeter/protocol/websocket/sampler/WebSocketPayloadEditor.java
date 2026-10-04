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

package org.apache.jmeter.protocol.websocket.sampler;

import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.beans.PropertyEditorSupport;
import java.util.Objects;

import javax.swing.AbstractAction;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;

import org.apache.jmeter.gui.action.KeyStrokes;
import org.apache.jorphan.gui.ui.TextComponentUI;

/** Payloads are data, not source code: avoid syntax parsing for huge hex lines. */
public class WebSocketPayloadEditor extends PropertyEditorSupport {
    private final UndoManager undo = new UndoManager();
    private CompoundEdit replacement;
    private final JTextArea text = new JTextArea(10, 60) {
        @Override
        public void replaceSelection(String content) {
            replacement = new CompoundEdit();
            try {
                super.replaceSelection(content);
            } finally {
                replacement.end();
                undo.addEdit(replacement);
                replacement = null;
            }
        }
    };
    private final JScrollPane scroll = new JScrollPane(text);

    public WebSocketPayloadEditor() {
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, text.getFont().getSize()));
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        TextComponentUI.uninstallUndo(text);
        text.getDocument().addUndoableEditListener(event -> {
            if (replacement != null) {
                replacement.addEdit(event.getEdit());
            } else {
                undo.addEdit(event.getEdit());
            }
        });
        text.getInputMap().put(KeyStrokes.UNDO, "undo");
        text.getInputMap().put(KeyStrokes.REDO, "redo");
        text.getActionMap().put("undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (undo.canUndo()) {
                    undo.undo();
                }
            }
        });
        text.getActionMap().put("redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (undo.canRedo()) {
                    undo.redo();
                }
            }
        });
        text.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                firePropertyChange();
            }
        });
    }

    @Override
    public boolean supportsCustomEditor() { return true; }

    @Override
    public Component getCustomEditor() { return scroll; }

    @Override
    public String getAsText() { return text.getText(); }

    @Override
    public Object getValue() { return getAsText(); }

    @Override
    public void setAsText(String value) { setValue(value); }

    @Override
    public void setValue(Object value) {
        text.setText(Objects.toString(value, ""));
        text.setCaretPosition(0);
        undo.discardAllEdits();
    }
}
