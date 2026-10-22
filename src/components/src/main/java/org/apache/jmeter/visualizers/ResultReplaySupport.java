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

import java.awt.event.MouseEvent;
import java.util.function.Consumer;

import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.apache.jmeter.samplers.SampleResult;

/** Keeps explicitly opened results selected and their details visible. */
final class ResultReplaySupport {
    private ResultReplaySupport() {
    }

    static void selectResult(SampleResult sample, JTree tree, JTable table, ResultTableModel model,
            ResultListSplitPane split, boolean tableMode) {
        if (tableMode) {
            for (int row = 0; row < model.getRowCount(); row++) {
                if (model.sampleAt(row) == sample) {
                    int viewRow = table.convertRowIndexToView(row);
                    if (viewRow >= 0) {
                        table.setRowSelectionInterval(viewRow, viewRow);
                        table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
                        split.setDividerLocation(0.45);
                    }
                    return;
                }
            }
        } else {
            var nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                if (node.getUserObject() == sample) {
                    TreePath path = new TreePath(node.getPath());
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                    return;
                }
            }
        }
    }

    static void showTableResult(MouseEvent event, JTable table, ResultListSplitPane split,
            boolean detached, Consumer<SampleResult> navigate) {
        if (event.getClickCount() != 2 || event.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        int viewRow = table.rowAtPoint(event.getPoint());
        if (viewRow < 0) {
            return;
        }
        table.setRowSelectionInterval(viewRow, viewRow);
        if (detached) {
            ResultTableModel model = (ResultTableModel) table.getModel();
            navigate.accept(model.sampleAt(table.convertRowIndexToModel(viewRow)));
        } else {
            split.setDividerLocation(0.45);
        }
    }
}
