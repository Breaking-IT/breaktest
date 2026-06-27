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

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JFileChooser;
import javax.swing.JTree;
import javax.swing.tree.TreePath;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.FileDialoger;
import org.apache.jmeter.gui.util.FocusRequester;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.MissingTestElement;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.HashTreeTraverser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;
import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.io.StreamException;

/**
 * Handles the Open (load a new file) and Merge commands.
 *
 */
@AutoService(Command.class)
public class Load extends AbstractActionWithNoRunningTest {
    private static final Logger log = LoggerFactory.getLogger(Load.class);

    private static final boolean EXPAND_TREE = JMeterUtils.getPropDefault("onload.expandtree", false); //$NON-NLS-1$

    private static final Set<String> commands = new HashSet<>();

    static {
        commands.add(ActionNames.OPEN);
        commands.add(ActionNames.MERGE);
    }

    public Load() {
        super();
    }

    @Override
    public Set<String> getActionNames() {
        return commands;
    }

    @Override
    public void doActionAfterCheck(final ActionEvent e) {
        final JFileChooser chooser = FileDialoger.promptToOpenFile(new String[] { ".jmx" }); //$NON-NLS-1$
        if (chooser == null) {
            return;
        }
        final File selectedFile = chooser.getSelectedFile();
        if (selectedFile != null) {
            final boolean merging = e.getActionCommand().equals(ActionNames.MERGE);
            // We must ask the user if it is ok to close current project
            if (!merging) { // i.e. it is OPEN
                if (!Close.performAction(e)) {
                    return;
                }
            }
            loadProjectFile(e, selectedFile, merging);
        }
    }

    /**
     * Loads or merges a file into the current GUI, reporting any errors to the user.
     * If the file is a complete test plan, sets the GUI test plan file name
     *
     * @param e the event that triggered the action
     * @param f the file to load
     * @param merging if true, then try to merge the file into the current GUI.
     */
    static void loadProjectFile(final ActionEvent e, final File f, final boolean merging) {
        loadProjectFile(e, f, merging, true);
    }

    /**
     * Loads or merges a file into the current GUI, reporting any errors to the user.
     * If the file is a complete test plan, sets the GUI test plan file name
     *
     * @param e the event that triggered the action
     * @param f the file to load
     * @param merging if true, then try to merge the file into the current GUI.
     * @param setDetails if true, then set the file details (if not merging)
     */
    static void loadProjectFile(final ActionEvent e, final File f, final boolean merging, final boolean setDetails) {
        ActionRouter.getInstance().doActionNow(new ActionEvent(e.getSource(), e.getID(), ActionNames.STOP_THREAD));

        final GuiPackage guiPackage = GuiPackage.getInstance();
        if (f != null) {
            try {
                if (merging) {
                    log.info("Merging file: {}", f);
                } else {
                    log.info("Loading file: {}", f);
                    // TODO should this be done even if not a full test plan?
                    // and what if load fails?
                    if (setDetails) {
                        FileServer.getFileServer().setBaseForScript(f);
                    }
                }
                final HashTree tree = SaveService.loadTree(f);
                final boolean isTestPlan = insertLoadedTree(e.getID(), tree, merging);
                reportMissingPluginElements(tree);

                // don't change name if merging
                if (!merging && isTestPlan && setDetails) {
                    // TODO should setBaseForScript be called here rather than
                    // above?
                    guiPackage.setTestPlanFile(f.getAbsolutePath());
                    scheduleBreakTestHarPreflight(f);
                }
            } catch (NoClassDefFoundError ex) {// Allow for missing optional jars
                reportError("Missing jar file. {}", ex, true);
            } catch (ConversionException ex) {
                if (log.isWarnEnabled()) {
                    log.warn("Could not convert file. {}", ex.toString());
                }
                JMeterUtils.reportErrorToUser(SaveService.CEtoString(ex));
            } catch (IOException ex) {
                reportError("Error reading file. {}", ex, false);
            } catch (StreamException ex) {
                Throwable exceptionToDisplay = ex;
                if ("".equals(ex.getMessage()) && ex.getCause() != null) {
                    exceptionToDisplay = ex.getCause();
                }
                reportError("Error in XML format. {}", exceptionToDisplay, false);
            } catch (Exception ex) {
                reportError("Unexpected error. {}", ex, true);
            }
            FileDialoger.setLastJFCDirectory(f.getParentFile().getAbsolutePath());
            guiPackage.updateCurrentGui();
            guiPackage.getMainFrame().repaint();
        }
    }

