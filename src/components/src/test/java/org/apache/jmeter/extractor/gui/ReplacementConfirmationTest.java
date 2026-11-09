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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.util.Collections;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.apache.jmeter.junit.JMeterTestCase;
import org.junit.jupiter.api.Test;

class ReplacementConfirmationTest extends JMeterTestCase {
    @Test
    void keepsValuesUpToTheLimit() {
        String value = "a".repeat(ReplacementConfirmation.MAX_TEXT_LENGTH);

        assertEquals(value, ReplacementConfirmation.abbreviateMiddle(value, ReplacementConfirmation.MAX_TEXT_LENGTH));
    }

    @Test
    void abbreviatesLongValuesInTheMiddle() {
        String value = "s".repeat(60) + "e".repeat(60);

        String preview = ReplacementConfirmation.abbreviateMiddle(value, ReplacementConfirmation.MAX_TEXT_LENGTH);

        assertEquals(ReplacementConfirmation.MAX_TEXT_LENGTH, preview.length());
        assertEquals("s".repeat(48) + "..." + "e".repeat(49), preview);
    }

    @Test
    void neverSplitsSurrogatePairs() {
        String emoji = "😀";
        String value = String.join("", Collections.nCopies(120, emoji));

        String preview = ReplacementConfirmation.abbreviateMiddle(value, ReplacementConfirmation.MAX_TEXT_LENGTH);

        assertEquals(ReplacementConfirmation.MAX_TEXT_LENGTH, preview.codePointCount(0, preview.length()));
        assertEquals(String.join("", Collections.nCopies(48, emoji)) + "..."
                + String.join("", Collections.nCopies(49, emoji)), preview);
    }

    @Test
    void reservesVerticalScrollBarWidthWhenOnlyHeightIsCapped() {
        JPanel content = new JPanel();
        content.setPreferredSize(new Dimension(300, 1000));

        JScrollPane scroll = ReplacementConfirmation.boundedScroll(content, 720, 360);

        int barWidth = scroll.getVerticalScrollBar().getPreferredSize().width;
        assertEquals(new Dimension(300 + barWidth + insetsWidth(scroll), 360), scroll.getPreferredSize());
    }

    @Test
    void keepsSmallContentAtItsNaturalSize() {
        JPanel content = new JPanel();
        content.setPreferredSize(new Dimension(300, 100));

        JScrollPane scroll = ReplacementConfirmation.boundedScroll(content, 720, 360);

        assertEquals(new Dimension(300 + insetsWidth(scroll), 100 + insetsHeight(scroll)), scroll.getPreferredSize());
    }

    @Test
    void capsWideAndTallContent() {
        JPanel content = new JPanel();
        content.setPreferredSize(new Dimension(5000, 5000));

        JScrollPane scroll = ReplacementConfirmation.boundedScroll(content, 720, 360);

        assertEquals(new Dimension(720, 360), scroll.getPreferredSize());
    }

    @Test
    void shortensLongTargetPathsButKeepsTheFullPathAsTooltip() {
        String path = "Test Plan > Thread Group > " + "x".repeat(200);

        JScrollPane scroll = ReplacementConfirmation.createMessage("token", "abc",
                List.of(new ReplacementConfirmation.Target(path, 3)));

        JPanel panel = (JPanel) scroll.getViewport().getView();
        JLabel targetLabel = (JLabel) panel.getComponent(2);
        assertTrue(targetLabel.getText().contains("..."), targetLabel.getText());
        assertTrue(targetLabel.getText().endsWith("(3 occurrence(s))"), targetLabel.getText());
        assertFalse(targetLabel.getText().contains(path), targetLabel.getText());
        assertEquals(path, targetLabel.getToolTipText());
    }

    private static int insetsWidth(JScrollPane scroll) {
        return scroll.getInsets().left + scroll.getInsets().right;
    }

    private static int insetsHeight(JScrollPane scroll) {
        return scroll.getInsets().top + scroll.getInsets().bottom;
    }
}
