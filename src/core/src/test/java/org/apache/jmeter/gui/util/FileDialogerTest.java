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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import javax.swing.JFileChooser;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.plaf.metal.MetalFileChooserUI;
import javax.swing.plaf.metal.MetalLookAndFeel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.ui.FlatFileChooserUI;

@Isolated
class FileDialogerTest {

    @Test
    void updatesRetainedChooserAfterLookAndFeelChanges() throws Exception {
        LookAndFeel originalLookAndFeel = UIManager.getLookAndFeel();
        try {
            UIManager.setLookAndFeel(new MetalLookAndFeel());
            JFileChooser chooser = new JFileChooser();
            assertInstanceOf(MetalFileChooserUI.class, chooser.getUI());

            UIManager.setLookAndFeel(new FlatDarkLaf());
            FileDialoger.updateUI(chooser);

            assertInstanceOf(FlatFileChooserUI.class, chooser.getUI());
        } finally {
            UIManager.setLookAndFeel(originalLookAndFeel);
        }
    }
}
