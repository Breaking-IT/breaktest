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

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.KeyStrokes;
import org.apache.jmeter.gui.action.Start;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.SampleResultNodeResolver;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;
import org.apache.jorphan.gui.JMeterUIDefaults;
import org.apache.jorphan.reflect.LogAndIgnoreServiceLoadExceptionHandler;
import org.apache.jorphan.util.StringUtilities;
import org.apache.jorphan.util.StringWrap;
import org.apiguardian.api.API;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.miginfocom.swing.MigLayout;

/**
 * Base for ViewResults
 */
@GUIMenuSortOrder(1)
@TestElementMetadata(labelResource = "view_results_tree_title")
public class ViewResultsFullVisualizer extends AbstractVisualizer
implements ActionListener, TreeSelectionListener, Clearable, ItemListener {

    private static final long serialVersionUID = 2L;

    private static final Logger log = LoggerFactory.getLogger(ViewResultsFullVisualizer.class);

    public static final Color SERVER_ERROR_COLOR = Color.red;
    public static final Color CLIENT_ERROR_COLOR = Color.blue;
    public static final Color REDIRECT_COLOR = Color.green;
    private static final Color SUCCESS_ICON_COLOR = new Color(0x2EAD4F);
    private static final Color FAILURE_ICON_COLOR = new Color(0xD83A34);

    protected static final String COMBO_CHANGE_COMMAND = "change_combo"; // $NON-NLS-1$

    private static final Border RED_BORDER = BorderFactory.createLineBorder(Color.red);
    private static final Border BLUE_BORDER = BorderFactory.createLineBorder(Color.blue);
    private static final String ICON_SIZE = JMeterUtils.getPropDefault(JMeter.TREE_ICON_SIZE, JMeter.DEFAULT_TREE_ICON_SIZE);
    private static final Pattern JMETER_THREAD_NAME =
            Pattern.compile("(.+) \\d+-\\d+$"); // $NON-NLS-1$
    private static final DateTimeFormatter RESULT_TABLE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault()); // $NON-NLS-1$

    // Default limited to 10 megabytes
    private static final int MAX_DISPLAY_SIZE =
            JMeterUtils.getPropDefault("view.results.tree.max_size", 10485760); // $NON-NLS-1$

    // Default limited to 110K
    private static final int MAX_LINE_SIZE =
            JMeterUtils.getPropDefault("view.results.tree.max_line_size", 110000); // $NON-NLS-1$

    // Limit the soft wrap to 100K (hard limit divided by 1.1)
    private static final int SOFT_WRAP_LINE_SIZE =
            JMeterUtils.getPropDefault("view.results.tree.soft_wrap_line_size", (int) (MAX_LINE_SIZE / 1.1f)); // $NON-NLS-1$

    // default display order
    private static final String VIEWERS_ORDER =
            JMeterUtils.getPropDefault("view.results.tree.renderers_order", ""); // $NON-NLS-1$ //$NON-NLS-2$

    private static final int REFRESH_PERIOD = JMeterUtils.getPropDefault("jmeter.gui.refresh_period", 500);

    private static final String AUTO_DETACH_ON_VALIDATION =
            "ViewResultsFullVisualizer.auto_detach_on_validation"; // $NON-NLS-1$

    private static final Icon imageSuccess = createStatusIcon(true);

    private static final Icon imageFailure = createStatusIcon(false);

    private JSplitPane mainSplit;
    private DefaultMutableTreeNode root;
    private DefaultTreeModel treeModel;
    private JTree jTree;
    private JTable resultTable;
    private ResultTableModel resultTableModel;
    private TableColumn[] resultTableColumns;
    private boolean[] selectedResultTableColumns;
    private JTabbedPane resultListTabs;
    private JComboBox<String> threadGroupFilter;
    private JComboBox<String> threadNameFilter;
    private JComboBox<String> labelFilter;
    private JPanel resultFilterPanel;
    private JToggleButton resultFilterToggle;
    private JButton resultColumnsButton;
    private JCheckBox calculateResponseDiffCB;
    private Component leftSide;
    private JTabbedPane rightSide;
    private JComboBox<ResultRenderer> selectRenderPanel;
    private Component visualizerPanel;
    private Component detachedPlaceholder;
    private JCheckBox autoDetachOnValidationCB;
    private JButton detachButton;
    private JButton validateButton;
    private JButton storeReplayButton;
    private JToggleButton searchPanelToggle;
    private JToggleButton filePanelToggle;
    private JPanel filePanelWrapper;
    private SearchTreePanel searchPanel;
    private JFrame detachedWindow;
    private JPanel detachedWindowContent;
    private AbstractThreadGroup[] validationThreadGroups;
    private TestElement configuredElement;
    private int selectedTab;
    private ResultRenderer resultsRender = null;
    private Object resultsObject = null;
    private Object renderedResponseObject = null;
    private TreeSelectionEvent lastSelectionEvent;
    private JCheckBox autoScrollCB;
    private final Queue<SampleResult> buffer = new ArrayDeque<>();
    private final int maxResults;
    private boolean dataChanged;
    private String selectedThreadGroup;
    private String selectedThreadName;
    private String selectedLabel;
    private boolean updatingResultFilters;

    /**
     * Constructor
     */
    public ViewResultsFullVisualizer() {
        super();
        this.maxResults = JMeterUtils.getPropDefault("view.results.tree.max_results", 500);
        init();
        new Timer(REFRESH_PERIOD, e -> updateGui()).start();
    }

    /** {@inheritDoc} */
    @Override
    public void add(final SampleResult sample) {
        synchronized (buffer) {
            if (maxResults > 0 && buffer.size() >= maxResults) {
                buffer.remove();
            }
            buffer.add(sample);
            dataChanged = true;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void add(final SampleEvent event) {
        SampleResult sample = event.getResult();
        if (sample != null) {
            detachForValidationIfNeeded();
            if (!sample.hasJMeterVariables()) {
                sample.setJMeterVariables(snapshotVariables(event));
            }
            add(sample);
        }
    }

    @Override
    public boolean needsSampleResultMetadata() {
        return true;
    }

    /**
     * Update the visualizer with new data.
     */
    private void updateGui() {
        TreePath selectedPath = null;
        Object oldSelectedElement;
        Set<Object> oldExpandedElements;
        Set<TreePath> newExpandedPaths = new HashSet<>();
        synchronized (buffer) {
            if (!dataChanged) {
                return;
            }

            final Enumeration<TreePath> expandedElements = jTree.getExpandedDescendants(new TreePath(root));
            oldExpandedElements = extractExpandedObjects(expandedElements);
            oldSelectedElement = getSelectedObject();
            root.removeAllChildren();
            updateThreadGroupFilterOptions();
            List<ResultTableModel.ResultTableRow> tableRows = new ArrayList<>();
            for (SampleResult sampler: buffer) {
                if (!matchesSelectedThreadFilters(sampler) || !sampleOrSubResultMatchesSelectedLabel(sampler)) {
                    continue;
                }
                SampleResult res = sampler;
                // Add sample
                DefaultMutableTreeNode currNode = new SearchableTreeNode(res, treeModel);
                treeModel.insertNodeInto(currNode, root, root.getChildCount());
                if (matchesSelectedLabel(res)) {
                    tableRows.add(new ResultTableModel.ResultTableRow(res, 0));
                }
                List<TreeNode> path = new ArrayList<>(Arrays.asList(root, currNode));
                selectedPath = checkExpandedOrSelected(path,
                        res, oldSelectedElement,
                        oldExpandedElements, newExpandedPaths, selectedPath);
                TreePath potentialSelection = addSubResults(currNode, res, path, oldSelectedElement,
                        oldExpandedElements, newExpandedPaths, tableRows, 1);
                if (potentialSelection != null) {
                    selectedPath = potentialSelection;
                }
                // Add any assertion that failed as children of the sample node
                AssertionResult[] assertionResults = res.getAssertionResults();
                int assertionIndex = currNode.getChildCount();
                for (AssertionResult assertionResult : assertionResults) {
                    if (assertionResult.isFailure() || assertionResult.isError()) {
                        DefaultMutableTreeNode assertionNode = new SearchableTreeNode(assertionResult, treeModel);
                        treeModel.insertNodeInto(assertionNode, currNode, assertionIndex++);
                        selectedPath = checkExpandedOrSelected(path,
                                assertionResult, oldSelectedElement,
                                oldExpandedElements, newExpandedPaths, selectedPath,
                                assertionNode);
                    }
                }
            }
            treeModel.nodeStructureChanged(root);
            resultTableModel.setRows(tableRows);
            dataChanged = false;
        }

        if (root.getChildCount() == 1) {
            jTree.expandPath(new TreePath(root));
        }
        newExpandedPaths.stream().forEach(jTree::expandPath);
        if (selectedPath != null) {
            jTree.setSelectionPath(selectedPath);
        }
        if (autoScrollCB.isSelected() && isTableMode() && resultTable.getRowCount() > 0) {
            resultTable.scrollRectToVisible(resultTable.getCellRect(resultTable.getRowCount() - 1, 0, true));
        } else if (autoScrollCB.isSelected() && root.getChildCount() > 1) {
            jTree.scrollPathToVisible(new TreePath(new Object[] { root,
                    treeModel.getChild(root, root.getChildCount() - 1) }));
        }
    }

    private Object getSelectedObject() {
        Object oldSelectedElement;
        DefaultMutableTreeNode oldSelectedNode = (DefaultMutableTreeNode) jTree.getLastSelectedPathComponent();
        oldSelectedElement = oldSelectedNode == null ? null : oldSelectedNode.getUserObject();
        return oldSelectedElement;
    }

    private static TreePath checkExpandedOrSelected(List<TreeNode> path,
            Object item, Object oldSelectedObject,
            Set<Object> oldExpandedObjects, Set<? super TreePath> newExpandedPaths,
            TreePath defaultPath) {
        TreePath result = defaultPath;
        if (oldSelectedObject == item) {
            result = toTreePath(path);
        }
        if (oldExpandedObjects.contains(item)) {
            newExpandedPaths.add(toTreePath(path));
        }
        return result;
    }

    private static TreePath checkExpandedOrSelected(List<TreeNode> path,
            Object item, Object oldSelectedObject,
            Set<Object> oldExpandedObjects, Set<? super TreePath> newExpandedPaths,
            TreePath defaultPath, DefaultMutableTreeNode extensionNode) {
        TreePath result = defaultPath;
        if (oldSelectedObject == item) {
            result = toTreePath(path, extensionNode);
        }
        if (oldExpandedObjects.contains(item)) {
            newExpandedPaths.add(toTreePath(path, extensionNode));
        }
        return result;
    }

    private static Set<Object> extractExpandedObjects(final Enumeration<TreePath> expandedElements) {
        if (expandedElements != null) {
            final List<TreePath> list = Collections.list(expandedElements);
            log.debug("Expanded: {}", list);
            Set<Object> result = list.stream()
                    .map(TreePath::getLastPathComponent)
                    .map(c -> (DefaultMutableTreeNode) c)
                    .map(DefaultMutableTreeNode::getUserObject)
                    .collect(Collectors.toSet());
            log.debug("Elements: {}", result);
            return result;
        }
        return Collections.emptySet();
    }

    private TreePath addSubResults(DefaultMutableTreeNode currNode,
            SampleResult res, List<TreeNode> path, Object selectedObject,
            Set<Object> oldExpandedObjects, Set<? super TreePath> newExpandedPaths,
            List<ResultTableModel.ResultTableRow> tableRows, int depth) {
        SampleResult[] subResults = res.getSubResults();

        int leafIndex = 0;
        TreePath result = null;

        for (SampleResult child : subResults) {
            log.debug("updateGui1 : child sample result - {}", child);
            if (!sampleOrSubResultMatchesSelectedLabel(child)) {
                continue;
            }
            DefaultMutableTreeNode leafNode = new SearchableTreeNode(child, treeModel);

            treeModel.insertNodeInto(leafNode, currNode, leafIndex++);
            if (matchesSelectedLabel(child)) {
                tableRows.add(new ResultTableModel.ResultTableRow(child, depth));
            }
            List<TreeNode> newPath = new ArrayList<>(path);
            newPath.add(leafNode);
            result = checkExpandedOrSelected(newPath, child, selectedObject, oldExpandedObjects, newExpandedPaths, result);
            addSubResults(leafNode, child, newPath, selectedObject, oldExpandedObjects, newExpandedPaths,
                    tableRows, depth + 1);
            // Add any assertion that failed as children of the sample node
            AssertionResult[] assertionResults = child.getAssertionResults();
            int assertionIndex = leafNode.getChildCount();
            for (AssertionResult item : assertionResults) {
                if (item.isFailure() || item.isError()) {
                    DefaultMutableTreeNode assertionNode = new SearchableTreeNode(item, treeModel);
                    treeModel.insertNodeInto(assertionNode, leafNode, assertionIndex++);
                    result = checkExpandedOrSelected(path, item,
                            selectedObject, oldExpandedObjects, newExpandedPaths, result,
                            assertionNode);
                }
            }
        }
        return result;
    }

    private static TreePath toTreePath(List<TreeNode> newPath) {
        return new TreePath(newPath.toArray(new TreeNode[newPath.size()]));
    }

    private static TreePath toTreePath(List<TreeNode> path,
            DefaultMutableTreeNode extensionNode) {
        TreeNode[] result = path.toArray(new TreeNode[path.size() + 1]);
        result[result.length - 1] = extensionNode;
        return new TreePath(result);
    }

    /** {@inheritDoc} */
    @Override
    public void clearData() {
        synchronized (buffer) {
            buffer.clear();
            dataChanged = true;
        }
        if (threadGroupFilter != null) {
            updatingResultFilters = true;
            try {
                threadGroupFilter.setModel(new DefaultComboBoxModel<>(new String[] { allThreadGroupsLabel() }));
                if (threadNameFilter != null) {
                    threadNameFilter.setModel(new DefaultComboBoxModel<>(new String[] { allThreadNamesLabel() }));
                }
                if (labelFilter != null) {
                    labelFilter.setModel(new DefaultComboBoxModel<>(new String[] { allLabelsLabel() }));
                }
                selectedThreadGroup = null;
                selectedThreadName = null;
                selectedLabel = null;
            } finally {
                updatingResultFilters = false;
            }
        }
        if (resultTableModel != null) {
            resultTableModel.setRows(Collections.emptyList());
        }
        resultsRender.clearData();
        resultsObject = null;
        renderedResponseObject = null;
    }

    /** {@inheritDoc} */
    @Override
    public String getLabelResource() {
        return "view_results_tree_title"; // $NON-NLS-1$
    }

    @Override
    public void configure(TestElement el) {
        configuredElement = el;
        super.configure(el);
        if (autoDetachOnValidationCB != null) {
            autoDetachOnValidationCB.setSelected(el.getPropertyAsBoolean(AUTO_DETACH_ON_VALIDATION, false));
        }
        updateDetachedWindowTitle();
    }

    @Override
    public void modifyTestElement(TestElement c) {
        super.modifyTestElement(c);
        c.setProperty(AUTO_DETACH_ON_VALIDATION,
                autoDetachOnValidationCB != null && autoDetachOnValidationCB.isSelected(),
                false);
    }

    /**
     * Initialize this visualizer
     */
    private void init() {  // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        log.debug("init() - pass");
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());

        leftSide = createLeftPanel();
        // Prepare the common tab
        rightSide = new JTabbedPane();
        rightSide.addChangeListener(e -> renderSelectedTabIfNeeded());

        // Create the split pane
        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSide, rightSide);
        mainSplit.setOneTouchExpandable(true);
        updateResultListLayout();

        searchPanel = new SearchTreePanel(root);
        searchPanel.setVisible(false);

        JPanel resultsPanel = new JPanel(new BorderLayout());
        resultsPanel.add(searchPanel, BorderLayout.NORTH);
        resultsPanel.add(mainSplit, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder());
        panel.add(makeTitlePanel(), BorderLayout.NORTH);
        panel.add(resultsPanel, BorderLayout.CENTER);
        visualizerPanel = panel;
        add(visualizerPanel, BorderLayout.CENTER);

        // init right side with first render
        resultsRender.setRightSide(rightSide);
        resultsRender.init();
    }

    @Override
    protected Container wrapTitlePanel(Container titlePanel) {
        configureTitlePanel(titlePanel);
        filePanelWrapper = new JPanel(new BorderLayout());
        filePanelWrapper.setVisible(false);

        JPanel wrapper = new JPanel(new MigLayout("fillx, wrap 1, insets 0, hidemode 3", "[fill,grow]")) { // $NON-NLS-1$ //$NON-NLS-2$
            private static final long serialVersionUID = 1L;

            @Override
            protected void addImpl(Component comp, Object constraints, int index) {
                if (comp == ViewResultsFullVisualizer.this.getFilePanel() && filePanelWrapper != null) {
                    filePanelWrapper.add(comp, BorderLayout.CENTER);
                    updateFilePanelVisibility();
                    return;
                }
                super.addImpl(comp, constraints, index);
            }
        };
        wrapper.add(titlePanel, "growx"); // $NON-NLS-1$
        wrapper.add(filePanelWrapper, "growx"); // $NON-NLS-1$
        updateFilePanelVisibility();
        return wrapper;
    }

    private void configureTitlePanel(Container titlePanel) {
        if (!(titlePanel instanceof JPanel panel) || panel.getComponentCount() < 6) {
            return;
        }

        Component[] titleComponents = panel.getComponents();
        autoDetachOnValidationCB = new JCheckBox(JMeterUtils.getResString("view_results_auto_detach_on_validation")); // $NON-NLS-1$
        validateButton = new JButton(JMeterUtils.getResString("validate_threadgroup")); // $NON-NLS-1$
        validateButton.setToolTipText(JMeterUtils.getResString("view_results_validate_tooltip")); // $NON-NLS-1$
        validateButton.setActionCommand(ActionNames.VALIDATE_TG);
        validateButton.addActionListener(this::validateThreadGroup);
        storeReplayButton = new JButton(JMeterUtils.getResString("view_results_store_replay_button")); // $NON-NLS-1$
        storeReplayButton.setToolTipText(
                JMeterUtils.getResString("view_results_store_replay_tooltip")); // $NON-NLS-1$
        storeReplayButton.addActionListener(e -> storeReplayResults());
        detachButton = new JButton();
        detachButton.addActionListener(e -> toggleDetached());
        filePanelToggle = new JToggleButton(JMeterUtils.getResString("view_results_file_panel_show")); // $NON-NLS-1$
        filePanelToggle.addActionListener(e -> updateFilePanelVisibility());
        searchPanelToggle = new JToggleButton(JMeterUtils.getResString("view_results_search_panel_show")); // $NON-NLS-1$
        searchPanelToggle.addActionListener(e -> updateSearchPanelVisibility());

        titleComponents[2].setMinimumSize(new Dimension(80, titleComponents[2].getPreferredSize().height));
        titleComponents[5].setMinimumSize(new Dimension(80, titleComponents[5].getPreferredSize().height));

        panel.removeAll();
        panel.setLayout(new MigLayout(
                "fillx, wrap 9, insets 0, hidemode 3", // $NON-NLS-1$
                "[][fill,grow,shrinkprio 200][right,shrinkprio 0][right,shrinkprio 0]"
                        + "[right,shrinkprio 0][right,shrinkprio 0][right,shrinkprio 0][right,shrinkprio 0]"
                        + "[right,shrinkprio 0]")); // $NON-NLS-1$
        panel.add(titleComponents[0], "span 9"); // $NON-NLS-1$
        panel.add(titleComponents[1]);
        panel.add(titleComponents[2], "growx, pushx, wmin 80"); // $NON-NLS-1$
        panel.add(titleComponents[3], "gapleft 8"); // $NON-NLS-1$
        panel.add(validateButton, "gapleft 8"); // $NON-NLS-1$
        panel.add(storeReplayButton, "gapleft 8"); // $NON-NLS-1$
        panel.add(searchPanelToggle, "gapleft 8"); // $NON-NLS-1$
        panel.add(filePanelToggle, "gapleft 8"); // $NON-NLS-1$
        panel.add(autoDetachOnValidationCB, "gapleft 8"); // $NON-NLS-1$
        panel.add(detachButton, "wmin button, gapleft 6, wrap"); // $NON-NLS-1$
        panel.add(titleComponents[4]);
        panel.add(titleComponents[5], "span 8, growx, wmin 80"); // $NON-NLS-1$
        updateDetachButton();
        updateSearchPanelVisibility();
    }

    private void updateFilePanelVisibility() {
        if (filePanelToggle == null || filePanelWrapper == null) {
            return;
        }
        boolean visible = filePanelToggle.isSelected();
        filePanelWrapper.setVisible(visible);
        filePanelToggle.setText(JMeterUtils.getResString(
                visible ? "view_results_file_panel_hide" : "view_results_file_panel_show")); // $NON-NLS-1$ //$NON-NLS-2$
        filePanelToggle.setToolTipText(JMeterUtils.getResString(
                visible ? "view_results_file_panel_hide_tooltip" : "view_results_file_panel_show_tooltip")); // $NON-NLS-1$ //$NON-NLS-2$
        revalidateVisualizerLayout(filePanelWrapper);
    }

    private void updateSearchPanelVisibility() {
        if (searchPanelToggle == null || searchPanel == null) {
            return;
        }
        boolean visible = searchPanelToggle.isSelected();
        searchPanel.setVisible(visible);
        searchPanelToggle.setText(JMeterUtils.getResString(
                visible ? "view_results_search_panel_hide" : "view_results_search_panel_show")); // $NON-NLS-1$ //$NON-NLS-2$
        searchPanelToggle.setToolTipText(JMeterUtils.getResString(
                visible ? "view_results_search_panel_hide_tooltip" : "view_results_search_panel_show_tooltip")); // $NON-NLS-1$ //$NON-NLS-2$
        revalidateVisualizerLayout(searchPanel);
    }

    private void revalidateVisualizerLayout(Component component) {
        Container parent = component == null ? null : component.getParent();
        Component target = parent == null ? component : parent;
        if (target != null) {
            target.revalidate();
            target.repaint();
        }
        if (visualizerPanel != null) {
            visualizerPanel.revalidate();
            visualizerPanel.repaint();
        }
    }

    private void toggleDetached() {
        if (detachedWindow == null) {
            detachResultTree();
        } else {
            dockResultTree(true);
        }
    }

    private void detachResultTree() {
        if (detachedWindow != null) {
            return;
        }

        AbstractThreadGroup configuredThreadGroup = findConfiguredThreadGroup();
        if (configuredThreadGroup != null) {
            validationThreadGroups = new AbstractThreadGroup[] { configuredThreadGroup };
        }
        remove(visualizerPanel);
        detachedPlaceholder = createDetachedPlaceholder();
        add(detachedPlaceholder, BorderLayout.CENTER);

        detachedWindow = new JFrame(detachedWindowTitle());
        detachedWindow.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        detachedWindow.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                dockResultTree(true);
            }
        });
        detachedWindowContent = new JPanel(new BorderLayout());
        detachedWindowContent.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        detachedWindowContent.add(visualizerPanel, BorderLayout.CENTER);
        detachedWindow.getContentPane().add(detachedWindowContent, BorderLayout.CENTER);
        installDetachedWindowShortcuts();
        detachedWindow.setSize(detachedWindowSize());
        GuiPackage guiPackage = GuiPackage.getInstance();
        detachedWindow.setLocationRelativeTo(guiPackage == null ? null : guiPackage.getMainFrame());
        detachedWindow.setVisible(true);

        updateDetachButton();
        revalidate();
        repaint();
    }

    private void installDetachedWindowShortcuts() {
        InputMap inputMap = detachedWindow.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = detachedWindow.getRootPane().getActionMap();
        inputMap.put(KeyStrokes.CLEAR_ALL, ActionNames.CLEAR_ALL);
        actionMap.put(ActionNames.CLEAR_ALL, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                ActionRouter.getInstance().actionPerformed(
                        new ActionEvent(detachedWindow, event.getID(), ActionNames.CLEAR_ALL));
            }
        });
        inputMap.put(KeyStrokes.CLEAR, ActionNames.CLEAR);
        actionMap.put(ActionNames.CLEAR, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                clearData();
            }
        });
    }

    private void detachForValidationIfNeeded() {
        if (autoDetachOnValidationCB == null || !autoDetachOnValidationCB.isSelected()
                || !JMeterContextService.isValidationRun()) {
            return;
        }
        AbstractThreadGroup[] activeValidationThreadGroups = Start.getActiveValidationThreadGroups();
        if (detachedWindow == null && activeValidationThreadGroups != null
                && activeValidationThreadGroups.length > 0) {
            validationThreadGroups = activeValidationThreadGroups;
        }
        SwingUtilities.invokeLater(() -> {
            if (autoDetachOnValidationCB.isSelected() && JMeterContextService.isValidationRun()
                    && detachedWindow == null) {
                detachResultTree();
            }
        });
    }

    private void dockResultTree(boolean selectVisualizer) {
        if (detachedWindow == null) {
            return;
        }

        JFrame window = detachedWindow;
        detachedWindow = null;
        if (detachedWindowContent != null) {
            detachedWindowContent.remove(visualizerPanel);
            detachedWindowContent = null;
        } else {
            window.getContentPane().remove(visualizerPanel);
        }
        window.dispose();

        if (detachedPlaceholder != null) {
            remove(detachedPlaceholder);
            detachedPlaceholder = null;
        }
        add(visualizerPanel, BorderLayout.CENTER);
        updateDetachButton();
        revalidate();
        repaint();

        if (selectVisualizer) {
            selectConfiguredVisualizerNode();
        }
    }

    private Component createDetachedPlaceholder() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        JLabel message = new JLabel(JMeterUtils.getResString("view_results_detached_message")); // $NON-NLS-1$
        message.setHorizontalAlignment(SwingConstants.CENTER);
        JButton dockButton = new JButton(JMeterUtils.getResString("view_results_dock")); // $NON-NLS-1$
        dockButton.addActionListener(e -> dockResultTree(true));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        buttonPanel.add(dockButton);
        panel.add(message, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        panel.setBorder(BorderFactory.createEmptyBorder(30, 30, 30, 30));
        return panel;
    }

    private Dimension detachedWindowSize() {
        return new Dimension(Math.max(getWidth(), 900), Math.max(getHeight(), 650));
    }

    private void updateDetachButton() {
        if (detachButton == null) {
            return;
        }
        if (detachedWindow == null) {
            detachButton.setText(JMeterUtils.getResString("view_results_detach")); // $NON-NLS-1$
            detachButton.setToolTipText(JMeterUtils.getResString("view_results_detach_tooltip")); // $NON-NLS-1$
        } else {
            detachButton.setText(JMeterUtils.getResString("view_results_dock")); // $NON-NLS-1$
            detachButton.setToolTipText(JMeterUtils.getResString("view_results_dock_tooltip")); // $NON-NLS-1$
        }
    }

    private String detachedWindowTitle() {
        String name = getName().trim();
        String title = JMeterUtils.getResString("view_results_tree_title"); // $NON-NLS-1$
        return StringUtilities.isEmpty(name) || name.equals(title) ? title : name + " - " + title; // $NON-NLS-1$
    }

    private void updateDetachedWindowTitle() {
        if (detachedWindow != null) {
            detachedWindow.setTitle(detachedWindowTitle());
        }
    }

    private void selectConfiguredVisualizerNode() {
        if (configuredElement == null) {
            return;
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null || guiPackage.getTreeListener() == null) {
            return;
        }
        JMeterTreeNode visualizerNode = guiPackage.getNodeOf(configuredElement);
        if (visualizerNode == null) {
            return;
        }
        JTree testPlanTree = guiPackage.getTreeListener().getJTree();
        if (testPlanTree == null) {
            return;
        }
        TreePath visualizerPath = new TreePath(visualizerNode.getPath());
        testPlanTree.setSelectionPath(visualizerPath);
        testPlanTree.scrollPathToVisible(visualizerPath);
    }

    private void validateThreadGroup(ActionEvent event) {
        AbstractThreadGroup configuredThreadGroup = findConfiguredThreadGroup();
        AbstractThreadGroup[] threadGroupsToRun = configuredThreadGroup == null
                ? validationThreadGroups
                : new AbstractThreadGroup[] { configuredThreadGroup };
        if (threadGroupsToRun == null || threadGroupsToRun.length == 0) {
            JMeterUtils.reportErrorToUser("No validation thread group is available for this visual tree yet.");
            return;
        }
        ActionRouter.getInstance().actionPerformed(Start.validateThreadGroupsEvent(
                event.getSource(), event.getID(), threadGroupsToRun));
    }

    private AbstractThreadGroup findConfiguredThreadGroup() {
        if (configuredElement == null) {
            return null;
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return null;
        }
        JMeterTreeNode visualizerNode = guiPackage.getNodeOf(configuredElement);
        if (visualizerNode == null) {
            return null;
        }
        return visualizerNode.getPathToThreadGroup().stream()
                .map(JMeterTreeNode::getTestElement)
                .filter(AbstractThreadGroup.class::isInstance)
                .map(AbstractThreadGroup.class::cast)
                .findFirst()
                .orElse(null);
    }

    /** {@inheritDoc} */
    @Override
    public void valueChanged(TreeSelectionEvent e) {
        valueChanged(e, false);
    }

    /**
     * @param e {@link TreeSelectionEvent}
     * @param forceRendering boolean
     */
    private void valueChanged(TreeSelectionEvent e, boolean forceRendering) {
        lastSelectionEvent = e;
        DefaultMutableTreeNode node;
        synchronized (this) {
            node = (DefaultMutableTreeNode) jTree.getLastSelectedPathComponent();
        }

        if (node != null && (forceRendering || node.getUserObject() != resultsObject)) {
            showResult(node.getUserObject());
        }
    }

    private void showResult(Object userObject) {
        resultsObject = userObject;
        // to restore last tab used
        if (rightSide.getTabCount() > selectedTab) {
            resultsRender.setLastSelectedTab(rightSide.getSelectedIndex());
        }
        resultsRender.setSamplerResult(userObject);
        resultsRender.setupTabPane(); // Processes Assertions
        renderedResponseObject = null;
        renderSelectedTabIfNeeded();
    }

    private void renderSelectedTabIfNeeded() {
        if (resultsRender != null && resultsObject != null) {
            ensureResultsRendererInitialized();
            resultsRender.renderSelectedTab();
        }
        renderSelectedResponseIfNeeded();
        showPreferredResponseViewIfNeeded();
    }

    private void renderSelectedResponseIfNeeded() {
        if (resultsRender == null || resultsObject == null || resultsObject == renderedResponseObject
                || !shouldRenderResponse()) {
            return;
        }
        if (resultsObject instanceof SampleResult sampleResult) {
            ensureResultsRendererInitialized();
            if (isTextDataType(sampleResult)) {
                resultsRender.renderResult(sampleResult);
            } else {
                resultsRender.renderImage(sampleResult);
            }
            renderedResponseObject = resultsObject;
        }
    }

    private boolean shouldRenderResponse() {
        return isResponseTabSelected()
                || rightSide.indexOfTab(JMeterUtils.getResString("view_results_tab_response")) < 0; // $NON-NLS-1$
    }

    private void ensureResultsRendererInitialized() {
        if (resultsRender instanceof SamplerResultTab samplerResultTab) {
            samplerResultTab.ensureInitialized();
        }
    }

    private void showPreferredResponseViewIfNeeded() {
        if (isResponseTabSelected() && resultsRender instanceof SamplerResultTab samplerResultTab) {
            samplerResultTab.showPreferredResponseView();
        }
    }

    private boolean isResponseTabSelected() {
        int selectedIndex = rightSide.getSelectedIndex();
        return selectedIndex >= 0
                && JMeterUtils.getResString("view_results_tab_response").equals(rightSide.getTitleAt(selectedIndex)); // $NON-NLS-1$
    }

    /**
     * @param sampleResult SampleResult
     * @return true if sampleResult is text or has empty content type
     */
    protected static boolean isTextDataType(SampleResult sampleResult) {
        String dataType = sampleResult.getDataType();
        return SampleResult.TEXT.equals(dataType) ||
                StringUtilities.isEmpty(dataType);
    }

    private synchronized Component createLeftPanel() {
        SampleResult rootSampleResult = new SampleResult();
        rootSampleResult.setSampleLabel("Root");
        rootSampleResult.setSuccessful(true);
        root = new SearchableTreeNode(rootSampleResult, null);

        treeModel = new DefaultTreeModel(root);
        jTree = new JTree(treeModel);
        jTree.setCellRenderer(new ResultsNodeRenderer());
        jTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        jTree.addTreeSelectionListener(this);
        jTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                jumpToResultTestPlanElementOnDoubleClick(event);
            }

            @Override
            public void mousePressed(MouseEvent event) {
                showResultTreePopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showResultTreePopup(event);
            }
        });
        jTree.setRootVisible(false);
        jTree.setShowsRootHandles(true);
        JScrollPane treePane = new JScrollPane(jTree);
        treePane.setPreferredSize(new Dimension(200, 300));

        resultTableModel = new ResultTableModel(imageSuccess, imageFailure, RESULT_TABLE_TIMESTAMP_FORMAT);
        resultTable = new JTable(resultTableModel) {
            private static final long serialVersionUID = 7261698980341042273L;

            @Override
            public String getToolTipText(MouseEvent event) {
                int row = rowAtPoint(event.getPoint());
                int column = columnAtPoint(event.getPoint());
                if (row < 0 || column < 0) {
                    return null;
                }
                Object value = getValueAt(row, column);
                return value instanceof Icon ? null : Objects.toString(value, null);
            }
        };
        resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultTable.setAutoCreateRowSorter(true);
        resultTable.setDefaultRenderer(Icon.class, new StatusIconTableCellRenderer());
        resultTable.setDefaultRenderer(Double.class, new DiffPercentTableCellRenderer());
        resultTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectTableResult();
            }
        });
        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                jumpToTableResultTestPlanElementOnDoubleClick(event);
            }

            @Override
            public void mousePressed(MouseEvent event) {
                showResultTablePopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showResultTablePopup(event);
            }
        });
        configureResultTableColumns();
        resultTableColumns = captureTableColumns(resultTable);
        selectedResultTableColumns = ResultTableModel.defaultVisibleColumns();
        applySelectedResultTableColumns();
        JMeterUtils.applyHiDPI(resultTable);

        resultListTabs = new JTabbedPane();
        resultListTabs.addTab(JMeterUtils.getResString("view_results_tree_list"), treePane); // $NON-NLS-1$
        resultListTabs.addTab(JMeterUtils.getResString("view_results_table_list"), new JScrollPane(resultTable)); // $NON-NLS-1$
        resultListTabs.addChangeListener(event -> updateResultListLayout());

        resultFilterPanel = new JPanel(new MigLayout("fillx, wrap 2, insets 0", "[][fill,grow]")); // $NON-NLS-1$ //$NON-NLS-2$
        resultFilterPanel.add(new JLabel(JMeterUtils.getResString("view_results_thread_group_filter"))); // $NON-NLS-1$
        threadGroupFilter = new JComboBox<>(new String[] { allThreadGroupsLabel() });
        threadGroupFilter.addActionListener(event -> updateSelectedThreadGroup());
        resultFilterPanel.add(threadGroupFilter, "growx"); // $NON-NLS-1$
        resultFilterPanel.add(new JLabel(JMeterUtils.getResString("view_results_thread_name_filter"))); // $NON-NLS-1$
        threadNameFilter = new JComboBox<>(new String[] { allThreadNamesLabel() });
        threadNameFilter.addActionListener(event -> updateSelectedThreadName());
        resultFilterPanel.add(threadNameFilter, "growx"); // $NON-NLS-1$
        resultFilterPanel.add(new JLabel(JMeterUtils.getResString("view_results_label_filter"))); // $NON-NLS-1$
        labelFilter = new JComboBox<>(new String[] { allLabelsLabel() });
        labelFilter.addActionListener(event -> updateSelectedLabel());
        resultFilterPanel.add(labelFilter, "growx"); // $NON-NLS-1$
        resultFilterPanel.setVisible(false);

        resultFilterToggle = new JToggleButton(JMeterUtils.getResString("view_results_filter_panel_show")); // $NON-NLS-1$
        resultFilterToggle.addActionListener(event -> updateResultFilterPanelVisibility());
        resultColumnsButton = new JButton(JMeterUtils.getResString("view_results_columns")); // $NON-NLS-1$
        resultColumnsButton.addActionListener(event -> showResultTableColumnsMenu(resultColumnsButton));
        calculateResponseDiffCB = new JCheckBox(JMeterUtils.getResString("view_results_diff_enable")); // $NON-NLS-1$
        calculateResponseDiffCB.setToolTipText(JMeterUtils.getResString("view_results_diff_tooltip")); // $NON-NLS-1$
        calculateResponseDiffCB.addActionListener(event ->
                resultTableModel.setResponseBodyDiffEnabled(calculateResponseDiffCB.isSelected()));
        JPanel controlsPanel = new JPanel(new MigLayout("fillx, insets 0, hidemode 3", // $NON-NLS-1$
                "[fill,grow][right][right][right]")); // $NON-NLS-1$
        controlsPanel.add(createComboRender(), "growx"); // $NON-NLS-1$
        controlsPanel.add(resultFilterToggle, "gapleft 6"); // $NON-NLS-1$
        controlsPanel.add(calculateResponseDiffCB, "gapleft 6"); // $NON-NLS-1$
        controlsPanel.add(resultColumnsButton, "gapleft 6"); // $NON-NLS-1$

        JPanel topPanel = new JPanel(new MigLayout("fillx, wrap 1, insets 0, hidemode 3", "[fill,grow]")); // $NON-NLS-1$ //$NON-NLS-2$
        topPanel.add(controlsPanel, "growx"); // $NON-NLS-1$
        topPanel.add(resultFilterPanel, "growx"); // $NON-NLS-1$

        JPanel leftPane = new JPanel(new BorderLayout(0, 5));
        leftPane.add(topPanel, BorderLayout.NORTH);
        leftPane.add(resultListTabs, BorderLayout.CENTER);
        autoScrollCB = new JCheckBox(JMeterUtils.getResString("view_results_autoscroll")); // $NON-NLS-1$
        autoScrollCB.setSelected(false);
        autoScrollCB.addItemListener(this);
        leftPane.add(autoScrollCB, BorderLayout.SOUTH);
        return leftPane;
    }

    private void configureResultTableColumns() {
        TableColumnModel columns = resultTable.getColumnModel();
        columns.getColumn(ResultTableModel.STATUS).setMinWidth(26);
        columns.getColumn(ResultTableModel.STATUS).setPreferredWidth(30);
        columns.getColumn(ResultTableModel.STATUS).setMaxWidth(34);
        columns.getColumn(ResultTableModel.TIMESTAMP).setPreferredWidth(95);
        columns.getColumn(ResultTableModel.THREAD_GROUP).setPreferredWidth(120);
        columns.getColumn(ResultTableModel.THREAD_NAME).setPreferredWidth(140);
        columns.getColumn(ResultTableModel.LABEL).setPreferredWidth(180);
        columns.getColumn(ResultTableModel.TIME).setPreferredWidth(70);
        columns.getColumn(ResultTableModel.LATENCY).setPreferredWidth(70);
        columns.getColumn(ResultTableModel.CONNECT_TIME).setPreferredWidth(80);
        columns.getColumn(ResultTableModel.REQUEST_SIZE).setPreferredWidth(85);
        columns.getColumn(ResultTableModel.RECEIVED_BYTES).setPreferredWidth(90);
        columns.getColumn(ResultTableModel.COMPRESSION).setMinWidth(58);
        columns.getColumn(ResultTableModel.COMPRESSION).setPreferredWidth(64);
        columns.getColumn(ResultTableModel.COMPRESSION).setMaxWidth(76);
        columns.getColumn(ResultTableModel.DIFF_PERCENT).setMinWidth(52);
        columns.getColumn(ResultTableModel.DIFF_PERCENT).setPreferredWidth(58);
        columns.getColumn(ResultTableModel.DIFF_PERCENT).setMaxWidth(72);
        columns.getColumn(ResultTableModel.URL).setPreferredWidth(240);
    }

    private void showResultTableColumnsMenu(Component invoker) {
        JPopupMenu popup = new JPopupMenu();
        for (int modelColumn = 0; modelColumn < resultTableColumns.length; modelColumn++) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(
                    resultTableColumnConfigurationLabel(modelColumn), selectedResultTableColumns[modelColumn]);
            final int column = modelColumn;
            item.addActionListener(event -> {
                selectedResultTableColumns[column] = item.isSelected();
                applySelectedResultTableColumns();
            });
            popup.add(item);
        }
        popup.show(invoker, 0, invoker.getHeight());
    }

    private static String resultTableColumnConfigurationLabel(int modelColumn) {
        return modelColumn == ResultTableModel.STATUS
                ? JMeterUtils.getResString("table_visualizer_status") // $NON-NLS-1$
                : JMeterUtils.getResString(ResultTableModel.COLUMNS[modelColumn]);
    }

    private void applySelectedResultTableColumns() {
        TableColumnModel columnModel = resultTable.getColumnModel();
        for (int modelColumn = 0; modelColumn < resultTableColumns.length; modelColumn++) {
            boolean shouldShow = selectedResultTableColumns[modelColumn];
            boolean isVisible = isColumnVisible(columnModel, resultTableColumns[modelColumn]);
            if (shouldShow && !isVisible) {
                columnModel.addColumn(resultTableColumns[modelColumn]);
                columnModel.moveColumn(columnModel.getColumnCount() - 1, countVisibleResultTableColumnsBefore(modelColumn));
            } else if (!shouldShow && isVisible) {
                columnModel.removeColumn(resultTableColumns[modelColumn]);
            }
        }
    }

    private static TableColumn[] captureTableColumns(JTable table) {
        TableColumnModel columnModel = table.getColumnModel();
        TableColumn[] columns = new TableColumn[columnModel.getColumnCount()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = columnModel.getColumn(i);
        }
        return columns;
    }

    private static boolean isColumnVisible(TableColumnModel columnModel, TableColumn column) {
        for (int i = 0; i < columnModel.getColumnCount(); i++) {
            if (columnModel.getColumn(i) == column) {
                return true;
            }
        }
        return false;
    }

    private int countVisibleResultTableColumnsBefore(int modelColumn) {
        int count = 0;
        for (int i = 0; i < modelColumn; i++) {
            if (selectedResultTableColumns[i]) {
                count++;
            }
        }
        return count;
    }

    private void updateResultFilterPanelVisibility() {
        if (resultFilterPanel == null || resultFilterToggle == null) {
            return;
        }
        boolean visible = resultFilterToggle.isSelected();
        resultFilterPanel.setVisible(visible);
        resultFilterToggle.setText(JMeterUtils.getResString(
                visible ? "view_results_filter_panel_hide" : "view_results_filter_panel_show")); // $NON-NLS-1$ //$NON-NLS-2$
        revalidateVisualizerLayout(resultFilterPanel);
    }

    private void showResultFilterPanel() {
        if (resultFilterToggle == null) {
            return;
        }
        resultFilterToggle.setSelected(true);
        updateResultFilterPanelVisibility();
    }

    private void updateResultListLayout() {
        if (mainSplit == null || resultListTabs == null) {
            return;
        }
        int divider = mainSplit.getDividerLocation();
        mainSplit.setOrientation(isTableMode() ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setTopComponent(leftSide);
        mainSplit.setBottomComponent(rightSide);
        mainSplit.setResizeWeight(isTableMode() ? 0.45 : 0.25);
        if (resultColumnsButton != null) {
            resultColumnsButton.setVisible(isTableMode());
            resultColumnsButton.getParent().revalidate();
        }
        if (calculateResponseDiffCB != null) {
            calculateResponseDiffCB.setVisible(isTableMode());
        }
        if (divider > 0) {
            mainSplit.setDividerLocation(divider);
        }
        mainSplit.revalidate();
        mainSplit.repaint();
    }

    private boolean isTableMode() {
        return resultListTabs != null && resultListTabs.getSelectedIndex() == 1;
    }

    private void jumpToResultTestPlanElementOnDoubleClick(MouseEvent event) {
        if (detachedWindow == null || event.getClickCount() != 2 || event.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        TreePath resultPath = jTree.getPathForLocation(event.getX(), event.getY());
        if (resultPath == null) {
            return;
        }
        if (!jTree.getModel().isLeaf(resultPath.getLastPathComponent())) {
            return;
        }
        jTree.setSelectionPath(resultPath);

        SampleResult sampleResult = getSampleResult(resultPath);
        jumpToTestPlanElement(findTestPlanNode(sampleResult));
    }

    private void jumpToTableResultTestPlanElementOnDoubleClick(MouseEvent event) {
        if (detachedWindow == null || event.getClickCount() != 2 || event.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        int viewRow = resultTable.rowAtPoint(event.getPoint());
        if (viewRow < 0) {
            return;
        }
        int modelRow = resultTable.convertRowIndexToModel(viewRow);
        jumpToTestPlanElement(findTestPlanNode(resultTableModel.sampleAt(modelRow)));
    }

    private void showResultTablePopup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int viewRow = resultTable.rowAtPoint(event.getPoint());
        if (viewRow < 0) {
            return;
        }
        resultTable.setRowSelectionInterval(viewRow, viewRow);
        int modelRow = resultTable.convertRowIndexToModel(viewRow);
        SampleResult sample = resultTableModel.sampleAt(modelRow);
        if (sample == null || StringUtilities.isEmpty(sample.getThreadName())) {
            return;
        }

        JMenuItem filterThread = new JMenuItem(JMeterUtils.getResString("view_results_filter_thread_name")); // $NON-NLS-1$
        filterThread.addActionListener(action -> filterByThreadName(sample.getThreadName()));
        JMenuItem clearThreadFilter = new JMenuItem(
                JMeterUtils.getResString("view_results_clear_thread_name_filter")); // $NON-NLS-1$
        clearThreadFilter.setEnabled(selectedThreadName != null);
        clearThreadFilter.addActionListener(action -> filterByThreadName(null));
        JMenuItem filterLabel = new JMenuItem(JMeterUtils.getResString("view_results_filter_label")); // $NON-NLS-1$
        filterLabel.addActionListener(action -> filterByLabel(sample.getSampleLabel()));
        JMenuItem clearLabelFilter = new JMenuItem(JMeterUtils.getResString("view_results_clear_label_filter")); // $NON-NLS-1$
        clearLabelFilter.setEnabled(selectedLabel != null);
        clearLabelFilter.addActionListener(action -> filterByLabel(null));

        JPopupMenu popup = new JPopupMenu();
        popup.add(filterThread);
        popup.add(clearThreadFilter);
        popup.addSeparator();
        popup.add(filterLabel);
        popup.add(clearLabelFilter);
        popup.show(resultTable, event.getX(), event.getY());
    }

    private void selectTableResult() {
        int viewRow = resultTable.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        int modelRow = resultTable.convertRowIndexToModel(viewRow);
        SampleResult sample = resultTableModel.sampleAt(modelRow);
        if (sample != null && sample != resultsObject) {
            showResult(sample);
        }
    }

    private void updateSelectedThreadGroup() {
        if (updatingResultFilters || threadGroupFilter == null) {
            return;
        }
        Object selectedItem = threadGroupFilter.getSelectedItem();
        String all = allThreadGroupsLabel();
        selectedThreadGroup = selectedItem == null || all.equals(selectedItem.toString()) ? null : selectedItem.toString();
        synchronized (buffer) {
            dataChanged = true;
        }
        updateGui();
    }

    private void updateSelectedThreadName() {
        if (updatingResultFilters || threadNameFilter == null) {
            return;
        }
        Object selectedItem = threadNameFilter.getSelectedItem();
        String all = allThreadNamesLabel();
        selectedThreadName = selectedItem == null || all.equals(selectedItem.toString()) ? null : selectedItem.toString();
        synchronized (buffer) {
            dataChanged = true;
        }
        updateGui();
    }

    private void updateSelectedLabel() {
        if (updatingResultFilters || labelFilter == null) {
            return;
        }
        Object selectedItem = labelFilter.getSelectedItem();
        String all = allLabelsLabel();
        selectedLabel = selectedItem == null || all.equals(selectedItem.toString()) ? null : selectedItem.toString();
        synchronized (buffer) {
            dataChanged = true;
        }
        updateGui();
    }

    private void updateThreadGroupFilterOptions() {
        if (threadGroupFilter == null) {
            return;
        }
        List<String> threadGroups = buffer.stream()
                .map(SampleResult::getThreadName)
                .map(ViewResultsFullVisualizer::threadGroupName)
                .filter(threadGroup -> !StringUtilities.isEmpty(threadGroup))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        String selected = selectedThreadGroup;
        if (selected != null && !threadGroups.contains(selected)) {
            selected = null;
            selectedThreadGroup = null;
        }
        String activeThreadGroup = selected;
        List<String> threadNames = buffer.stream()
                .map(SampleResult::getThreadName)
                .filter(threadName -> !StringUtilities.isEmpty(threadName))
                .filter(threadName -> activeThreadGroup == null || activeThreadGroup.equals(threadGroupName(threadName)))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        String selectedThread = selectedThreadName;
        if (selectedThread != null && !threadNames.contains(selectedThread)) {
            selectedThread = null;
            selectedThreadName = null;
        }
        List<String> labels = buffer.stream()
                .flatMap(ViewResultsFullVisualizer::sampleAndSubResults)
                .map(SampleResult::getSampleLabel)
                .filter(label -> !StringUtilities.isEmpty(label))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        String selectedSamplerLabel = selectedLabel;
        if (selectedSamplerLabel != null && !labels.contains(selectedSamplerLabel)) {
            selectedSamplerLabel = null;
            selectedLabel = null;
        }

        DefaultComboBoxModel<String> threadGroupModel = new DefaultComboBoxModel<>();
        threadGroupModel.addElement(allThreadGroupsLabel());
        threadGroups.forEach(threadGroupModel::addElement);
        DefaultComboBoxModel<String> threadNameModel = new DefaultComboBoxModel<>();
        threadNameModel.addElement(allThreadNamesLabel());
        threadNames.forEach(threadNameModel::addElement);
        DefaultComboBoxModel<String> labelModel = new DefaultComboBoxModel<>();
        labelModel.addElement(allLabelsLabel());
        labels.forEach(labelModel::addElement);

        updatingResultFilters = true;
        try {
            updateComboBoxModelIfNeeded(threadGroupFilter, threadGroupModel);
            updateComboBoxSelectionIfNeeded(threadGroupFilter, selected == null ? allThreadGroupsLabel() : selected);
            if (threadNameFilter != null) {
                updateComboBoxModelIfNeeded(threadNameFilter, threadNameModel);
                updateComboBoxSelectionIfNeeded(threadNameFilter,
                        selectedThread == null ? allThreadNamesLabel() : selectedThread);
            }
            if (labelFilter != null) {
                updateComboBoxModelIfNeeded(labelFilter, labelModel);
                updateComboBoxSelectionIfNeeded(labelFilter,
                        selectedSamplerLabel == null ? allLabelsLabel() : selectedSamplerLabel);
            }
        } finally {
            updatingResultFilters = false;
        }
    }

    private static void updateComboBoxSelectionIfNeeded(JComboBox<String> comboBox, String selectedItem) {
        if (!comboBox.isPopupVisible() && !Objects.equals(comboBox.getSelectedItem(), selectedItem)) {
            comboBox.setSelectedItem(selectedItem);
        }
    }

    private static void updateComboBoxModelIfNeeded(JComboBox<String> comboBox, DefaultComboBoxModel<String> model) {
        if (comboBox.isPopupVisible() || comboBoxModelEquals(comboBox, model)) {
            return;
        }
        comboBox.setModel(model);
    }

    private static boolean comboBoxModelEquals(JComboBox<String> comboBox, DefaultComboBoxModel<String> model) {
        ComboBoxModel<String> existingModel = comboBox.getModel();
        if (existingModel.getSize() != model.getSize()) {
            return false;
        }
        for (int i = 0; i < model.getSize(); i++) {
            if (!Objects.equals(existingModel.getElementAt(i), model.getElementAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesSelectedThreadFilters(SampleResult sample) {
        return (selectedThreadGroup == null || selectedThreadGroup.equals(threadGroupName(sample.getThreadName())))
                && (selectedThreadName == null || selectedThreadName.equals(sample.getThreadName()));
    }

    private boolean matchesSelectedLabel(SampleResult sample) {
        return matchesLabel(sample, selectedLabel);
    }

    private boolean sampleOrSubResultMatchesSelectedLabel(SampleResult sample) {
        return sampleOrSubResultMatchesLabel(sample, selectedLabel);
    }

    static boolean matchesLabel(SampleResult sample, String label) {
        return label == null || label.equals(sample.getSampleLabel());
    }

    static boolean sampleOrSubResultMatchesLabel(SampleResult sample, String label) {
        return label == null || sampleAndSubResults(sample)
                .anyMatch(result -> label.equals(result.getSampleLabel()));
    }

    private static Stream<SampleResult> sampleAndSubResults(SampleResult sample) {
        return Stream.concat(
                Stream.of(sample),
                Arrays.stream(sample.getSubResults()).flatMap(ViewResultsFullVisualizer::sampleAndSubResults));
    }

    private void filterByThreadName(String threadName) {
        selectedThreadName = StringUtilities.isEmpty(threadName) ? null : threadName;
        if (selectedThreadName != null) {
            selectedThreadGroup = threadGroupName(selectedThreadName);
        }
        showResultFilterPanel();
        synchronized (buffer) {
            dataChanged = true;
        }
        updateGui();
    }

    private void filterByLabel(String label) {
        selectedLabel = StringUtilities.isEmpty(label) ? null : label;
        showResultFilterPanel();
        synchronized (buffer) {
            dataChanged = true;
        }
        updateGui();
    }

    static String threadGroupName(String threadName) {
        if (StringUtilities.isEmpty(threadName)) {
            return ""; // $NON-NLS-1$
        }
        Matcher matcher = JMETER_THREAD_NAME.matcher(threadName);
        return matcher.matches() ? matcher.group(1) : threadName;
    }

    private static String allThreadGroupsLabel() {
        return JMeterUtils.getResString("view_results_thread_group_filter_all"); // $NON-NLS-1$
    }

    private static String allThreadNamesLabel() {
        return JMeterUtils.getResString("view_results_thread_name_filter_all"); // $NON-NLS-1$
    }

    private static String allLabelsLabel() {
        return JMeterUtils.getResString("view_results_label_filter_all"); // $NON-NLS-1$
    }

    private void showResultTreePopup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        TreePath resultPath = jTree.getPathForLocation(event.getX(), event.getY());
        if (resultPath == null) {
            return;
        }
        jTree.setSelectionPath(resultPath);

        SampleResult sampleResult = getSampleResult(resultPath);
        JMeterTreeNode testPlanNode = findTestPlanNode(sampleResult);
        JMenuItem jumpTo = new JMenuItem("Jump to");
        jumpTo.setEnabled(testPlanNode != null);
        jumpTo.addActionListener(e -> jumpToTestPlanElement(testPlanNode));

        JMenuItem storeReplay = new JMenuItem(
                JMeterUtils.getResString("view_results_store_replay_recording")); // $NON-NLS-1$
        storeReplay.setEnabled(canStoreReplay(sampleResult, testPlanNode));
        storeReplay.addActionListener(e -> storeReplayRecording(sampleResult, testPlanNode));

        JPopupMenu popup = new JPopupMenu();
        popup.add(jumpTo);
        popup.addSeparator();
        popup.add(storeReplay);
        popup.show(jTree, event.getX(), event.getY());
    }

    private static boolean canStoreReplay(SampleResult sampleResult, JMeterTreeNode testPlanNode) {
        return sampleResult != null
                && testPlanNode != null
                && testPlanNode.getTestElement() instanceof Sampler
                && !sampleResult.getUrlAsString().isEmpty();
    }

    private void storeReplayResults() {
        Map<JMeterTreeNode, SampleResult> replayedSamples = new LinkedHashMap<>();
        synchronized (buffer) {
            for (SampleResult sampleResult : buffer) {
                collectReplayableSamples(sampleResult, replayedSamples);
            }
        }
        ReplayRecordingStore.chooseAndStore(jTree, replayedSamples);
    }

    static void collectReplayableSamples(
            SampleResult sampleResult, Map<JMeterTreeNode, SampleResult> replayedSamples) {
        JMeterTreeNode samplerNode = findTestPlanNode(sampleResult);
        if (canStoreReplay(sampleResult, samplerNode)) {
            replayedSamples.put(samplerNode, sampleResult);
        }
        for (SampleResult subResult : sampleResult.getSubResults()) {
            collectReplayableSamples(subResult, replayedSamples);
        }
    }

    private void storeReplayRecording(SampleResult sampleResult, JMeterTreeNode samplerNode) {
        ReplayRecordingStore.chooseAndStore(jTree, canStoreReplay(sampleResult, samplerNode)
                ? Map.of(samplerNode, sampleResult)
                : Map.of());
    }

    private static SampleResult getSampleResult(TreePath resultPath) {
        Object pathComponent = resultPath.getLastPathComponent();
        if (!(pathComponent instanceof DefaultMutableTreeNode node)) {
            return null;
        }
        for (DefaultMutableTreeNode current = node; current != null; current = (DefaultMutableTreeNode) current.getParent()) {
            if (current.getUserObject() instanceof SampleResult sampleResult) {
                return sampleResult;
            }
        }
        return null;
    }

    static JMeterTreeNode findTestPlanNode(SampleResult sampleResult) {
        return SampleResultNodeResolver.find(sampleResult);
    }

    private static void jumpToTestPlanElement(JMeterTreeNode testPlanNode) {
        if (testPlanNode == null) {
            return;
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null || guiPackage.getTreeListener() == null) {
            return;
        }
        JTree testPlanTree = guiPackage.getTreeListener().getJTree();
        if (testPlanTree == null) {
            return;
        }
        TreePath testPlanPath = new TreePath(testPlanNode.getPath());
        testPlanTree.setSelectionPath(testPlanPath);
        testPlanTree.scrollPathToVisible(testPlanPath);
        testPlanTree.requestFocusInWindow();
    }

    /**
     * Create the drop-down list to changer render
     * @return List of all render (implement ResultsRender)
     */
    private Component createComboRender() {
        ComboBoxModel<ResultRenderer> nodesModel = new DefaultComboBoxModel<>();
        // drop-down list for renderer
        selectRenderPanel = new JComboBox<>(nodesModel);
        selectRenderPanel.setActionCommand(COMBO_CHANGE_COMMAND);
        selectRenderPanel.addActionListener(this);

        // if no results render in jmeter.properties, load Standard (default)
        String defaultRenderer = expandToClassname(".RenderAsText"); // $NON-NLS-1$
        if (!VIEWERS_ORDER.isEmpty()) {
            defaultRenderer = expandToClassname(VIEWERS_ORDER.split(",", 2)[0]);
        }
        ResultRenderer defaultObject = null;
        Map<String, ResultRenderer> map = new HashMap<>();
        for (ResultRenderer renderer : JMeterUtils.loadServicesAndScanJars(
                ResultRenderer.class,
                ServiceLoader.load(ResultRenderer.class),
                Thread.currentThread().getContextClassLoader(),
                new LogAndIgnoreServiceLoadExceptionHandler(log)
        )) {
            // Instantiate render classes
            if (defaultRenderer.equals(renderer.getClass().getName())) {
                defaultObject = renderer;
            }
            renderer.setBackgroundColor(getBackground());
            map.put(renderer.getClass().getName(), renderer);
        }
        if (!VIEWERS_ORDER.isEmpty()) {
            Arrays.stream(VIEWERS_ORDER.split(","))
                    .map(ViewResultsFullVisualizer::expandToClassname)
                    .forEach(key -> {
                        ResultRenderer renderer = map.remove(key);
                        if (renderer != null) {
                            selectRenderPanel.addItem(renderer);
                        } else {
                            log.warn(
                                    "Missing (check renderer name) or already added (check doublon) result renderer," +
                                            " check property 'view.results.tree.renderers_order', renderer name: '{}'",
                                    key);
                        }
                    });
        }
        // Add remaining (plugins or missed in property)
        map.values().forEach(renderer -> selectRenderPanel.addItem(renderer));
        nodesModel.setSelectedItem(defaultObject); // preset to "Text" option or the first option from the view.results.tree.renderers_order property
        return selectRenderPanel;
    }

    private static String expandToClassname(String name) {
        if (name.startsWith(".")) {
            return "org.apache.jmeter.visualizers" + name; // $NON-NLS-1$
        }
        return name;
    }

    /** {@inheritDoc} */
    @Override
    public void actionPerformed(ActionEvent event) {
        String command = event.getActionCommand();
        if (COMBO_CHANGE_COMMAND.equals(command)) {
            JComboBox<?> jcb = (JComboBox<?>) event.getSource();

            if (jcb != null) {
                resultsRender = (ResultRenderer) jcb.getSelectedItem();
                if (rightSide != null) {
                    // to restore last selected tab (better user-friendly)
                    selectedTab = rightSide.getSelectedIndex();
                    // Remove old right side and keep the position of the divider
                    int dividerLocation = mainSplit.getDividerLocation();
                    mainSplit.remove(rightSide);

                    // create and add a new right side at the old position
                    rightSide = new JTabbedPane();
                    rightSide.addChangeListener(e -> renderSelectedTabIfNeeded());
                    mainSplit.setBottomComponent(rightSide);
                    updateResultListLayout();
                    if (dividerLocation > 0) {
                        mainSplit.setDividerLocation(dividerLocation);
                    }
                    resultsRender.setRightSide(rightSide);
                    resultsRender.setLastSelectedTab(selectedTab);
                    renderedResponseObject = null;
                    log.debug("selectedTab={}", selectedTab);
                    resultsRender.init();
                    // To display current sampler result before change
                    this.valueChanged(lastSelectionEvent, true);
                }
            }
        }
    }

    public static String getResponseAsString(SampleResult res) {
        String response = null;
        if (isTextDataType(res)) {
            String responseData = compactKnownTimeoutStackTrace(res, res.getResponseDataAsString());
            // Showing large strings can be VERY costly, so we will avoid
            // doing so if the response
            // data is larger than 200K. TODO: instead, we could delay doing
            // the result.setText
            // call until the user chooses the "Response data" tab. Plus we
            // could warn the user
            // if this happens and revert the choice if they doesn't confirm
            // they are ready to wait.
            int len = responseData.length();
            if (MAX_DISPLAY_SIZE > 0 && len > MAX_DISPLAY_SIZE) {
                response = """
                        %s%d > Max: %d, %s
                        %s
                        ...""".formatted(
                            JMeterUtils.getResString("view_results_response_too_large_message"), //$NON-NLS-1$
                            len, MAX_DISPLAY_SIZE,
                            JMeterUtils.getResString("view_results_response_partial_message"), // $NON-NLS-1$
                            responseData.substring(0, MAX_DISPLAY_SIZE));
            } else {
                response = responseData;
            }
        }
        return response;
    }

    private static String compactKnownTimeoutStackTrace(SampleResult result, String responseData) {
        if (!looksLikeInterruptedIoTimeoutStackTrace(responseData)) {
            return responseData;
        }
        return "Response timeout after " + formatTimeout(firstLine(responseData))
                + " waiting for response: " + formatLocalIp(result)
                + " -> " + formatDestination(result);
    }

    private static boolean looksLikeInterruptedIoTimeoutStackTrace(String responseData) {
        if (StringUtilities.isEmpty(responseData)
                || !responseData.startsWith("java.io.InterruptedIOException: ")
                || !responseData.contains("\tat ")) {
            return false;
        }
        String normalized = firstLine(responseData).toLowerCase(Locale.ROOT);
        return normalized.contains("timeout") || normalized.contains("timed out");
    }

    private static String firstLine(String text) {
        int lineEnd = text.indexOf('\n');
        if (lineEnd < 0) {
            return text;
        }
        return text.substring(0, lineEnd).trim();
    }

    private static String formatLocalIp(SampleResult result) {
        return StringUtilities.isEmpty(result.getLocalEndpoint())
                ? "local IP unavailable"
                : stripPort(result.getLocalEndpoint());
    }

    private static String formatDestination(SampleResult result) {
        URL url = result.getURL();
        String host = url == null ? "" : url.getHost();
        String protocol = url == null || StringUtilities.isEmpty(url.getProtocol())
                ? "unknown"
                : url.getProtocol().toLowerCase(Locale.ROOT);
        String destinationEndpoint = StringUtilities.isEmpty(result.getDestinationEndpoint())
                ? formatEndpoint(host, portFrom(url))
                : result.getDestinationEndpoint();
        return formatTarget(protocol, destinationEndpoint, host);
    }

    private static int portFrom(URL url) {
        if (url == null) {
            return -1;
        }
        int port = url.getPort();
        return port > 0 ? port : url.getDefaultPort();
    }

    private static String formatEndpoint(String address, int port) {
        if (StringUtilities.isEmpty(address)) {
            return port > 0 ? "unknown:" + port : "unknown";
        }
        String hostAddress = address;
        if (hostAddress.indexOf(':') >= 0 && !hostAddress.startsWith("[")) {
            hostAddress = "[" + hostAddress + "]";
        }
        return port > 0 ? hostAddress + ":" + port : hostAddress;
    }

    private static String formatTarget(String protocol, String destinationEndpoint, String host) {
        String target = protocol + "://" + destinationEndpoint;
        if (StringUtilities.isEmpty(host) || host.equals(stripPort(destinationEndpoint))) {
            return target;
        }
        return target + " (" + host + ")";
    }

    private static String stripPort(String endpoint) {
        if (StringUtilities.isEmpty(endpoint)) {
            return endpoint;
        }
        if (endpoint.startsWith("[")) {
            int closingBracket = endpoint.indexOf(']');
            if (closingBracket > 0) {
                return endpoint.substring(0, closingBracket + 1);
            }
        }
        int colon = endpoint.lastIndexOf(':');
        if (colon > 0 && endpoint.indexOf(':') == colon) {
            return endpoint.substring(0, colon);
        }
        return endpoint;
    }

    private static String formatTimeout(String message) {
        int start = message.lastIndexOf('(');
        int end = message.lastIndexOf(')');
        if (start < 0 || end <= start) {
            return "unknown";
        }
        return message.substring(start + 1, end).trim()
                .replace(" MILLISECONDS", " ms")
                .replace(" SECONDS", " s")
                .replace(" MINUTES", " min")
                .replace(" HOURS", " h")
                .toLowerCase(Locale.ROOT);
    }

    private static Icon createStatusIcon(boolean success) {
        String propertyName = success ? "viewResultsTree.success" : "viewResultsTree.failure"; // $NON-NLS-1$ //$NON-NLS-2$
        String configuredIcon = JMeterUtils.getProperty(propertyName);
        if (!StringUtilities.isEmpty(configuredIcon)) {
            return JMeterUtils.getImage(configuredIcon);
        }

        int size = Math.max(12, Math.min(20, iconSize()));
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(success ? SUCCESS_ICON_COLOR : FAILURE_ICON_COLOR);
            graphics.fillOval(1, 1, size - 2, size - 2);
            graphics.setColor(Color.WHITE);
            graphics.setStroke(new BasicStroke(Math.max(1.7f, size / 8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (success) {
                graphics.drawLine(size / 4, size / 2, size * 5 / 12, size * 2 / 3);
                graphics.drawLine(size * 5 / 12, size * 2 / 3, size * 3 / 4, size / 3);
            } else {
                int inset = Math.max(4, size / 4);
                graphics.drawLine(inset, inset, size - inset, size - inset);
                graphics.drawLine(size - inset, inset, inset, size - inset);
            }
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(image);
    }

    private static int iconSize() {
        int separator = ICON_SIZE.indexOf('x');
        String size = separator < 0 ? ICON_SIZE : ICON_SIZE.substring(0, separator);
        try {
            return Integer.parseInt(size);
        } catch (NumberFormatException e) {
            return 16;
        }
    }

    @API(status = API.Status.INTERNAL, since = "5.5")
    public static String wrapLongLines(String input) {
        if (StringUtilities.isEmpty(input)) {
            return input;
        }
        if (SOFT_WRAP_LINE_SIZE > 0 && MAX_LINE_SIZE > 0) {
            StringWrap stringWrap = new StringWrap(SOFT_WRAP_LINE_SIZE, MAX_LINE_SIZE);
            return stringWrap.wrap(input, "\n");
        }
        return input;
    }


    private static class ResultsNodeRenderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 4159626601097711565L;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, focus);
            boolean failure = true;
            Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
            if (userObject instanceof SampleResult sampleResult) {
                failure = !sampleResult.isSuccessful();
            } else if (userObject instanceof AssertionResult assertion) {
                failure = assertion.isError() || assertion.isFailure();
            }

            // Set the status for the node
            if (failure) {
                this.setForeground(UIManager.getColor(JMeterUIDefaults.LABEL_ERROR_FOREGROUND));
                this.setIcon(imageFailure);
            } else {
                this.setIcon(imageSuccess);
            }

            // Handle search related rendering
            SearchableTreeNode node = (SearchableTreeNode) value;
            if(node.isNodeHasMatched()) {
                setBorder(RED_BORDER);
            } else if (node.isChildrenNodesHaveMatched()) {
                setBorder(BLUE_BORDER);
            } else {
                setBorder(null);
            }
            return this;
        }
    }

    private static class StatusIconTableCellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = -6384657543198064346L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, "", isSelected, hasFocus, row, column); // $NON-NLS-1$
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setIcon(value instanceof Icon icon ? icon : null);
            return label;
        }
    }

    private static class DiffPercentTableCellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = -3979405095985883119L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            String text = value instanceof Number number
                    ? formatDiffPercentage(number.doubleValue())
                    : ""; // $NON-NLS-1$
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, text, isSelected, hasFocus, row, column);
            label.setHorizontalAlignment(SwingConstants.RIGHT);
            return label;
        }

        private static String formatDiffPercentage(double value) {
            if (value > 0D && value < 0.1D) {
                return "<0.1%"; // $NON-NLS-1$
            }
            return String.format(Locale.ROOT, "%.1f%%", value); // $NON-NLS-1$
        }
    }

    /**
     * Handler for Checkbox
     */
    @Override
    public void itemStateChanged(ItemEvent e) {
        // NOOP state is held by component
    }

    private static Map<String, String> snapshotVariables(SampleEvent event) {
        JMeterVariables variables = JMeterContextService.getContext().getVariables();
        if (variables != null) {
            Map<String, String> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                Object value = entry.getValue();
                snapshot.put(entry.getKey(), value == null ? "" : value.toString()); // $NON-NLS-1$
            }
            return snapshot;
        }

        if (SampleEvent.getVarCount() == 0) {
            return new LinkedHashMap<>();
        }

        Map<String, String> snapshot = new LinkedHashMap<>();
        for (int i = 0; i < SampleEvent.getVarCount(); i++) {
            String value = event.getVarValue(i);
            snapshot.put(SampleEvent.getVarName(i), value == null ? "" : value); // $NON-NLS-1$
        }
        return snapshot;
    }

}
