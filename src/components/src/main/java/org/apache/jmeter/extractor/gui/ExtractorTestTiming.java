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

package org.apache.jmeter.extractor.gui;

import java.awt.Color;
import java.awt.FlowLayout;
import java.util.Locale;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

import org.apache.jmeter.util.JMeterUtils;

/** Displays elapsed parse time, highlighting only slow timing values. */
final class ExtractorTestTiming extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JLabel value = new JLabel();

    ExtractorTestTiming() {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        add(new JLabel(JMeterUtils.getResString("regex_test_parse_time")));
        add(value);
        setVisible(false);
    }

    void setElapsed(long elapsedNanos) {
        value.setText(elapsedNanos < 1_000_000L
                ? String.format(Locale.ROOT, "%.1f µs", elapsedNanos / 1_000d)
                : String.format(Locale.ROOT, "%.1f ms", elapsedNanos / 1_000_000d));
        value.setForeground(elapsedNanos > 10_000_000L ? Color.RED : UIManager.getColor("Label.foreground"));
        setVisible(true);
    }
}
