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

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;

import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

final class ResultListSplitPane extends JSplitPane {
    private static final long serialVersionUID = -2065601726870149831L;
    private static final int MINIMUM_TABLE_DIVIDER_SIZE = 10;

    private Border defaultDividerBorder;
    private int defaultDividerSize;
    private boolean tableMode;

    ResultListSplitPane(int orientation, Component leftComponent, Component rightComponent) {
        super(orientation, leftComponent, rightComponent);
        captureDividerDefaults();
        addPropertyChangeListener("UI", event -> SwingUtilities.invokeLater(() -> { // $NON-NLS-1$
            captureDividerDefaults();
            updateDividerAppearance();
        }));
    }

    void setTableMode(boolean tableMode) {
        this.tableMode = tableMode;
        updateDividerAppearance();
    }

    private void captureDividerDefaults() {
        defaultDividerSize = getDividerSize();
        BasicSplitPaneDivider divider = divider();
        defaultDividerBorder = divider == null ? null : divider.getBorder();
    }

    private void updateDividerAppearance() {
        setDividerSize(tableMode ? Math.max(defaultDividerSize, MINIMUM_TABLE_DIVIDER_SIZE) : defaultDividerSize);
        BasicSplitPaneDivider divider = divider();
        if (divider != null) {
            divider.setBorder(tableMode ? TableDividerBorder.INSTANCE : defaultDividerBorder);
        }
    }

    private BasicSplitPaneDivider divider() {
        return getUI() instanceof BasicSplitPaneUI splitPaneUI ? splitPaneUI.getDivider() : null;
    }

    private static final class TableDividerBorder extends AbstractBorder {
        private static final long serialVersionUID = -7281649492300139679L;
        private static final TableDividerBorder INSTANCE = new TableDividerBorder();

        @Override
        public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            Color dividerColor = UIManager.getColor("Separator.foreground"); // $NON-NLS-1$
            if (dividerColor == null) {
                dividerColor = UIManager.getColor("Component.borderColor"); // $NON-NLS-1$
            }
            Color previousColor = graphics.getColor();
            graphics.setColor(dividerColor == null ? Color.GRAY : dividerColor);
            graphics.drawLine(x, y + height / 2, x + width - 1, y + height / 2);
            graphics.setColor(previousColor);
        }
    }
}
