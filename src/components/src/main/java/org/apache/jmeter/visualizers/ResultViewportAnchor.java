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

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.tree.DefaultMutableTreeNode;

/** Keeps the first visible result in place across result list rebuilds. Used on the EDT. */
record ResultViewportAnchor(JComponent view, Object result, Point position, int rowOffset) {
    static ResultViewportAnchor capture(JComponent view) {
        Point position = ((JViewport) view.getParent()).getViewPosition();
        int row = view instanceof JTable table ? table.rowAtPoint(position)
                : ((JTree) view).getClosestRowForLocation(0, position.y);
        Object result = row < 0 ? null : resultAtViewRow(view, row);
        Rectangle bounds = row < 0 ? null : resultRowBounds(view, row);
        return new ResultViewportAnchor(view, result, position, bounds == null ? 0 : position.y - bounds.y);
    }

    private static Object resultAtViewRow(JComponent view, int row) {
        if (view instanceof JTable table) {
            return ((ResultTableModel) table.getModel()).sampleAt(table.convertRowIndexToModel(row));
        }
        return ((DefaultMutableTreeNode) ((JTree) view).getPathForRow(row).getLastPathComponent()).getUserObject();
    }

    private static Rectangle resultRowBounds(JComponent view, int row) {
        return view instanceof JTable table ? table.getCellRect(row, 0, true) : ((JTree) view).getRowBounds(row);
    }

    void restore() {
        JViewport viewport = (JViewport) view.getParent();
        Point restoredPosition = new Point(position);
        int rowCount = view instanceof JTable table ? table.getRowCount() : ((JTree) view).getRowCount();
        // Keep the same visible result in place when older rows are evicted or change height.
        for (int row = 0; result != null && row < rowCount; row++) {
            if (resultAtViewRow(view, row) == result) {
                restoredPosition.y = resultRowBounds(view, row).y + rowOffset;
                break;
            }
        }
        Dimension size = view.getPreferredSize();
        Dimension extent = viewport.getExtentSize();
        restoredPosition.x = Math.max(0, Math.min(restoredPosition.x, size.width - extent.width));
        restoredPosition.y = Math.max(0, Math.min(restoredPosition.y, size.height - extent.height));
        viewport.setViewPosition(restoredPosition);
    }
}
