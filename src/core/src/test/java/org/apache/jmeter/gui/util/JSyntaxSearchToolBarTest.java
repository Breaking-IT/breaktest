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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Rectangle;

import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Test;

class JSyntaxSearchToolBarTest extends JMeterTestCase implements JMeterSerialTest {

    @Test
    void searchFieldAbsorbsResizingWithoutClippingActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JSyntaxSearchToolBar search = new JSyntaxSearchToolBar(JSyntaxTextArea.getInstance(5, 30));
            search.setDiffButtonVisible(true);
            JToolBar toolbar = search.getToolBar();
            // Supply the inherited font normally provided by the application window.
            toolbar.setFont(UIManager.getFont("Label.font"));
            toolbar.add(new JButton("Pretty Print"));
            JTextField input = (JTextField) toolbar.getComponent(0);
            int preferredWidth = toolbar.getPreferredSize().width;
            int height = toolbar.getPreferredSize().height;
            int previousInputWidth = -1;
            int previousWidth = -1;
            for (int width : new int[]{preferredWidth - 150, preferredWidth, preferredWidth + 250}) {
                toolbar.setSize(width, height);
                toolbar.doLayout();
                Rectangle visible = new Rectangle(0, 0, width, height);
                for (Component component : toolbar.getComponents()) {
                    assertTrue(visible.contains(component.getBounds()),
                            "Toolbar control must remain visible: " + component.getBounds());
                    if (component != input) {
                        assertEquals(component.getPreferredSize().width, component.getWidth());
                    }
                }
                assertTrue(input.getWidth() > 0);
                if (previousWidth >= 0) {
                    assertEquals(width - previousWidth, input.getWidth() - previousInputWidth);
                }
                previousWidth = width;
                previousInputWidth = input.getWidth();
            }
            int widthWithDiff = input.getWidth();
            search.setDiffButtonVisible(false);
            toolbar.doLayout();
            assertTrue(input.getWidth() > widthWithDiff, "Hidden actions must release space to the input");
        });
    }

    @Test
    void createsLineAndCharacterHighlightsForDiff() {
        JSyntaxSearchToolBar.DiffView diff = JSyntaxSearchToolBar.createDiffView(
                "GET https://example.invalid/a HTTP/1.1\nAccept: application/json\n", // $NON-NLS-1$
                "GET https://example.invalid/b HTTP/1.1\nAccept: application/json\nX-Test: replayed\n"); // $NON-NLS-1$

        assertTrue(diff.text().contains("- GET https://example.invalid/a HTTP/1.1")); // $NON-NLS-1$
        assertTrue(diff.text().contains("+ GET https://example.invalid/b HTTP/1.1")); // $NON-NLS-1$
        assertTrue(diff.text().contains("  Accept: application/json")); // $NON-NLS-1$
        assertTrue(diff.text().contains("+ X-Test: replayed")); // $NON-NLS-1$
        assertEquals(3, diff.lineHighlights().size());
        assertEquals(4, diff.lineHighlights().get(0).line());
        assertTrue(diff.textHighlights().stream()
                .anyMatch(highlight -> diff.text().substring(highlight.start(), highlight.end()).equals("a"))); // $NON-NLS-1$
        assertTrue(diff.textHighlights().stream()
                .anyMatch(highlight -> diff.text().substring(highlight.start(), highlight.end()).equals("b"))); // $NON-NLS-1$
    }
}
