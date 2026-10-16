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

package org.apache.jmeter.protocol.http.openapi;

import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.MenuElement;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.TreePath;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.AbstractActionWithNoRunningTest;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Command;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

/** File menu entry for importing an OpenAPI document as a test fragment. */
@AutoService({Command.class, MenuCreator.class})
public class OpenApiImportAction extends AbstractActionWithNoRunningTest implements MenuCreator {
    private static final Logger LOG = LoggerFactory.getLogger(OpenApiImportAction.class);

    @Override
    public Set<String> getActionNames() {
        return Set.of(ActionNames.OPENAPI_IMPORT);
    }

    @Override
    public void doActionAfterCheck(ActionEvent event) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(JMeterUtils.getResString(ActionNames.OPENAPI_IMPORT));
        chooser.setFileFilter(new FileNameExtensionFilter("OpenAPI JSON (*.json)", "json"));
        if (chooser.showOpenDialog(gui.getMainFrame()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        gui.getMainFrame().showLoadingOverlay(JMeterUtils.getResString("openapi_import_progress"));
        new SwingWorker<HashTree, Void>() {
            @Override
            protected HashTree doInBackground() throws Exception {
                return OpenApiConverter.read(file);
            }

            @Override
            protected void done() {
                try {
                    HashTree imported = get();
                    gui.updateCurrentGui();
                    var model = gui.getTreeModel();
                    var tree = gui.getMainFrame().getTree();
                    JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
                    List<TreePath> expanded = new ArrayList<>();
                    var paths = tree.getExpandedDescendants(new TreePath(root.getPath()));
                    if (paths != null) {
                        paths.asIterator().forEachRemaining(expanded::add);
                    }
                    model.addSubTree(imported, (JMeterTreeNode) root.getChildAt(0), false);
                    expanded.forEach(tree::expandPath);
                    JMeterTreeNode fragment = model.getNodeOf((org.apache.jmeter.testelement.TestElement) imported.getArray()[0]);
                    TreePath fragmentPath = new TreePath(fragment.getPath());
                    tree.expandPath(fragmentPath);
                    for (int i = 0; i < fragment.getChildCount(); i++) {
                        tree.expandPath(new TreePath(((JMeterTreeNode) fragment.getChildAt(i)).getPath()));
                    }
                    tree.setSelectionPath(fragmentPath);
                    tree.scrollPathToVisible(fragmentPath);
                    gui.refreshCurrentGui();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | IllegalUserActionException | RuntimeException ex) {
                    Throwable cause = ex instanceof ExecutionException ? ex.getCause() : ex;
                    LOG.error("Could not import OpenAPI JSON", cause);
                    JMeterUtils.reportErrorToUser(cause.getMessage(), JMeterUtils.getResString(ActionNames.OPENAPI_IMPORT));
                } finally {
                    gui.getMainFrame().hideLoadingOverlay();
                }
            }
        }.execute();
    }

    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location != MENU_LOCATION.FILE) {
            return new JMenuItem[0];
        }
        JMenuItem item = new JMenuItem(JMeterUtils.getResString(ActionNames.OPENAPI_IMPORT));
        item.setName(ActionNames.OPENAPI_IMPORT);
        item.setActionCommand(ActionNames.OPENAPI_IMPORT);
        item.addActionListener(ActionRouter.getInstance());
        return new JMenuItem[] {item};
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
        // Menu labels are refreshed by the main menu.
    }
}
