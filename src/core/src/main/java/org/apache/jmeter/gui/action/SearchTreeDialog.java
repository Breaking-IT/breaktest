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

package org.apache.jmeter.gui.action;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.tree.TreePath;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.Searchable;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.documentation.VisibleForTesting;
import org.apache.jorphan.gui.ComponentUtil;
import org.apache.jorphan.gui.JFactory;
import org.apache.jorphan.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.miginfocom.swing.MigLayout;

/**
 * Dialog to search in tree of element
 */
public class SearchTreeDialog extends JDialog implements ActionListener { // NOSONAR

    record SearchConditions(String word, Boolean caseSensitive, Boolean regex,
            Boolean recordedExchanges, Set<NodeType> nodeTypes) {}

    enum NodeType {
        PRE_PROCESSOR,
        POST_PROCESSOR,
        ASSERTION,
        TIMER,
        CONFIG_ELEMENT
    }

    private static final long serialVersionUID = -4436834972710248247L;

    private static final Logger logger = LoggerFactory.getLogger(SearchTreeDialog.class);

    private JButton searchButton;

    private JButton nextButton;

    private JButton previousButton;

    private JButton searchAndExpandButton;

    private JButton replaceButton;

    private JButton replaceAllButton;

    private JButton replaceAndFindButton;

    private JButton removeMatchingButton;

    private JButton resetSearchButton;

    private JButton cancelButton;

    private JTextField searchTF;

    private JTextField replaceTF;

    private JLabel statusLabel;

    private JCheckBox isRegexpCB;

    private JCheckBox isCaseSensitiveCB;

    private JCheckBox includeRecordedExchangesCB;

    private JCheckBox flagPreProcessorsCB;

    private JCheckBox flagPostProcessorsCB;

    private JCheckBox flagAssertionsCB;

    private JCheckBox flagTimersCB;

    private JCheckBox flagConfigElementsCB;


    private transient SearchConditions lastSearchConditions = null;

    private final List<JMeterTreeNode> lastSearchResult = new ArrayList<>();
    private int currentSearchIndex;

    @VisibleForTesting
    public SearchTreeDialog() {
        super();
    }

    public SearchTreeDialog(JFrame parent) {
        super(parent, JMeterUtils.getResString("search_tree_title"), false); //$NON-NLS-1$
        init();
    }

