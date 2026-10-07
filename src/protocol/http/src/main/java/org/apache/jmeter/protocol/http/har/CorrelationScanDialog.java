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

package org.apache.jmeter.protocol.http.har;

import java.awt.BorderLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.util.JMeterUtils;

/** Modal progress feedback while correlation analysis leaves the plan untouched. */
final class CorrelationScanDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JButton cancel = new JButton(JMeterUtils.getResString("cancel"));

    CorrelationScanDialog(Window owner) {
        super(owner, JMeterUtils.getResString(ActionNames.FIND_PREDEFINED_CORRELATIONS),
                ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        content.add(new JLabel(JMeterUtils.getResString("find_predefined_correlations_searching")), BorderLayout.NORTH);
        progress.setStringPainted(true);
        content.add(progress, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(cancel);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
    }

    void start(SwingWorker<?, ?> worker) {
        cancel.addActionListener(event -> worker.cancel(true));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                worker.cancel(true);
            }
        });
        worker.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName())) {
                progress.setValue((Integer) event.getNewValue());
            }
        });
        worker.execute();
        setVisible(true);
    }
}
