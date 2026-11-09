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

package org.apache.jmeter.readiness.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

import javax.swing.Icon;
import javax.swing.UIManager;

import org.apache.jmeter.readiness.CheckResult;
import org.apache.jmeter.readiness.CheckState;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.ReadinessVerdict;

/**
 * Vector status icons of the readiness wizard, so passed, failed and pending items stand out at a glance. Shapes
 * differ as well as colours (check, cross, triangle, outline), so the states stay apart without colour vision.
 * Colours follow the look and feel (FlatLaf action colours, light and dark).
 */
final class StatusIcon implements Icon {

    /** What an icon shows. */
    enum Kind {
        PASSED("Passed"),
        ACCEPTED("Accepted"),
        FAILED("Blocker"),
        WARNING("Warning"),
        REVIEW("Review required"),
        NOT_VERIFIED("Not verified"),
        NOT_APPLICABLE("Not applicable"),
        NOT_RUN("Not run"),
        SUGGESTION("Suggestion"),
        INFO("Info");

        private final String description;

        Kind(String description) {
            this.description = description;
        }

        String description() {
            return description;
        }
    }

    static final int SMALL = 16;
    static final int LARGE = 32;

    private final Kind kind;
    private final int size;

    StatusIcon(Kind kind, int size) {
        this.kind = kind;
        this.size = size;
    }

    Kind kind() {
        return kind;
    }

    static StatusIcon of(Kind kind) {
        return new StatusIcon(kind, SMALL);
    }

    static Kind kindOf(CheckState state) {
        return switch (state) {
            case PASSED -> Kind.PASSED;
            case ACCEPTED -> Kind.ACCEPTED;
            case BLOCKER -> Kind.FAILED;
            case WARNING -> Kind.WARNING;
            case REVIEW -> Kind.REVIEW;
            case NOT_VERIFIED -> Kind.NOT_VERIFIED;
            case NOT_APPLICABLE -> Kind.NOT_APPLICABLE;
            case NOT_RUN -> Kind.NOT_RUN;
            case SUGGESTION -> Kind.SUGGESTION;
        };
    }

    static Kind kindOf(CheckResult result) {
        return result.stale() ? Kind.NOT_VERIFIED : kindOf(result.state());
    }

    static Kind kindOf(Finding finding) {
        if (finding.isAccepted()) {
            return Kind.ACCEPTED;
        }
        return switch (finding.severity()) {
            case BLOCKER -> Kind.FAILED;
            case WARNING -> Kind.WARNING;
            case REVIEW -> Kind.REVIEW;
            case NOT_VERIFIED -> Kind.NOT_VERIFIED;
            case SUGGESTION -> Kind.SUGGESTION;
            case INFO -> Kind.INFO;
        };
    }

    static Kind kindOf(ReadinessVerdict verdict) {
        return switch (verdict) {
            case READY -> Kind.PASSED;
            case NEEDS_ATTENTION -> Kind.FAILED;
            case NOT_VERIFIED -> Kind.NOT_VERIFIED;
        };
    }

    /**
     * @param kinds kinds of the items of a group
     * @return the kind summarizing them: the most severe one
     */
    static Kind worst(Iterable<Kind> kinds) {
        Kind worst = null;
        for (Kind kind : kinds) {
            if (worst == null || rank(kind) < rank(worst)) {
                worst = kind;
            }
        }
        return worst == null ? Kind.PASSED : worst;
    }

    private static int rank(Kind kind) {
        return switch (kind) {
            case FAILED -> 0;
            case WARNING -> 1;
            case REVIEW -> 2;
            case NOT_VERIFIED -> 3;
            case ACCEPTED -> 4;
            case SUGGESTION -> 5;
            case PASSED -> 6;
            case INFO -> 7;
            case NOT_APPLICABLE -> 8;
            case NOT_RUN -> 9;
        };
    }

