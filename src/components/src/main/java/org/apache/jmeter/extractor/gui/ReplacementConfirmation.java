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

import java.awt.Dimension;
import java.text.MessageFormat;
import java.util.List;
import java.util.StringJoiner;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Builds the bounded confirmation message shown before replacing an extracted
 * value in later samplers. Long values and paths are shortened for display only;
 * the replacement itself always uses the complete value.
 */
final class ReplacementConfirmation {
    static final int MAX_TEXT_LENGTH = 100;
    static final int MAX_WIDTH = 720;
    static final int MAX_HEIGHT = 360;
    private static final String ELLIPSIS = "..."; //$NON-NLS-1$

    record Target(String path, int occurrences) {
    }

    private ReplacementConfirmation() {
    }

    static Target target(JMeterTreeNode node, int occurrences) {
        return new Target(formatNodePath(node), occurrences);
    }

    static JScrollPane createMessage(String variableName, String value, List<Target> targets) {
        JPanel targetsPanel = new JPanel();
        targetsPanel.setLayout(new BoxLayout(targetsPanel, BoxLayout.Y_AXIS));
        targetsPanel.add(new JLabel(MessageFormat.format(
                JMeterUtils.getResString("regex_test_replace_confirmation"), //$NON-NLS-1$
                abbreviateMiddle(variableName, MAX_TEXT_LENGTH), abbreviateMiddle(value, MAX_TEXT_LENGTH))));
        targetsPanel.add(Box.createVerticalStrut(8));
        for (Target target : targets) {
            JLabel label = new JLabel(MessageFormat.format(
                    JMeterUtils.getResString("regex_test_replacement_target"), //$NON-NLS-1$
                    abbreviateMiddle(target.path(), MAX_TEXT_LENGTH), target.occurrences()));
            label.setToolTipText(target.path());
            targetsPanel.add(label);
        }
        return boundedScroll(targetsPanel, MAX_WIDTH, MAX_HEIGHT);
    }

    /**
     * Shortens {@code value} to at most {@code maxLength} code points by keeping
     * its start and end around an ellipsis. Never splits a surrogate pair.
     */
    static String abbreviateMiddle(String value, int maxLength) {
        int length = value.codePointCount(0, value.length());
        if (length <= maxLength) {
            return value;
        }
        int kept = maxLength - ELLIPSIS.length();
        int head = kept / 2;
        int tail = kept - head;
        return value.substring(0, value.offsetByCodePoints(0, head))
                + ELLIPSIS
                + value.substring(value.offsetByCodePoints(value.length(), -tail));
    }

    /**
     * Wraps {@code content} in a scroll pane no larger than the given bounds.
     * When one dimension is capped, the space for its scroll bar is added to the
     * other so that only the scroll bar that is actually needed appears.
     */
    static JScrollPane boundedScroll(JComponent content, int maxWidth, int maxHeight) {
        JScrollPane scroll = new JScrollPane(content);
        Dimension size = scroll.getPreferredSize();
        int width = size.width;
        int height = size.height;
        if (height > maxHeight) {
            width += scroll.getVerticalScrollBar().getPreferredSize().width;
        }
        if (width > maxWidth) {
            height += scroll.getHorizontalScrollBar().getPreferredSize().height;
        }
        scroll.setPreferredSize(new Dimension(Math.min(width, maxWidth), Math.min(height, maxHeight)));
        return scroll;
    }

    static String formatNodePath(JMeterTreeNode node) {
        StringJoiner path = new StringJoiner(" > "); //$NON-NLS-1$
        for (Object element : node.getPath()) {
            if (element instanceof JMeterTreeNode treeNode) {
                path.add(treeNode.getName());
            }
        }
        return path.toString();
    }
}