    @Override
    protected JRootPane createRootPane() {
        JRootPane rootPane = new JRootPane();
        // Hide Window on ESC
        Action escapeAction = new AbstractAction("ESCAPE") {

            private static final long serialVersionUID = -6543764044868772971L;

            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                setVisible(false);
            }
        };
        // Do search on Enter
        Action enterAction = new AbstractAction("ENTER") {

            private static final long serialVersionUID = -3661361497864527363L;

            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                doSearch(actionEvent);
            }
        };
        ActionMap actionMap = rootPane.getActionMap();
        actionMap.put(escapeAction.getValue(Action.NAME), escapeAction);
        actionMap.put(enterAction.getValue(Action.NAME), enterAction);
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        inputMap.put(KeyStrokes.ESC, escapeAction.getValue(Action.NAME));
        inputMap.put(KeyStrokes.ENTER, enterAction.getValue(Action.NAME));

        return rootPane;
    }

    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        this.getContentPane().setLayout(new BorderLayout(10,10));

        searchTF = new JTextField(20);
        searchTF.setAlignmentY(TOP_ALIGNMENT);
        if (lastSearchConditions != null) {
            searchTF.setText(lastSearchConditions.word());
            isCaseSensitiveCB.setSelected(lastSearchConditions.caseSensitive());
            isRegexpCB.setSelected(lastSearchConditions.regex());
        }

        replaceTF = new JTextField(20);
        replaceTF.setAlignmentX(TOP_ALIGNMENT);
        statusLabel = new JLabel(" ");
        statusLabel.setPreferredSize(new Dimension(100, 20));
        statusLabel.setMinimumSize(new Dimension(100, 20));
        isRegexpCB = new JCheckBox(JMeterUtils.getResString("search_text_chkbox_regexp"), false); //$NON-NLS-1$
        isCaseSensitiveCB = new JCheckBox(JMeterUtils.getResString("search_text_chkbox_case"), true); //$NON-NLS-1$
        includeRecordedExchangesCB = new JCheckBox(
                JMeterUtils.getResString("search_text_chkbox_recorded_exchanges"), false); //$NON-NLS-1$

        JFactory.small(isRegexpCB);
        JFactory.small(isCaseSensitiveCB);
        JFactory.small(includeRecordedExchangesCB);
        includeRecordedExchangesCB.addActionListener(e -> updateReplaceControlsState());

        JPanel searchCriterionPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        searchCriterionPanel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString("search_matching"))); //$NON-NLS-1$
        searchCriterionPanel.add(isCaseSensitiveCB);
        searchCriterionPanel.add(isRegexpCB);
        searchCriterionPanel.add(includeRecordedExchangesCB);

        JPanel flagNodeTypesPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        flagNodeTypesPanel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString("search_flag_node_types")));
        flagPreProcessorsCB = createFlagNodeTypeCheckBox("menu_pre_processors");
        flagPostProcessorsCB = createFlagNodeTypeCheckBox("menu_post_processors");
        flagAssertionsCB = createFlagNodeTypeCheckBox("menu_assertions");
        flagTimersCB = createFlagNodeTypeCheckBox("menu_timer");
        flagConfigElementsCB = createFlagNodeTypeCheckBox("menu_config_element");
        flagNodeTypesPanel.add(flagPreProcessorsCB);
        flagNodeTypesPanel.add(flagPostProcessorsCB);
        flagNodeTypesPanel.add(flagAssertionsCB);
        flagNodeTypesPanel.add(flagTimersCB);
        flagNodeTypesPanel.add(flagConfigElementsCB);

        JPanel searchPanel = new JPanel();
        searchPanel.setLayout(new MigLayout("fillx, wrap 2", "[][fill,grow]"));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(7, 3, 3, 3));
        searchPanel.add(JMeterUtils.labelFor(searchTF, "search_text_field"));
        searchPanel.add(searchTF);
        searchPanel.add(JMeterUtils.labelFor(replaceTF, "search_text_replace"));
        searchPanel.add(replaceTF);
        searchPanel.add(statusLabel, "span 2");
        searchPanel.add(searchCriterionPanel, "span 2");
        searchPanel.add(flagNodeTypesPanel, "span 2");
        resetSearchButton = createButton("menu_search_reset");
        resetSearchButton.addActionListener(this);
        searchPanel.add(resetSearchButton);


        JPanel buttonsPanel = new JPanel(new GridLayout(10, 1));
        searchButton = createButton("search_search_all"); //$NON-NLS-1$
        searchButton.addActionListener(this);
        nextButton = createButton("search_next"); //$NON-NLS-1$
        nextButton.addActionListener(this);
        previousButton = createButton("search_previous"); //$NON-NLS-1$
        previousButton.addActionListener(this);
        searchAndExpandButton = createButton("search_search_all_expand"); //$NON-NLS-1$
        searchAndExpandButton.addActionListener(this);
        replaceButton = createButton("search_replace"); //$NON-NLS-1$
        replaceButton.addActionListener(this);
        replaceAllButton = createButton("search_replace_all"); //$NON-NLS-1$
        replaceAllButton.addActionListener(this);
        replaceAndFindButton = createButton("search_replace_and_find"); //$NON-NLS-1$
        replaceAndFindButton.addActionListener(this);
        removeMatchingButton = createButton("search_remove_matching"); //$NON-NLS-1$
        removeMatchingButton.addActionListener(this);
        cancelButton = createButton("cancel"); //$NON-NLS-1$
        cancelButton.addActionListener(this);
        buttonsPanel.add(nextButton);
        buttonsPanel.add(previousButton);
        buttonsPanel.add(searchButton);
        buttonsPanel.add(searchAndExpandButton);
        buttonsPanel.add(Box.createVerticalStrut(30));
        buttonsPanel.add(replaceButton);
        buttonsPanel.add(replaceAllButton);
        buttonsPanel.add(replaceAndFindButton);
        buttonsPanel.add(removeMatchingButton);
        buttonsPanel.add(cancelButton);
        updateReplaceControlsState();

        JPanel searchAndReplacePanel = new JPanel();
        searchAndReplacePanel.setLayout(new BorderLayout());
        searchAndReplacePanel.add(searchPanel, BorderLayout.CENTER);
        searchAndReplacePanel.add(buttonsPanel, BorderLayout.EAST);
        this.getContentPane().add(searchAndReplacePanel);
        searchTF.requestFocusInWindow();

        this.pack();
        ComponentUtil.centerComponentInWindow(this);
    }

    private static JButton createButton(String messageKey) {
        return new JButton(JMeterUtils.getResString(messageKey));
    }

    private JCheckBox createFlagNodeTypeCheckBox(String messageKey) {
        JCheckBox checkBox = new JCheckBox(JMeterUtils.getResString(messageKey), false);
        JFactory.small(checkBox);
        checkBox.addActionListener(e -> updateReplaceControlsState());
        return checkBox;
    }

    /**
     * Do search
     * @param e {@link ActionEvent}
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        Object source = e.getSource();
        statusLabel.setText("");
        if (source == cancelButton) {
            searchTF.requestFocusInWindow();
            this.setVisible(false);
        } else if (source == searchButton
                || source == searchAndExpandButton) {
            doSearch(e);
        } else if (source == nextButton ||
                source == previousButton) {
            doNavigateToSearchResult(source == nextButton);
        } else if (source == replaceAllButton && !isNodeTypeFlagMode()){
            doReplaceAll(e);
        } else if (!lastSearchResult.isEmpty() && source == replaceButton && !isNodeTypeFlagMode()){
            doReplace();
        } else if (source == replaceAndFindButton && !isNodeTypeFlagMode()){
            if(!lastSearchResult.isEmpty()) {
                doReplace();
            }
            doNavigateToSearchResult(true);
        } else if (source == removeMatchingButton) {
            doRemoveMatching(e);
        } else if(source == resetSearchButton) {
            doResetSearch(e);
        }
    }


    /**
    * Provides Reset Search Action
    */
    private static void doResetSearch(ActionEvent event) {
        ActionRouter.getInstance().doActionNow(new ActionEvent(event.getSource(), event.getID(), ActionNames.SEARCH_RESET));
    }

    /**
     *
     */
    private void doReplace() {
        GuiPackage.getInstance().updateCurrentNode();
        int nbReplacements = 0;
        if(currentSearchIndex >= 0) {
            JMeterTreeNode currentNode = lastSearchResult.get(currentSearchIndex);
            if(currentNode != null) {
                String wordToSearch = searchTF.getText();
                String wordToReplace = replaceTF.getText();
                String regex = isRegexpCB.isSelected() ? wordToSearch : Pattern.quote(wordToSearch);
                boolean caseSensitiveReplacement = isCaseSensitiveCB.isSelected();
                Map.Entry<Integer, JMeterTreeNode> pair = doReplacementInCurrentNode(currentNode, regex, wordToReplace, caseSensitiveReplacement);
                if(pair != null) {
                    nbReplacements = pair.getKey();
                    GuiPackage.getInstance().updateCurrentGui();
                    GuiPackage.getInstance().getMainFrame().repaint();
                }
            }
        }
        statusLabel.setText(MessageFormat.format("Replaced {0} occurrences", nbReplacements));
    }

    private JMeterTreeNode doNavigateToSearchResult(boolean isNext) {
        SearchConditions currentSearchConditions = currentSearchConditions();
        boolean doSearchAgain =
                lastSearchConditions == null ||
                !currentSearchConditions.equals(lastSearchConditions);
        if(doSearchAgain) {
            String wordToSearch = searchTF.getText();
            Set<NodeType> nodeTypes = currentSearchConditions.nodeTypes();
            if (nodeTypes.isEmpty() && StringUtilities.isEmpty(wordToSearch)) {
                this.lastSearchConditions = null;
                return null;
            } else {
                this.lastSearchConditions = currentSearchConditions;
            }
            if (nodeTypes.isEmpty()) {
                Searcher searcher = createSearcher(wordToSearch);
                searchInTree(GuiPackage.getInstance(), searcher, wordToSearch,
                        currentSearchConditions.recordedExchanges());
            } else {
                flagNodeTypesInTree(GuiPackage.getInstance(), nodeTypes);
            }
        }
        if(!lastSearchResult.isEmpty()) {
            if(isNext) {
                currentSearchIndex = ++currentSearchIndex % lastSearchResult.size();
            } else {
                currentSearchIndex = currentSearchIndex > 0 ? --currentSearchIndex : lastSearchResult.size()-1;
            }
            JMeterTreeNode selectedNode = lastSearchResult.get(currentSearchIndex);
            TreePath selection = new TreePath(selectedNode.getPath());
            GuiPackage.getInstance().getMainFrame().getTree().setSelectionPath(selection);
            GuiPackage.getInstance().getMainFrame().getTree().scrollPathToVisible(selection);
            return selectedNode;
        }
        return null;
    }

    /**
     * @param e {@link ActionEvent}
     */
    private void doSearch(ActionEvent e) {
        boolean expand = e.getSource()==searchAndExpandButton;
        String wordToSearch = searchTF.getText();
        Set<NodeType> nodeTypes = getSelectedNodeTypes();
        if (nodeTypes.isEmpty() && StringUtilities.isEmpty(wordToSearch)) {
            this.lastSearchConditions = null;
            return;
        } else {
            this.lastSearchConditions = currentSearchConditions();
        }

        // reset previous result
        ActionRouter.getInstance().doActionNow(new ActionEvent(e.getSource(), e.getID(), ActionNames.SEARCH_RESET));
        // do search
        GuiPackage guiPackage = GuiPackage.getInstance();
        guiPackage.beginUndoTransaction();
        int numberOfMatches = 0;
        try {
            Map.Entry<Integer, Set<JMeterTreeNode>> result = nodeTypes.isEmpty()
                    ? searchInTree(guiPackage, createSearcher(wordToSearch), wordToSearch,
                            includeRecordedExchangesCB.isSelected())
                    : flagNodeTypesInTree(guiPackage, nodeTypes);
            numberOfMatches = result.getKey();
            markConcernedNodes(expand, result.getValue());
        } finally {
            guiPackage.endUndoTransaction();
        }
        GuiPackage.getInstance().getMainFrame().repaint();
        searchTF.requestFocusInWindow();
        statusLabel.setText(
                MessageFormat.format(
                        JMeterUtils.getResString("search_tree_matches"), numberOfMatches));
    }

    private void doRemoveMatching(ActionEvent e) {
        SearchConditions currentSearchConditions = currentSearchConditions();
        String wordToSearch = currentSearchConditions.word();
        Set<NodeType> nodeTypes = currentSearchConditions.nodeTypes();
        if (nodeTypes.isEmpty() && StringUtilities.isEmpty(wordToSearch)) {
            this.lastSearchConditions = null;
            return;
        }

        GuiPackage guiPackage = GuiPackage.getInstance();
        SearchResult result = findMatchingNodes(guiPackage, currentSearchConditions);
        List<JMeterTreeNode> nodesToRemove = new ArrayList<>(result.nodes());
        if (nodesToRemove.isEmpty()) {
            statusLabel.setText(MessageFormat.format(
                    JMeterUtils.getResString("search_tree_matches"), result.numberOfMatches()));
            return;
        }
        List<JMeterTreeNode> confirmedNodesToRemove = confirmRemoveMatching(nodesToRemove);
        if (confirmedNodesToRemove == null) {
            searchTF.requestFocusInWindow();
            return;
        }
        if (confirmedNodesToRemove.isEmpty()) {
            searchTF.requestFocusInWindow();
            statusLabel.setText(MessageFormat.format(
                    JMeterUtils.getResString("search_remove_matching_status"), 0));
            return;
        }

        // Save any change to current node before mutating the tree.
        guiPackage.updateCurrentNode();
        ActionRouter.getInstance().doActionNow(new ActionEvent(e.getSource(), e.getID(), ActionNames.SEARCH_RESET));

        int removed = 0;
        guiPackage.beginUndoTransaction();
        try {
            for (JMeterTreeNode node : sortedForRemoval(confirmedNodesToRemove)) {
                if (removeMatchingNode(guiPackage, node)) {
                    removed++;
                }
            }
        } finally {
            guiPackage.endUndoTransaction();
        }

        this.lastSearchConditions = null;
        this.currentSearchIndex = -1;
        this.lastSearchResult.clear();
        selectRootNode(guiPackage);
        guiPackage.refreshCurrentGui();
        guiPackage.getMainFrame().repaint();
        searchTF.requestFocusInWindow();
        statusLabel.setText(MessageFormat.format(
                JMeterUtils.getResString("search_remove_matching_status"), removed));
    }

    private SearchResult findMatchingNodes(GuiPackage guiPackage, SearchConditions searchConditions) {
        if (searchConditions.nodeTypes().isEmpty()) {
            return searchInTree(guiPackage, createSearcher(searchConditions.word()), searchConditions.word(),
                    searchConditions.recordedExchanges());
        }
        return flagNodeTypesInTree(guiPackage, searchConditions.nodeTypes());
    }

    private List<JMeterTreeNode> confirmRemoveMatching(List<JMeterTreeNode> nodesToRemove) {
        List<JCheckBox> matchedElementCheckboxes = nodesToRemove.stream()
                .map(node -> new JCheckBox(formatNodePath(node), true))
                .toList();
        JPanel matchedElements = new JPanel(new GridLayout(0, 1));
        for (JCheckBox matchedElementCheckbox : matchedElementCheckboxes) {
            matchedElements.add(matchedElementCheckbox);
        }
        JScrollPane scrollPane = new JScrollPane(matchedElements);
        scrollPane.setPreferredSize(new Dimension(520, 260));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(MessageFormat.format(
                JMeterUtils.getResString("search_remove_matching_confirm"), nodesToRemove.size())), BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(
                this,
                panel,
                JMeterUtils.getResString("search_remove_matching_title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        List<JMeterTreeNode> selectedNodes = new ArrayList<>();
        for (int i = 0; i < matchedElementCheckboxes.size(); i++) {
            if (matchedElementCheckboxes.get(i).isSelected()) {
                selectedNodes.add(nodesToRemove.get(i));
            }
        }
        return selectedNodes;
    }

    private static String formatNodePath(JMeterTreeNode node) {
        return String.join(" > ",
                List.of(node.getPath()).stream()
                        .map(pathNode -> ((JMeterTreeNode) pathNode).getName())
                        .toList());
    }

    private static List<JMeterTreeNode> sortedForRemoval(List<JMeterTreeNode> nodes) {
        return nodes.stream()
                .sorted(Comparator.comparingInt(JMeterTreeNode::getLevel).reversed())
                .toList();
    }

    private static boolean removeMatchingNode(GuiPackage guiPackage, JMeterTreeNode node) {
        TestElement testElement = node.getTestElement();
        if (!testElement.canRemove()) {
            logger.warn("Cannot remove matching search element {} because it is busy", testElement.getName());
            return false;
        }
        guiPackage.getTreeModel().removeNodeFromParent(node);
        guiPackage.removeNode(testElement);
        testElement.removed();
        return true;
    }

    private static void selectRootNode(GuiPackage guiPackage) {
        Object root = guiPackage.getTreeModel().getRoot();
        if (root instanceof JMeterTreeNode rootNode) {
            guiPackage.getMainFrame().getTree().setSelectionPath(new TreePath(rootNode.getPath()));
        }
    }

    /**
     * @param wordToSearch
     * @return
     */
    private Searcher createSearcher(String wordToSearch) {
        if (isRegexpCB.isSelected()) {
            return new RegexpSearcher(isCaseSensitiveCB.isSelected(), wordToSearch);
        } else {
            return new RawTextSearcher(isCaseSensitiveCB.isSelected(), wordToSearch);
        }
    }

    private SearchResult searchInTree(GuiPackage guiPackage, Searcher searcher, String wordToSearch,
            boolean includeRecordedExchanges) {
        int numberOfMatches = 0;
        JMeterTreeModel jMeterTreeModel = guiPackage.getTreeModel();
        Set<JMeterTreeNode> nodes = new LinkedHashSet<>();
        Path testPlanFile = testPlanFile(guiPackage);
        for (JMeterTreeNode jMeterTreeNode : jMeterTreeModel.getNodesOfType(Searchable.class)) {
            try {
                Searchable searchable = (Searchable) jMeterTreeNode.getUserObject();
                List<String> searchableTokens = new ArrayList<>(searchable.getSearchableTokens());
                if (includeRecordedExchanges && testPlanFile != null) {
                    searchableTokens.addAll(RecordedHarExchangeResolver.searchableTokensFor(
                            jMeterTreeNode, testPlanFile));
                }
                boolean result = searcher.search(searchableTokens);
                if (result) {
                    numberOfMatches++;
                    nodes.add(jMeterTreeNode);
                }
            } catch (Exception ex) {
                logger.error("Error occurred searching for word:{} in node:{}", wordToSearch, jMeterTreeNode.getName(), ex);
            }
        }
        this.currentSearchIndex = -1;
        this.lastSearchResult.clear();
        this.lastSearchResult.addAll(nodes);
        return new SearchResult(numberOfMatches, nodes);
    }

    private static Path testPlanFile(GuiPackage guiPackage) {
        String testPlanFile = guiPackage.getTestPlanFile();
        return StringUtilities.isEmpty(testPlanFile) ? null : Path.of(testPlanFile);
    }

    private SearchResult flagNodeTypesInTree(GuiPackage guiPackage, Set<NodeType> nodeTypes) {
        int numberOfMatches = 0;
        JMeterTreeModel jMeterTreeModel = guiPackage.getTreeModel();
        Set<JMeterTreeNode> nodes = new LinkedHashSet<>();
        for (JMeterTreeNode jMeterTreeNode : jMeterTreeModel.getNodesOfType(TestElement.class)) {
            TestElement testElement = (TestElement) jMeterTreeNode.getUserObject();
            if (matchesAnySelectedNodeType(testElement, nodeTypes)) {
                numberOfMatches++;
                nodes.add(jMeterTreeNode);
            }
        }
        this.currentSearchIndex = -1;
        this.lastSearchResult.clear();
        this.lastSearchResult.addAll(nodes);
        return new SearchResult(numberOfMatches, nodes);
    }

    private record SearchResult(Integer numberOfMatches, Set<JMeterTreeNode> nodes)
            implements Map.Entry<Integer, Set<JMeterTreeNode>> {
        @Override
        public Integer getKey() {
            return numberOfMatches;
        }

        @Override
        public Set<JMeterTreeNode> getValue() {
            return nodes;
        }

        @Override
        public Set<JMeterTreeNode> setValue(Set<JMeterTreeNode> value) {
            throw new UnsupportedOperationException();
        }
    }

    static boolean matchesAnySelectedNodeType(TestElement testElement, Set<NodeType> nodeTypes) {
        return nodeTypes.contains(NodeType.PRE_PROCESSOR) && testElement instanceof PreProcessor
                || nodeTypes.contains(NodeType.POST_PROCESSOR) && testElement instanceof PostProcessor
                || nodeTypes.contains(NodeType.ASSERTION) && testElement instanceof Assertion
                || nodeTypes.contains(NodeType.TIMER) && testElement instanceof Timer
                || nodeTypes.contains(NodeType.CONFIG_ELEMENT) && testElement instanceof ConfigElement;
    }

    private SearchConditions currentSearchConditions() {
        return new SearchConditions(
                searchTF.getText(),
                isCaseSensitiveCB.isSelected(),
                isRegexpCB.isSelected(),
                includeRecordedExchangesCB.isSelected(),
                Set.copyOf(getSelectedNodeTypes()));
    }

    private Set<NodeType> getSelectedNodeTypes() {
        Set<NodeType> nodeTypes = EnumSet.noneOf(NodeType.class);
        if (flagPreProcessorsCB.isSelected()) {
            nodeTypes.add(NodeType.PRE_PROCESSOR);
        }
        if (flagPostProcessorsCB.isSelected()) {
            nodeTypes.add(NodeType.POST_PROCESSOR);
        }
        if (flagAssertionsCB.isSelected()) {
            nodeTypes.add(NodeType.ASSERTION);
        }
        if (flagTimersCB.isSelected()) {
            nodeTypes.add(NodeType.TIMER);
        }
        if (flagConfigElementsCB.isSelected()) {
            nodeTypes.add(NodeType.CONFIG_ELEMENT);
        }
        return nodeTypes;
    }

    private boolean isNodeTypeFlagMode() {
        return !getSelectedNodeTypes().isEmpty();
    }

    private void updateReplaceControlsState() {
        boolean enabled = !isNodeTypeFlagMode() && !includeRecordedExchangesCB.isSelected();
        replaceTF.setEnabled(enabled);
        replaceButton.setEnabled(enabled);
        replaceAllButton.setEnabled(enabled);
        replaceAndFindButton.setEnabled(enabled);
    }

    /**
     * @param expand true if we want to expand
     * @param nodes Set of {@link JMeterTreeNode} to mark
     */
    private static void markConcernedNodes(boolean expand, Set<? extends JMeterTreeNode> nodes) {
        GuiPackage guiInstance = GuiPackage.getInstance();
        JTree jTree = guiInstance.getMainFrame().getTree();
        for (JMeterTreeNode jMeterTreeNode : nodes) {
            jMeterTreeNode.setMarkedBySearch(true);
            if (expand) {
                if(jMeterTreeNode.isLeaf()) {
                    jTree.expandPath(new TreePath(((JMeterTreeNode)jMeterTreeNode.getParent()).getPath()));
                } else {
                    jTree.expandPath(new TreePath(jMeterTreeNode.getPath()));
                }
            }
        }
    }

    /**
     * Replace all occurrences in nodes that contain {@link Replaceable} Test Elements
     * @param e {@link ActionEvent}
     */
    private void doReplaceAll(ActionEvent e) {
        boolean expand = e.getSource()==searchAndExpandButton;
        String wordToSearch = searchTF.getText();
        String wordToReplace = replaceTF.getText();
        if (StringUtilities.isEmpty(wordToReplace)) {
            return;
        }
        // Save any change to current node
        GuiPackage.getInstance().updateCurrentNode();
        // reset previous result
        ActionRouter.getInstance().doActionNow(new ActionEvent(e.getSource(), e.getID(), ActionNames.SEARCH_RESET));
        Searcher searcher = createSearcher(wordToSearch);
        String regex = isRegexpCB.isSelected() ? wordToSearch : Pattern.quote(wordToSearch);
        GuiPackage guiPackage = GuiPackage.getInstance();
        boolean caseSensitiveReplacement = isCaseSensitiveCB.isSelected();
        int totalReplaced = 0;
        Map.Entry<Integer, Set<JMeterTreeNode>> result = searchInTree(guiPackage, searcher, wordToSearch, false);
        Set<JMeterTreeNode> matchingNodes = result.getValue();
        Set<JMeterTreeNode> replacedNodes = new HashSet<>();
        for (JMeterTreeNode jMeterTreeNode : matchingNodes) {
            Map.Entry<Integer, JMeterTreeNode> pair = doReplacementInCurrentNode(jMeterTreeNode, regex, wordToReplace, caseSensitiveReplacement);
            if(pair != null) {
                totalReplaced += pair.getKey();
                replacedNodes.add(pair.getValue());
            }
        }
        statusLabel.setText(MessageFormat.format("Replaced {0} occurrences", totalReplaced));
        markConcernedNodes(expand, replacedNodes);
        // Update GUI as current node may be concerned by changes
        if (totalReplaced > 0) {
            GuiPackage.getInstance().refreshCurrentGui();
        }
        GuiPackage.getInstance().getMainFrame().repaint();

        searchTF.requestFocusInWindow();
    }

    /**
     * Replace in jMeterTreeNode regex by replaceBy
     * @param jMeterTreeNode Current {@link JMeterTreeNode}
     * @param regex Text to search (can be regex)
     * @param replaceBy Replacement text
     * @param caseSensitiveReplacement boolean if search is case sensitive
     * @return null if no replacement occurred or Map.Entry of (number of replacement, current tree node)
     */
    private static Map.Entry<Integer, JMeterTreeNode> doReplacementInCurrentNode(JMeterTreeNode jMeterTreeNode,
            String regex, String replaceBy, boolean caseSensitiveReplacement) {
        try {
            if (jMeterTreeNode.getUserObject() instanceof Replaceable replaceable) {
                int numberOfReplacements = replaceable.replace(regex, replaceBy, caseSensitiveReplacement);
                if (numberOfReplacements > 0) {
                    if (logger.isInfoEnabled()) {
                        logger.info("Replaced {} in element:{}", numberOfReplacements,
                                ((TestElement) jMeterTreeNode.getUserObject()).getName());
                    }
                    return Map.entry(numberOfReplacements, jMeterTreeNode);
                }
            }
        } catch (Exception ex) {
            logger.error("Error occurred replacing data in node:{}", jMeterTreeNode.getName(), ex);
        }
        return null;
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        searchTF.requestFocusInWindow();
    }
}
