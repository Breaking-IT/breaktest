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

package org.apache.jmeter.visualizers;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.swing.Icon;
import javax.swing.ImageIcon;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.StringUtilities;

/**
 * Status icons of the View Results Tree. Each can be replaced by an image configured with the
 * {@code viewResultsTree.success}, {@code viewResultsTree.failure} or {@code viewResultsTree.running}
 * property.
 */
final class ResultStatusIcons {
    private static final Color SUCCESS_COLOR = new Color(0x2EAD4F);
    private static final Color FAILURE_COLOR = new Color(0xD83A34);
    private static final Color RUNNING_COLOR = new Color(0x2F80ED);
    private static final String ICON_SIZE =
            JMeterUtils.getPropDefault(JMeter.TREE_ICON_SIZE, JMeter.DEFAULT_TREE_ICON_SIZE);

    static final Icon SUCCESS = create("viewResultsTree.success", SUCCESS_COLOR, Glyph.CHECK); // $NON-NLS-1$
    static final Icon FAILURE = create("viewResultsTree.failure", FAILURE_COLOR, Glyph.CROSS); // $NON-NLS-1$
    /** A transaction that has started but not finished */
    static final Icon RUNNING = create("viewResultsTree.running", RUNNING_COLOR, Glyph.CLOCK); // $NON-NLS-1$

    private enum Glyph { CHECK, CROSS, CLOCK }

    private ResultStatusIcons() {
    }

    private static Icon create(String propertyName, Color color, Glyph glyph) {
        String configuredIcon = JMeterUtils.getProperty(propertyName);
        if (!StringUtilities.isEmpty(configuredIcon)) {
            return JMeterUtils.getImage(configuredIcon);
        }

        int size = Math.max(12, Math.min(20, iconSize()));
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(color);
            graphics.fillOval(1, 1, size - 2, size - 2);
            graphics.setColor(Color.WHITE);
            graphics.setStroke(new BasicStroke(Math.max(1.7f, size / 8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (glyph) {
                case CHECK -> {
                    graphics.drawLine(size / 4, size / 2, size * 5 / 12, size * 2 / 3);
                    graphics.drawLine(size * 5 / 12, size * 2 / 3, size * 3 / 4, size / 3);
                }
                case CROSS -> {
                    int inset = Math.max(4, size / 4);
                    graphics.drawLine(inset, inset, size - inset, size - inset);
                    graphics.drawLine(size - inset, inset, inset, size - inset);
                }
                case CLOCK -> {
                    int center = size / 2;
                    graphics.drawLine(center, center, center, size / 4);
                    graphics.drawLine(center, center, size * 2 / 3, center);
                }
            }
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(image);
    }

    private static int iconSize() {
        int separator = ICON_SIZE.indexOf('x');
        String size = separator < 0 ? ICON_SIZE : ICON_SIZE.substring(0, separator);
        try {
            return Integer.parseInt(size);
        } catch (NumberFormatException e) {
            return 16;
        }
    }
}
