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

package org.apache.jmeter.protocol.http.har;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import javax.swing.SwingWorker;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.AbstractActionWithNoRunningTest;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Command;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.ViewResultsFullVisualizer;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

/**
 * Launches the HAR import wizard and inserts the generated Thread Group (with
 * its supporting config elements) under the currently open Test Plan. Ported
 * from the BreakTest platform's Python HAR to JMX converter.
 */
@AutoService({
        Command.class,
        MenuCreator.class
})
public class HarImportAction extends AbstractActionWithNoRunningTest implements MenuCreator {

    private static final Logger LOG = LoggerFactory.getLogger(HarImportAction.class);
    private static final Set<String> commands = new HashSet<>();

    static {
        commands.add(ActionNames.HAR_IMPORT);
    }

    @Override
    public void doActionAfterCheck(ActionEvent e) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return;
        }
        HarImportWizard wizard = new HarImportWizard(guiPackage.getMainFrame());
        wizard.setVisible(true);
        HarImportWizard.Result result = wizard.getResult();
        if (result == null) {
            return;
        }
        HarImportOptions options = result.getOptions();
        applyExistingTopLevelElements(guiPackage, options);
        HarConverter converter = new HarConverter(
                result.getEntries(), options, result.getHarName(), result.getHarMd5());

        // Convert off the EDT behind the same loading overlay a JMX load uses, then
        // insert the generated sub-tree back on the EDT.
        var mainFrame = guiPackage.getMainFrame();
        mainFrame.showLoadingOverlay(JMeterUtils.getResString("har_import_progress"));
        SwingWorker<HashTree, Void> worker = new SwingWorker<>() {
            @Override
            protected HashTree doInBackground() {
                return convertAndRegister(result, options, converter);
            }

            @Override
            protected void done() {
                try {
                    HashTree convertedTree = get();
                    insertUnderTestPlan(guiPackage, convertedTree);
                    guiPackage.updateCurrentGui();
                    ActionRouter.getInstance().doActionNow(
                            new ActionEvent(e.getSource(), e.getID(), ActionNames.EXPAND_ALL));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    LOG.error("HAR import interrupted", ex);
                } catch (ExecutionException ex) {
                    LOG.error("Failed to import HAR file", ex.getCause());
                    JMeterUtils.reportErrorToUser(String.valueOf(ex.getCause().getMessage()),
                            JMeterUtils.getResString("har_import_title"));
                } catch (RuntimeException ex) {
                    LOG.error("Failed to import HAR file", ex);
                    JMeterUtils.reportErrorToUser(ex.getMessage(), JMeterUtils.getResString("har_import_title"));
                } finally {
                    mainFrame.hideLoadingOverlay();
                }
            }
        };
        worker.execute();
    }

    private static HashTree convertAndRegister(
            HarImportWizard.Result result, HarImportOptions options, HarConverter converter) {
        HashTree convertedTree = converter.convert(result.getSelectedHostnames());
        try {
            var filteredHar = HarArchiveFilter.filterAndRelink(
                    result.getHarContent(), convertedTree, result.getHarName(), options.getRecordingStorageMode());
            if (filteredHar.isEmpty()) {
                LOG.info("Recorded HAR storage disabled or no eligible entries remained");
            } else {
                var archive = filteredHar.orElseThrow();
                JmxArchiveEntryStore.registerBundle(
                        archive.manifestEntryName(), archive.checksum(), archive.entries());
                LOG.info("Filtered embedded HAR from {} to {} entries",
                        result.getEntries().size(), archive.exchangeCount());
            }
            return convertedTree;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to filter the HAR for archive storage", ex);
        }
    }

    /**
     * Skip adding the View Results Tree, Cookie Manager, or HTTP Request Defaults
     * when the Test Plan already has one at its top level.
     */
    private static void applyExistingTopLevelElements(GuiPackage guiPackage, HarImportOptions options) {
        JMeterTreeModel treeModel = guiPackage.getTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        if (root.getChildCount() == 0) {
            return;
        }
        JMeterTreeNode testPlanNode = (JMeterTreeNode) root.getChildAt(0);
        for (int i = 0; i < testPlanNode.getChildCount(); i++) {
            TestElement el = ((JMeterTreeNode) testPlanNode.getChildAt(i)).getTestElement();
            String guiClass = el.getPropertyAsString(TestElement.GUI_CLASS);
            if (el instanceof CookieManager) {
                options.setIncludeCookieManager(false);
            } else if (el instanceof ResultCollector
                    && ViewResultsFullVisualizer.class.getName().equals(guiClass)) {
                options.setIncludeViewResultsTree(false);
            } else if (el instanceof ConfigTestElement
                    && HttpDefaultsGui.class.getName().equals(guiClass)) {
                options.setIncludeHttpDefaults(false);
            }
        }
    }

    private static void insertUnderTestPlan(GuiPackage guiPackage, HashTree subTree) {
        JMeterTreeModel treeModel = guiPackage.getTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        JMeterTreeNode testPlanNode = (JMeterTreeNode) root.getChildAt(0);
        JMeterUtils.runSafe(true, () -> {
            try {
                // configureGui=false preserves the exact properties we set (BreakTest
                // HAR metadata, TransactionController delay/pacing) as if loading a .jmx.
                treeModel.addSubTree(subTree, testPlanNode, false);
            } catch (IllegalUserActionException ex) {
                LOG.error("Failed to insert HAR test plan", ex);
                JMeterUtils.reportErrorToUser(ex.getMessage());
            }
        });
    }

    @Override
    public Set<String> getActionNames() {
        return commands;
    }

    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location == MENU_LOCATION.FILE) {
            JMenuItem menuItem = new JMenuItem(JMeterUtils.getResString(ActionNames.HAR_IMPORT), KeyEvent.VK_UNDEFINED);
            menuItem.setName(ActionNames.HAR_IMPORT);
            menuItem.setActionCommand(ActionNames.HAR_IMPORT);
            menuItem.setAccelerator(null);
            menuItem.addActionListener(ActionRouter.getInstance());
            return new JMenuItem[] { menuItem };
        }
        return new JMenuItem[0];
    }

    @Override
    public JMenu[] getTopLevelMenus() {
        return new JMenu[0];
    }

    @Override
    public boolean localeChanged(MenuElement menu) {
        return false;
    }

    @Override
    public void localeChanged() {
        // NOOP
    }
}
