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

package org.apache.jmeter.extractor.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.Color;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

class ExtractorTestTimingTest {
    @Test
    void highlightsOnlyValuesAboveTenMillisecondsAndResetsForFastResults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ExtractorTestTiming timing = new ExtractorTestTiming();
            JLabel caption = (JLabel) timing.getComponent(0);
            JLabel value = (JLabel) timing.getComponent(1);
            Color captionColor = caption.getForeground();
            timing.setElapsed(10_000_001L);
            assertEquals(Color.RED, value.getForeground());
            assertEquals(captionColor, caption.getForeground());
            timing.setElapsed(10_000_000L);
            assertNotEquals(Color.RED, value.getForeground());
            assertEquals("10.0 ms", value.getText());
            timing.setElapsed(125_400L);
            assertEquals("125.4 µs", value.getText());
            assertNotEquals(Color.RED, value.getForeground());
            timing.setElapsed(1_000_000L);
            assertEquals("1.0 ms", value.getText());
        });
    }
}
