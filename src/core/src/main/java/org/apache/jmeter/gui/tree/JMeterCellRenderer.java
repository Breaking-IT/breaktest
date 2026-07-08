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

package org.apache.jmeter.gui.tree;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.border.Border;
import javax.swing.tree.DefaultTreeCellRenderer;

import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.util.StringUtilities;

/**
 * Class to render the test tree - sets the enabled/disabled versions of the icons
 */
public class JMeterCellRenderer extends DefaultTreeCellRenderer {
    private static final long serialVersionUID = 241L;

    private static final int DEFAULT_LENGTH = 15;

    private static final String BLANK = " ".repeat(DEFAULT_LENGTH);

    private static final Border RED_BORDER = BorderFactory.createLineBorder(new Color(0xDC2626));
    private static final Border BLUE_BORDER = BorderFactory.createLineBorder(new Color(0x2563EB));
    private static final int DELAY_SUMMARY_GAP = 7;
    private static final int BADGE_RIGHT_INSET = 8;

    private String delaySummary;

    public JMeterCellRenderer() {
        // A little more air between the node icon and its label
        setIconTextGap(6);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
            boolean leaf, int row, boolean p_hasFocus) {
        JMeterTreeNode node = (JMeterTreeNode) value;
        this.delaySummary = delaySummary(node);
        super.getTreeCellRendererComponent(tree,
                StringUtilities.isBlank(node.getName()) ? BLANK : node.getName(),
                        sel, expanded, leaf, row, p_hasFocus);
        boolean enabled = node.isEnabled();
        Icon icon = ModernTreeIcon.from(node, enabled);
        setIcon(icon);
        setDisabledIcon(icon);
        this.setEnabled(enabled);
        if(node.isMarkedBySearch()) {
            setBorder(RED_BORDER);
        } else if (node.isChildrenMarkedBySearch()) {
            setBorder(BLUE_BORDER);
        } else {
            setBorder(null);
        }
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        if (delaySummary != null) {
            Font suffixFont = suffixFont();
            FontMetrics suffixMetrics = getFontMetrics(suffixFont);
            size.width += suffixMetrics.stringWidth(delaySummary) + DELAY_SUMMARY_GAP + BADGE_RIGHT_INSET;
        }
        return size;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (delaySummary != null) {
                paintDelaySummary(g2);
            }
        } finally {
            g2.dispose();
        }
    }

    private int textStartX() {
        Icon icon = getIcon();
        int x = getInsets().left;
        if (icon != null) {
            x += icon.getIconWidth() + getIconTextGap();
        }
        return x;
    }

    private int primaryTextEndX() {
        return textStartX() + getFontMetrics(getFont()).stringWidth(getText());
    }

    private Font suffixFont() {
        return getFont().deriveFont(Math.max(9f, getFont().getSize2D() - 3f));
    }

    private void paintDelaySummary(Graphics2D g) {
        Font font = suffixFont();
        FontMetrics metrics = g.getFontMetrics(font);
        int x = primaryTextEndX() + DELAY_SUMMARY_GAP;
        int maxX = getWidth() - BADGE_RIGHT_INSET;
        if (x + metrics.stringWidth(delaySummary) > maxX) {
            return;
        }
        g.setFont(font);
        g.setColor(isEnabled() ? new Color(0x9CA3AF) : new Color(0x6B7280));
        int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent() + 1;
        g.drawString(delaySummary, x, y);
    }

    private static String delaySummary(JMeterTreeNode node) {
        TestElement element = node.getTestElement();
        if (!(element instanceof TransactionController)) {
            return null;
        }
        String mode = element.getPropertyAsString("TransactionController.delayMode", TransactionController.DELAY_DISABLED);
        long millis;
        if (TransactionController.DELAY_FIXED.equals(mode)) {
            millis = parseMillis(element.getPropertyAsString("TransactionController.fixedDelay", "0"));
        } else if (TransactionController.DELAY_RANDOM.equals(mode)
                || TransactionController.DELAY_GAUSSIAN_RANDOM.equals(mode)) {
            long min = parseMillis(element.getPropertyAsString("TransactionController.delayMin", "0"));
            long max = parseMillis(element.getPropertyAsString("TransactionController.delayMax", "0"));
            if (min < 0 || max < 0) {
                return null;
            }
            millis = Math.round((min + max) / 2.0);
        } else {
            return null;
        }
        return millis > 0 ? "(" + formatSeconds(millis) + ")" : null;
    }

    private static long parseMillis(String value) {
        if (StringUtilities.isBlank(value)) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String formatSeconds(long millis) {
        double seconds = millis / 1000.0;
        if (Math.abs(seconds - Math.rint(seconds)) < 0.000001) {
            return Long.toString(Math.round(seconds)) + "s";
        }
        return String.format(java.util.Locale.ROOT, "%.1fs", seconds);
    }

    static final class ModernTreeIcon implements Icon {
        private static final int SIZE = 16;

        private final Kind kind;
        private final boolean enabled;

        private ModernTreeIcon(Kind kind, boolean enabled) {
            this.kind = kind;
            this.enabled = enabled;
        }

        // Ordered substring -> icon kind matching; first hit wins, so keep the
        // more specific tokens (e.g. RandomOrderController) before the general ones.
        private static final List<Map.Entry<Kind, String[]>> PRIMARY_KINDS = List.of(
                Map.entry(Kind.PLAN, new String[] {"TestPlanGui"}), // $NON-NLS-1$
                Map.entry(Kind.THREADS, new String[] {"ThreadGroup"}), // $NON-NLS-1$
                Map.entry(Kind.REPORT, new String[] {"Visualizer", "Listener", "ResultCollector", "Report"}), // $NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$ $NON-NLS-4$
                Map.entry(Kind.COOKIE, new String[] {"Cookie"}), // $NON-NLS-1$
                Map.entry(Kind.CONFIG, new String[] {"Config", "BreakTestAiKnowledge", "BreakTest AI Knowledge"}), // $NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$
                Map.entry(Kind.REQUEST, new String[] {"Sampler"})); // $NON-NLS-1$

        private static final List<Map.Entry<Kind, String[]>> CONTROLLER_KINDS = List.of(
                Map.entry(Kind.IF_CONTROLLER, new String[] {"IfController"}), // $NON-NLS-1$
                Map.entry(Kind.SWITCH_CONTROLLER, new String[] {"SwitchController"}), // $NON-NLS-1$
                Map.entry(Kind.TRANSACTION, new String[] {"TransactionController"}), // $NON-NLS-1$
                Map.entry(Kind.LOOP, new String[] {"LoopController"}), // $NON-NLS-1$
                Map.entry(Kind.WHILE_CONTROLLER, new String[] {"WhileController"}), // $NON-NLS-1$
                Map.entry(Kind.CRITICAL_CONTROLLER, new String[] {"CriticalSectionController"}), // $NON-NLS-1$
                Map.entry(Kind.FOREACH_CONTROLLER, new String[] {"ForeachController", "ForEachController"}), // $NON-NLS-1$ $NON-NLS-2$
                Map.entry(Kind.INCLUDE_CONTROLLER, new String[] {"IncludeController"}), // $NON-NLS-1$
                Map.entry(Kind.INTERLEAVE_CONTROLLER, new String[] {"InterleaveControl"}), // $NON-NLS-1$
                Map.entry(Kind.ONCE_CONTROLLER, new String[] {"OnceOnlyController"}), // $NON-NLS-1$
                Map.entry(Kind.RANDOM_ORDER_CONTROLLER, new String[] {"RandomOrderController"}), // $NON-NLS-1$
                Map.entry(Kind.RANDOM_CONTROLLER, new String[] {"RandomController"}), // $NON-NLS-1$
                Map.entry(Kind.RECORDING_CONTROLLER, new String[] {"RecordingController"}), // $NON-NLS-1$
                Map.entry(Kind.RUNTIME_CONTROLLER, new String[] {"RuntimeController"}), // $NON-NLS-1$
                Map.entry(Kind.PARALLEL_CONTROLLER, new String[] {"ParallelController"}), // $NON-NLS-1$
                Map.entry(Kind.THROUGHPUT_CONTROLLER, new String[] {"ThroughputController"}), // $NON-NLS-1$
                Map.entry(Kind.FORK_CONTROLLER, new String[] {"ForkController"}), // $NON-NLS-1$
                Map.entry(Kind.MODULE, new String[] {"ModuleController"}), // $NON-NLS-1$
                Map.entry(Kind.SIMPLE_CONTROLLER, new String[] {"SimpleController", "Controller"})); // $NON-NLS-1$ $NON-NLS-2$

        private static final List<Map.Entry<Kind, String[]>> FALLBACK_KINDS = List.of(
                Map.entry(Kind.TIMER, new String[] {"Timer"}), // $NON-NLS-1$
                Map.entry(Kind.ASSERTION, new String[] {"Assertion"}), // $NON-NLS-1$
                Map.entry(Kind.PRE_PROCESSOR, new String[] {"PreProcessor"}), // $NON-NLS-1$
                Map.entry(Kind.POST_PROCESSOR, new String[] {"PostProcessor", "Extractor"})); // $NON-NLS-1$ $NON-NLS-2$

        static Icon from(JMeterTreeNode node, boolean enabled) {
            return new ModernTreeIcon(kindFor(node), enabled);
        }

        static Kind kindFor(JMeterTreeNode node) {
            TestElement element = node.getTestElement();
            if (element == null) {
                return Kind.NODE;
            }
            String guiClass = element.getPropertyAsString(TestElement.GUI_CLASS);
            String name = (guiClass == null ? "" : guiClass) + " " + element.getClass().getName(); // $NON-NLS-1$ // $NON-NLS-2$
            Kind primary = matchKind(PRIMARY_KINDS, name);
            if (primary != null) {
                return primary;
            }
            Kind controllerKind = matchKind(CONTROLLER_KINDS, name);
            if (controllerKind != null) {
                return controllerKind;
            }
            Kind interfaceKind = interfaceKind(element);
            if (interfaceKind != null) {
                return interfaceKind;
            }
            Kind fallback = matchKind(FALLBACK_KINDS, name);
            return fallback != null ? fallback : Kind.NODE;
        }

        private static Kind interfaceKind(TestElement element) {
            if (element instanceof PreProcessor) {
                return Kind.PRE_PROCESSOR;
            }
            if (element instanceof PostProcessor) {
                return Kind.POST_PROCESSOR;
            }
            return null;
        }

        private static Kind matchKind(List<Map.Entry<Kind, String[]>> table, String descriptor) {
            for (Map.Entry<Kind, String[]> entry : table) {
                for (String token : entry.getValue()) {
                    if (descriptor.contains(token)) {
                        return entry.getKey();
                    }
                }
            }
            return null;
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float alpha = enabled ? 1f : 0.42f;
                Color stroke = withAlpha(foreground(c), alpha);
                Color accent = withAlpha(kind.accent, alpha);
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                kind.paint(g2, x, y, stroke, accent);
            } finally {
                g2.dispose();
            }
        }

        private static Color foreground(Component c) {
            return c == null || c.getForeground() == null ? new Color(0x4B5563) : c.getForeground();
        }

        private static Color withAlpha(Color color, float alpha) {
            return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(alpha * 255));
        }

        enum Kind {
            PLAN(new Color(0x2563EB)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 3, x + 4, y + 13);
                    g.drawLine(x + 8, y + 3, x + 12, y + 13);
                    g.setColor(stroke);
                    g.drawLine(x + 6, y + 9, x + 10, y + 9);
                }
            },
            THREADS(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawOval(x + 5, y + 2, 6, 6);
                    g.setColor(stroke);
                    g.drawArc(x + 3, y + 8, 10, 7, 20, 140);
                    g.drawLine(x + 3, y + 13, x + 13, y + 13);
                }
            },
            REQUEST(new Color(0x16A34A)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 3, y + 12, x + 12, y + 3);
                    g.setColor(stroke);
                    g.drawLine(x + 9, y + 3, x + 13, y + 3);
                    g.drawLine(x + 12, y + 3, x + 12, y + 7);
                }
            },
            HTTP_SAMPLER(new Color(0x16A34A)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 3, y + 5, 10, 7, 2, 2);
                    g.setColor(accent);
                    g.drawLine(x + 11, y + 8, x + 14, y + 8);
                    g.drawLine(x + 12, y + 6, x + 14, y + 8);
                    g.drawLine(x + 12, y + 10, x + 14, y + 8);
                }
            },
            CODE_SAMPLER(new Color(0x8B5CF6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 7, y + 5, x + 4, y + 8);
                    g.drawLine(x + 4, y + 8, x + 7, y + 11);
                    g.drawLine(x + 10, y + 5, x + 13, y + 8);
                    g.drawLine(x + 13, y + 8, x + 10, y + 11);
                    g.setColor(stroke);
                    g.drawLine(x + 9, y + 4, x + 7, y + 12);
                }
            },
            DB_SAMPLER(new Color(0x0EA5E9)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawOval(x + 4, y + 3, 9, 4);
                    g.drawLine(x + 4, y + 5, x + 4, y + 12);
                    g.drawLine(x + 13, y + 5, x + 13, y + 12);
                    g.drawOval(x + 4, y + 10, 9, 4);
                    g.setColor(stroke);
                    g.drawLine(x + 5, y + 8, x + 12, y + 8);
                }
            },
            MESSAGE_SAMPLER(new Color(0x14B8A6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawRoundRect(x + 3, y + 5, 10, 7, 2, 2);
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 6, x + 8, y + 9);
                    g.drawLine(x + 12, y + 6, x + 8, y + 9);
                }
            },
            FILE_SAMPLER(new Color(0xD97706)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 4, y + 4, x + 9, y + 4);
                    g.drawLine(x + 9, y + 4, x + 12, y + 7);
                    g.drawLine(x + 12, y + 7, x + 12, y + 13);
                    g.drawLine(x + 4, y + 4, x + 4, y + 13);
                    g.drawLine(x + 4, y + 13, x + 12, y + 13);
                    g.setColor(stroke);
                    g.drawLine(x + 6, y + 8, x + 10, y + 8);
                }
            },
            MAIL_SAMPLER(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawRoundRect(x + 3, y + 5, 10, 8, 2, 2);
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 6, x + 8, y + 10);
                    g.drawLine(x + 12, y + 6, x + 8, y + 10);
                }
            },
            TCP_SAMPLER(new Color(0x06B6D4)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 8, x + 12, y + 8);
                    g.setColor(accent);
                    g.drawOval(x + 2, y + 6, 4, 4);
                    g.drawOval(x + 10, y + 6, 4, 4);
                    g.drawOval(x + 6, y + 11, 4, 4);
                }
            },
            SYSTEM_SAMPLER(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 3, y + 5, 10, 8, 2, 2);
                    g.setColor(accent);
                    g.drawLine(x + 6, y + 8, x + 8, y + 10);
                    g.drawLine(x + 8, y + 10, x + 11, y + 6);
                }
            },
            COOKIE(new Color(0xB45309)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawOval(x + 3, y + 3, 10, 10);
                    g.setColor(stroke);
                    g.fillOval(x + 6, y + 6, 2, 2);
                    g.fillOval(x + 10, y + 5, 2, 2);
                    g.fillOval(x + 8, y + 10, 2, 2);
                    g.setColor(accent);
                    g.drawArc(x + 8, y + 2, 6, 6, 190, 130);
                }
            },
            CONFIG(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 5, x + 12, y + 5);
                    g.drawLine(x + 4, y + 8, x + 12, y + 8);
                    g.drawLine(x + 4, y + 11, x + 12, y + 11);
                    g.setColor(accent);
                    g.drawOval(x + 3, y + 4, 2, 2);
                    g.drawOval(x + 10, y + 10, 2, 2);
                }
            },
            CONTROLLER(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRect(x + 3, y + 3, 10, 10);
                    g.setColor(accent);
                    g.drawLine(x + 6, y + 7, x + 10, y + 7);
                    g.drawLine(x + 6, y + 10, x + 10, y + 10);
                }
            },
            LOOP(new Color(0x8B5CF6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawArc(x + 3, y + 3, 10, 10, 35, 280);
                    g.drawLine(x + 12, y + 4, x + 13, y + 8);
                    g.drawLine(x + 12, y + 4, x + 8, y + 4);
                }
            },
            IF_CONTROLLER(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 3, x + 4, y + 13);
                    g.drawLine(x + 4, y + 8, x + 9, y + 8);
                    g.setColor(accent);
                    g.drawLine(x + 9, y + 5, x + 13, y + 8);
                    g.drawLine(x + 9, y + 11, x + 13, y + 8);
                }
            },
            SWITCH_CONTROLLER(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 8, x + 8, y + 8);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 8, x + 13, y + 4);
                    g.drawLine(x + 8, y + 8, x + 13, y + 12);
                    g.drawLine(x + 11, y + 3, x + 13, y + 4);
                    g.drawLine(x + 11, y + 5, x + 13, y + 4);
                    g.drawLine(x + 11, y + 11, x + 13, y + 12);
                    g.drawLine(x + 11, y + 13, x + 13, y + 12);
                }
            },
            WHILE_CONTROLLER(new Color(0x8B5CF6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawArc(x + 3, y + 3, 10, 10, 45, 250);
                    g.drawLine(x + 4, y + 6, x + 3, y + 3);
                    g.drawLine(x + 4, y + 6, x + 7, y + 5);
                    g.setColor(stroke);
                    g.fillOval(x + 7, y + 7, 3, 3);
                }
            },
            CRITICAL_CONTROLLER(new Color(0xDC2626)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawArc(x + 5, y + 2, 6, 7, 0, 180);
                    g.setColor(stroke);
                    g.drawRoundRect(x + 4, y + 7, 8, 6, 2, 2);
                    g.drawLine(x + 8, y + 9, x + 8, y + 11);
                }
            },
            FOREACH_CONTROLLER(new Color(0x14B8A6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 4, x + 10, y + 4);
                    g.drawLine(x + 4, y + 8, x + 10, y + 8);
                    g.drawLine(x + 4, y + 12, x + 10, y + 12);
                    g.setColor(accent);
                    g.drawLine(x + 10, y + 4, x + 13, y + 7);
                    g.drawLine(x + 13, y + 7, x + 10, y + 10);
                }
            },
            INCLUDE_CONTROLLER(new Color(0x14B8A6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 3, x + 4, y + 13);
                    g.drawLine(x + 12, y + 3, x + 12, y + 13);
                    g.setColor(accent);
                    g.drawLine(x + 5, y + 8, x + 11, y + 8);
                    g.drawLine(x + 9, y + 6, x + 11, y + 8);
                    g.drawLine(x + 9, y + 10, x + 11, y + 8);
                }
            },
            INTERLEAVE_CONTROLLER(new Color(0xEC4899)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 5, x + 7, y + 5);
                    g.drawLine(x + 3, y + 11, x + 7, y + 11);
                    g.setColor(accent);
                    g.drawLine(x + 7, y + 5, x + 13, y + 11);
                    g.drawLine(x + 7, y + 11, x + 13, y + 5);
                }
            },
            ONCE_CONTROLLER(new Color(0x6366F1)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawOval(x + 3, y + 3, 10, 10);
                    g.setColor(stroke);
                    g.drawLine(x + 8, y + 6, x + 8, y + 11);
                    g.drawLine(x + 6, y + 7, x + 8, y + 6);
                }
            },
            RANDOM_CONTROLLER(new Color(0xEC4899)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 4, y + 4, 8, 8, 2, 2);
                    g.setColor(accent);
                    g.fillOval(x + 6, y + 6, 2, 2);
                    g.fillOval(x + 9, y + 9, 2, 2);
                }
            },
            RANDOM_ORDER_CONTROLLER(new Color(0xEC4899)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 5, x + 7, y + 5);
                    g.drawLine(x + 3, y + 11, x + 7, y + 11);
                    g.setColor(accent);
                    g.drawLine(x + 7, y + 5, x + 13, y + 11);
                    g.drawLine(x + 7, y + 11, x + 13, y + 5);
                    g.drawLine(x + 11, y + 4, x + 13, y + 5);
                    g.drawLine(x + 11, y + 6, x + 13, y + 5);
                    g.drawLine(x + 11, y + 10, x + 13, y + 11);
                    g.drawLine(x + 11, y + 12, x + 13, y + 11);
                }
            },
            RECORDING_CONTROLLER(new Color(0xEF4444)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 3, y + 4, 10, 8, 3, 3);
                    g.setColor(accent);
                    g.fillOval(x + 6, y + 6, 4, 4);
                }
            },
            RUNTIME_CONTROLLER(new Color(0xD97706)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawOval(x + 4, y + 4, 8, 8);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 8, x + 8, y + 5);
                    g.drawLine(x + 8, y + 8, x + 11, y + 8);
                }
            },
            SIMPLE_CONTROLLER(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 4, y + 3, 8, 10, 2, 2);
                    g.setColor(accent);
                    g.drawLine(x + 6, y + 6, x + 10, y + 6);
                    g.drawLine(x + 6, y + 9, x + 10, y + 9);
                }
            },
            PARALLEL_CONTROLLER(new Color(0x06B6D4)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 4, x + 4, y + 12);
                    g.drawLine(x + 8, y + 4, x + 8, y + 12);
                    g.drawLine(x + 12, y + 4, x + 12, y + 12);
                    g.setColor(accent);
                    g.drawLine(x + 3, y + 8, x + 13, y + 8);
                }
            },
            THROUGHPUT_CONTROLLER(new Color(0x22C55E)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawArc(x + 3, y + 5, 10, 10, 0, 180);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 10, x + 12, y + 6);
                    g.fillOval(x + 7, y + 9, 2, 2);
                }
            },
            FORK_CONTROLLER(new Color(0x0EA5E9)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 8, x + 8, y + 8);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 8, x + 13, y + 4);
                    g.drawLine(x + 8, y + 8, x + 13, y + 12);
                    g.drawOval(x + 3, y + 7, 2, 2);
                    g.drawOval(x + 12, y + 3, 2, 2);
                    g.drawOval(x + 12, y + 11, 2, 2);
                }
            },
            BRANCH(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 4, x + 4, y + 12);
                    g.drawLine(x + 4, y + 8, x + 10, y + 8);
                    g.setColor(accent);
                    g.drawLine(x + 10, y + 5, x + 13, y + 8);
                    g.drawLine(x + 10, y + 11, x + 13, y + 8);
                }
            },
            TRANSACTION(new Color(0x06B6D4)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 3, x + 3, y + 13);
                    g.drawLine(x + 13, y + 3, x + 13, y + 13);
                    g.drawLine(x + 3, y + 3, x + 6, y + 3);
                    g.drawLine(x + 10, y + 3, x + 13, y + 3);
                    g.drawLine(x + 3, y + 13, x + 6, y + 13);
                    g.drawLine(x + 10, y + 13, x + 13, y + 13);
                    g.setColor(accent);
                    g.drawLine(x + 5, y + 8, x + 11, y + 8);
                    g.drawLine(x + 9, y + 6, x + 11, y + 8);
                    g.drawLine(x + 9, y + 10, x + 11, y + 8);
                    g.fillOval(x + 4, y + 7, 3, 3);
                    g.fillOval(x + 10, y + 7, 3, 3);
                }
            },
            MODULE(new Color(0x14B8A6)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRect(x + 3, y + 3, 4, 4);
                    g.drawRect(x + 9, y + 3, 4, 4);
                    g.drawRect(x + 6, y + 9, 4, 4);
                    g.setColor(accent);
                    g.drawLine(x + 7, y + 5, x + 9, y + 5);
                    g.drawLine(x + 8, y + 7, x + 8, y + 9);
                }
            },
            ROUTER(new Color(0xEC4899)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 5, x + 8, y + 5);
                    g.drawLine(x + 3, y + 11, x + 8, y + 11);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 5, x + 13, y + 3);
                    g.drawLine(x + 8, y + 11, x + 13, y + 13);
                    g.drawLine(x + 11, y + 2, x + 13, y + 3);
                    g.drawLine(x + 11, y + 4, x + 13, y + 3);
                    g.drawLine(x + 11, y + 12, x + 13, y + 13);
                    g.drawLine(x + 11, y + 14, x + 13, y + 13);
                }
            },
            TIMER(new Color(0xD97706)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawOval(x + 4, y + 4, 8, 8);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 8, x + 8, y + 5);
                    g.drawLine(x + 8, y + 8, x + 10, y + 10);
                }
            },
            ASSERTION(new Color(0x16A34A)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawOval(x + 3, y + 3, 10, 10);
                    g.setColor(accent);
                    g.drawLine(x + 5, y + 8, x + 7, y + 10);
                    g.drawLine(x + 7, y + 10, x + 12, y + 5);
                }
            },
            REPORT(new Color(0x2563EB)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawLine(x + 3, y + 13, x + 13, y + 13);
                    g.drawLine(x + 3, y + 13, x + 3, y + 4);
                    g.setColor(accent);
                    g.drawLine(x + 5, y + 10, x + 8, y + 7);
                    g.drawLine(x + 8, y + 7, x + 11, y + 9);
                    g.drawLine(x + 11, y + 9, x + 13, y + 4);
                    g.setColor(new Color(0x16A34A));
                    g.fillOval(x + 4, y + 9, 2, 2);
                    g.setColor(new Color(0xF59E0B));
                    g.fillOval(x + 12, y + 3, 2, 2);
                }
            },
            PROCESSOR(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawOval(x + 4, y + 4, 8, 8);
                    g.setColor(accent);
                    g.drawLine(x + 8, y + 2, x + 8, y + 14);
                    g.drawLine(x + 2, y + 8, x + 14, y + 8);
                }
            },
            PRE_PROCESSOR(new Color(0x0EA5E9)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 12, y + 4, x + 5, y + 8);
                    g.drawLine(x + 5, y + 8, x + 12, y + 12);
                    g.setColor(stroke);
                    g.drawLine(x + 4, y + 4, x + 4, y + 12);
                }
            },
            POST_PROCESSOR(new Color(0xF59E0B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(accent);
                    g.drawLine(x + 4, y + 4, x + 11, y + 8);
                    g.drawLine(x + 11, y + 8, x + 4, y + 12);
                    g.setColor(stroke);
                    g.drawLine(x + 12, y + 4, x + 12, y + 12);
                }
            },
            NODE(new Color(0x64748B)) {
                @Override
                void paint(Graphics2D g, int x, int y, Color stroke, Color accent) {
                    g.setColor(stroke);
                    g.drawRoundRect(x + 3, y + 4, 10, 8, 3, 3);
                }
            };

            private final Color accent;

            Kind(Color accent) {
                this.accent = accent;
            }

            abstract void paint(Graphics2D g, int x, int y, Color stroke, Color accent);
        }
    }
}
