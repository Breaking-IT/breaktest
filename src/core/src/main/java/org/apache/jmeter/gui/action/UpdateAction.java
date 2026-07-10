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

import java.awt.Desktop;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.gui.update.ReleaseInfo;
import org.apache.jmeter.gui.update.UpdateInstaller;
import org.apache.jmeter.gui.update.UpdateService;
import org.apache.jmeter.gui.update.UpdateService.PreparedUpdate;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

/** GUI actions for checking for and installing BreakTest updates. */
@AutoService(Command.class)
public class UpdateAction extends AbstractAction {
    private static final Logger log = LoggerFactory.getLogger(UpdateAction.class);
    private static final Set<String> COMMANDS = Set.of(
            ActionNames.CHECK_FOR_UPDATES, ActionNames.INSTALL_UPDATE);

    @Override
    public void doAction(ActionEvent event) {
        if (ActionNames.CHECK_FOR_UPDATES.equals(event.getActionCommand())) {
            checkForUpdates();
        } else if (ActionNames.INSTALL_UPDATE.equals(event.getActionCommand())) {
            ReleaseInfo release = UpdateService.getInstance().getAvailableRelease();
            if (release != null) {
                showUpdateDialog(release);
            }
        }
    }

    @Override
    public Set<String> getActionNames() {
        return COMMANDS;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static void checkForUpdates() {
        MainFrame frame = GuiPackage.getInstance().getMainFrame();
        frame.showLoadingOverlay(JMeterUtils.getResString("update_checking"));
        UpdateService.getInstance().checkNow().whenComplete((release, failure) ->
                SwingUtilities.invokeLater(() -> {
                    frame.hideLoadingOverlay();
                    if (failure != null) {
                        showError(JMeterUtils.getResString("update_check_error"), rootMessage(failure));
                    } else if (release.isPresent()) {
                        showUpdateDialog(release.get());
                    } else {
                        showNoUpdateDialog(frame);
                    }
                }));
    }

    private static void showNoUpdateDialog(MainFrame frame) {
        Object[] options = {
                JMeterUtils.getResString("update_close"),
                JMeterUtils.getResString("update_view_release")
        };
        int selected = JOptionPane.showOptionDialog(frame,
                JMeterUtils.getResString("update_none_available"),
                JMeterUtils.getResString("update_title"), JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (selected == 1) {
            ActionRouter.getInstance().doActionNow(
                    new ActionEvent(frame, 0, ActionNames.LINK_RELEASE_NOTES));
        }
    }

    private static void showUpdateDialog(ReleaseInfo release) {
        MainFrame frame = GuiPackage.getInstance().getMainFrame();
        UpdateService service = UpdateService.getInstance();
        if (!service.canInstall()) {
            Object[] options = {
                    JMeterUtils.getResString("update_view_release"),
                    JMeterUtils.getResString("update_later")
            };
            int selected = JOptionPane.showOptionDialog(frame,
                    JMeterUtils.getResString("update_manual_install"),
                    JMeterUtils.getResString("update_available_title"), JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
            if (selected == 0) {
                openReleasePage(release);
            }
            return;
        }

        String message = JMeterUtils.getResString("update_available_message")
                .replace("{0}", JMeterUtils.getJMeterVersion())
                .replace("{1}", release.version());
        Object[] options = {
                JMeterUtils.getResString("update_install_restart"),
                JMeterUtils.getResString("update_later"),
                JMeterUtils.getResString("update_view_release")
        };
        int selected = JOptionPane.showOptionDialog(frame, message,
                JMeterUtils.getResString("update_available_title"), JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (selected == 0) {
            downloadUpdate(release);
        } else if (selected == 2) {
            openReleasePage(release);
        }
    }

    private static void downloadUpdate(ReleaseInfo release) {
        MainFrame frame = GuiPackage.getInstance().getMainFrame();
        frame.showLoadingOverlay(JMeterUtils.getResString("update_downloading"));
        SwingWorker<PreparedUpdate, Void> worker = new SwingWorker<>() {
            @Override
            protected PreparedUpdate doInBackground() throws Exception {
                return UpdateService.getInstance().prepareUpdate(release,
                        message -> SwingUtilities.invokeLater(() -> frame.showLoadingOverlay(message)));
            }

            @Override
            protected void done() {
                frame.hideLoadingOverlay();
                try {
                    PreparedUpdate prepared = get();
                    if (!confirmReadyToRestart(prepared)) {
                        prepared.close();
                    }
                } catch (Exception e) {
                    showError(JMeterUtils.getResString("update_install_error"), rootMessage(e));
                }
            }
        };
        worker.execute();
    }

    private static boolean confirmReadyToRestart(PreparedUpdate prepared) throws IOException {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (JMeterContextService.getNumberOfThreads() > 0) {
            JOptionPane.showMessageDialog(guiPackage.getMainFrame(),
                    JMeterUtils.getResString("update_test_running"),
                    JMeterUtils.getResString("update_title"), JOptionPane.WARNING_MESSAGE);
            return false;
        }
        ActionRouter.getInstance().doActionNow(new ActionEvent(prepared, 0, ActionNames.CHECK_DIRTY));
        if (guiPackage.isDirty()) {
            int choice = JOptionPane.showConfirmDialog(guiPackage.getMainFrame(),
                    JMeterUtils.getResString("cancel_exit_to_save"),
                    JMeterUtils.getResString("save?"), JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                return false;
            }
            if (choice == JOptionPane.YES_OPTION) {
                ActionRouter.getInstance().doActionNow(new ActionEvent(prepared, 0, ActionNames.SAVE));
                if (guiPackage.isDirty()) {
                    return false;
                }
            }
        }

        Path home = Path.of(JMeterUtils.getJMeterHome()).toAbsolutePath().normalize();
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path logFile = UpdateInstaller.launch(prepared.workspace(), prepared.distributionRoot(), home,
                workingDirectory, Restart.createRestartCommand());
        log.info("BreakTest update scheduled; installer log: {}", logFile);
        System.exit(0); // NOSONAR Required to release installed JARs before replacement
        return true;
    }

    private static void openReleasePage(ReleaseInfo release) {
        try {
            Desktop.getDesktop().browse(release.releasePage());
        } catch (Exception e) {
            showError(JMeterUtils.getResString("update_open_error"), e.getMessage());
        }
    }

    private static void showError(String message, String detail) {
        JOptionPane.showMessageDialog(GuiPackage.getInstance().getMainFrame(),
                message + "\n" + detail, JMeterUtils.getResString("update_title"),
                JOptionPane.ERROR_MESSAGE);
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
