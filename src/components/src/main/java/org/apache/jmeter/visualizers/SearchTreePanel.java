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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.function.BooleanSupplier;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;

import org.apache.jmeter.gui.Searchable;
import org.apache.jmeter.gui.action.KeyStrokes;
import org.apache.jmeter.gui.action.RawTextSearcher;
import org.apache.jmeter.gui.action.RegexpSearcher;
import org.apache.jmeter.gui.action.Searcher;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.utils.Colors;
import org.apache.jorphan.gui.JFactory;
import org.apache.jorphan.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panel used by {@link ViewResultsFullVisualizer} to search its tree and table result lists.
 * @since 3.0
 */
public class SearchTreePanel extends JPanel implements ActionListener {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(SearchTreePanel.class);

    private static final String SEARCH_TEXT_COMMAND = "search_text"; // $NON-NLS-1$

    private JButton searchButton;

    private JTextField searchTF;

    private JCheckBox isRegexpCB;

    private JCheckBox isCaseSensitiveCB;

    private JButton resetButton;

    private DefaultMutableTreeNode defaultMutableTreeNode;

    private ResultTableModel resultTableModel;

    private TableRowSorter<ResultTableModel> resultTableSorter;

    private BooleanSupplier tableMode;

    public SearchTreePanel(DefaultMutableTreeNode defaultMutableTreeNode) {
        this(defaultMutableTreeNode, null, null, () -> false);
    }

    SearchTreePanel(DefaultMutableTreeNode defaultMutableTreeNode, ResultTableModel resultTableModel,
            TableRowSorter<ResultTableModel> resultTableSorter, BooleanSupplier tableMode) {
        super();
        init();
        this.defaultMutableTreeNode = defaultMutableTreeNode;
        this.resultTableModel = resultTableModel;
        this.resultTableSorter = resultTableSorter;
        this.tableMode = tableMode;
    }

    /**
     * @deprecated only for use by test code
     */
    @Deprecated
    public SearchTreePanel() {
    }

    private class EnterAction extends AbstractAction {
        private static final long serialVersionUID = 2L;
        @Override
        public void actionPerformed(ActionEvent ev) {
            updateSearchFeedback(doSearch());
        }
    }

    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        setLayout(new BorderLayout(10,10));

        searchTF = new JTextField(20); //$NON-NLS-1$
        JFactory.small(searchTF);

        InputMap im = searchTF
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStrokes.ENTER, SEARCH_TEXT_COMMAND);
        ActionMap am = searchTF.getActionMap();
        am.put(SEARCH_TEXT_COMMAND, new EnterAction());

        isRegexpCB = new JCheckBox(JMeterUtils.getResString("search_text_chkbox_regexp"), false); //$NON-NLS-1$
        isCaseSensitiveCB = new JCheckBox(JMeterUtils.getResString("search_text_chkbox_case"), false); //$NON-NLS-1$

        JFactory.small(isRegexpCB);
        JFactory.small(isCaseSensitiveCB);

        searchButton = new JButton(JMeterUtils.getResString("search")); //$NON-NLS-1$
        searchButton.addActionListener(this);
        resetButton = new JButton(JMeterUtils.getResString("reset")); //$NON-NLS-1$
        resetButton.addActionListener(this);

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JLabel searchLabel = new JLabel(JMeterUtils.getResString("search_text_field"));
        JFactory.small(searchLabel);
        searchPanel.add(searchLabel);
        searchPanel.add(searchTF);
        searchPanel.add(isCaseSensitiveCB);
        searchPanel.add(isRegexpCB);
        searchPanel.add(searchButton);
        searchPanel.add(resetButton);
        add(searchPanel);
    }

    /**
     * Do search
     * @param e {@link ActionEvent}
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        if(e.getSource() == searchButton) {
            updateSearchFeedback(doSearch());
        } else if (e.getSource() == resetButton) {
            resetSearch();
        }
    }

    private void updateSearchFeedback(boolean found) {
        searchTF.setBackground(found ? Color.WHITE : Colors.LIGHT_RED);
        searchTF.setForeground(found ? Color.BLACK : Color.WHITE);
    }

    void resetSearch() {
        doResetSearch((SearchableTreeNode) defaultMutableTreeNode);
        if (resultTableSorter != null) {
            resultTableSorter.setRowFilter(null);
        }
        searchTF.setBackground(Color.WHITE);
        searchTF.setForeground(Color.BLACK);
    }

    void updateSearchTarget() {
        if (StringUtilities.isNotEmpty(searchTF.getText())) {
            updateSearchFeedback(doSearch());
        }
    }

    boolean search(String text, boolean caseSensitive, boolean regexp) {
        searchTF.setText(text);
        isCaseSensitiveCB.setSelected(caseSensitive);
        isRegexpCB.setSelected(regexp);
        boolean found = doSearch();
        updateSearchFeedback(found);
        return found;
    }

    /**
     * @param searchableTreeNode
     */
    private static void doResetSearch(SearchableTreeNode searchableTreeNode) {
        searchableTreeNode.reset();
        searchableTreeNode.updateState();
        for (int i = 0; i < searchableTreeNode.getChildCount(); i++) {
            doResetSearch((SearchableTreeNode)searchableTreeNode.getChildAt(i));
        }
    }


    /**
     * return true if a match occurred
     */
    private boolean doSearch() {
        String wordToSearch = searchTF.getText();
        if (StringUtilities.isEmpty(wordToSearch)) {
            return false;
        }
        Searcher searcher = isRegexpCB.isSelected() ?
            new RegexpSearcher(isCaseSensitiveCB.isSelected(), searchTF.getText()) :
            new RawTextSearcher(isCaseSensitiveCB.isSelected(), searchTF.getText());
        if (tableMode != null && tableMode.getAsBoolean() && resultTableSorter != null) {
            return searchTable(searcher);
        }
        return searchInNode(searcher, (SearchableTreeNode)defaultMutableTreeNode);
    }

    private boolean searchTable(Searcher searcher) {
        RowFilter<ResultTableModel, Integer> filter = new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends ResultTableModel, ? extends Integer> entry) {
                try {
                    return searcher.search(resultTableModel.sampleAt(entry.getIdentifier()).getSearchableTokens());
                } catch (Exception e) {
                    log.error("Error extracting data from table row using searcher:{}", searcher, e);
                    return false;
                }
            }
        };
        resultTableSorter.setRowFilter(filter);
        return resultTableSorter.getViewRowCount() > 0;
    }

    /**
     * @param searcher
     * @param node
     */
    private static boolean searchInNode(Searcher searcher, SearchableTreeNode node) {
        node.reset();
        Object userObject = node.getUserObject();

        try {
            Searchable searchable;
            if(userObject instanceof Searchable searchable1) {
                searchable = searchable1;
            } else {
                return false;
            }
            if(searcher.search(searchable.getSearchableTokens())) {
                node.setNodeHasMatched(true);
            }
            boolean foundInChildren = false;
            for (int i = 0; i < node.getChildCount(); i++) {
                foundInChildren =
                        searchInNode(searcher, (SearchableTreeNode)node.getChildAt(i))
                        || foundInChildren; // Must be the last in condition
            }
            if(!node.isNodeHasMatched()) {
                node.setChildrenNodesHaveMatched(foundInChildren);
            }
            node.updateState();
            return node.isNodeHasMatched() || node.isChildrenNodesHaveMatched();
        } catch (Exception e) {
            log.error("Error extracting data from tree node using searcher:{}", searcher, e);
            return false;
        }
    }
}
