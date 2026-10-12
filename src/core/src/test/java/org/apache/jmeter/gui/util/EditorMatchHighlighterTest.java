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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Point;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

class EditorMatchHighlighterTest {
    @Test
    void revealsAFieldOutsideTheViewport() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = new JPanel(null);
            content.setPreferredSize(new Dimension(400, 1000));
            JTextField field = new JTextField("match");
            field.setBounds(10, 800, 200, 30);
            content.add(field);
            JScrollPane scroll = new JScrollPane(content);
            scroll.setSize(300, 200);
            scroll.doLayout();
            scroll.getViewport().doLayout();
            scroll.getViewport().setViewPosition(new Point(0, 0));
            EditorMatchHighlighter.reveal(field);
            assertTrue(scroll.getViewport().getViewRect().contains(field.getBounds()));
        });
    }
}
