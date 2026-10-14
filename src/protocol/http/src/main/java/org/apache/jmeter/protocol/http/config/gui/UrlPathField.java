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

package org.apache.jmeter.protocol.http.config.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.View;

import org.apache.jorphan.gui.JLabeledField;

/**
 * Single-line URL path editor that soft-wraps while it has focus, so long paths and
 * query strings can be read and edited without horizontal scrolling. Without focus it
 * collapses to one row and marks hidden content with an ellipsis.
 */
final class UrlPathField extends JPanel implements JLabeledField, FocusListener {

    private static final long serialVersionUID = 1L;

    static final int MAX_EXPANDED_ROWS = 5;

    private static final int MIN_WIDTH = 100;

    private static final String ELLIPSIS = " … "; // $NON-NLS-1$

    private final JLabel label;

    private final PathTextArea textArea;

    private final JScrollPane scrollPane;

    private final List<ChangeListener> changeListeners = new ArrayList<>(3);

    private String valueOnFocus = "";

    private int visibleRows = 1;

    UrlPathField(String labelText) {
        super(new BorderLayout(5, 0));
        textArea = new PathTextArea();
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(false);
        textArea.setFont(UIManager.getFont("TextField.font")); // $NON-NLS-1$
        // The scroll pane draws the field border; inside it, pad the text like a text field
        Insets margin = UIManager.getInsets("TextField.margin"); // $NON-NLS-1$
        textArea.setBorder(margin == null
                ? BorderFactory.createEmptyBorder()
                : BorderFactory.createEmptyBorder(margin.top, margin.left, margin.bottom, margin.right));
        // A path is a single line: drop line breaks typed or pasted into it
        ((AbstractDocument) textArea.getDocument()).setDocumentFilter(new SingleLineFilter());
        // Tab moves focus like in a text field instead of inserting a tab character
        textArea.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        textArea.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        textArea.addFocusListener(this);
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateVisibleRows();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateVisibleRows();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateVisibleRows();
            }
        });

        scrollPane = new JScrollPane(textArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getPreferredSize() {
                // Width comes from the layout; the text wraps to whatever it gets
                return new Dimension(MIN_WIDTH, heightForRows(visibleRows));
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
        };
        if (UIManager.getBorder("TextField.border") != null) { // $NON-NLS-1$
            scrollPane.setBorder(UIManager.getBorder("TextField.border")); // $NON-NLS-1$
        }
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateVisibleRows();
            }
        });

        label = new JLabel(labelText);
        label.setLabelFor(textArea);
        // Keep the label beside the first row when the field grows
        label.setVerticalAlignment(JLabel.TOP);
        Insets insets = scrollPane.getInsets();
        label.setBorder(BorderFactory.createEmptyBorder(insets.top, 0, 0, 0));

        add(label, BorderLayout.WEST);
        add(scrollPane, BorderLayout.CENTER);
    }

    @Override
    public String getText() {
        return textArea.getText();
    }

    @Override
    public void setText(String text) {
        textArea.setText(text);
        textArea.setCaretPosition(0);
    }

    @Override
    public void setLabel(String labelText) {
        label.setText(labelText);
    }

    @Override
    public void addChangeListener(ChangeListener listener) {
        changeListeners.add(listener);
    }

    @Override
    public List<JComponent> getComponentList() {
        return List.of(label, textArea);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        textArea.setEnabled(enabled);
    }

    @Override
    public boolean requestFocusInWindow() {
        return textArea.requestFocusInWindow();
    }

    JTextArea getTextArea() {
        return textArea;
    }

    /**
     * @return the number of rows currently shown: one when collapsed, up to
     *         {@link #MAX_EXPANDED_ROWS} while editing
     */
    int getVisibleRows() {
        return visibleRows;
    }

    @Override
    public void focusGained(FocusEvent e) {
        valueOnFocus = textArea.getText();
        updateVisibleRows();
    }

    @Override
    public void focusLost(FocusEvent e) {
        updateVisibleRows();
        if (!valueOnFocus.equals(textArea.getText())) {
            ChangeEvent event = new ChangeEvent(this);
            for (ChangeListener listener : changeListeners) {
                listener.stateChanged(event);
            }
        }
    }

    private void updateVisibleRows() {
        boolean expanded = textArea.isFocusOwner();
        int rows = expanded ? Math.min(Math.max(contentRows(), 1), MAX_EXPANDED_ROWS) : 1;
        scrollPane.setVerticalScrollBarPolicy(expanded && contentRows() > MAX_EXPANDED_ROWS
                ? ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                : ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        if (!expanded) {
            // Collapsed: always show the start of the path
            scrollPane.getViewport().setViewPosition(new Point(0, 0));
        }
        if (rows != visibleRows) {
            visibleRows = rows;
            revalidate();
            repaint();
        }
        textArea.repaint();
    }

    /**
     * @return the number of rows the wrapped text needs at the current width
     */
    int contentRows() {
        int width = scrollPane.getViewport().getWidth();
        if (width <= 0 || textArea.getDocument().getLength() == 0) {
            return 1;
        }
        View root = textArea.getUI().getRootView(textArea);
        Insets insets = textArea.getInsets();
        root.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
        float height = root.getPreferredSpan(View.Y_AXIS);
        int rowHeight = rowHeight();
        return Math.max(1, Math.round(height / rowHeight));
    }

    private int rowHeight() {
        return textArea.getFontMetrics(textArea.getFont()).getHeight();
    }

    private int heightForRows(int rows) {
        Insets scrollInsets = scrollPane.getInsets();
        Insets textInsets = textArea.getInsets();
        return rows * rowHeight() + scrollInsets.top + scrollInsets.bottom + textInsets.top + textInsets.bottom;
    }

    private final class PathTextArea extends JTextArea {
        private static final long serialVersionUID = 1L;

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (isFocusOwner() || contentRows() <= 1) {
                return;
            }
            // Collapsed with more rows below: mark the cut-off at the end of the first row
            FontMetrics metrics = g.getFontMetrics(getFont());
            int ellipsisWidth = metrics.stringWidth(ELLIPSIS);
            Insets insets = getInsets();
            int x = getWidth() - insets.right - ellipsisWidth;
            int y = insets.top;
            g.setColor(getBackground());
            g.fillRect(x, y, ellipsisWidth, metrics.getHeight());
            g.setColor(isEnabled() ? getForeground() : getDisabledTextColor());
            g.drawString(ELLIPSIS, x, y + metrics.getAscent());
        }
    }

    private static final class SingleLineFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr)
                throws BadLocationException {
            super.insertString(fb, offset, stripLineBreaks(text), attr);
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            super.replace(fb, offset, length, stripLineBreaks(text), attrs);
        }

        private static String stripLineBreaks(String text) {
            return text == null ? null : text.replace("\r", "").replace("\n", ""); // $NON-NLS-1$ // $NON-NLS-2$
        }
    }
}
