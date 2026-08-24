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
import java.awt.CardLayout;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.TreePath;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.ReplaceableField;
import org.apache.jmeter.gui.Searchable;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
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
            FlagSource flagSource, Set<NodeType> nodeTypes, JMeterTreeNode scope, SearchMode mode) {}

    private record ScopeOption(String label, JMeterTreeNode node) {
        @Override
        public String toString() {
            return node == null ? label : node.getName();
        }
    }

    record FieldChange(ReplaceableField field, String value, int replacements) {}

    private enum SearchMode {
        FLAGGING,
        REPLACE
    }

    private enum FlagSource {
        TEXT,
        NODE_TYPES
    }

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

    private JButton replaceSearchButton;

    private JButton replaceNextButton;

    private JButton replacePreviousButton;

    private JButton replaceButton;

    private JButton replaceAllButton;

    private JButton findAndReplaceButton;

    private JButton removeMatchingButton;

    private JButton resetSearchButton;

    private JButton cancelButton;

    private JTextField searchTF;

    private JTextField replaceTF;

    private JComboBox<ScopeOption> scopeComboBox;

    private JTabbedPane modeTabs;

    private JLabel statusLabel;

    private JCheckBox isRegexpCB;

    private JCheckBox isCaseSensitiveCB;

    private JRadioButton flagByTextRB;

    private JRadioButton flagByNodeTypeRB;

    private JCheckBox flagPreProcessorsCB;

    private JCheckBox flagPostProcessorsCB;

    private JCheckBox flagAssertionsCB;

    private JCheckBox flagTimersCB;

    private JCheckBox flagConfigElementsCB;

    private transient javax.swing.Timer liveFlaggingTimer;


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
                if (modeTabs.getSelectedIndex() == 0) {
                    doSearch(actionEvent);
                } else {
                    doFindReplaceable(actionEvent);
                }
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

        searchTF = new JTextField(32);
        searchTF.setAlignmentY(TOP_ALIGNMENT);
        if (lastSearchConditions != null) {
            searchTF.setText(lastSearchConditions.word());
            isCaseSensitiveCB.setSelected(lastSearchConditions.caseSensitive());
            isRegexpCB.setSelected(lastSearchConditions.regex());
        }

        replaceTF = new JTextField(32);
        replaceTF.setAlignmentX(TOP_ALIGNMENT);
        scopeComboBox = new JComboBox<>();
        scopeComboBox.addActionListener(e -> {
            lastSearchConditions = null;
            lastSearchResult.clear();
            currentSearchIndex = -1;
            scheduleLiveFlagging();
        });
        statusLabel = new JLabel(" ");
        statusLabel.setPreferredSize(new Dimension(100, 20));
        statusLabel.setMinimumSize(new Dimension(100, 20));
        isRegexpCB = new JCheckBox(JMeterUtils.getResString("search_text_chkbox_regexp"), false); //$NON-NLS-1$
        isCaseSensitiveCB = new JCheckBox(JMeterUtils.getResString("search_text_chkbox_case"), true); //$NON-NLS-1$
        flagByTextRB = new JRadioButton(JMeterUtils.getResString("search_flag_by_text"), true);
        flagByNodeTypeRB = new JRadioButton(JMeterUtils.getResString("search_flag_by_node_types"), false);
        ButtonGroup flagSourceGroup = new ButtonGroup();
        flagSourceGroup.add(flagByTextRB);
        flagSourceGroup.add(flagByNodeTypeRB);

        JFactory.small(isRegexpCB);
        JFactory.small(isCaseSensitiveCB);

        JPanel searchCriterionPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        searchCriterionPanel.add(isCaseSensitiveCB);
        searchCriterionPanel.add(isRegexpCB);

        JPanel flagNodeTypesPanel = new JPanel(new GridLayout(0, 1));
        flagNodeTypesPanel.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 0));
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

        JPanel criteriaPanel = new JPanel(new MigLayout("fillx, wrap 2, hidemode 3", "[][fill,grow]"));
        criteriaPanel.setBorder(BorderFactory.createEmptyBorder(7, 3, 3, 3));
        criteriaPanel.add(JMeterUtils.labelFor(scopeComboBox, "scope"));
        criteriaPanel.add(scopeComboBox);
        criteriaPanel.add(flagByTextRB, "span 2");
        JLabel searchLabel = JMeterUtils.labelFor(searchTF, "search_text_field");
        criteriaPanel.add(searchLabel);
        criteriaPanel.add(searchTF);
        JLabel replaceLabel = JMeterUtils.labelFor(replaceTF, "search_text_replace");
        criteriaPanel.add(replaceLabel);
        criteriaPanel.add(replaceTF);
        replaceLabel.setVisible(false);
        replaceTF.setVisible(false);
        criteriaPanel.add(new JLabel());
        criteriaPanel.add(searchCriterionPanel, "growx");

        JPanel flaggingOptions = new JPanel(new BorderLayout());
        flaggingOptions.add(flagByNodeTypeRB, BorderLayout.NORTH);
        flaggingOptions.add(flagNodeTypesPanel, BorderLayout.CENTER);
        resetSearchButton = createButton("search_clear_flags");
        resetSearchButton.addActionListener(this);

        JPanel flaggingButtons = new JPanel(new GridLayout(0, 1, 0, 4));
        searchButton = createButton("search_flag_all"); //$NON-NLS-1$
        searchButton.addActionListener(this);
        nextButton = createButton("search_next"); //$NON-NLS-1$
        nextButton.addActionListener(this);
        previousButton = createButton("search_previous"); //$NON-NLS-1$
        previousButton.addActionListener(this);
        searchAndExpandButton = createButton("search_flag_all_expand"); //$NON-NLS-1$
        searchAndExpandButton.addActionListener(this);
        removeMatchingButton = createButton("search_remove_matching"); //$NON-NLS-1$
        removeMatchingButton.addActionListener(this);
        flaggingButtons.add(searchButton);
        flaggingButtons.add(nextButton);
        flaggingButtons.add(previousButton);
        flaggingButtons.add(searchAndExpandButton);
        flaggingButtons.add(removeMatchingButton);
        flaggingButtons.add(resetSearchButton);

        flaggingOptions.setBorder(BorderFactory.createEmptyBorder(0, 7, 0, 7));

        JPanel replaceOptions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        replaceOptions.add(new JLabel(JMeterUtils.getResString("search_replace_editable_fields_only")));

        JPanel replaceButtons = new JPanel(new GridLayout(0, 1, 0, 4));
        replaceSearchButton = createButton("search_find_all");
        replaceSearchButton.addActionListener(this);
        replaceNextButton = createButton("search_next");
        replaceNextButton.addActionListener(this);
        replacePreviousButton = createButton("search_previous");
        replacePreviousButton.addActionListener(this);
        replaceButton = createButton("search_replace"); //$NON-NLS-1$
        replaceButton.addActionListener(this);
        replaceAllButton = createButton("search_replace_all"); //$NON-NLS-1$
        replaceAllButton.addActionListener(this);
        findAndReplaceButton = createButton("search_find_and_replace"); //$NON-NLS-1$
        findAndReplaceButton.addActionListener(this);
        replaceButtons.add(replaceSearchButton);
        replaceButtons.add(replaceNextButton);
        replaceButtons.add(replacePreviousButton);
        replaceButtons.add(replaceButton);
        replaceButtons.add(replaceAllButton);
        replaceButtons.add(findAndReplaceButton);

        replaceOptions.setBorder(BorderFactory.createEmptyBorder(0, 7, 0, 7));

        CardLayout modeCardLayout = new CardLayout();
        JPanel modeCards = new JPanel(modeCardLayout);
        modeCards.add(flaggingOptions, SearchMode.FLAGGING.name());
        modeCards.add(replaceOptions, SearchMode.REPLACE.name());
        int modeCardWidth = Math.max(
                flaggingOptions.getPreferredSize().width, replaceOptions.getPreferredSize().width);
        modeCards.setPreferredSize(new Dimension(modeCardWidth, flaggingOptions.getPreferredSize().height));

        CardLayout buttonCardLayout = new CardLayout();
        JPanel buttonCards = new JPanel(buttonCardLayout);
        buttonCards.setBorder(BorderFactory.createEmptyBorder(7, 0, 7, 7));
        JPanel flaggingButtonColumn = new JPanel(new BorderLayout());
        flaggingButtonColumn.add(flaggingButtons, BorderLayout.NORTH);
        JPanel replaceButtonColumn = new JPanel(new BorderLayout());
        replaceButtonColumn.add(replaceButtons, BorderLayout.NORTH);
        buttonCards.add(flaggingButtonColumn, SearchMode.FLAGGING.name());
        buttonCards.add(replaceButtonColumn, SearchMode.REPLACE.name());

        modeTabs = new JTabbedPane();
        JPanel emptyFlaggingTab = new JPanel();
        emptyFlaggingTab.setPreferredSize(new Dimension(0, 0));
        JPanel emptyReplaceTab = new JPanel();
        emptyReplaceTab.setPreferredSize(new Dimension(0, 0));
        modeTabs.addTab(JMeterUtils.getResString("search_tab_flagging"), emptyFlaggingTab);
        modeTabs.addTab(JMeterUtils.getResString("search_tab_replace"), emptyReplaceTab);
        modeTabs.addChangeListener(e -> {
            boolean replaceMode = modeTabs.getSelectedIndex() == 1;
            replaceLabel.setVisible(replaceMode);
            replaceTF.setVisible(replaceMode);
            flagByTextRB.setVisible(!replaceMode);
            modeCardLayout.show(modeCards, replaceMode ? SearchMode.REPLACE.name() : SearchMode.FLAGGING.name());
            JPanel activeOptions = replaceMode ? replaceOptions : flaggingOptions;
            modeCards.setPreferredSize(new Dimension(modeCardWidth, activeOptions.getPreferredSize().height));
            buttonCardLayout.show(buttonCards, replaceMode ? SearchMode.REPLACE.name() : SearchMode.FLAGGING.name());
            updateFlagSourceControls(searchLabel);
            lastSearchConditions = null;
            lastSearchResult.clear();
            currentSearchIndex = -1;
            statusLabel.setText(" ");
            this.pack();
            scheduleLiveFlagging();
        });

        cancelButton = createButton("cancel"); //$NON-NLS-1$
        cancelButton.addActionListener(this);
        JPanel closePanel = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        closePanel.add(cancelButton);

        JPanel criteriaAndOptions = new JPanel(
                new MigLayout("fillx, wrap 1, insets 0, gapy 0", "[fill,grow]"));
        criteriaAndOptions.add(criteriaPanel, "growx");
        criteriaAndOptions.add(modeCards, "growx");
        criteriaAndOptions.add(statusLabel, "growx, gapleft 7");

        JPanel dialogContent = new JPanel(new BorderLayout(8, 4));
        dialogContent.add(criteriaAndOptions, BorderLayout.CENTER);
        dialogContent.add(buttonCards, BorderLayout.EAST);

        this.getContentPane().add(modeTabs, BorderLayout.NORTH);
        this.getContentPane().add(dialogContent, BorderLayout.CENTER);
        this.getContentPane().add(closePanel, BorderLayout.SOUTH);
        liveFlaggingTimer = new javax.swing.Timer(120, e -> refreshLiveFlagging());
        liveFlaggingTimer.setRepeats(false);
        installLiveFlaggingListeners(searchLabel);
        updateFlagSourceControls(searchLabel);
        searchTF.requestFocusInWindow();

        this.pack();
        ComponentUtil.centerComponentInWindow(this);
    }

    private static JButton createButton(String messageKey) {
        return new JButton(JMeterUtils.getResString(messageKey));
    }

    private static JCheckBox createFlagNodeTypeCheckBox(String messageKey) {
        JCheckBox checkBox = new JCheckBox(JMeterUtils.getResString(messageKey), false);
        JFactory.small(checkBox);
        return checkBox;
    }

    private void installLiveFlaggingListeners(JLabel searchLabel) {
        searchTF.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                scheduleLiveFlagging();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                scheduleLiveFlagging();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                scheduleLiveFlagging();
            }
        });
        flagByTextRB.addActionListener(e -> {
            updateFlagSourceControls(searchLabel);
            scheduleLiveFlagging();
        });
        flagByNodeTypeRB.addActionListener(e -> {
            updateFlagSourceControls(searchLabel);
            scheduleLiveFlagging();
        });
        isCaseSensitiveCB.addActionListener(e -> scheduleLiveFlagging());
        isRegexpCB.addActionListener(e -> scheduleLiveFlagging());
        flagPreProcessorsCB.addActionListener(e -> scheduleLiveFlagging());
        flagPostProcessorsCB.addActionListener(e -> scheduleLiveFlagging());
        flagAssertionsCB.addActionListener(e -> scheduleLiveFlagging());
        flagTimersCB.addActionListener(e -> scheduleLiveFlagging());
        flagConfigElementsCB.addActionListener(e -> scheduleLiveFlagging());
    }

    private void updateFlagSourceControls(JLabel searchLabel) {
        boolean replaceMode = modeTabs != null && modeTabs.getSelectedIndex() == 1;
        boolean textEnabled = replaceMode || flagByTextRB.isSelected();
        searchLabel.setEnabled(textEnabled);
        searchTF.setEnabled(textEnabled);
        isCaseSensitiveCB.setEnabled(textEnabled);
        isRegexpCB.setEnabled(textEnabled);

        boolean nodeTypesEnabled = !replaceMode && flagByNodeTypeRB.isSelected();
        flagPreProcessorsCB.setEnabled(nodeTypesEnabled);
        flagPostProcessorsCB.setEnabled(nodeTypesEnabled);
        flagAssertionsCB.setEnabled(nodeTypesEnabled);
        flagTimersCB.setEnabled(nodeTypesEnabled);
        flagConfigElementsCB.setEnabled(nodeTypesEnabled);
    }

    private void scheduleLiveFlagging() {
        if (liveFlaggingTimer == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            liveFlaggingTimer.restart();
        } else {
            SwingUtilities.invokeLater(liveFlaggingTimer::restart);
        }
    }

    private void refreshLiveFlagging() {
        if (!isVisible() || modeTabs == null || modeTabs.getSelectedIndex() != 0) {
            return;
        }
        boolean missingCriterion = flagByTextRB.isSelected()
                ? StringUtilities.isEmpty(searchTF.getText())
                : getSelectedNodeTypes().isEmpty();
        if (missingCriterion) {
            clearLiveFlagging();
            return;
        }
        doSearch(new ActionEvent(searchButton, ActionEvent.ACTION_PERFORMED, "live-flagging"));
    }

    private void clearLiveFlagging() {
        ActionRouter.getInstance().doActionNow(
                new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.SEARCH_RESET));
        lastSearchConditions = null;
        lastSearchResult.clear();
        currentSearchIndex = -1;
        statusLabel.setText(" ");
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage != null && guiPackage.getMainFrame() != null) {
            guiPackage.getMainFrame().repaint();
        }
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
        } else if (source == nextButton || source == previousButton) {
            doNavigateToSearchResult(source == nextButton, SearchMode.FLAGGING);
        } else if (source == replaceSearchButton) {
            doFindReplaceable(e);
        } else if (source == replaceNextButton || source == replacePreviousButton) {
            doNavigateToSearchResult(source == replaceNextButton, SearchMode.REPLACE);
        } else if (source == replaceAllButton) {
            doReplaceAll(e);
        } else if (source == replaceButton) {
            doReplace();
        } else if (source == findAndReplaceButton) {
            doFindAndReplace();
        } else if (source == removeMatchingButton) {
            doRemoveMatching(e);
        } else if(source == resetSearchButton) {
            doResetSearch(e);
        }
    }


    /**
    * Provides Reset Search Action
    */
    private void doResetSearch(ActionEvent event) {
        ActionRouter.getInstance().doActionNow(new ActionEvent(event.getSource(), event.getID(), ActionNames.SEARCH_RESET));
        lastSearchConditions = null;
        lastSearchResult.clear();
        currentSearchIndex = -1;
        statusLabel.setText(" ");
    }

    private boolean doReplace() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        guiPackage.updateCurrentNode();
        JMeterTreeNode selectedNode = guiPackage.getCurrentNode();
        if (selectedNode == null || !isWithinScope(selectedNode, selectedScopeNode())) {
            statusLabel.setText(JMeterUtils.getResString("search_replace_select_result"));
            return false;
        }
        Pattern pattern = replacementPatternOrShowError();
        if (pattern == null) {
            return false;
        }
        List<FieldChange> changes = replacementChangesOrShowError(
                selectedNode, pattern, replaceTF.getText());
        if (changes == null) {
            return false;
        }
        int replacements = applyChanges(changes);
        if (replacements > 0) {
            guiPackage.addUndoHistory("Replace in " + selectedNode.getName());
            guiPackage.updateCurrentGui();
            guiPackage.getMainFrame().repaint();
            scopeComboBox.repaint();
        }
        refreshReplaceableResults(pattern);
        statusLabel.setText(MessageFormat.format(
                JMeterUtils.getResString("search_replaced_occurrences"), replacements));
        return true;
    }

    private void doFindAndReplace() {
        if (doNavigateToSearchResult(true, SearchMode.REPLACE) != null) {
            doReplace();
        }
    }

    private JMeterTreeNode doNavigateToSearchResult(boolean isNext, SearchMode mode) {
        SearchConditions currentSearchConditions = currentSearchConditions(mode);
        boolean doSearchAgain =
                lastSearchConditions == null ||
                !currentSearchConditions.equals(lastSearchConditions);
        if(doSearchAgain) {
            String wordToSearch = searchTF.getText();
            if (currentSearchConditions.flagSource() == FlagSource.TEXT
                    && StringUtilities.isEmpty(wordToSearch)) {
                this.lastSearchConditions = null;
                statusLabel.setText(JMeterUtils.getResString("search_enter_text"));
                return null;
            }
            if (currentSearchConditions.flagSource() == FlagSource.NODE_TYPES
                    && currentSearchConditions.nodeTypes().isEmpty()) {
                this.lastSearchConditions = null;
                statusLabel.setText(JMeterUtils.getResString("search_select_node_type"));
                return null;
            }
            this.lastSearchConditions = currentSearchConditions;
            GuiPackage.getInstance().updateCurrentNode();
            if (mode == SearchMode.REPLACE) {
                Pattern pattern = replacementPatternOrShowError();
                if (pattern == null) {
                    return null;
                }
                searchReplaceableInTree(GuiPackage.getInstance(), pattern, currentSearchConditions.scope());
            } else if (currentSearchConditions.flagSource() == FlagSource.TEXT) {
                if (!validateSearchPattern()) {
                    return null;
                }
                searchInTree(GuiPackage.getInstance(), createSearcher(wordToSearch), wordToSearch,
                        currentSearchConditions.scope());
            } else {
                flagNodeTypesInTree(GuiPackage.getInstance(),
                        currentSearchConditions.nodeTypes(), currentSearchConditions.scope());
            }
        }
        return navigateInCurrentResults(isNext);
    }

    private JMeterTreeNode navigateInCurrentResults(boolean isNext) {
        if(!lastSearchResult.isEmpty()) {
            if(isNext) {
                currentSearchIndex = ++currentSearchIndex % lastSearchResult.size();
            } else {
                currentSearchIndex = currentSearchIndex > 0 ? --currentSearchIndex : lastSearchResult.size()-1;
            }
            return selectSearchResult(currentSearchIndex);
        }
        return null;
    }

    private JMeterTreeNode selectSearchResult(int index) {
        currentSearchIndex = index;
        JMeterTreeNode selectedNode = lastSearchResult.get(index);
        TreePath selection = new TreePath(selectedNode.getPath());
        GuiPackage.getInstance().getMainFrame().getTree().setSelectionPath(selection);
        GuiPackage.getInstance().getMainFrame().getTree().scrollPathToVisible(selection);
        return selectedNode;
    }

    /**
     * @param e {@link ActionEvent}
     */
    private void doSearch(ActionEvent e) {
        boolean expand = e.getSource()==searchAndExpandButton;
        String wordToSearch = searchTF.getText();
        Set<NodeType> nodeTypes = getSelectedNodeTypes();
        boolean flagByNodeType = flagByNodeTypeRB.isSelected();
        if (!flagByNodeType && StringUtilities.isEmpty(wordToSearch)) {
            this.lastSearchConditions = null;
            statusLabel.setText(JMeterUtils.getResString("search_enter_text"));
            return;
        }
        if (flagByNodeType && nodeTypes.isEmpty()) {
            this.lastSearchConditions = null;
            statusLabel.setText(JMeterUtils.getResString("search_select_node_type"));
            return;
        }
        if (!flagByNodeType && !validateSearchPattern()) {
            return;
        }
        this.lastSearchConditions = currentSearchConditions(SearchMode.FLAGGING);

        GuiPackage guiPackage = GuiPackage.getInstance();
        guiPackage.updateCurrentNode();
        // reset previous result
        ActionRouter.getInstance().doActionNow(new ActionEvent(e.getSource(), e.getID(), ActionNames.SEARCH_RESET));
        // do search
        Map.Entry<Integer, Set<JMeterTreeNode>> result = !flagByNodeType
                ? searchInTree(guiPackage, createSearcher(wordToSearch), wordToSearch, selectedScopeNode())
                : flagNodeTypesInTree(guiPackage, nodeTypes, selectedScopeNode());
        int numberOfMatches = result.getKey();
        guiPackage.withoutUndoHistory(() -> markConcernedNodes(expand, result.getValue()));
        GuiPackage.getInstance().getMainFrame().repaint();
        statusLabel.setText(
                MessageFormat.format(
                        JMeterUtils.getResString("search_tree_matches"), numberOfMatches));
    }

    private void doFindReplaceable(ActionEvent e) {
        if (StringUtilities.isEmpty(searchTF.getText())) {
            lastSearchConditions = null;
            statusLabel.setText(JMeterUtils.getResString("search_enter_text"));
            return;
        }
        Pattern pattern = replacementPatternOrShowError();
        if (pattern == null) {
            return;
        }
        lastSearchConditions = currentSearchConditions(SearchMode.REPLACE);
        GuiPackage.getInstance().updateCurrentNode();
        ActionRouter.getInstance().doActionNow(
                new ActionEvent(e.getSource(), e.getID(), ActionNames.SEARCH_RESET));
        SearchResult result = searchReplaceableInTree(
                GuiPackage.getInstance(), pattern, selectedScopeNode());
        GuiPackage.getInstance().withoutUndoHistory(() -> markConcernedNodes(false, result.nodes()));
        GuiPackage.getInstance().getMainFrame().repaint();
        statusLabel.setText(MessageFormat.format(
                JMeterUtils.getResString("search_replace_matches"),
                result.numberOfMatches(), result.nodes().size()));
        searchTF.requestFocusInWindow();
    }

    private void doRemoveMatching(ActionEvent e) {
        SearchConditions currentSearchConditions = currentSearchConditions(SearchMode.FLAGGING);
        String wordToSearch = currentSearchConditions.word();
        Set<NodeType> nodeTypes = currentSearchConditions.nodeTypes();
        if (currentSearchConditions.flagSource() == FlagSource.TEXT
                && StringUtilities.isEmpty(wordToSearch)) {
            this.lastSearchConditions = null;
            statusLabel.setText(JMeterUtils.getResString("search_enter_text"));
            return;
        }
        if (currentSearchConditions.flagSource() == FlagSource.NODE_TYPES && nodeTypes.isEmpty()) {
            this.lastSearchConditions = null;
            statusLabel.setText(JMeterUtils.getResString("search_select_node_type"));
            return;
        }
        if (currentSearchConditions.flagSource() == FlagSource.TEXT && !validateSearchPattern()) {
            return;
        }

        GuiPackage guiPackage = GuiPackage.getInstance();
        guiPackage.updateCurrentNode();
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

        // Clear search marks before mutating the tree.
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
        if (searchConditions.flagSource() == FlagSource.TEXT) {
            return searchInTree(guiPackage, createSearcher(searchConditions.word()), searchConditions.word(),
                    searchConditions.scope());
        }
        return flagNodeTypesInTree(guiPackage, searchConditions.nodeTypes(), searchConditions.scope());
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
            JMeterTreeNode scope) {
        int numberOfMatches = 0;
        JMeterTreeModel jMeterTreeModel = guiPackage.getTreeModel();
        Set<JMeterTreeNode> nodes = new LinkedHashSet<>();
        Path testPlanFile = testPlanFile(guiPackage);
        for (JMeterTreeNode jMeterTreeNode : jMeterTreeModel.getNodesOfType(Searchable.class)) {
            if (!isWithinScope(jMeterTreeNode, scope)) {
                continue;
            }
            try {
                Searchable searchable = (Searchable) jMeterTreeNode.getUserObject();
                List<String> searchableTokens = new ArrayList<>(searchable.getSearchableTokens());
                addRecordedExchangeTokens(searchableTokens, jMeterTreeNode, testPlanFile);
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

    private SearchResult searchReplaceableInTree(
            GuiPackage guiPackage, Pattern pattern, JMeterTreeNode scope) {
        int numberOfMatches = 0;
        Set<JMeterTreeNode> nodes = new LinkedHashSet<>();
        for (JMeterTreeNode node : guiPackage.getTreeModel().getNodesOfType(TestElement.class)) {
            if (!isWithinScope(node, scope)) {
                continue;
            }
            int nodeMatches = replaceableFields(node).stream()
                    .mapToInt(field -> countMatches(pattern, field.value()))
                    .sum();
            if (nodeMatches > 0) {
                numberOfMatches += nodeMatches;
                nodes.add(node);
            }
        }
        currentSearchIndex = -1;
        lastSearchResult.clear();
        lastSearchResult.addAll(nodes);
        return new SearchResult(numberOfMatches, nodes);
    }

    private void refreshReplaceableResults(Pattern pattern) {
        lastSearchConditions = currentSearchConditions(SearchMode.REPLACE);
        searchReplaceableInTree(GuiPackage.getInstance(), pattern, selectedScopeNode());
    }

    @VisibleForTesting
    static List<ReplaceableField> replaceableFields(JMeterTreeNode node) {
        if (!(node.getUserObject() instanceof TestElement testElement)) {
            return List.of();
        }
        List<ReplaceableField> fields = new ArrayList<>();
        fields.add(new ReplaceableField("Name", testElement::getName, testElement::setName));
        fields.add(new ReplaceableField("Comments", testElement::getComment, testElement::setComment));
        if (testElement instanceof Replaceable replaceable) {
            fields.addAll(replaceable.getReplaceableFields());
        }
        return fields;
    }

    private static int countMatches(Pattern pattern, String value) {
        if (StringUtilities.isEmpty(value)) {
            return 0;
        }
        int matches = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            matches++;
        }
        return matches;
    }

    private boolean validateSearchPattern() {
        if (!isRegexpCB.isSelected()) {
            return true;
        }
        return replacementPatternOrShowError() != null;
    }

    private Pattern replacementPatternOrShowError() {
        if (StringUtilities.isEmpty(searchTF.getText())) {
            statusLabel.setText(JMeterUtils.getResString("search_enter_text"));
            return null;
        }
        String expression = isRegexpCB.isSelected()
                ? searchTF.getText()
                : Pattern.quote(searchTF.getText());
        int flags = isCaseSensitiveCB.isSelected()
                ? 0
                : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return Pattern.compile(expression, flags);
        } catch (PatternSyntaxException ex) {
            statusLabel.setText(MessageFormat.format(
                    JMeterUtils.getResString("search_invalid_regexp"), ex.getDescription()));
            return null;
        }
    }

    @VisibleForTesting
    static List<FieldChange> replacementChanges(
            JMeterTreeNode node, Pattern pattern, String replacement, boolean regex) {
        List<FieldChange> changes = new ArrayList<>();
        String effectiveReplacement = regex ? replacement : Matcher.quoteReplacement(replacement);
        for (ReplaceableField field : replaceableFields(node)) {
            String currentValue = field.value();
            if (StringUtilities.isEmpty(currentValue)) {
                continue;
            }
            Matcher matcher = pattern.matcher(currentValue);
            int replacements = 0;
            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(result, effectiveReplacement);
                replacements++;
            }
            if (replacements > 0) {
                matcher.appendTail(result);
                changes.add(new FieldChange(field, result.toString(), replacements));
            }
        }
        return changes;
    }

    private List<FieldChange> replacementChangesOrShowError(
            JMeterTreeNode node, Pattern pattern, String replacement) {
        try {
            return replacementChanges(node, pattern, replacement, isRegexpCB.isSelected());
        } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
            statusLabel.setText(MessageFormat.format(
                    JMeterUtils.getResString("search_invalid_replacement"), ex.getMessage()));
            return null;
        }
    }

    @VisibleForTesting
    static int applyChanges(List<FieldChange> changes) {
        int replacements = 0;
        for (FieldChange change : changes) {
            change.field().setValue(change.value());
            replacements += change.replacements();
        }
        return replacements;
    }

    @VisibleForTesting
    static void addRecordedExchangeTokens(List<String> searchableTokens, JMeterTreeNode node,
            Path testPlanFile) {
        searchableTokens.addAll(RecordedHarExchangeResolver.searchableTokensFor(node, testPlanFile));
    }

    private static Path testPlanFile(GuiPackage guiPackage) {
        String testPlanFile = guiPackage.getTestPlanFile();
        return StringUtilities.isEmpty(testPlanFile) ? null : Path.of(testPlanFile);
    }

    private SearchResult flagNodeTypesInTree(
            GuiPackage guiPackage, Set<NodeType> nodeTypes, JMeterTreeNode scope) {
        int numberOfMatches = 0;
        JMeterTreeModel jMeterTreeModel = guiPackage.getTreeModel();
        Set<JMeterTreeNode> nodes = new LinkedHashSet<>();
        for (JMeterTreeNode jMeterTreeNode : jMeterTreeModel.getNodesOfType(TestElement.class)) {
            if (!isWithinScope(jMeterTreeNode, scope)) {
                continue;
            }
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

    private SearchConditions currentSearchConditions(SearchMode mode) {
        FlagSource flagSource = mode == SearchMode.FLAGGING && flagByNodeTypeRB.isSelected()
                ? FlagSource.NODE_TYPES
                : FlagSource.TEXT;
        return new SearchConditions(
                searchTF.getText(),
                isCaseSensitiveCB.isSelected(),
                isRegexpCB.isSelected(),
                flagSource,
                flagSource == FlagSource.NODE_TYPES ? Set.copyOf(getSelectedNodeTypes()) : Set.of(),
                selectedScopeNode(),
                mode);
    }

    private JMeterTreeNode selectedScopeNode() {
        ScopeOption selectedScope = (ScopeOption) scopeComboBox.getSelectedItem();
        return selectedScope == null ? null : selectedScope.node();
    }

    private void refreshScopeOptions() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        JMeterTreeNode currentNode = guiPackage == null ? null : guiPackage.getCurrentNode();
        JMeterTreeNode defaultScope = findThreadGroupScope(currentNode);

        ScopeOption all = new ScopeOption(JMeterUtils.getResString("search_scope_all"), null);
        scopeComboBox.removeAllItems();
        scopeComboBox.addItem(all);
        ScopeOption selected = all;
        if (guiPackage != null && guiPackage.getTreeModel() != null) {
            for (JMeterTreeNode threadGroupNode
                    : guiPackage.getTreeModel().getNodesOfType(AbstractThreadGroup.class)) {
                ScopeOption option = new ScopeOption(threadGroupNode.getName(), threadGroupNode);
                scopeComboBox.addItem(option);
                if (threadGroupNode == defaultScope) {
                    selected = option;
                }
            }
        }
        scopeComboBox.setSelectedItem(selected);
        lastSearchConditions = null;
    }

    @VisibleForTesting
    static JMeterTreeNode findThreadGroupScope(JMeterTreeNode node) {
        JMeterTreeNode current = node;
        while (current != null) {
            if (current.getTestElement() instanceof AbstractThreadGroup) {
                return current;
            }
            current = current.getParent() instanceof JMeterTreeNode parent ? parent : null;
        }
        return null;
    }

    @VisibleForTesting
    static boolean isWithinScope(JMeterTreeNode node, JMeterTreeNode scope) {
        if (scope == null) {
            return true;
        }
        JMeterTreeNode current = node;
        while (current != null) {
            if (current == scope) {
                return true;
            }
            current = current.getParent() instanceof JMeterTreeNode parent ? parent : null;
        }
        return false;
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
     * Replace all occurrences in explicitly replaceable fields.
     * @param e {@link ActionEvent}
     */
    private void doReplaceAll(ActionEvent e) {
        if (StringUtilities.isEmpty(searchTF.getText())) {
            statusLabel.setText(JMeterUtils.getResString("search_enter_text"));
            return;
        }
        Pattern pattern = replacementPatternOrShowError();
        if (pattern == null) {
            return;
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        guiPackage.updateCurrentNode();
        ActionRouter.getInstance().doActionNow(new ActionEvent(e.getSource(), e.getID(), ActionNames.SEARCH_RESET));
        SearchResult result = searchReplaceableInTree(guiPackage, pattern, selectedScopeNode());

        List<Map.Entry<JMeterTreeNode, List<FieldChange>>> plannedChanges = new ArrayList<>();
        for (JMeterTreeNode node : result.nodes()) {
            List<FieldChange> changes = replacementChangesOrShowError(
                    node, pattern, replaceTF.getText());
            if (changes == null) {
                return;
            }
            plannedChanges.add(Map.entry(node, changes));
        }

        int totalReplaced = 0;
        Set<JMeterTreeNode> replacedNodes = new HashSet<>();
        for (Map.Entry<JMeterTreeNode, List<FieldChange>> plannedChange : plannedChanges) {
            int replaced = applyChanges(plannedChange.getValue());
            if (replaced > 0) {
                totalReplaced += replaced;
                replacedNodes.add(plannedChange.getKey());
            }
        }
        if (totalReplaced > 0) {
            guiPackage.addUndoHistory("Replace all");
            guiPackage.withoutUndoHistory(() -> markConcernedNodes(false, replacedNodes));
            guiPackage.refreshCurrentGui();
            scopeComboBox.repaint();
        }
        refreshReplaceableResults(pattern);
        guiPackage.getMainFrame().repaint();
        statusLabel.setText(MessageFormat.format(
                JMeterUtils.getResString("search_replaced_occurrences"), totalReplaced));
        searchTF.requestFocusInWindow();
    }

    @Override
    public void setVisible(boolean b) {
        if (b && !isVisible()) {
            refreshScopeOptions();
        }
        super.setVisible(b);
        searchTF.requestFocusInWindow();
    }
}
