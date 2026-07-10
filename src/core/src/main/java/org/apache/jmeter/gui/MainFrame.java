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

package org.apache.jmeter.gui;

import java.awt.AWTEvent;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DropMode;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.MenuElement;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

import org.apache.jmeter.ai.gui.AiAutoScriptingLogWindow;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.KeyStrokes;
import org.apache.jmeter.gui.action.LoadDraggedFile;
import org.apache.jmeter.gui.logging.GuiLogEventListener;
import org.apache.jmeter.gui.logging.LogEventObject;
import org.apache.jmeter.gui.tree.JMeterCellRenderer;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.tree.JMeterTreeTransferHandler;
import org.apache.jmeter.gui.update.ReleaseInfo;
import org.apache.jmeter.gui.update.UpdateService;
import org.apache.jmeter.gui.util.EscapeDialog;
import org.apache.jmeter.gui.util.JMeterMenuBar;
import org.apache.jmeter.gui.util.JMeterToolBar;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.ComponentUtil;
import org.apache.jorphan.gui.JFactory;
import org.apache.jorphan.gui.JMeterUIDefaults;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.jorphan.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * The main JMeter frame, containing the menu bar, test tree, and an area for
 * JMeter component GUIs.
 */
public class MainFrame extends JFrame implements TestStateListener, DropTargetListener, Clearable, ActionListener {

    private static final long serialVersionUID = 241L;

    // This is used to keep track of test state notifications.
    public static final String LOCAL = "*local*"; // $NON-NLS-1$

    // The application name
    private static final String DEFAULT_APP_NAME = "BreakTest"; // $NON-NLS-1$

    // The default title for the Menu bar
    private static final String DEFAULT_TITLE = DEFAULT_APP_NAME +
            " (" + JMeterUtils.getJMeterVersion() + ")"; // $NON-NLS-1$ $NON-NLS-2$

