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

package org.apache.jmeter.gui.util;

import java.awt.Cursor;
import java.awt.Insets;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.apache.jmeter.util.JMeterUtils;

/**
 * Small reusable button for contextual form help.
 */
public class InfoButton extends JButton {
    private static final long serialVersionUID = 1L;

    public InfoButton(String title, String message) {
        ImageIcon icon = JMeterUtils.getImage("question.gif", title); // $NON-NLS-1$
        if (icon == null) {
            setText("i"); // $NON-NLS-1$
        } else {
            setIcon(icon);
        }
        setToolTipText(title);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setFocusable(false);
        setMargin(new Insets(0, 0, 0, 0));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName(title);
        getAccessibleContext().setAccessibleDescription(message);
        addActionListener(e -> JOptionPane.showMessageDialog(
                SwingUtilities.getWindowAncestor(this),
                message,
                title,
                JOptionPane.INFORMATION_MESSAGE));
    }
}
