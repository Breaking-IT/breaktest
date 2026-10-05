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

import java.awt.BorderLayout;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;

import org.apache.jmeter.protocol.http.proxy.RecordingDiagnostics;

/** Persistent session summary, including problems that cannot produce a selectable sampler. */
final class RecordingStatusPanel extends JPanel {
    private final JLabel summary = new JLabel();
    private final JTextArea details = new JTextArea(3, 50);
    private final JScrollPane scroll = new JScrollPane(details);
    private final Supplier<RecordingDiagnostics> diagnostics;
    private final Timer timer = new Timer(500, event -> refresh());

    RecordingStatusPanel(Supplier<RecordingDiagnostics> diagnostics) {
        super(new BorderLayout(4, 4));
        this.diagnostics = diagnostics;
        setBorder(BorderFactory.createTitledBorder("Recording status"));
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        add(summary, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    private void refresh() {
        RecordingDiagnostics status = diagnostics.get();
        if (status == null) {
            summary.setText("No recording started");
            details.setText("");
            return;
        }
        String problems = status.details();
        summary.setText((problems.isEmpty() ? "" : "Warning — ") + status.summary());
        String text = problems.isEmpty() ? "Captured includes requests excluded by filters. No capture issues reported."
                : "The recording has issues. Incomplete counts may overlap failed captures.\n" + problems;
        if (!details.getText().equals(text)) {
            details.setText(text);
            details.setCaretPosition(0);
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        refresh();
        timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }
}
