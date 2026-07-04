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
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;
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
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
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
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
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

    protected static final String COMBO_CHANGE_COMMAND = "change_combo"; // $NON-NLS-1$

    private static final Border RED_BORDER = BorderFactory.createLineBorder(Color.red);
    private static final Border BLUE_BORDER = BorderFactory.createLineBorder(Color.blue);
    private static final String ICON_SIZE = JMeterUtils.getPropDefault(JMeter.TREE_ICON_SIZE, JMeter.DEFAULT_TREE_ICON_SIZE);

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

    private static final ImageIcon imageSuccess = JMeterUtils.getImage(
            JMeterUtils.getPropDefault("viewResultsTree.success",  //$NON-NLS-1$
                    "vrt/" + ICON_SIZE + "/security-high-2.png")); //$NON-NLS-1$ $NON-NLS-2$

    private static final ImageIcon imageFailure = JMeterUtils.getImage(
            JMeterUtils.getPropDefault("viewResultsTree.failure",  //$NON-NLS-1$
                    "vrt/" + ICON_SIZE + "/security-low-2.png")); //$NON-NLS-1$ $NON-NLS-2$

    private JSplitPane mainSplit;
    private DefaultMutableTreeNode root;
    private DefaultTreeModel treeModel;
    private JTree jTree;
    private Component leftSide;
    private JTabbedPane rightSide;
    private JComboBox<ResultRenderer> selectRenderPanel;
    private Component visualizerPanel;
    private Component detachedPlaceholder;
    private JCheckBox autoDetachOnValidationCB;
    private JButton detachButton;
    private JFrame detachedWindow;
    private JPanel detachedWindowContent;
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
            for (SampleResult sampler: buffer) {
                SampleResult res = sampler;
                // Add sample
                DefaultMutableTreeNode currNode = new SearchableTreeNode(res, treeModel);
                treeModel.insertNodeInto(currNode, root, root.getChildCount());
                List<TreeNode> path = new ArrayList<>(Arrays.asList(root, currNode));
                selectedPath = checkExpandedOrSelected(path,
                        res, oldSelectedElement,
                        oldExpandedElements, newExpandedPaths, selectedPath);
                TreePath potentialSelection = addSubResults(currNode, res, path, oldSelectedElement, oldExpandedElements, newExpandedPaths);
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
            dataChanged = false;
        }

        if (root.getChildCount() == 1) {
            jTree.expandPath(new TreePath(root));
        }
        newExpandedPaths.stream().forEach(jTree::expandPath);
        if (selectedPath != null) {
            jTree.setSelectionPath(selectedPath);
        }
        if (autoScrollCB.isSelected() && root.getChildCount() > 1) {
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
            Set<Object> oldExpandedObjects, Set<? super TreePath> newExpandedPaths) {
        SampleResult[] subResults = res.getSubResults();

        int leafIndex = 0;
        TreePath result = null;

        for (SampleResult child : subResults) {
            log.debug("updateGui1 : child sample result - {}", child);
            DefaultMutableTreeNode leafNode = new SearchableTreeNode(child, treeModel);

            treeModel.insertNodeInto(leafNode, currNode, leafIndex++);
            List<TreeNode> newPath = new ArrayList<>(path);
            newPath.add(leafNode);
            result = checkExpandedOrSelected(newPath, child, selectedObject, oldExpandedObjects, newExpandedPaths, result);
            addSubResults(leafNode, child, newPath, selectedObject, oldExpandedObjects, newExpandedPaths);
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

        JSplitPane searchAndMainSP = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new SearchTreePanel(root), mainSplit);
        searchAndMainSP.setOneTouchExpandable(true);
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, makeTitlePanel(), searchAndMainSP);
        splitPane.setOneTouchExpandable(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        visualizerPanel = splitPane;
        add(visualizerPanel, BorderLayout.CENTER);

        // init right side with first render
        resultsRender.setRightSide(rightSide);
        resultsRender.init();
    }

    @Override
    protected Container wrapTitlePanel(Container titlePanel) {
        addDetachButtonToTitlePanel(titlePanel);
        return super.wrapTitlePanel(titlePanel);
    }

    private void addDetachButtonToTitlePanel(Container titlePanel) {
        if (!(titlePanel instanceof JPanel panel) || panel.getComponentCount() < 5) {
            return;
        }

        Component[] titleComponents = panel.getComponents();
        autoDetachOnValidationCB = new JCheckBox(JMeterUtils.getResString("view_results_auto_detach_on_validation")); // $NON-NLS-1$
        detachButton = new JButton();
        detachButton.addActionListener(e -> toggleDetached());

        panel.removeAll();
        panel.setLayout(new MigLayout("fillx, wrap 4, insets 0", "[][fill,grow][][]")); // $NON-NLS-1$ //$NON-NLS-2$
        panel.add(titleComponents[0], "span 4"); // $NON-NLS-1$
        panel.add(titleComponents[1]);
        panel.add(titleComponents[2], "growx"); // $NON-NLS-1$
        panel.add(autoDetachOnValidationCB);
        panel.add(detachButton);
        panel.add(titleComponents[3]);
        panel.add(titleComponents[4], "span 3, growx"); // $NON-NLS-1$
        updateDetachButton();
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
        detachedWindow.setSize(detachedWindowSize());
        GuiPackage guiPackage = GuiPackage.getInstance();
        detachedWindow.setLocationRelativeTo(guiPackage == null ? null : guiPackage.getMainFrame());
        detachedWindow.setVisible(true);

        updateDetachButton();
        revalidate();
        repaint();
    }

    private void detachForValidationIfNeeded() {
        if (autoDetachOnValidationCB == null || !autoDetachOnValidationCB.isSelected()
                || !JMeterContextService.isValidationRun()) {
            return;
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
            resultsObject = node.getUserObject();
            // to restore last tab used
            if (rightSide.getTabCount() > selectedTab) {
                resultsRender.setLastSelectedTab(rightSide.getSelectedIndex());
            }
            Object userObject = node.getUserObject();
            resultsRender.setSamplerResult(userObject);
            resultsRender.setupTabPane(); // Processes Assertions
            renderedResponseObject = null;
            renderSelectedTabIfNeeded();
        }
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

        VerticalPanel leftPane = new VerticalPanel();
        leftPane.add(treePane, BorderLayout.CENTER);
        leftPane.add(createComboRender(), BorderLayout.NORTH);
        autoScrollCB = new JCheckBox(JMeterUtils.getResString("view_results_autoscroll")); // $NON-NLS-1$
        autoScrollCB.setSelected(false);
        autoScrollCB.addItemListener(this);
        leftPane.add(autoScrollCB, BorderLayout.SOUTH);
        return leftPane;
    }

    private void jumpToResultTestPlanElementOnDoubleClick(MouseEvent event) {
        if (detachedWindow == null || event.getClickCount() != 2 || event.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        TreePath resultPath = jTree.getPathForLocation(event.getX(), event.getY());
        if (resultPath == null) {
            return;
        }
        jTree.setSelectionPath(resultPath);

        SampleResult sampleResult = getSampleResult(resultPath);
        jumpToTestPlanElement(findTestPlanNode(sampleResult));
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

        JPopupMenu popup = new JPopupMenu();
        popup.add(jumpTo);
        popup.show(jTree, event.getX(), event.getY());
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

    private static JMeterTreeNode findTestPlanNode(SampleResult sampleResult) {
        if (sampleResult == null || sampleResult.getSourceTestElementPath().isEmpty()) {
            return null;
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return null;
        }
        List<SampleResult.TestElementPathEntry> sourcePath = sampleResult.getSourceTestElementPath();
        JMeterTreeNode current = findDescendant((JMeterTreeNode) guiPackage.getTreeModel().getRoot(), sourcePath.get(0));
        for (SampleResult.TestElementPathEntry pathEntry : sourcePath.subList(1, sourcePath.size())) {
            current = findChild(current, pathEntry);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static JMeterTreeNode findDescendant(JMeterTreeNode parent, SampleResult.TestElementPathEntry pathEntry) {
        JMeterTreeNode child = findChild(parent, pathEntry);
        if (child != null) {
            return child;
        }
        Enumeration<?> children = parent.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode descendant = findDescendant((JMeterTreeNode) children.nextElement(), pathEntry);
            if (descendant != null) {
                return descendant;
            }
        }
        return null;
    }

    private static JMeterTreeNode findChild(JMeterTreeNode parent, SampleResult.TestElementPathEntry pathEntry) {
        if (parent == null) {
            return null;
        }
        int occurrence = 0;
        Enumeration<?> children = parent.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            Object userObject = child.getUserObject();
            if (userObject != null
                    && userObject.getClass().getName().equals(pathEntry.className())
                    && Objects.equals(child.getName(), pathEntry.name())) {
                if (occurrence == pathEntry.occurrence()) {
                    return child;
                }
                occurrence++;
            }
        }
        return null;
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
                    mainSplit.add(rightSide);
                    mainSplit.setDividerLocation(dividerLocation);
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
