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

package org.apache.jmeter.protocol.http.proxy.gui;

import java.util.function.Consumer;

import javax.swing.JTextField;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/** Publishes transaction names immediately, even when recording from another device. */
final class RecordingTransactionNameField extends JTextField {
    RecordingTransactionNameField(int columns, Consumer<String> changed) {
        super(columns);
        // Publish after the complete edit. A DocumentListener sees a transient empty value
        // between removal and insertion when replacing selected text or calling setText.
        ((AbstractDocument) getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes)
                    throws BadLocationException {
                super.insertString(bypass, offset, text, attributes);
                changed.accept(getText());
            }

            @Override
            public void remove(FilterBypass bypass, int offset, int length) throws BadLocationException {
                super.remove(bypass, offset, length);
                changed.accept(getText());
            }

            @Override
            public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes)
                    throws BadLocationException {
                super.replace(bypass, offset, length, text, attributes);
                changed.accept(getText());
            }
        });
    }
}