    static Color color(Kind kind) {
        return switch (kind) {
            case PASSED, ACCEPTED -> color("Actions.Green", new Color(0x2E7D32));
            case FAILED -> color("Actions.Red", new Color(0xD32F2F));
            case WARNING -> color("Actions.Yellow", new Color(0xD97706));
            case REVIEW, INFO, SUGGESTION -> color("Actions.Blue", new Color(0x1E6FD9));
            case NOT_VERIFIED, NOT_APPLICABLE, NOT_RUN -> color("Actions.Grey", new Color(0x8A8A8A));
        };
    }

    private static Color color(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            float s = size / 16f;
            g2.scale(s, s);
            Color color = color(kind);
            Ellipse2D circle = new Ellipse2D.Float(1.5f, 1.5f, 13f, 13f);
            switch (kind) {
                case PASSED -> {
                    fill(g2, circle, color);
                    check(g2, Color.WHITE);
                }
                case ACCEPTED -> {
                    outline(g2, circle, color);
                    check(g2, color);
                }
                case FAILED -> {
                    fill(g2, circle, color);
                    cross(g2, Color.WHITE);
                }
                case WARNING -> {
                    Path2D triangle = new Path2D.Float();
                    triangle.moveTo(8, 1.2);
                    triangle.lineTo(15.2, 14.2);
                    triangle.lineTo(0.8, 14.2);
                    triangle.closePath();
                    g2.setColor(color);
                    g2.fill(triangle);
                    glyph(g2, "!", Color.WHITE, 9.8f, 1f);
                }
                case REVIEW -> {
                    fill(g2, circle, color);
                    glyph(g2, "?", Color.WHITE, 8.6f, 0f);
                }
                case INFO -> {
                    outline(g2, circle, color);
                    glyph(g2, "i", color, 8.6f, 0f);
                }
                case NOT_VERIFIED -> {
                    outline(g2, circle, color);
                    glyph(g2, "?", color, 8.6f, 0f);
                }
                case NOT_APPLICABLE -> {
                    outline(g2, circle, color);
                    g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(5, 8, 11, 8);
                }
                case SUGGESTION -> {
                    // A light bulb: an optional improvement
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D bulb = new Path2D.Float();
                    bulb.moveTo(6, 11);
                    bulb.curveTo(6, 9.6, 3.2, 8.6, 3.2, 6);
                    bulb.curveTo(3.2, 3.4, 5.4, 1.4, 8, 1.4);
                    bulb.curveTo(10.6, 1.4, 12.8, 3.4, 12.8, 6);
                    bulb.curveTo(12.8, 8.6, 10, 9.6, 10, 11);
                    bulb.closePath();
                    g2.draw(bulb);
                    g2.drawLine(6, 13, 10, 13);
                    g2.drawLine(7, 15, 9, 15);
                }
                case NOT_RUN -> {
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                            new float[]{2f, 2.2f}, 0f));
                    g2.draw(circle);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    private static void fill(Graphics2D g2, Ellipse2D circle, Color color) {
        g2.setColor(color);
        g2.fill(circle);
    }

    private static void outline(Graphics2D g2, Ellipse2D circle, Color color) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(circle);
    }

    private static void check(Graphics2D g2, Color color) {
        Path2D path = new Path2D.Float();
        path.moveTo(4.6, 8.3);
        path.lineTo(7, 10.7);
        path.lineTo(11.6, 5.6);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(path);
    }

    private static void cross(Graphics2D g2, Color color) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(5, 5, 11, 11);
        g2.drawLine(11, 5, 5, 11);
    }

    private static void glyph(Graphics2D g2, String text, Color color, float fontSize, float dy) {
        g2.setColor(color);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(fontSize));
        FontMetrics metrics = g2.getFontMetrics();
        float width = metrics.stringWidth(text);
        float baseline = 8f + dy + (metrics.getAscent() - metrics.getDescent()) / 2f;
        g2.drawString(text, 8f - width / 2f, baseline);
    }

    @Override
    public String toString() {
        return kind.description();
    }
}