    private static void reportMissingPluginElements(HashTree tree) {
        List<String> missingElements = new ArrayList<>();
        int[] count = {0};
        tree.traverse(new HashTreeTraverser() {
            @Override
            public void addNode(Object node, HashTree subTree) {
                if (node instanceof MissingTestElement missingElement) {
                    count[0]++;
                    if (missingElements.size() < 10) {
                        missingElements.add(formatMissingPluginElement(missingElement));
                    }
                }
            }

            @Override
            public void subtractNode() {
            }

            @Override
            public void processPath() {
            }
        });
        if (missingElements.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder("""
                Loaded the JMX, but some elements could not be loaded because plugin classes are missing.
                Those elements are disabled and will be ignored when running or validating the test plan.

                """);
        missingElements.forEach(element -> message.append("- ").append(element).append('\n'));
        if (count[0] > missingElements.size()) {
            message.append("... and ").append(count[0] - missingElements.size()).append(" more\n");
        }
        JMeterUtils.reportInfoToUser(message.toString(), "Missing plugin elements");
    }

    private static String formatMissingPluginElement(MissingTestElement missingElement) {
        String testClass = missingElement.getMissingTestClass();
        String guiClass = missingElement.getMissingGuiClass();
        StringBuilder message = new StringBuilder(missingElement.getName());
        if (!testClass.isEmpty()) {
            message.append(": ").append(testClass);
        }
        if (!guiClass.isEmpty() && !guiClass.equals(testClass)) {
            message.append(" (GUI: ").append(guiClass).append(')');
        }
        return message.toString();
    }

    public static void scheduleBreakTestHarPreflight(File jmxFile) {
        Path testPlanFile = jmxFile.toPath().toAbsolutePath().normalize();
        List<BreakTestHarReference> references = collectBreakTestHarReferences(
                GuiPackage.getInstance().getTreeModel().getTestPlan());
        if (references.isEmpty()) {
            return;
        }
        Thread harPreflight = new Thread(() -> {
            try {
                for (BreakTestHarReference reference : references) {
                    log.info("BreakTest HAR reference detected in JMX: source='{}', file='{}', expectedMd5='{}'",
                            reference.sourceName(), reference.filename(), reference.expectedMd5());
                    RecordedHarExchangeResolver.HarFileCheck check = RecordedHarExchangeResolver.checkHarFile(
                            reference.filename(), reference.expectedMd5(), testPlanFile);
                    switch (check.status()) {
                        case FOUND ->
                            log.info("BreakTest HAR file found and MD5 matched: path='{}', md5='{}'",
                                    check.harPath(), check.actualMd5());
                        case HAR_FILE_NOT_FOUND ->
                            log.warn("BreakTest HAR file not found: tried path='{}'",
                                    check.harPath());
                        case MD5_MISMATCH ->
                            log.warn("BreakTest HAR file found but MD5 did not match: path='{}', expectedMd5='{}', actualMd5='{}'",
                                    check.harPath(), check.expectedMd5(), check.actualMd5());
                        default ->
                            log.warn("BreakTest HAR reference check failed: source='{}', file='{}', status='{}', detail='{}'",
                                    reference.sourceName(), reference.filename(), check.status(), check.diagnostic());
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("BreakTest HAR reference check failed unexpectedly", ex);
            }
        }, "BreakTest HAR preflight"); // $NON-NLS-1$
        harPreflight.setDaemon(true);
        harPreflight.start();
    }

    static List<BreakTestHarReference> collectBreakTestHarReferences(HashTree tree) {
        List<BreakTestHarReference> references = new ArrayList<>();
        collectBreakTestHarReferences(tree, references, new HashSet<>());
        return references;
    }

    private static void collectBreakTestHarReferences(HashTree tree, List<BreakTestHarReference> references,
            Set<String> seenReferences) {
        for (Object item : tree.list()) {
            TestElement element = testElementFromTreeItem(item);
            if (element != null) {
                String filename = element.getPropertyAsString(RecordedHarExchangeResolver.HAR_FILENAME);
                if (!filename.isEmpty()) {
                    String expectedMd5 = element.getPropertyAsString(RecordedHarExchangeResolver.HAR_MD5);
                    String referenceKey = filename + '\0' + expectedMd5;
                    if (seenReferences.add(referenceKey)) {
                        references.add(new BreakTestHarReference(element.getName(), filename, expectedMd5));
                    }
                }
            }
            collectBreakTestHarReferences(tree.getTree(item), references, seenReferences);
        }
    }

    private static TestElement testElementFromTreeItem(Object item) {
        if (item instanceof TestElement element) {
            return element;
        }
        if (item instanceof JMeterTreeNode treeNode) {
            return treeNode.getTestElement();
        }
        return null;
    }

    record BreakTestHarReference(String sourceName, String filename, String expectedMd5) {
    }

    /**
     * Inserts (or merges) the tree into the GUI.
     * Does not check if the previous tree has been saved.
     * Clears the existing GUI test plan if we are inserting a complete plan.
     * @param id the id for the ActionEvent that is created
     * @param tree the tree to load
     * @param merging true if the tree is to be merged; false if it is to replace the existing tree
     * @return true if the loaded tree was a full test plan
     * @throws IllegalUserActionException if the tree cannot be merged at the selected position or the tree is empty
     */
    // Does not appear to be used externally; called by #loadProjectFile()
    public static boolean insertLoadedTree(final int id, final HashTree tree, final boolean merging) throws IllegalUserActionException {
        if (tree == null) {
            throw new IllegalUserActionException("Empty TestPlan or error reading test plan - see log file");
        }
        final boolean isTestPlan = tree.getArray()[0] instanceof TestPlan;

        // If we are loading a new test plan, initialize the tree with the testplan node we are loading
        final GuiPackage guiInstance = GuiPackage.getInstance();
        if(isTestPlan && !merging) {
            // Why does this not call guiInstance.clearTestPlan() ?
            // Is there a reason for not clearing everything?
            guiInstance.clearTestPlan((TestElement)tree.getArray()[0]);
        }

        if (merging){ // Check if target of merge is reasonable
            final TestElement te = (TestElement)tree.getArray()[0];
            if (!(te instanceof TestPlan)) {// These are handled specially by addToTree
                final boolean ok = MenuFactory.canAddTo(guiInstance.getCurrentNode(), te);
                if (!ok){
                    String name = te.getName();
                    String className = te.getClass().getName();
                    className = className.substring(className.lastIndexOf('.')+1);
                    throw new IllegalUserActionException("Can't merge "+name+" ("+className+") here");
                }
            }
        }
        final HashTree newTree = guiInstance.addSubTree(tree);
        guiInstance.updateCurrentGui();
        guiInstance.getMainFrame().getTree().setSelectionPath(
                new TreePath(((JMeterTreeNode) newTree.getArray()[0]).getPath()));
        final HashTree subTree = guiInstance.getCurrentSubTree();
        // Send different event whether we are merging a test plan into another test plan,
        // or loading a testplan from scratch
        ActionEvent actionEvent =
            new ActionEvent(subTree.get(subTree.getArray()[subTree.size() - 1]), id,
                    merging ? ActionNames.SUB_TREE_MERGED : ActionNames.SUB_TREE_LOADED);

        ActionRouter.getInstance().actionPerformed(actionEvent);
        final JTree jTree = guiInstance.getMainFrame().getTree();
        if (EXPAND_TREE && !merging) { // don't automatically expand when merging
            for(int i = 0; i < jTree.getRowCount(); i++) {
                jTree.expandRow(i);
            }
        } else {
            jTree.expandRow(0);
        }
        jTree.setSelectionPath(jTree.getPathForRow(1));
        FocusRequester.requestFocus(jTree);
        return isTestPlan;
    }

    /**
     * Inserts the tree into the GUI.
     * Does not check if the previous tree has been saved.
     * Clears the existing GUI test plan if we are inserting a complete plan.
     * @param id the id for the ActionEvent that is created
     * @param tree the tree to load
     * @return true if the loaded tree was a full test plan
     * @throws IllegalUserActionException if the tree cannot be merged at the selected position or the tree is empty
     */
    // Called by JMeter#startGui()
    public static boolean insertLoadedTree(final int id, final HashTree tree) throws IllegalUserActionException {
        return insertLoadedTree(id, tree, false);
    }

    // Helper method to simplify code
    private static void reportError(final String messageFormat, final Throwable ex, final boolean stackTrace) {
        if (log.isWarnEnabled()) {
            if (stackTrace) {
                log.warn(messageFormat, ex.toString(), ex);
            } else {
                log.warn(messageFormat, ex.toString());
            }
        }
        String msg = ex.getMessage();
        if (msg == null) {
            msg = "Unexpected error - see log for details";
        }
        JMeterUtils.reportErrorToUser(msg);
    }

}
