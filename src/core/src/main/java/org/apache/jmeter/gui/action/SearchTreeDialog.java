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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.RemovableRow;
import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.ReplaceableField;
import org.apache.jmeter.gui.RowField;
import org.apache.jmeter.gui.SearchArea;
import org.apache.jmeter.gui.Searchable;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.ParameterCompletion;
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
            FlagSource flagSource, Set<NodeType> nodeTypes, SearchScope scope, Set<SearchArea> areas, RowField rowField, SearchMode mode) {}

    record SearchScope(JMeterTreeNode threadGroup) {}

    static final class ScopeOption {
        private final String label;
        private JMeterTreeNode node;
        private ScopeKey key;

        ScopeOption(String label, JMeterTreeNode node) {
            this.label = label;
            this.node = node;
            this.key = scopeKey(node);
        }

        JMeterTreeNode resolve(JMeterTreeModel model) {
            node = resolveScope(node, key, model);
            if (node != null && node.getRoot() == model.getRoot()) {
                key = scopeKey(node);
            }
            return node;
        }

        @Override
        public String toString() {
            return node == null ? label : node.getName();
        }
    }

    record FieldChange(ReplaceableField field, String value, int replacements) {}

    record RowMatch(JMeterTreeNode node, RemovableRow row, javax.swing.tree.TreeNode root) {}

    enum RemovalTarget {
        ELEMENTS, HEADERS, PARAMETERS, ROWS;

        Set<SearchArea> areas() {
            return switch (this) {
                case ELEMENTS -> Set.of();
                case HEADERS -> Set.of(SearchArea.HEADERS);
                case PARAMETERS -> Set.of(SearchArea.PARAMETERS);
                case ROWS -> Set.of(SearchArea.HEADERS, SearchArea.PARAMETERS);
            };
        }

        @Override
        public String toString() {
            return JMeterUtils.getResString("search_removal_target_" + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

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

    private JComboBox<RemovalTarget> removalTarget;
    private JComboBox<RowField> rowFieldCombo;
    private transient GuiPackage scopeGui;
    private long scopeSession = -1;
    private Dimension searchDialogSize;
    private final Map<String, Dimension> previewSizes = new java.util.HashMap<>();

    private JButton resetSearchButton;

    private JButton cancelButton;

    private JTextField searchTF;

    private JTextField replaceTF;

    private JComboBox<ScopeOption> scopeComboBox;
    private final Map<SearchArea, JCheckBox> areaBoxes = new EnumMap<>(SearchArea.class);

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
        ParameterCompletion.install(searchTF);
        searchTF.setAlignmentY(TOP_ALIGNMENT);
        if (lastSearchConditions != null) {
            searchTF.setText(lastSearchConditions.word());
            isCaseSensitiveCB.setSelected(lastSearchConditions.caseSensitive());
            isRegexpCB.setSelected(lastSearchConditions.regex());
        }

        replaceTF = new JTextField(32);
        ParameterCompletion.install(replaceTF);
        replaceTF.setAlignmentX(TOP_ALIGNMENT);
        scopeComboBox = new JComboBox<>();
        scopeComboBox.addActionListener(e -> scopeChanged());
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
        JPanel scopeBox = new JPanel(new MigLayout("fillx, wrap 2", "[][fill,grow]"));
        scopeBox.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString("scope")));
        scopeBox.add(JMeterUtils.labelFor(scopeComboBox, "search_thread_groups"));
        scopeBox.add(scopeComboBox, "growx");
        scopeBox.add(new JLabel(JMeterUtils.getResString("search_areas")), "top");
        scopeBox.add(createAreaPanel(), "growx");
        rowFieldCombo = new JComboBox<>(RowField.values());
        rowFieldCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof RowField field
                        ? JMeterUtils.getResString("search_row_field_" + field.name().toLowerCase(java.util.Locale.ROOT)) : value,
                        index, selected, focus);
            }
        });
        rowFieldCombo.addActionListener(e -> scopeChanged());
        scopeBox.add(JMeterUtils.labelFor(rowFieldCombo, "search_row_fields"));
        scopeBox.add(rowFieldCombo, "growx");
        criteriaPanel.add(scopeBox, "span 2, growx");
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

        JPanel flaggingButtons = new JPanel(new MigLayout("insets 0, wrap 1, fillx, gapy 4", "[fill,grow]"));
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
        removalTarget = new JComboBox<>(RemovalTarget.values());
        removalTarget.addActionListener(e -> {
            if (removalTarget.getSelectedItem() != RemovalTarget.ELEMENTS) {
                flagByTextRB.doClick();
            }
        });
        JPanel removalOptions = new JPanel(new BorderLayout(0, 2));
        removalOptions.add(JMeterUtils.labelFor(removalTarget, "search_removal_target"), BorderLayout.NORTH);
        removalOptions.add(removalTarget, BorderLayout.CENTER);
        flaggingButtons.add(removalOptions);
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
            Dimension previousSize = isVisible() ? getSize() : null;
            this.pack();
            if (previousSize != null) {
                setSize(Math.max(previousSize.width, getWidth()), Math.max(previousSize.height, getHeight()));
            }
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

    static <T> T editWithUndo(GuiPackage gui, String description, Predicate<T> changed, Supplier<T> edit) {
        // Capture pending editor edits as the baseline, independently of dirty trackers.
        gui.beginUndoTransaction(description);
        try {
            T result = edit.get();
            if (!changed.test(result)) {
                return result;
            }
            gui.setDirty(true);
            if (gui.getCurrentNode() == null || gui.getCurrentNode().getRoot() != gui.getTreeModel().getRoot()) {
                selectRootNode(gui);
            }
            gui.refreshCurrentGui();
            gui.addUndoHistory(description);
            return result;
        } finally {
            gui.endUndoTransaction();
        }
    }

    private boolean doReplace() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        guiPackage.updateCurrentNode();
        JMeterTreeNode selectedNode = guiPackage.getCurrentNode();
        if (selectedNode == null || !isWithinSearchScope(selectedNode, selectedScope())) {
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
        int replacements = editWithUndo(guiPackage, "Replace in " + selectedNode.getName(), count -> count > 0, () -> applyChanges(changes));
        if (replacements > 0) {
            guiPackage.refreshCurrentGui();
            guiPackage.getMainFrame().repaint();
            refreshScopeLabels();
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
                ? searchInTree(guiPackage, createSearcher(wordToSearch), wordToSearch, selectedScope())
                : flagNodeTypesInTree(guiPackage, nodeTypes, selectedScope());
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
                GuiPackage.getInstance(), pattern, selectedScope());
        GuiPackage.getInstance().withoutUndoHistory(() -> markConcernedNodes(false, result.nodes()));
        GuiPackage.getInstance().getMainFrame().repaint();
        statusLabel.setText(MessageFormat.format(
                JMeterUtils.getResString("search_replace_matches"),
                result.numberOfMatches(), result.nodes().size()));
        searchTF.requestFocusInWindow();
    }

    private void doRemoveMatching(ActionEvent e) {
        if (removalTarget.getSelectedItem() != RemovalTarget.ELEMENTS) {
            doRemoveRows();
            return;
        }
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

        liveFlaggingTimer.stop();
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
        int cleaned = 0;
        int skipped = 0;
        Set<JMeterTreeNode> affectedControllers = new LinkedHashSet<>();
        guiPackage.beginUndoTransaction("Remove matching elements");
        try {
            for (JMeterTreeNode node : sortedForRemoval(confirmedNodesToRemove)) {
                List<JMeterTreeNode> ancestors = controllerAncestors(node);
                if (removeMatchingNode(guiPackage, node)) {
                    removed++;
                    affectedControllers.addAll(ancestors);
                } else {
                    skipped++;
                }
            }
            List<JMeterTreeNode> emptied = emptiedControllers(affectedControllers, (JMeterTreeNode) guiPackage.getTreeModel().getRoot());
            if (!emptied.isEmpty()) {
                List<JMeterTreeNode> selected = confirmRemoveMatching(emptied, true);
                if (selected != null) {
                    for (JMeterTreeNode node : sortedForRemoval(selected)) {
                        // A retained or busy child must also keep its parent controller.
                        if (node.getChildCount() == 0 && removeMatchingNode(guiPackage, node)) {
                            cleaned++;
                        } else {
                            skipped++;
                        }
                    }
                }
            }
            if (removed > 0 || cleaned > 0) {
                guiPackage.setDirty(true);
            }
            selectRootNode(guiPackage);
            guiPackage.refreshCurrentGui();
            guiPackage.addUndoHistory("Remove matching elements and empty controllers");
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
                JMeterUtils.getResString("search_remove_matching_cleanup_status"), removed, cleaned, skipped));
    }

    private void doRemoveRows() {
        if (flagByNodeTypeRB.isSelected()) {
            statusLabel.setText(JMeterUtils.getResString("search_remove_rows_text_only"));
            return;
        }
        if (StringUtilities.isEmpty(searchTF.getText())) {
            statusLabel.setText(JMeterUtils.getResString("search_enter_text"));
            return;
        }
        if (!validateSearchPattern()) {
            return;
        }
        Set<SearchArea> areas = selectedAreas();
        areas.retainAll(((RemovalTarget) removalTarget.getSelectedItem()).areas());
        if (areas.isEmpty()) {
            statusLabel.setText(JMeterUtils.getResString("search_remove_rows_select_area"));
            return;
        }
        // Modal confirmation runs a nested event loop: a pending live search must
        // not save the editor and replace the row objects captured below.
        liveFlaggingTimer.stop();
        GuiPackage gui = GuiPackage.getInstance();
        gui.updateCurrentNode();
        List<RowMatch> matches = matchingRows(gui.getTreeModel().getNodesOfType(TestElement.class),
                selectedScope(), areas, createSearcher(searchTF.getText()), selectedRowField());
        if (matches.isEmpty()) {
            statusLabel.setText(JMeterUtils.getResString("search_remove_rows_no_matches"));
            return;
        }
        List<RowMatch> selected = confirmRemoval(matches, SearchTreeDialog::formatRowMatch,
                "search_remove_rows_confirm", "search_remove_rows_title");
        if (selected == null || selected.isEmpty()) {
            return;
        }
        // ActionRouter refreshes the open editor before commands, invalidating
        // row identities in its header/parameter collections. Only clear marks.
        ResetSearchCommand.clearSearchMarks(gui);
        RowRemovalResult removalResult = editWithUndo(gui, "Remove matching header/parameter rows", result -> result.removed() > 0,
                () -> removeRowsWithResult(selected));
        lastSearchConditions = null;
        lastSearchResult.clear();
        currentSearchIndex = -1;
        gui.refreshCurrentGui();
        gui.getMainFrame().repaint();
        statusLabel.setText(MessageFormat.format(JMeterUtils.getResString("search_remove_rows_result"),
                removalResult.removed(), removalResult.skipped(), removalResult.stale(), removalResult.busy()));
    }

    static List<RowMatch> matchingRows(List<JMeterTreeNode> nodes, SearchScope scope,
            Set<SearchArea> areas, Searcher searcher) {
        return matchingRows(nodes, scope, areas, searcher, RowField.ALL);
    }

    static List<RowMatch> matchingRows(List<JMeterTreeNode> nodes, SearchScope scope,
            Set<SearchArea> areas, Searcher searcher, RowField field) {
        List<RowMatch> matches = new ArrayList<>();
        for (JMeterTreeNode node : nodes) {
            if (!node.isRoot() && isWithinSearchScope(node, scope)) {
                for (RemovableRow row : RemovableRow.forElement(node.getTestElement())) {
                    if (areas.contains(row.area()) && searcher.search(field.tokens(row.tokens()))) {
                        matches.add(new RowMatch(node, row, node.getRoot()));
                    }
                }
            }
        }
        return matches;
    }

    record RowRemovalResult(int removed, int stale, int busy) {
        int skipped() {
            return stale + busy;
        }
    }

    static int removeRows(List<RowMatch> rows) {
        return removeRowsWithResult(rows).removed();
    }

    static RowRemovalResult removeRowsWithResult(List<RowMatch> rows) {
        int removed = 0;
        int stale = 0;
        int busy = 0;
        for (RowMatch match : rows) {
            if (!match.node().getTestElement().canRemove()) {
                busy++;
            } else if (match.node().getParent() == null || match.node().getRoot() != match.root() || !match.row().remove()) {
                stale++;
            } else {
                removed++;
            }
        }
        return new RowRemovalResult(removed, stale, busy);
    }

    private static String formatRowMatch(RowMatch match) {
        String area = JMeterUtils.getResString("search_area_" + match.row().area().name().toLowerCase(java.util.Locale.ROOT));
        String values = String.join(" = ", match.row().tokens()).replace('\n', ' ').replace('\r', ' ');
        return formatNodePath(match.node()) + " > " + area + " #" + match.row().number() + ": " + values;
    }

    private SearchResult findMatchingNodes(GuiPackage guiPackage, SearchConditions searchConditions) {
        if (searchConditions.flagSource() == FlagSource.TEXT) {
            return searchInTree(guiPackage, createSearcher(searchConditions.word()), searchConditions.word(),
                    searchConditions.scope());
        }
        return flagNodeTypesInTree(guiPackage, searchConditions.nodeTypes(), searchConditions.scope());
    }

    private List<JMeterTreeNode> confirmRemoveMatching(List<JMeterTreeNode> nodesToRemove) {
        return confirmRemoveMatching(nodesToRemove, false);
    }

    private List<JMeterTreeNode> confirmRemoveMatching(List<JMeterTreeNode> nodesToRemove, boolean cleanup) {
        return confirmRemoval(nodesToRemove, SearchTreeDialog::formatNodePath,
                cleanup ? "search_cleanup_controllers_confirm" : "search_remove_matching_confirm",
                cleanup ? "search_cleanup_controllers_title" : "search_remove_matching_title");
    }

    private <T> List<T> confirmRemoval(List<T> candidates, Function<T, String> label, String messageKey, String titleKey) {
        List<JCheckBox> matchedElementCheckboxes = candidates.stream()
                .map(candidate -> new JCheckBox(label.apply(candidate), true))
                .toList();
        JPanel matchedElements = new JPanel(new MigLayout("insets 0, wrap 1, gapy 0, aligny top", "[left]"));
        for (JCheckBox matchedElementCheckbox : matchedElementCheckboxes) {
            matchedElements.add(matchedElementCheckbox);
        }
        JScrollPane scrollPane = new JScrollPane(matchedElements);
        scrollPane.setPreferredSize(new Dimension(850, 420));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(MessageFormat.format(
                JMeterUtils.getResString(messageKey), candidates.size())), BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        JButton removeSelected = createButton("search_remove_selected");
        JButton cancel = createButton("cancel");
        panel.add(removalSelectionControls(matchedElementCheckboxes, removeSelected), BorderLayout.SOUTH);
        JOptionPane confirmation = new JOptionPane(panel, JOptionPane.WARNING_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION, null, new Object[] {removeSelected, cancel}, cancel);
        removeSelected.addActionListener(e -> confirmation.setValue(JOptionPane.OK_OPTION));
        cancel.addActionListener(e -> confirmation.setValue(JOptionPane.CANCEL_OPTION));
        JDialog dialog = confirmation.createDialog(this, JMeterUtils.getResString(titleKey));
        dialog.setResizable(true);
        dialog.setMinimumSize(new Dimension(520, 300));
        Dimension savedSize = previewSizes.get(titleKey);
        if (savedSize != null) {
            dialog.setSize(savedSize);
        }
        try {
            dialog.setVisible(true);
        } finally {
            previewSizes.put(titleKey, dialog.getSize());
            dialog.dispose();
        }
        if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(confirmation.getValue())) {
            return null;
        }
        List<T> selectedNodes = new ArrayList<>();
        for (int i = 0; i < matchedElementCheckboxes.size(); i++) {
            if (matchedElementCheckboxes.get(i).isSelected()) {
                selectedNodes.add(candidates.get(i));
            }
        }
        return selectedNodes;
    }

    static JPanel removalSelectionControls(List<JCheckBox> matchedElementCheckboxes, JButton removeSelected) {
        JLabel count = new JLabel();
        Runnable updateSelection = () -> {
            long selected = matchedElementCheckboxes.stream().filter(JCheckBox::isSelected).count();
            count.setText(MessageFormat.format(JMeterUtils.getResString("search_removal_selected_count"), selected, matchedElementCheckboxes.size()));
            removeSelected.setEnabled(selected > 0);
        };
        JPanel selectionControls = new JPanel(new FlowLayout(FlowLayout.LEADING));
        JButton all = createButton("search_select_all");
        JButton none = createButton("search_select_none");
        all.addActionListener(e -> {
            matchedElementCheckboxes.forEach(box -> box.setSelected(true));
            updateSelection.run();
        });
        none.addActionListener(e -> {
            matchedElementCheckboxes.forEach(box -> box.setSelected(false));
            updateSelection.run();
        });
        matchedElementCheckboxes.forEach(box -> box.addActionListener(e -> updateSelection.run()));
        selectionControls.add(all);
        selectionControls.add(none);
        selectionControls.add(count);
        updateSelection.run();
        return selectionControls;
    }

    static String formatNodePath(JMeterTreeNode node) {
        return String.join(" > ",
                List.of(node.getPath()).stream()
                        // The tree model root is hidden and also holds the Test Plan.
                        .skip(1)
                        .map(pathNode -> ((JMeterTreeNode) pathNode).getName())
                        .toList());
    }

    static List<JMeterTreeNode> controllerAncestors(JMeterTreeNode node) {
        List<JMeterTreeNode> controllers = new ArrayList<>();
        for (var parent = node.getParent(); parent instanceof JMeterTreeNode ancestor; parent = parent.getParent()) {
            TestElement element = ancestor.getTestElement();
            if (element instanceof AbstractThreadGroup || element instanceof TestFragmentController) {
                break;
            }
            if (element instanceof Controller) {
                controllers.add(ancestor);
            }
        }
        return controllers;
    }

    static List<JMeterTreeNode> emptiedControllers(Set<JMeterTreeNode> affectedControllers, JMeterTreeNode root) {
        Set<JMeterTreeNode> removable = new LinkedHashSet<>();
        for (JMeterTreeNode node : sortedForRemoval(new ArrayList<>(affectedControllers))) {
            if (node.getParent() == null || node.getRoot() != root || !node.getTestElement().canRemove()) {
                continue;
            }
            boolean emptyAfterCleanup = true;
            for (int i = 0; i < node.getChildCount(); i++) {
                if (!removable.contains(node.getChildAt(i))) {
                    emptyAfterCleanup = false;
                    break;
                }
            }
            if (emptyAfterCleanup) {
                removable.add(node);
            }
        }
        return new ArrayList<>(removable);
    }

    private static List<JMeterTreeNode> sortedForRemoval(List<JMeterTreeNode> nodes) {
        return nodes.stream()
                .sorted(Comparator.comparingInt(JMeterTreeNode::getLevel).reversed())
                .toList();
    }

    static boolean removeMatchingNode(GuiPackage guiPackage, JMeterTreeNode node) {
        TestElement testElement = node.getTestElement();
        if (node.getParent() == null || node.getRoot() != guiPackage.getTreeModel().getRoot()
                || testElement instanceof org.apache.jmeter.testelement.TestPlan) {
            return false;
        }
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
        JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
        JMeterTreeNode plan = (JMeterTreeNode) root.getChildAt(0);
        guiPackage.getTreeListener().setSelectionPathWithoutEdit(new TreePath(plan.getPath()));
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
            SearchScope scope) {
        int numberOfMatches = 0;
        JMeterTreeModel jMeterTreeModel = guiPackage.getTreeModel();
        Set<JMeterTreeNode> nodes = new LinkedHashSet<>();
        Path testPlanFile = testPlanFile(guiPackage);
        for (JMeterTreeNode jMeterTreeNode : jMeterTreeModel.getNodesOfType(Searchable.class)) {
            if (jMeterTreeNode.isRoot() || !isWithinSearchScope(jMeterTreeNode, scope)) {
                continue;
            }
            try {
                List<String> searchableTokens = searchableTokens(jMeterTreeNode, testPlanFile, selectedAreas(), selectedRowField());
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
            GuiPackage guiPackage, Pattern pattern, SearchScope scope) {
        int numberOfMatches = 0;
        Set<JMeterTreeNode> nodes = new LinkedHashSet<>();
        for (JMeterTreeNode node : guiPackage.getTreeModel().getNodesOfType(TestElement.class)) {
            if (node.isRoot() || !isWithinSearchScope(node, scope)) {
                continue;
            }
            int nodeMatches = replaceableFields(node, selectedAreas(), selectedRowField()).stream()
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
        searchReplaceableInTree(GuiPackage.getInstance(), pattern, selectedScope());
    }

    @VisibleForTesting
    static List<ReplaceableField> replaceableFields(JMeterTreeNode node) {
        if (!(node.getUserObject() instanceof TestElement testElement)) {
            return List.of();
        }
        List<ReplaceableField> fields = new ArrayList<>();
        fields.add(new ReplaceableField("Name", testElement::getName, testElement::setName, SearchArea.NAME));
        fields.add(new ReplaceableField("Comments", testElement::getComment, testElement::setComment));
        if (testElement instanceof Replaceable replaceable) {
            fields.addAll(replaceable.getReplaceableFields());
        }
        return fields;
    }

    static List<ReplaceableField> replaceableFields(JMeterTreeNode node, Set<SearchArea> areas, RowField field) {
        return replaceableFields(node, areas).stream().filter(value -> field == RowField.ALL
                || (value.area() != SearchArea.HEADERS && value.area() != SearchArea.PARAMETERS)
                || value.rowField() == field).toList();
    }

    static List<ReplaceableField> replaceableFields(JMeterTreeNode node, Set<SearchArea> areas) {
        return replaceableFields(node).stream().filter(field -> areas.contains(field.area())).toList();
    }

    static List<String> searchableTokens(JMeterTreeNode node, Path testPlanFile, Set<SearchArea> areas, RowField field)
            throws Exception {
        if (field == RowField.ALL) {
            return searchableTokens(node, testPlanFile, areas);
        }
        Set<SearchArea> nonRowAreas = EnumSet.noneOf(SearchArea.class);
        nonRowAreas.addAll(areas);
        nonRowAreas.removeAll(Set.of(SearchArea.HEADERS, SearchArea.PARAMETERS));
        List<String> tokens = searchableTokens(node, testPlanFile, nonRowAreas);
        for (RemovableRow row : RemovableRow.forElement(node.getTestElement())) {
            if (areas.contains(row.area())) {
                tokens.addAll(field.tokens(row.tokens()));
            }
        }
        return tokens;
    }

    static List<String> searchableTokens(JMeterTreeNode node, Path testPlanFile, Set<SearchArea> areas)
            throws Exception {
        List<String> tokens = new ArrayList<>(((Searchable) node.getUserObject()).getSearchableTokens(areas));
        if (areas.contains(SearchArea.RECORDED_REQUEST) || areas.contains(SearchArea.RECORDED_RESPONSE)) {
            RecordedHarExchangeResolver.resolveFor(node, testPlanFile).exchange().ifPresent(exchange ->
                    tokens.addAll(exchange.searchableTokens(areas.contains(SearchArea.RECORDED_REQUEST),
                            areas.contains(SearchArea.RECORDED_RESPONSE))));
        }
        return tokens;
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
        return replacementChanges(node, pattern, replacement, regex, EnumSet.allOf(SearchArea.class));
    }

    static List<FieldChange> replacementChanges(JMeterTreeNode node, Pattern pattern,
            String replacement, boolean regex, Set<SearchArea> areas) {
        return replacementChanges(node, pattern, replacement, regex, areas, RowField.ALL);
    }

    static List<FieldChange> replacementChanges(JMeterTreeNode node, Pattern pattern,
            String replacement, boolean regex, Set<SearchArea> areas, RowField field) {
        List<FieldChange> changes = new ArrayList<>();
        String effectiveReplacement = regex ? replacement : Matcher.quoteReplacement(replacement);
        for (ReplaceableField valueField : replaceableFields(node, areas, field)) {
            String currentValue = valueField.value();
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
                changes.add(new FieldChange(valueField, result.toString(), replacements));
            }
        }
        return changes;
    }

    private List<FieldChange> replacementChangesOrShowError(
            JMeterTreeNode node, Pattern pattern, String replacement) {
        try {
            return replacementChanges(node, pattern, replacement, isRegexpCB.isSelected(), selectedAreas(), selectedRowField());
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
            GuiPackage guiPackage, Set<NodeType> nodeTypes, SearchScope scope) {
        int numberOfMatches = 0;
        JMeterTreeModel jMeterTreeModel = guiPackage.getTreeModel();
        Set<JMeterTreeNode> nodes = new LinkedHashSet<>();
        for (JMeterTreeNode jMeterTreeNode : jMeterTreeModel.getNodesOfType(TestElement.class)) {
            if (jMeterTreeNode.isRoot() || !isWithinSearchScope(jMeterTreeNode, scope)) {
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
                selectedScope(),
                selectedAreas(),
                selectedRowField(),
                mode);
    }

    private SearchScope selectedScope() {
        ScopeOption selected = (ScopeOption) scopeComboBox.getSelectedItem();
        GuiPackage gui = GuiPackage.getInstance();
        return new SearchScope(selected == null ? null : gui == null ? selected.node : selected.resolve(gui.getTreeModel()));
    }

    record ScopeKey(String path, int occurrence) {}

    static ScopeKey scopeKey(JMeterTreeNode node) {
        if (node == null) {
            return null;
        }
        String path = formatNodePath(node);
        int occurrence = 0;
        var nodes = ((JMeterTreeNode) node.getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            JMeterTreeNode candidate = (JMeterTreeNode) nodes.nextElement();
            if (candidate == node) {
                return new ScopeKey(path, occurrence);
            }
            if (candidate.getTestElement() instanceof AbstractThreadGroup && formatNodePath(candidate).equals(path)) {
                occurrence++;
            }
        }
        throw new IllegalStateException("Scope is not in its tree");
    }

    static JMeterTreeNode resolveScope(JMeterTreeNode original, ScopeKey key, JMeterTreeModel model) {
        if (original == null || original.getRoot() == model.getRoot() || key == null) {
            return original;
        }
        // Keep an unresolved scope detached, so it matches nothing rather than
        // silently widening a destructive action to All or another group.
        return model.getNodesOfType(AbstractThreadGroup.class).stream()
                .filter(group -> formatNodePath(group).equals(key.path()))
                .skip(key.occurrence())
                .findFirst().orElse(original);
    }

    private RowField selectedRowField() {
        return (RowField) rowFieldCombo.getSelectedItem();
    }

    private Set<SearchArea> selectedAreas() {
        Set<SearchArea> selected = EnumSet.noneOf(SearchArea.class);
        areaBoxes.forEach((area, box) -> {
            if (box.isSelected()) {
                selected.add(area);
            }
        });
        return selected;
    }

    private JPanel createAreaPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 0, wrap 4", "[][][][]"));
        for (SearchArea area : SearchArea.values()) {
            JCheckBox box = new JCheckBox(JMeterUtils.getResString(
                    "search_area_" + area.name().toLowerCase(java.util.Locale.ROOT)), true);
            if (area == SearchArea.OTHER) {
                box.setToolTipText(JMeterUtils.getResString("search_area_other_help"));
            } else if (area == SearchArea.BODY) {
                box.setToolTipText(JMeterUtils.getResString("search_area_body_help"));
            }
            areaBoxes.put(area, box);
            box.addActionListener(e -> scopeChanged());
            panel.add(box);
        }
        JButton all = createButton("search_select_all");
        JButton none = createButton("search_select_none");
        all.addActionListener(e -> selectAreas(true));
        none.addActionListener(e -> selectAreas(false));
        panel.add(all);
        panel.add(none);
        return panel;
    }

    private void selectAreas(boolean selected) {
        areaBoxes.values().forEach(box -> box.setSelected(selected));
        scopeChanged();
    }

    private void scopeChanged() {
        lastSearchConditions = null;
        lastSearchResult.clear();
        currentSearchIndex = -1;
        scheduleLiveFlagging();
    }

    private void refreshScopeLabels() {
        scopeComboBox.repaint();
    }

    private void refreshScopeOptions() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        JMeterTreeNode currentNode = guiPackage == null ? null : guiPackage.getCurrentNode();
        JMeterTreeNode defaultScope = findThreadGroupScope(currentNode);
        boolean samePlan = guiPackage != null && guiPackage == scopeGui && scopeSession == guiPackage.getTestPlanSession();
        JMeterTreeNode previousScope = samePlan ? selectedScope().threadGroup() : defaultScope;
        ScopeOption previousOption = samePlan ? (ScopeOption) scopeComboBox.getSelectedItem() : null;
        if (!samePlan) {
            searchDialogSize = null;
            previewSizes.clear();
            areaBoxes.values().forEach(box -> box.setSelected(true));
            rowFieldCombo.setSelectedItem(RowField.ALL);
            removalTarget.setSelectedItem(RemovalTarget.ELEMENTS);
        }
        scopeGui = guiPackage;
        scopeSession = guiPackage == null ? -1 : guiPackage.getTestPlanSession();
        scopeComboBox.removeAllItems();
        ScopeOption all = new ScopeOption(JMeterUtils.getResString("search_scope_all"), null);
        scopeComboBox.addItem(all);
        ScopeOption selected = all;
        if (guiPackage != null && guiPackage.getTreeModel() != null) {
            for (JMeterTreeNode node : guiPackage.getTreeModel().getNodesOfType(AbstractThreadGroup.class)) {
                ScopeOption option = new ScopeOption(node.getName(), node);
                scopeComboBox.addItem(option);
                if (node == previousScope) {
                    selected = option;
                }
            }
        }
        if (selected.node == null && previousScope != null && previousOption != null) {
            selected = previousOption;
            scopeComboBox.addItem(selected);
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
    static boolean isWithinSearchScope(JMeterTreeNode node, SearchScope scope) {
        return isWithinScope(node, scope.threadGroup());
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
        SearchResult result = searchReplaceableInTree(guiPackage, pattern, selectedScope());

        List<Map.Entry<JMeterTreeNode, List<FieldChange>>> plannedChanges = new ArrayList<>();
        for (JMeterTreeNode node : result.nodes()) {
            List<FieldChange> changes = replacementChangesOrShowError(
                    node, pattern, replaceTF.getText());
            if (changes == null) {
                return;
            }
            plannedChanges.add(Map.entry(node, changes));
        }

        Set<JMeterTreeNode> replacedNodes = new HashSet<>();
        int totalReplaced = editWithUndo(guiPackage, "Replace all", count -> count > 0, () -> {
            int count = 0;
            for (Map.Entry<JMeterTreeNode, List<FieldChange>> plannedChange : plannedChanges) {
                int replaced = applyChanges(plannedChange.getValue());
                if (replaced > 0) {
                    count += replaced;
                    replacedNodes.add(plannedChange.getKey());
                }
            }
            return count;
        });
        if (totalReplaced > 0) {
            guiPackage.withoutUndoHistory(() -> markConcernedNodes(false, replacedNodes));
            guiPackage.refreshCurrentGui();
            refreshScopeLabels();
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
            if (searchDialogSize != null) {
                setSize(searchDialogSize);
            } else {
                pack();
            }
        } else if (!b && isVisible()) {
            searchDialogSize = getSize();
        }
        super.setVisible(b);
        searchTF.requestFocusInWindow();
    }
}