    // Allow display/hide LoggerPanel
    private static final boolean DISPLAY_LOGGER_PANEL =
            JMeterUtils.getPropDefault("jmeter.loggerpanel.display", false); // $NON-NLS-1$

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);

    private static final String OS_NAME = System.getProperty("os.name");// $NON-NLS-1$

    private static final boolean IS_MAC =
            Pattern.compile("mac os x|darwin|osx", Pattern.CASE_INSENSITIVE)
                    .matcher(OS_NAME)
                    .find();

    private static final int APP_CHROME_GAP = 8;

    private static final int TEST_PLAN_PANE_WIDTH =
            JMeterUtils.getPropDefault("jmeter.gui.testplan.width", 300); // $NON-NLS-1$

    /** The menu bar. */
    private JMeterMenuBar menuBar;

    /** The main panel where components display their GUIs. */
    private JScrollPane mainPanel;

    /** The scrollable view inside the main panel. */
    private ScrollableMainPanel mainPanelView;

    /** The panel where the test tree is shown. */
    private JComponent treePanel;

    /** The LOG panel. */
    private LoggerPanel logPanel;

    /** Bottom tabs for log-like panels. */
    private JTabbedPane bottomLogTabs;

    /** Split between the main editor and bottom log tabs. */
    private JSplitPane topAndDown;

    /** The test tree. */
    private JTree tree;

    /** An image which is displayed when a test is running. */
    private final Icon runningIcon = new StatusDotIcon(new Color(0x16A34A), true);

    /** An image which is displayed when a test is not currently running. */
    private final Icon stoppedIcon = new StatusDotIcon(new Color(0x9CA3AF), false);

    /** An image which is displayed to indicate FATAL, ERROR or WARNING. */
    private final Icon warningIcon = new WarningStatusIcon();

    /** The set of currently running hosts. */
    private final Set<String> hosts = new HashSet<>();

    /** A message dialog shown while JMeter threads are stopping. */
    private JDialog stoppingMessage;

    private LoadingGlassPane loadingGlassPane;

    private JLabel activeAndTotalThreads;

    private JLabel bottomElapsed;

    private JLabel bottomThreads;

    private JLabel bottomWarnings;

    private JLabel bottomRunState;

    private JLabel bottomProjectFile;

    private JLabel bottomSaveState;

    private JButton bottomUpdate;

    private JButton bottomReleaseNotes;

    private JMeterToolBar toolbar;

    /** Label at top right showing test duration */
    private JLabel testTimeDuration;

    /** Indicator for Log errors and Fatals */
    private JButton warnIndicator;

    /** LogTarget that receives ERROR or FATAL */
    private transient ErrorsAndFatalsCounterLogTarget errorsAndFatalsCounterLogTarget;

    private final javax.swing.Timer computeTestDurationTimer = new javax.swing.Timer(1000,
            this::computeTestDuration);

    private final AtomicInteger errorOrFatal = new AtomicInteger(0);

    private final javax.swing.Timer refreshErrorsTimer = new javax.swing.Timer(1000,
            this::refreshErrors);

    /**
     * Create a new JMeter frame.
     *
     * @param treeModel
     *            the model for the test tree
     * @param treeListener
     *            the listener for the test tree
     */
    public MainFrame(TreeModel treeModel, JMeterTreeListener treeListener) {
        testTimeDuration = new JLabel("00:00:00"); //$NON-NLS-1$
        testTimeDuration.setToolTipText(JMeterUtils.getResString("duration_tooltip")); //$NON-NLS-1$

        activeAndTotalThreads = new JLabel("0/0"); // $NON-NLS-1$
        activeAndTotalThreads.setToolTipText(JMeterUtils.getResString("active_total_threads_tooltip")); // $NON-NLS-1$

        warnIndicator = new JButton(warningIcon);
        warnIndicator.setMargin(new Insets(0, 0, 0, 0));
        // Transparent JButton with no border
        warnIndicator.setOpaque(false);
        warnIndicator.setContentAreaFilled(false);
        warnIndicator.setBorderPainted(false);
        warnIndicator.setCursor(new Cursor(Cursor.HAND_CURSOR));
        warnIndicator.setToolTipText(JMeterUtils.getResString("error_indicator_tooltip")); // $NON-NLS-1$
        warnIndicator.addActionListener(this);

        tree = makeTree(treeModel, treeListener);

        GuiPackage.getInstance().setMainFrame(this);
        init();
        UpdateService updateService = UpdateService.getInstance();
        updateService.addListener(this::showUpdateAvailable);
        updateService.startAutomaticChecks();
        initTopLevelDndHandler();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        int ctrlAltMask = (IS_MAC ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK) |
                InputEvent.ALT_DOWN_MASK;

        addMouseWheelListener(e -> {
            if (e.getWheelRotation() == 0) {
                // Nothing to do here. This happens when scroll event is delivered from a touchbar
                // or MagicMouse. There's getPreciseWheelRotation, however it looks like there's no
                // trivial and consistent way to use that
                // See https://github.com/JetBrains/intellij-community/blob/21c99af7c78fc82aefc4d05646389f4991b08b38/bin/idea.properties#L133-L156
                return;
            }
            // Shift down means "horizontal scrolling" on macOS, and we need only vertical one
            if ((e.getModifiersEx() & (ctrlAltMask | InputEvent.SHIFT_DOWN_MASK)) == ctrlAltMask) {
                e.consume();
                final float scale = 1.1f;
                int rotation = e.getWheelRotation();
                if (rotation > 0) { // DOWN
                    JMeterUtils.applyScaleOnFonts(1.0f / scale);
                } else if (rotation < 0) { // UP
                    JMeterUtils.applyScaleOnFonts(scale);
                }
                JMeterUtils.refreshUI();
            }
        });
        addPropertyChangeListener("graphicsConfiguration", evt -> {
            // Update UI when JMeter window moves to a different monitor as it might have different scaling settings
            JMeterUtils.refreshUI();
        });
    }

    /**
     * Refresh errors label
     * @param evt {@link ActionEvent}
     */
    private void refreshErrors(ActionEvent evt) {
        if (errorOrFatal.get() > 0) {
            warnIndicator.setForeground(UIManager.getColor(JMeterUIDefaults.BUTTON_ERROR_FOREGROUND));
            warnIndicator.setText(Integer.toString(errorOrFatal.get()));
            if (bottomWarnings != null) {
                bottomWarnings.setText("Issues: " + errorOrFatal.get()); // $NON-NLS-1$
            }
        }
    }

    protected void computeTestDuration(ActionEvent evt) {
        long startTime = JMeterContextService.getTestStartTime();
        if (startTime > 0) {
            long elapsedSec = (System.currentTimeMillis() - startTime + 500) / 1000; // rounded seconds
            String duration = JOrphanUtils.formatDuration(elapsedSec);
            testTimeDuration.setText(duration);
            if (bottomElapsed != null) {
                bottomElapsed.setText("Elapsed: " + duration); // $NON-NLS-1$
            }
        }
    }

    /**
     * Default constructor for the JMeter frame. This constructor will not
     * properly initialize the tree, so don't use it.
     *
     * @deprecated Do not use - only needed for JUnit tests
     */
    @Deprecated
    public MainFrame() {
    }

    // MenuBar related methods
    // TODO: Do we really need to have all these menubar methods duplicated
    // here? Perhaps we can make the menu bar accessible through GuiPackage?

    /**
     * Specify whether or not the File|Load menu item should be enabled.
     *
     * @param enabled
     *            true if the menu item should be enabled, false otherwise
     */
    public void setFileLoadEnabled(boolean enabled) {
        menuBar.setFileLoadEnabled(enabled);
    }

    /**
     * Specify whether or not the File|Save menu item should be enabled.
     *
     * @param enabled
     *            true if the menu item should be enabled, false otherwise
     */
    public void setFileSaveEnabled(boolean enabled) {
        menuBar.setFileSaveEnabled(enabled);
        updateDirtyStatus(enabled);
    }

    public void updateDirtyStatus(boolean dirty) {
        if (bottomSaveState == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            bottomSaveState.setText(dirty ? "Unsaved changes" : "Saved"); // $NON-NLS-1$ $NON-NLS-2$
            bottomSaveState.setForeground(uiColor(dirty ? "Actions.Yellow" : "Actions.Green", // $NON-NLS-1$ $NON-NLS-2$
                    dirty ? new Color(0xD97706) : new Color(0x16A34A)));
        });
    }

    /**
     * Specify whether or not the File|Revert item should be enabled.
     *
     * @param enabled
     *            true if the menu item should be enabled, false otherwise
     */
    public void setFileRevertEnabled(boolean enabled) {
        menuBar.setFileRevertEnabled(enabled);
    }

    /**
     * Specify the project file that was just loaded
     *
     * @param file - the full path to the file that was loaded
     */
    public void setProjectFileLoaded(String file) {
        menuBar.setProjectFileLoaded(file);
    }

    /**
     * Set the menu that should be used for the Edit menu.
     *
     * @param menu
     *            the new Edit menu
     */
    public void setEditMenu(JPopupMenu menu) {
        menuBar.setEditMenu(menu);
    }

    /**
     * Specify whether or not the Edit menu item should be enabled.
     *
     * @param enabled
     *            true if the menu item should be enabled, false otherwise
     */
    public void setEditEnabled(boolean enabled) {
        menuBar.setEditEnabled(enabled);
    }

    /**
     * Set the menu that should be used for the Edit|Add menu.
     *
     * @param menu
     *            the new Edit|Add menu
     */
    public void setEditAddMenu(JMenu menu) {
        menuBar.setEditAddMenu(menu);
    }

    /**
     * Specify whether or not the Edit|Add menu item should be enabled.
     *
     * @param enabled
     *            true if the menu item should be enabled, false otherwise
     */
    public void setEditAddEnabled(boolean enabled) {
        menuBar.setEditAddEnabled(enabled);
    }

    /**
     * Close the currently selected menu.
     */
    public void closeMenu() {
        if (!menuBar.isSelected()) {
            return;
        }
        MenuElement[] menuElement = menuBar.getSubElements();
        if (menuElement != null) {
            for (MenuElement element : menuElement) {
                JMenu menu = (JMenu) element;
                if (menu.isSelected()) {
                    menu.setPopupMenuVisible(false);
                    menu.setSelected(false);
                    break;
                }
            }
        }
    }

    /**
     * Show a dialog indicating that JMeter threads are stopping on a particular
     * host.
     *
     * @param host
     *            the host where JMeter threads are stopping
     */
    public void showStoppingMessage(String host) {
        if (stoppingMessage != null){
            stoppingMessage.dispose();
        }
        stoppingMessage = new EscapeDialog(this, JMeterUtils.getResString("stopping_test_title"), true); //$NON-NLS-1$
        String label = JMeterUtils.getResString("stopping_test"); //$NON-NLS-1
        if (StringUtilities.isNotEmpty(host)) {
            label = label + " " + JMeterUtils.getResString("stopping_test_host") + ": " + host;
        }
        JLabel stopLabel = new JLabel(label); //$NON-NLS-1$$NON-NLS-2$
        stopLabel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        stoppingMessage.getContentPane().add(stopLabel);
        stoppingMessage.pack();
        ComponentUtil.centerComponentInComponent(this, stoppingMessage);
        SwingUtilities.invokeLater(() -> {
                if (stoppingMessage != null) {
                    stoppingMessage.setVisible(true);
                }
        });
    }

    public void updateCounts() {
        SwingUtilities.invokeLater(() -> {
            String threads = String.format("%d/%d",
                    JMeterContextService.getNumberOfThreads(),
                    JMeterContextService.getTotalThreads());
            activeAndTotalThreads.setText(threads);
            if (bottomThreads != null) {
                bottomThreads.setText("Threads: " + threads); // $NON-NLS-1$
            }
        });
    }

    public void setMainPanel(JComponent comp) {
        mainPanelView.setMainPanel(comp);
    }

    public void showLoadingOverlay(String message) {
        if (loadingGlassPane == null) {
            loadingGlassPane = new LoadingGlassPane();
            setGlassPane(loadingGlassPane);
        }
        loadingGlassPane.setMessage(message);
        loadingGlassPane.start();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    }

    public void hideLoadingOverlay() {
        if (loadingGlassPane != null) {
            loadingGlassPane.stop();
        }
        setCursor(Cursor.getDefaultCursor());
    }

    public JTree getTree() {
        return tree;
    }

    // TestStateListener implementation

    /**
     * Called when a test is started on the local system. This implementation
     * sets the running indicator and ensures that the menubar is enabled and in
     * the running state.
     */
    @Override
    public void testStarted() {
        testStarted(LOCAL);
        menuBar.setEnabled(true);
    }

    /**
     * Called when a test is started on a specific host. This implementation
     * sets the running indicator and ensures that the menubar is in the running
     * state.
     *
     * @param host
     *            the host where the test is starting
     */
    @Override
    public void testStarted(String host) {
        hosts.add(host);
        computeTestDurationTimer.start();
        updateRunStatus(true);
        activeAndTotalThreads.setText("0/0"); // $NON-NLS-1$
        if (bottomThreads != null) {
            bottomThreads.setText("Threads: 0/0"); // $NON-NLS-1$
        }
        if (bottomElapsed != null) {
            bottomElapsed.setText("Elapsed: 00:00:00"); // $NON-NLS-1$
        }
        toolbar.setLocalTestStarted(true);
    }

    /**
     * Called when a test is ended on the local system. This implementation
     * disables the menubar, stops the running indicator, and closes the
     * stopping message dialog.
     */
    @Override
    public void testEnded() {
        testEnded(LOCAL);
        menuBar.setEnabled(false);
    }

    /**
     * Called when a test is ended. This implementation
     * stops the running indicator and closes the stopping message dialog.
     *
     * @param host
     *            the host where the test is ending
     */
    @Override
    public void testEnded(String host) {
        hosts.remove(host);
        if (hosts.isEmpty()) {
            updateRunStatus(false);
            JMeterContextService.endTest();
            computeTestDurationTimer.stop();
            if (bottomThreads != null) {
                bottomThreads.setText("Threads: 0/0"); // $NON-NLS-1$
            }
        }
        toolbar.setLocalTestStarted(false);
        if (stoppingMessage != null) {
            stoppingMessage.dispose();
            stoppingMessage = null;
        }
    }

    private void updateRunStatus(boolean running) {
        if (bottomRunState == null) {
            return;
        }
        bottomRunState.setIcon(running ? runningIcon : stoppedIcon);
        bottomRunState.setText(running ? "Running" : "Stopped"); // $NON-NLS-1$ $NON-NLS-2$
    }

    /**
     * Create the GUI components and layout.
     */
    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        menuBar = new JMeterMenuBar();
        setJMenuBar(menuBar);
        JPanel all = new JPanel(new BorderLayout());
        all.add(createToolBar(), BorderLayout.NORTH);

        JSplitPane treeAndMain = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        treePanel = createTreePanel();
        treeAndMain.setLeftComponent(treePanel);

        topAndDown = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        topAndDown.setOneTouchExpandable(true);
        topAndDown.setDividerLocation(0.8);
        topAndDown.setResizeWeight(.8);
        topAndDown.setContinuousLayout(true);
        topAndDown.setBorder(null); // see bug jdk 4131528
        mainPanel = createMainPanel();
        mainPanel.setBorder(BorderFactory.createEmptyBorder());

        logPanel = createLoggerPanel();
        logPanel.setBorder(BorderFactory.createEmptyBorder());
        errorsAndFatalsCounterLogTarget = new ErrorsAndFatalsCounterLogTarget();
        GuiPackage.getInstance().getLogEventBus().registerEventListener(logPanel);
        GuiPackage.getInstance().getLogEventBus().registerEventListener(errorsAndFatalsCounterLogTarget);

        topAndDown.setTopComponent(mainPanel);
        bottomLogTabs = new JTabbedPane();
        bottomLogTabs.setBorder(BorderFactory.createMatteBorder(2, 0, 0, 0, bottomLogDividerColor()));
        topAndDown.setBottomComponent(bottomLogTabs);
        if (DISPLAY_LOGGER_PANEL) {
            showLoggerPanel();
        } else {
            updateBottomLogVisibility();
        }

        treeAndMain.setRightComponent(topAndDown);

        treeAndMain.setDividerLocation(TEST_PLAN_PANE_WIDTH);
        treeAndMain.setResizeWeight(0);
        treeAndMain.setContinuousLayout(true);
        all.add(treeAndMain, BorderLayout.CENTER);
        all.add(createStatusBar(), BorderLayout.SOUTH);

        getContentPane().add(all);

        tree.setSelectionRow(1);
        addWindowListener(new WindowHappenings());
        // Building is complete, register as listener
        GuiPackage.getInstance().registerAsListener();
        setTitle(DEFAULT_TITLE);
        setApplicationIcon();
        setWindowTitle(); // define AWT WM_CLASS string
        refreshErrorsTimer.start();
    }

    /**
     * Support for Test Plan Dnd
     * see BUG 52281 (when JDK6 will be minimum JDK target)
     */
    public void initTopLevelDndHandler() {
        new DropTarget(this, this);
    }

    public void setExtendedFrameTitle(String fname) {
        // file New operation may set to null, so just return app name
        if (fname == null) {
            setTitle(DEFAULT_TITLE);
            if (bottomProjectFile != null) {
                bottomProjectFile.setText("Untitled plan"); // $NON-NLS-1$
            }
            return;
        }

        // allow for windows / chars in filename
        String temp = fname.replace('\\', '/'); // $NON-NLS-1$ // $NON-NLS-2$
        String simpleName = temp.substring(temp.lastIndexOf('/') + 1);// $NON-NLS-1$
        setTitle(simpleName + " (" + fname + ") - " + DEFAULT_TITLE); // $NON-NLS-1$ // $NON-NLS-2$
        if (bottomProjectFile != null) {
            bottomProjectFile.setText(simpleName);
        }
    }

    /**
     * Create the JMeter tool bar pane containing the running indicator.
     *
     * @return a panel containing the running indicator
     */
    private Component createToolBar() {
        JPanel commandBar = new JPanel(new BorderLayout(APP_CHROME_GAP, 0));
        JFactory.withDynamic(commandBar, MainFrame::styleChromeBar);

        JMeterToolBar toolPanel = JMeterToolBar.createToolbar(true);
        // add the toolbar
        this.toolbar = toolPanel;
        GuiPackage guiInstance = GuiPackage.getInstance();
        guiInstance.setMainToolbar(toolbar);

        toolPanel.add(Box.createGlue());

        warnIndicator.setText("0");
        commandBar.add(toolPanel, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        statusPanel.setOpaque(false);
        styleStatusLabel(testTimeDuration);
        styleStatusLabel(activeAndTotalThreads);
        styleStatusButton(warnIndicator);
        statusPanel.add(testTimeDuration);
        statusPanel.add(activeAndTotalThreads);
        statusPanel.add(warnIndicator);
        JButton aiLogButton = new JButton("AI Log");
        aiLogButton.setToolTipText("Show or hide the AI Auto Scripting (Beta) log");
        aiLogButton.addActionListener(event -> AiAutoScriptingLogWindow.toggleVisibility());
        styleStatusButton(aiLogButton);
        statusPanel.add(aiLogButton);
        commandBar.add(statusPanel, BorderLayout.EAST);
        return commandBar;
    }

    /**
     * Create the panel where the GUI representation of the test tree is
     * displayed. The tree should already be created before calling this method.
     *
     * @return a scroll pane containing the test tree GUI
     */
    private JComponent createTreePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(uiColor("Panel.background", Color.WHITE)); // $NON-NLS-1$
        panel.setMinimumSize(new Dimension(240, 0));
        panel.setPreferredSize(new Dimension(TEST_PLAN_PANE_WIDTH, 0));
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1,
                uiColor("Component.borderColor", new Color(0xD7DEE8)))); // $NON-NLS-1$

        JPanel header = new JPanel(new BorderLayout(APP_CHROME_GAP, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 10));
        JLabel title = new JLabel("Test Plan"); // $NON-NLS-1$
        title.setFont(title.getFont().deriveFont(title.getFont().getStyle() | java.awt.Font.BOLD));
        header.add(title, BorderLayout.WEST);

        JTextField searchField = new JTextField("Search plan"); // $NON-NLS-1$
        searchField.setEditable(false);
        searchField.setFocusable(false);
        searchField.setToolTipText(JMeterUtils.getResString("menu_search")); // $NON-NLS-1$
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(uiColor("Component.borderColor", new Color(0xD7DEE8))), // $NON-NLS-1$
                BorderFactory.createEmptyBorder(5, 9, 5, 9)));
        searchField.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                ActionRouter.getInstance().doActionNow(
                        new ActionEvent(searchField, ActionEvent.ACTION_PERFORMED, ActionNames.SEARCH_TREE));
            }
        });
        header.add(searchField, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);

        JScrollPane treeP = new JScrollPane(tree);
        treeP.setMinimumSize(new Dimension(100, 0));
        treeP.setBorder(BorderFactory.createEmptyBorder());
        treeP.getViewport().setBackground(uiColor("Panel.background", Color.WHITE)); // $NON-NLS-1$
        panel.add(treeP, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Create the main panel where components can display their GUIs.
     *
     * @return the main scroll pane
     */
    private JScrollPane createMainPanel() {
        mainPanelView = new ScrollableMainPanel();
        JScrollPane scrollPane = new JScrollPane(mainPanelView);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(uiColor("Panel.background", Color.WHITE)); // $NON-NLS-1$
        return scrollPane;
    }

    private JComponent createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout(APP_CHROME_GAP, 0));
        JFactory.withDynamic(statusBar, MainFrame::styleStatusBar);

        JLabel ready = new JLabel("Ready"); // $NON-NLS-1$
        ready.setForeground(uiColor("Actions.Green", new Color(0x16A34A))); // $NON-NLS-1$
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(ready);
        bottomUpdate = new JButton();
        bottomUpdate.setVisible(false);
        bottomUpdate.setActionCommand(ActionNames.INSTALL_UPDATE);
        bottomUpdate.addActionListener(ActionRouter.getInstance());
        styleStatusButton(bottomUpdate);
        left.add(bottomUpdate);
        bottomReleaseNotes = new JButton("<html><a href=''>"
                + JMeterUtils.getResString("link_release_notes") + "</a></html>");
        bottomReleaseNotes.setVisible(false);
        bottomReleaseNotes.setFocusable(false);
        bottomReleaseNotes.setOpaque(false);
        bottomReleaseNotes.setContentAreaFilled(false);
        bottomReleaseNotes.setBorderPainted(false);
        bottomReleaseNotes.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        bottomReleaseNotes.setActionCommand(ActionNames.LINK_RELEASE_NOTES);
        bottomReleaseNotes.addActionListener(ActionRouter.getInstance());
        left.add(bottomReleaseNotes);
        statusBar.add(left, BorderLayout.WEST);

        bottomProjectFile = new JLabel("Untitled plan"); // $NON-NLS-1$
        JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        center.setOpaque(false);
        center.add(bottomProjectFile);
        bottomSaveState = new JLabel();
        updateDirtyStatus(GuiPackage.getInstance() != null && GuiPackage.getInstance().isDirty());
        center.add(bottomSaveState);
        statusBar.add(center, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);
        bottomThreads = new JLabel("Threads: 0/0"); // $NON-NLS-1$
        bottomElapsed = new JLabel("Elapsed: 00:00:00"); // $NON-NLS-1$
        bottomWarnings = new JLabel("Issues: 0"); // $NON-NLS-1$
        bottomRunState = new JLabel("Stopped", stoppedIcon, SwingConstants.LEADING); // $NON-NLS-1$
        bottomRunState.setIconTextGap(6);
        right.add(bottomRunState);
        right.add(bottomThreads);
        right.add(bottomElapsed);
        right.add(bottomWarnings);
        statusBar.add(right, BorderLayout.EAST);
        return statusBar;
    }

    private void showUpdateAvailable(ReleaseInfo release) {
        SwingUtilities.invokeLater(() -> {
            bottomUpdate.setText(JMeterUtils.getResString("update_status_available")
                    .replace("{0}", release.version()));
            bottomUpdate.setToolTipText(JMeterUtils.getResString("update_status_tooltip"));
            bottomUpdate.setVisible(true);
            bottomReleaseNotes.setVisible(true);
            bottomUpdate.getParent().revalidate();
            bottomUpdate.getParent().repaint();
        });
    }

    private static class ScrollableMainPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 240L;

        ScrollableMainPanel() {
            super(new BorderLayout());
            setOpaque(true);
            setBackground(uiColor("Panel.background", Color.WHITE)); // $NON-NLS-1$
            setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
        }

        void setMainPanel(JComponent comp) {
            removeAll();
            add(comp, BorderLayout.CENTER);
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getPreferredSize().height <= getParent().getHeight();
        }
    }

    private static void styleStatusLabel(JLabel label) {
        JFactory.withDynamic(label, MainFrame::applyStatusLabelStyle);
    }

    private static void applyStatusLabelStyle(JLabel label) {
        label.setOpaque(true);
        label.setBackground(statusChipBackground());
        label.setForeground(statusChipForeground());
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(statusChipBorder()),
                BorderFactory.createEmptyBorder(4, 9, 4, 9)));
    }

    private static void styleStatusButton(JButton button) {
        JFactory.withDynamic(button, MainFrame::applyStatusButtonStyle);
    }

    private static void applyStatusButtonStyle(JButton button) {
        button.setFocusable(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(true);
        button.setMargin(new Insets(4, 9, 4, 9));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(statusChipBorder()),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        button.setBackground(statusChipBackground());
        button.setForeground(statusChipForeground());
    }

    private static void styleChromeBar(JPanel panel) {
        panel.setOpaque(true);
        panel.setBackground(uiColor("ToolBar.background", uiColor("Panel.background", Color.WHITE))); // $NON-NLS-1$ $NON-NLS-2$
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0,
                        uiColor("Component.borderColor", new Color(0xD7DEE8))), // $NON-NLS-1$
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
    }

    private static void styleStatusBar(JPanel panel) {
        panel.setOpaque(true);
        panel.setBackground(uiColor("ToolBar.background", uiColor("Panel.background", Color.WHITE))); // $NON-NLS-1$ $NON-NLS-2$
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        uiColor("Component.borderColor", new Color(0xD7DEE8))), // $NON-NLS-1$
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    private static Color statusChipBackground() {
        Color toolbar = uiColor("ToolBar.background", uiColor("Panel.background", Color.WHITE)); // $NON-NLS-1$ $NON-NLS-2$
        if (isDark(toolbar)) {
            return mix(toolbar, Color.WHITE, 0.08);
        }
        return uiColor("Component.background", Color.WHITE); // $NON-NLS-1$
    }

    private static Color statusChipBorder() {
        Color background = statusChipBackground();
        return isDark(background)
                ? mix(background, Color.WHITE, 0.18)
                : uiColor("Component.borderColor", new Color(0xD7DEE8)); // $NON-NLS-1$
    }

    private static Color statusChipForeground() {
        Color toolbar = uiColor("ToolBar.background", uiColor("Panel.background", Color.WHITE)); // $NON-NLS-1$ $NON-NLS-2$
        return isDark(toolbar)
                ? mix(toolbar, Color.WHITE, 0.78)
                : uiColor("Label.foreground", new Color(0x111827)); // $NON-NLS-1$
    }

    private static boolean isDark(Color color) {
        return luminance(color) < 0.45;
    }

    private static double luminance(Color color) {
        return 0.2126 * luminanceChannel(color.getRed())
                + 0.7152 * luminanceChannel(color.getGreen())
                + 0.0722 * luminanceChannel(color.getBlue());
    }

    private static double luminanceChannel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.03928
                ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }

    private static Color mix(Color base, Color overlay, double amount) {
        return new Color(
                mixChannel(base.getRed(), overlay.getRed(), amount),
                mixChannel(base.getGreen(), overlay.getGreen(), amount),
                mixChannel(base.getBlue(), overlay.getBlue(), amount));
    }

    private static int mixChannel(int base, int overlay, double amount) {
        return Math.max(0, Math.min(255, (int) Math.round(base + (overlay - base) * amount)));
    }

    private void setApplicationIcon() {
        URL svgUrl = JMeterUtils.class.getResource("/org/apache/jmeter/images/icon-breaktest.svg"); // $NON-NLS-1$
        if (svgUrl != null) {
            setIconImages(Arrays.asList(
                    svgImage(svgUrl, 16),
                    svgImage(svgUrl, 24),
                    svgImage(svgUrl, 32),
                    svgImage(svgUrl, 48),
                    svgImage(svgUrl, 64),
                    svgImage(svgUrl, 128),
                    svgImage(svgUrl, 256),
                    svgImage(svgUrl, 512)));
            return;
        }

        ImageIcon fallback = JMeterUtils.getImage("icon-breaktest.png"); // $NON-NLS-1$
        if (fallback != null) {
            setIconImage(fallback.getImage());
        }
    }

    private static Image svgImage(URL svgUrl, int size) {
        return new FlatSVGIcon(svgUrl).derive(size, size).getImage();
    }

    private static final class StatusDotIcon implements Icon {
        private static final int SIZE = 16;

        private final Color color;
        private final boolean filled;

        private StatusDotIcon(Color color, boolean filled) {
            this.color = color;
            this.filled = filled;
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setStroke(new BasicStroke(1.8f));
                g2.setColor(color);
                if (filled) {
                    g2.fillOval(x + 3, y + 3, 10, 10);
                } else {
                    g2.drawOval(x + 3, y + 3, 10, 10);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    private static final class WarningStatusIcon implements Icon {
        private static final int SIZE = 16;

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(new Color(0xD97706));
                g2.drawLine(x + 8, y + 3, x + 14, y + 13);
                g2.drawLine(x + 14, y + 13, x + 2, y + 13);
                g2.drawLine(x + 2, y + 13, x + 8, y + 3);
                g2.drawLine(x + 8, y + 7, x + 8, y + 10);
                g2.drawLine(x + 8, y + 12, x + 8, y + 12);
            } finally {
                g2.dispose();
            }
        }
    }

    private static class LoadingGlassPane extends JComponent implements ActionListener {
        private static final long serialVersionUID = 241L;
        private static final int TICK_COUNT = 12;
        private static final int TIMER_DELAY_MILLIS = 80;
        private static final int SPINNER_RADIUS = 22;
        private static final int TICK_LENGTH = 9;

        private final javax.swing.Timer timer = new javax.swing.Timer(TIMER_DELAY_MILLIS, this);
        private int tick;
        private String message = ""; // $NON-NLS-1$

        LoadingGlassPane() {
            setOpaque(false);
            setFocusTraversalKeysEnabled(false);
            enableEvents(AWTEvent.MOUSE_EVENT_MASK
                    | AWTEvent.MOUSE_MOTION_EVENT_MASK
                    | AWTEvent.MOUSE_WHEEL_EVENT_MASK
                    | AWTEvent.KEY_EVENT_MASK);
        }

        void setMessage(String message) {
            this.message = message == null ? "" : message; // $NON-NLS-1$
        }

        void start() {
            tick = 0;
            setVisible(true);
            requestFocusInWindow();
            timer.start();
            repaint();
        }

        void stop() {
            timer.stop();
            setVisible(false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            tick = (tick + 1) % TICK_COUNT;
            repaint();
        }

        @Override
        protected void processMouseEvent(MouseEvent e) {
            e.consume();
        }

        @Override
        protected void processMouseMotionEvent(MouseEvent e) {
            e.consume();
        }

        @Override
        protected void processMouseWheelEvent(java.awt.event.MouseWheelEvent e) {
            e.consume();
        }

        @Override
        protected void processKeyEvent(KeyEvent e) {
            e.consume();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0, 0, 0, 85));
                g2.fillRect(0, 0, getWidth(), getHeight());

                int centerX = getWidth() / 2;
                int centerY = getHeight() / 2;
                paintSpinner(g2, centerX, centerY - 14);
                paintMessage(g2, centerX, centerY + 34);
            } finally {
                g2.dispose();
            }
        }

        private void paintSpinner(Graphics2D g2, int centerX, int centerY) {
            g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Color base = UIManager.getColor("Label.foreground"); // $NON-NLS-1$
            if (base == null) {
                base = Color.WHITE;
            }
            for (int i = 0; i < TICK_COUNT; i++) {
                int alpha = Math.max(45, 255 - Math.floorMod(tick - i, TICK_COUNT) * 17);
                double angle = Math.PI * 2 * i / TICK_COUNT;
                int x1 = centerX + (int) Math.round(Math.cos(angle) * SPINNER_RADIUS);
                int y1 = centerY + (int) Math.round(Math.sin(angle) * SPINNER_RADIUS);
                int x2 = centerX + (int) Math.round(Math.cos(angle) * (SPINNER_RADIUS + TICK_LENGTH));
                int y2 = centerY + (int) Math.round(Math.sin(angle) * (SPINNER_RADIUS + TICK_LENGTH));
                g2.setComposite(AlphaComposite.SrcOver.derive(alpha / 255f));
                g2.setColor(base);
                g2.drawLine(x1, y1, x2, y2);
            }
            g2.setComposite(AlphaComposite.SrcOver);
        }

        private void paintMessage(Graphics2D g2, int centerX, int baselineY) {
            if (message.isEmpty()) {
                return;
            }
            FontMetrics metrics = g2.getFontMetrics();
            int textWidth = metrics.stringWidth(message);
            int paddingX = 14;
            int paddingY = 8;
            int boxX = centerX - textWidth / 2 - paddingX;
            int boxY = baselineY - metrics.getAscent() - paddingY;
            int boxWidth = textWidth + paddingX * 2;
            int boxHeight = metrics.getHeight() + paddingY * 2;
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawString(message, centerX - textWidth / 2, baselineY);
        }
    }

    /**
     * Create at the down of the left a Console for Log events
     * @return {@link LoggerPanel}
     */
    private static LoggerPanel createLoggerPanel() {
        LoggerPanel loggerPanel = new LoggerPanel();
        loggerPanel.setMinimumSize(new Dimension(0, 100));
        loggerPanel.setPreferredSize(new Dimension(0, 150));
        GuiPackage guiInstance = GuiPackage.getInstance();
        guiInstance.setLoggerPanel(loggerPanel);
        guiInstance.getMenuItemLoggerPanel().getModel().setSelected(DISPLAY_LOGGER_PANEL);
        loggerPanel.setVisible(DISPLAY_LOGGER_PANEL);
        return loggerPanel;
    }

    public void showLoggerPanel() {
        addBottomLogTab("jmeter.log", logPanel);
        logPanel.setVisible(true);
        bottomLogTabs.setSelectedComponent(logPanel);
        GuiPackage.getInstance().getMenuItemLoggerPanel().getModel().setSelected(true);
        updateBottomLogVisibility();
    }

    public void hideLoggerPanel() {
        removeBottomLogTab(logPanel);
        logPanel.setVisible(false);
        GuiPackage.getInstance().getMenuItemLoggerPanel().getModel().setSelected(false);
        updateBottomLogVisibility();
    }

    public boolean isLoggerPanelVisible() {
        return bottomLogTabs != null && bottomLogTabs.indexOfComponent(logPanel) >= 0;
    }

    public void showAiLogPanel() {
        JPanel aiPanel = AiAutoScriptingLogWindow.dockedComponent();
        addBottomLogTab("AI Auto Scripting (Beta)", aiPanel);
        bottomLogTabs.setSelectedComponent(aiPanel);
        updateBottomLogVisibility();
    }

    public void hideAiLogPanel() {
        removeBottomLogTab(AiAutoScriptingLogWindow.dockedComponent());
        updateBottomLogVisibility();
    }

    public boolean isAiLogPanelVisible() {
        return bottomLogTabs != null && bottomLogTabs.indexOfComponent(AiAutoScriptingLogWindow.dockedComponent()) >= 0;
    }

    private void addBottomLogTab(String title, Component component) {
        if (bottomLogTabs.indexOfComponent(component) < 0) {
            bottomLogTabs.addTab(title, component);
        }
    }

    private void removeBottomLogTab(Component component) {
        int index = bottomLogTabs.indexOfComponent(component);
        if (index >= 0) {
            bottomLogTabs.removeTabAt(index);
        }
    }

    private void updateBottomLogVisibility() {
        boolean wasVisible = bottomLogTabs.isVisible();
        boolean visible = bottomLogTabs.getTabCount() > 0;
        bottomLogTabs.setVisible(visible);
        topAndDown.setDividerSize(visible ? Math.max(UIManager.getInt("SplitPane.dividerSize"), 8) : 0);
        if (visible && !wasVisible) {
            topAndDown.setDividerLocation(0.8);
        }
    }

    private static Color bottomLogDividerColor() {
        Color panel = uiColor("Panel.background", Color.WHITE); // $NON-NLS-1$
        return isDark(panel)
                ? mix(panel, Color.WHITE, 0.22)
                : uiColor("Component.borderColor", new Color(0xC6CED8)); // $NON-NLS-1$
    }

    /**
     * Create and initialize the GUI representation of the test tree.
     *
     * @param treeModel
     *            the test tree model
     * @param treeListener
     *            the test tree listener
     *
     * @return the initialized test tree GUI
     */
    private static JTree makeTree(TreeModel treeModel, JMeterTreeListener treeListener) {
        JTree treevar = new TestPlanTree(treeModel);
        treevar.setToolTipText("");
        treevar.setCellRenderer(getCellRenderer());
        treevar.setRootVisible(false);
        treevar.setShowsRootHandles(false);
        treevar.setRowHeight(26);
        treevar.setToggleClickCount(2);
        treevar.setBackground(uiColor("Panel.background", Color.WHITE)); // $NON-NLS-1$
        treevar.expandRow(0);

        treeListener.setJTree(treevar);
        treevar.addTreeSelectionListener(treeListener);
        treevar.addMouseListener(treeListener);
        treevar.addKeyListener(treeListener);

        // enable drag&drop, install a custom transfer handler
        treevar.setDragEnabled(true);
        treevar.setDropMode(DropMode.ON_OR_INSERT);
        treevar.setTransferHandler(new JMeterTreeTransferHandler());

        addQuickComponentHotkeys(treevar);
        addUndoRedoHotkeys(treevar);

        return treevar;
    }

    private static final class TestPlanTree extends JTree {
        private static final long serialVersionUID = 240L;

        private static final int CHILD_BADGE_HORIZONTAL_PADDING = 7;
        private static final int CHILD_BADGE_RIGHT_INSET = 8;

        private TestPlanTree(TreeModel treeModel) {
            super(treeModel);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            TreePath path = this.getPathForLocation(event.getX(), event.getY());
            if (path == null) {
                return null;
            }
            Object treeNode = path.getLastPathComponent();
            if (treeNode instanceof DefaultMutableTreeNode defaultMutableTreeNode) {
                Object testElement = defaultMutableTreeNode.getUserObject();
                if (testElement instanceof TestElement element) {
                    String comment = element.getComment();
                    if (StringUtilities.isNotBlank(comment)) {
                        return comment.length() <= 80 ? comment : comment.substring(0, 77) + "...";
                    }
                }
            }
            return null;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            paintChildCountBadges(g);
        }

        private void paintChildCountBadges(Graphics g) {
            Rectangle visible = getVisibleRect();
            if (visible.width <= 0 || getRowCount() == 0) {
                return;
            }
            int firstRow = Math.max(0, getClosestRowForLocation(visible.x, visible.y));
            int lastRow = Math.max(firstRow,
                    getClosestRowForLocation(visible.x, visible.y + visible.height - 1));
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                paintChildCountBadges(g2, visible, firstRow, lastRow);
            } finally {
                g2.dispose();
            }
        }

        private void paintChildCountBadges(Graphics2D g, Rectangle visible, int firstRow, int lastRow) {
            for (int row = firstRow; row <= lastRow; row++) {
                TreePath path = getPathForRow(row);
                Rectangle rowBounds = getRowBounds(row);
                if (path == null || rowBounds == null || !rowBounds.intersects(visible)) {
                    continue;
                }
                Object node = path.getLastPathComponent();
                if (node instanceof JMeterTreeNode treeNode && treeNode.getChildCount() > 0) {
                    paintChildBadge(g, treeNode, path, rowBounds, visible);
                }
            }
        }

        private void paintChildBadge(Graphics2D g, JMeterTreeNode treeNode, TreePath path,
                Rectangle rowBounds, Rectangle visible) {
            String count = Integer.toString(treeNode.getChildCount());
            Font font = badgeFont();
            FontMetrics metrics = g.getFontMetrics(font);
            int width = metrics.stringWidth(count) + CHILD_BADGE_HORIZONTAL_PADDING * 2;
            int height = Math.max(16, metrics.getHeight() + 2);
            int x = visible.x + visible.width - CHILD_BADGE_RIGHT_INSET - width;
            int y = rowBounds.y + (rowBounds.height - height) / 2;
            boolean selected = isPathSelected(path);
            Color background = selected ? new Color(0xDBEAFE) : new Color(0xE5E7EB);
            Color foreground = selected ? new Color(0x1D4ED8) : new Color(0x6B7280);
            if (!treeNode.isEnabled()) {
                background = new Color(background.getRed(), background.getGreen(), background.getBlue(), 120);
                foreground = new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 150);
            }
            g.setColor(background);
            g.fillRoundRect(x, y, width, height, height, height);
            g.setColor(foreground);
            g.setFont(font);
            int textX = x + (width - metrics.stringWidth(count)) / 2;
            int textY = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(count, textX, textY);
        }

        private Font badgeFont() {
            return getFont().deriveFont(Font.BOLD, Math.max(9f, getFont().getSize2D() - 3f));
        }
    }

    private static void addUndoRedoHotkeys(JTree treevar) {
        InputMap inputMap = treevar.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        treevar.getActionMap().put(ActionNames.UNDO, new RoutedAction(ActionNames.UNDO));
        treevar.getActionMap().put(ActionNames.REDO, new RoutedAction(ActionNames.REDO));
        inputMap.put(KeyStrokes.UNDO, ActionNames.UNDO);
        inputMap.put(KeyStrokes.REDO, ActionNames.REDO);
        inputMap.put(KeyStrokes.UNDO_CONTROL, ActionNames.UNDO);
        inputMap.put(KeyStrokes.REDO_CONTROL, ActionNames.REDO);
    }

    private static void addQuickComponentHotkeys(JTree treevar) {
        Action quickComponent = new QuickComponent("Quick Component");

        InputMap inputMap = treevar.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        KeyStroke[] keyStrokes = new KeyStroke[]{KeyStrokes.CTRL_0,
                KeyStrokes.CTRL_1, KeyStrokes.CTRL_2, KeyStrokes.CTRL_3,
                KeyStrokes.CTRL_4, KeyStrokes.CTRL_5, KeyStrokes.CTRL_6,
                KeyStrokes.CTRL_7, KeyStrokes.CTRL_8, KeyStrokes.CTRL_9,};
        for (int n = 0; n < keyStrokes.length; n++) {
            treevar.getActionMap().put(ActionNames.QUICK_COMPONENT + String.valueOf(n), quickComponent);
            inputMap.put(keyStrokes[n], ActionNames.QUICK_COMPONENT + String.valueOf(n));
        }
    }

    private static final class RoutedAction extends AbstractAction {
        private static final long serialVersionUID = 1L;

        private final String actionName;

        private RoutedAction(String actionName) {
            super(actionName);
            this.actionName = actionName;
        }

        @Override
        public void actionPerformed(ActionEvent actionEvent) {
            // This is a WHEN_IN_FOCUSED_WINDOW binding, so it also fires while a
            // plain text field has focus. Those fields have no local undo, so a
            // global tree undo/redo here would silently wipe the test plan while
            // the user is just typing. Skip it and let editors with their own
            // undo (e.g. JSyntaxTextArea) handle the key via their WHEN_FOCUSED map.
            Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focusOwner instanceof JTextComponent textComponent && textComponent.isEditable()) {
                return;
            }
            ActionRouter.getInstance().doActionNow(
                    new ActionEvent(actionEvent.getSource(), actionEvent.getID(), actionName));
        }
    }

    /**
     * Create the tree cell renderer used to draw the nodes in the test tree.
     *
     * @return a renderer to draw the test tree nodes
     */
    private static TreeCellRenderer getCellRenderer() {
        return new JMeterCellRenderer();
    }

    private static final class QuickComponent extends AbstractAction {
        private static final long serialVersionUID = 1L;

        private QuickComponent(String name) {
            super(name);
        }

        @Override
        public void actionPerformed(ActionEvent actionEvent) {
            String propname = "gui.quick_" + getCurrentKey(actionEvent);
            String comp = JMeterUtils.getProperty(propname);
            log.debug("Event {}: {}", propname, comp);

            if (comp == null) {
                log.warn("No component set through property: {}", propname);
                return;
            }

            GuiPackage guiPackage = GuiPackage.getInstance();
            try {
                guiPackage.updateCurrentNode();
                TestElement testElement = guiPackage.createTestElement(SaveService.aliasToClass(comp));
                JMeterTreeNode parentNode = guiPackage.getCurrentNode();
                while (!MenuFactory.canAddTo(parentNode, testElement)) {
                    parentNode = (JMeterTreeNode) parentNode.getParent();
                }
                if (parentNode.getParent() == null) {
                    log.debug("Cannot add element on very top level");
                } else {
                    JMeterTreeNode node = guiPackage.getTreeModel().addComponent(testElement, parentNode);
                    guiPackage.getTreeListener().setSelectionPathWithoutEdit(new TreePath(node.getPath()));
                    ActionRouter.getInstance().doActionNow(
                            new ActionEvent(actionEvent.getSource(), actionEvent.getID(), ActionNames.EDIT));
                }
            } catch (Exception err) {
                log.warn("Failed to perform quick component add: {}", comp, err); // $NON-NLS-1$
            }
        }

        /**
         * Bug 62336: On Windows CTRL+6 doesn't give us an actionCommand, so
         * we have to try harder and read the KeyEvent from the EventQueue
         */
        private static String getCurrentKey(ActionEvent actionEvent) {
            String actionCommand = actionEvent.getActionCommand();
            if (actionCommand != null) {
                return actionCommand;
            }
            AWTEvent currentEvent = EventQueue.getCurrentEvent();
            if (currentEvent instanceof KeyEvent keyEvent) {
                return KeyEvent.getKeyText(keyEvent.getKeyCode());
            }
            log.debug("No keycode could be found for this actionEvent {}", actionEvent);
            return "NONE";
        }
    }

    /**
     * A window adapter used to detect when the main JMeter frame is being
     * closed.
     */
    private static class WindowHappenings extends WindowAdapter {
        /**
         * Called when the main JMeter frame is being closed. Sends a
         * notification so that JMeter can react appropriately.
         *
         * @param event
         *            the WindowEvent to handle
         */
        @Override
        public void windowClosing(WindowEvent event) {
            ActionRouter.getInstance().actionPerformed(new ActionEvent(this, event.getID(), ActionNames.EXIT));
        }
    }

    @Override
    public void dragEnter(DropTargetDragEvent dtde) {
        // NOOP
    }

    @Override
    public void dragExit(DropTargetEvent dte) {
        // NOOP
    }

    @Override
    public void dragOver(DropTargetDragEvent dtde) {
        // NOOP
    }

    /**
     * Handler of Top level Dnd
     */
    @Override
    public void drop(DropTargetDropEvent dtde) {
        Transferable tr = dtde.getTransferable();
        boolean anyFlavourIsJavaFileList =
                Arrays.stream(tr.getTransferDataFlavors())
                        .anyMatch(DataFlavor::isFlavorJavaFileListType);
        if (anyFlavourIsJavaFileList) {
            dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
            try {
                openJmxFilesFromDragAndDrop(tr);
            } catch (UnsupportedFlavorException | IOException e) {
                log.warn("Dnd failed", e);
            } finally {
                dtde.dropComplete(true);
            }
        }
    }

    public boolean openJmxFilesFromDragAndDrop(Transferable tr) throws UnsupportedFlavorException, IOException {
        @SuppressWarnings("unchecked")
        List<File> files = (List<File>)
                tr.getTransferData(DataFlavor.javaFileListFlavor);
        if (files.isEmpty()) {
            return false;
        }
        File file = files.get(0);
        if (!file.getName().endsWith(".jmx")) {
            if (log.isWarnEnabled()) {
                log.warn("Importing file, {}, from DnD failed because file extension does not end with .jmx", file.getName());
            }
            return false;
        }

        ActionEvent fakeEvent = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.OPEN);
        LoadDraggedFile.loadProject(fakeEvent, file);

        return true;
    }

    @Override
    public void dropActionChanged(DropTargetDragEvent dtde) {
        // NOOP
    }

    /**
     * ErrorsAndFatalsCounterLogTarget.
     */
    public final class ErrorsAndFatalsCounterLogTarget implements GuiLogEventListener, Clearable {

        @Override
        public void processLogEvent(LogEventObject logEventObject) {
            if (logEventObject.isMoreSpecificThanError()) {
                errorOrFatal.incrementAndGet();
            }
        }

        @Override
        public void clearData() {
            errorOrFatal.set(0);
            SwingUtilities.invokeLater(() -> {
                warnIndicator.setForeground(UIManager.getColor("Button.foreground"));
                warnIndicator.setText(Integer.toString(errorOrFatal.get()));
                if (bottomWarnings != null) {
                    bottomWarnings.setText("Issues: 0"); // $NON-NLS-1$
                }
            });
        }
    }

    @Override
    public void clearData() {
        logPanel.clear();
        errorsAndFatalsCounterLogTarget.clearData();
    }

    /**
     * Handles click on warnIndicator
     */
    @Override
    public void actionPerformed(ActionEvent event) {
        if (event.getSource() == warnIndicator) {
            ActionRouter.getInstance().doActionNow(
                    new ActionEvent(event.getSource(), event.getID(), ActionNames.LOGGER_PANEL_ENABLE_DISABLE));
        }
    }

    /**
     * Define AWT window title (WM_CLASS string) (useful on Gnome 3 / Linux)
     */
    private static void setWindowTitle() {
        Class<?> xtoolkit = Toolkit.getDefaultToolkit().getClass();
        if (xtoolkit.getName().equals("sun.awt.X11.XToolkit")) { // NOSONAR (we don't want to depend on native LAF) $NON-NLS-1$
            try {
                final Field awtAppClassName = xtoolkit.getDeclaredField("awtAppClassName"); // $NON-NLS-1$
                awtAppClassName.setAccessible(true);
                awtAppClassName.set(null, DEFAULT_APP_NAME);
            } catch (NoSuchFieldException | IllegalAccessException nsfe) {
                if (log.isWarnEnabled()) {
                    log.warn("Error awt title: {}", nsfe.toString()); // $NON-NLS-1$
                }
            } catch (RuntimeException e) {
                // By default, strong encapsulation prevents setAccessible on java.desktop
                if ("java.lang.reflect.InaccessibleObjectException".equals(e.getClass().getName())) {
                    /* ignore */
                    log.info("Unable to adjust awtAppClassName to {}", DEFAULT_APP_NAME);
                    return;
                }
                throw e;
            }
        }
    }

    /**
     * Update Undo/Redo icons state
     *
     * @param canUndo Flag whether the undo button should be enabled
     * @param canRedo Flag whether the redo button should be enabled
     */
    public void updateUndoRedoIcons(boolean canUndo, boolean canRedo) {
        toolbar.updateUndoRedoIcons(canUndo, canRedo);
        menuBar.updateUndoRedoItems(canUndo, canRedo);
    }
}
