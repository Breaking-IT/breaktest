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

package org.apache.jmeter.visualizers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.util.Arrays;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class RenderAsBinaryTest extends org.apache.jmeter.junit.JMeterTestCase {
    @Test
    void multilineSelectionsStayWithinTheirColumnAndRowsAlign() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RenderAsBinary renderer = new RenderAsBinary();
            JPanel panel = renderer.createResponseDataPanel();
            JScrollPane scroll = (JScrollPane) panel.getComponent(0);
            JPanel columns = (JPanel) scroll.getViewport().getView();
            SampleResult sample = new SampleResult();
            byte[] bytes = new byte[48];
            Arrays.fill(bytes, (byte) 'A');
            sample.setResponseData(bytes);
            renderer.renderImage(sample);
            List<JTextArea> areas = Arrays.stream(columns.getComponents())
                    .filter(JTextArea.class::isInstance).map(JTextArea.class::cast).toList();
            assertEquals(3, areas.size());
            JTextArea hex = areas.get(1);
            JTextArea ascii = areas.get(2);
            hex.selectAll();
            assertEquals(String.join("\n", java.util.Collections.nCopies(3,
                    String.join(" ", java.util.Collections.nCopies(16, "41")))), hex.getSelectedText());
            ascii.selectAll();
            assertEquals("A".repeat(16) + "\n" + "A".repeat(16) + "\n" + "A".repeat(16),
                    ascii.getSelectedText());
            columns.setSize(columns.getPreferredSize());
            columns.doLayout();
            for (Component area : areas) {
                assertEquals(hex.getY(), area.getY());
                assertEquals(hex.getHeight(), area.getHeight());
            }
        });
    }

    @Test
    void preservesUnsignedBytesAndMarksNonPrintableBytes() {
        String[] dump = RenderAsBinary.formatColumns(new byte[] {0, 31, 32, 65, 126, 127, (byte) 255}, 0);
        assertEquals("00000000", dump[0]);
        assertEquals("00 1F 20 41 7E 7F FF", dump[1]);
        assertEquals(".. A~..", dump[2]);
    }

    @Test
    void offsetsAndDisplayLimitDoNotModifyResponse() {
        byte[] bytes = new byte[18];
        bytes[16] = 65;
        bytes[17] = 66;
        String[] dump = RenderAsBinary.formatColumns(bytes, 17);
        assertEquals("00000000\n00000010", dump[0]);
        assertTrue(dump[1].endsWith("\n41"));
        assertFalse(dump[1].contains("41 42"));
        assertEquals(66, bytes[17]);
        assertFalse(RenderAsBinary.formatColumns(new byte[0], 17)[0].contains("00000000"));
    }

    @Test
    void rendererCapsHexExpansionBeforeBuildingTheView() {
        var properties = org.apache.jmeter.util.JMeterUtils.getJMeterProperties();
        Object previous = properties.put("view.results.tree.binary.max_bytes", "16");
        try {
            RenderAsBinary renderer = new RenderAsBinary();
            renderer.setRightSide(new JTabbedPane());
            SampleResult sample = new SampleResult();
            sample.setDataType(SampleResult.BINARY);
            sample.setResponseData(new byte[100000]);
            renderer.setSamplerResult(sample);
            renderer.setupTabPane();
            renderer.renderImage(sample);
            assertTrue(renderer.responseDataText().contains("16 / 100000 bytes"));
            assertTrue(renderer.responseDataText().length() < 1000);
            assertEquals(100000, sample.getResponseData().length);
        } finally {
            if (previous == null) {
                properties.remove("view.results.tree.binary.max_bytes");
            } else {
                properties.put("view.results.tree.binary.max_bytes", previous);
            }
        }
    }

    @Test
    void binaryRenderingUsesByteViewRatherThanImageDecoder() {
        RenderAsBinary renderer = new RenderAsBinary();
        renderer.setRightSide(new JTabbedPane());
        SampleResult sample = new SampleResult();
        sample.setDataType(SampleResult.BINARY);
        sample.setResponseData(new byte[] {(byte) 255, 0, 65});
        renderer.setSamplerResult(sample);
        renderer.setupTabPane();
        renderer.renderImage(sample);
        assertTrue(renderer.responseDataText().contains("FF 00 41"));
        assertTrue(renderer.responseDataText().contains("..A"));
        renderer.clearData();
        assertTrue(renderer.responseDataText().isBlank());
    }
}
