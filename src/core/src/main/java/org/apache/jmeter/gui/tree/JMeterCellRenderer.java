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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.border.Border;
import javax.swing.tree.DefaultTreeCellRenderer;

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
    public JMeterCellRenderer() {
        // A little more air between the node icon and its label
        setIconTextGap(6);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
            boolean leaf, int row, boolean p_hasFocus) {
        JMeterTreeNode node = (JMeterTreeNode) value;
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

    private static final class ModernTreeIcon implements Icon {
        private static final int SIZE = 16;

        private final Kind kind;
        private final boolean enabled;

        private ModernTreeIcon(Kind kind, boolean enabled) {
            this.kind = kind;
            this.enabled = enabled;
        }

        static Icon from(JMeterTreeNode node, boolean enabled) {
            TestElement element = node.getTestElement();
            if (element == null) {
                return new ModernTreeIcon(Kind.NODE, enabled);
            }
            String guiClass = element.getPropertyAsString(TestElement.GUI_CLASS);
            String name = (guiClass == null ? "" : guiClass) + " " + element.getClass().getName(); // $NON-NLS-1$ // $NON-NLS-2$
            if (name.contains("TestPlanGui")) { // $NON-NLS-1$
                return new ModernTreeIcon(Kind.PLAN, enabled);
            }
            if (name.contains("ThreadGroup")) { // $NON-NLS-1$
                return new ModernTreeIcon(Kind.THREADS, enabled);
            }
            if (name.contains("Visualizer") || name.contains("Listener") || name.contains("ResultCollector")
                    || name.contains("Report")) { // $NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$ $NON-NLS-4$
                return new ModernTreeIcon(Kind.REPORT, enabled);
            }
            if (name.contains("Cookie")) { // $NON-NLS-1$
                return new ModernTreeIcon(Kind.COOKIE, enabled);
            }
            if (name.contains("Config") || name.contains("BreakTestAiKnowledge")
                    || name.contains("BreakTest AI Knowledge")) { // $NON-NLS-1$ $NON-NLS-2$ $NON-NLS-3$
                return new ModernTreeIcon(Kind.CONFIG, enabled);
            }
            if (name.contains("Sampler")) { // $NON-NLS-1$
                return new ModernTreeIcon(Kind.REQUEST, enabled);
            }
            Kind controllerKind = controllerKind(name);
            if (controllerKind != null) {
                return new ModernTreeIcon(controllerKind, enabled);
            }
            if (name.contains("Timer")) { // $NON-NLS-1$
                return new ModernTreeIcon(Kind.TIMER, enabled);
            }
            if (name.contains("Assertion")) { // $NON-NLS-1$
                return new ModernTreeIcon(Kind.ASSERTION, enabled);
            }
            if (name.contains("PreProcessor")) { // $NON-NLS-1$
                return new ModernTreeIcon(Kind.PRE_PROCESSOR, enabled);
            }
            if (name.contains("PostProcessor")) { // $NON-NLS-1$
                return new ModernTreeIcon(Kind.POST_PROCESSOR, enabled);
            }
            return new ModernTreeIcon(Kind.NODE, enabled);
        }

        private static Kind controllerKind(String descriptor) {
            if (descriptor.contains("IfController")) { // $NON-NLS-1$
                return Kind.IF_CONTROLLER;
            }
            if (descriptor.contains("SwitchController")) { // $NON-NLS-1$
                return Kind.SWITCH_CONTROLLER;
            }
            if (descriptor.contains("TransactionController")) { // $NON-NLS-1$
                return Kind.TRANSACTION;
            }
            if (descriptor.contains("LoopController")) { // $NON-NLS-1$
                return Kind.LOOP;
            }
            if (descriptor.contains("WhileController")) { // $NON-NLS-1$
                return Kind.WHILE_CONTROLLER;
            }
            if (descriptor.contains("CriticalSectionController")) { // $NON-NLS-1$
                return Kind.CRITICAL_CONTROLLER;
            }
            if (descriptor.contains("ForeachController") || descriptor.contains("ForEachController")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.FOREACH_CONTROLLER;
            }
            if (descriptor.contains("IncludeController")) { // $NON-NLS-1$
                return Kind.INCLUDE_CONTROLLER;
            }
            if (descriptor.contains("InterleaveControl")) { // $NON-NLS-1$
                return Kind.INTERLEAVE_CONTROLLER;
            }
            if (descriptor.contains("OnceOnlyController")) { // $NON-NLS-1$
                return Kind.ONCE_CONTROLLER;
            }
            if (descriptor.contains("RandomOrderController")) { // $NON-NLS-1$
                return Kind.RANDOM_ORDER_CONTROLLER;
            }
            if (descriptor.contains("RandomController")) { // $NON-NLS-1$
                return Kind.RANDOM_CONTROLLER;
            }
            if (descriptor.contains("RecordingController")) { // $NON-NLS-1$
                return Kind.RECORDING_CONTROLLER;
            }
            if (descriptor.contains("RuntimeController")) { // $NON-NLS-1$
                return Kind.RUNTIME_CONTROLLER;
            }
            if (descriptor.contains("ParallelController")) { // $NON-NLS-1$
                return Kind.PARALLEL_CONTROLLER;
            }
            if (descriptor.contains("ThroughputController")) { // $NON-NLS-1$
                return Kind.THROUGHPUT_CONTROLLER;
            }
            if (descriptor.contains("ForkController")) { // $NON-NLS-1$
                return Kind.FORK_CONTROLLER;
            }
            if (descriptor.contains("ModuleController")) { // $NON-NLS-1$
                return Kind.MODULE;
            }
            if (descriptor.contains("SimpleController") || descriptor.contains("Controller")) { // $NON-NLS-1$ $NON-NLS-2$
                return Kind.SIMPLE_CONTROLLER;
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

        private enum Kind {
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
                    g.drawRoundRect(x + 3, y + 4, 10, 8, 3, 3);
                    g.setColor(accent);
                    g.drawLine(x + 5, y + 8, x + 11, y + 8);
                    g.drawLine(x + 9, y + 6, x + 11, y + 8);
                    g.drawLine(x + 9, y + 10, x + 11, y + 8);
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
