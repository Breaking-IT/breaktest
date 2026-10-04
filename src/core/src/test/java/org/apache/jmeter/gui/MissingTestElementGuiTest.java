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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.KeyStrokes;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.testelement.MissingTestElement;
import org.junit.jupiter.api.Test;

class MissingTestElementGuiTest extends JMeterTestCase {
    @Test
    void missingElementOffersNormalRemovalAndItsKeyboardShortcut() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MissingTestElement element = new MissingTestElement();
            element.configureMissingElement("UnavailableSampler", "UnavailableSampler", "UnavailableGui",
                    new ClassNotFoundException("UnavailableSampler"));
            element.setName("Old sampler");
            MissingTestElementGui gui = new MissingTestElementGui();
            gui.configure(element);
            var popup = gui.createPopupMenu();
            assertEquals(1, popup.getComponentCount());
            JMenuItem remove = (JMenuItem) popup.getComponent(0);
            assertTrue(remove.isEnabled());
            assertEquals(ActionNames.REMOVE, remove.getActionCommand());
            assertEquals(KeyStrokes.REMOVE, remove.getAccelerator());
            assertSame(ActionRouter.getInstance(), remove.getActionListeners()[0]);
            assertTrue(element.canRemove());
            assertFalse(element.isEnabled());
            assertTrue(gui.getMenuCategories().isEmpty());
        });
    }
}
