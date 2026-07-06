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
import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Set;

import javax.swing.JFrame;
import javax.swing.JOptionPane;

import org.apache.jmeter.gui.settings.SettingsDialog;
import org.apache.jmeter.gui.settings.SettingsModel;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

/**
 * Opens the Settings dialog, which shows all JMeter properties of this
 * installation and writes changes to {@code user.properties} and
 * {@code system.properties}.
 */
@AutoService(Command.class)
public class SettingsCommand extends AbstractAction {

    private static final Logger log = LoggerFactory.getLogger(SettingsCommand.class);

    private static final Set<String> commands = new HashSet<>();

    static {
        commands.add(ActionNames.SETTINGS);
    }

    @Override
    public void doAction(ActionEvent e) {
        JFrame parent = getParentFrame(e);
        SettingsModel model;
        try {
            // Re-read the properties files on every open, so external edits show up
            model = new SettingsModel();
        } catch (IOException ex) {
            log.error("Failed to read properties files for the Settings dialog", ex);
            JOptionPane.showMessageDialog(parent,
                    MessageFormat.format(JMeterUtils.getResString("settings_load_error"), ex.getMessage()),
                    JMeterUtils.getResString("settings_title"),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        new SettingsDialog(parent, model).setVisible(true);
    }

    @Override
    public Set<String> getActionNames() {
        return commands;
    }
}
