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

package org.apache.jmeter.protocol.websocket.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

import org.apache.jmeter.junit.JMeterTestCase;
import org.junit.jupiter.api.Test;

class WebSocketPayloadEditorTest extends JMeterTestCase {
    @Test
    void loadingAnotherSamplerClearsUndoHistory() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new WebSocketPayloadEditor();
            var area = (JTextArea) ((JScrollPane) editor.getCustomEditor()).getViewport().getView();
            editor.setValue("first message");
            area.selectAll();
            area.replaceSelection("edited message");
            editor.setValue("another sampler");
            area.getActionMap().get("undo").actionPerformed(null);
            assertEquals("another sampler", editor.getValue());
            editor.setValue(null);
            assertEquals("", editor.getValue());
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"31 32 37 33 34 37 30 32 ", "3132373334373032"})
    void largePayloadCanBeSelectedPaintedCopiedAndEditedWithoutChangingBytes(String block) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            var descriptor = Arrays.stream(new WebSocketSendWaitSamplerBeanInfo().getPropertyDescriptors())
                    .filter(property -> property.getName().equals("payload")).findFirst().orElseThrow();
            var editor = new WebSocketPayloadEditor();
            assertEquals(WebSocketPayloadEditor.class, descriptor.getPropertyEditorClass());
            var scroll = (JScrollPane) editor.getCustomEditor();
            var area = (JTextArea) scroll.getViewport().getView();
            String message = block.repeat(50000);
            assertTrue(area.getLineWrap());
            assertTrue(area.getWrapStyleWord());
            var frame = new JFrame("WebSocket payload editor regression");
            frame.add(scroll);
            frame.setSize(900, 300);
            frame.setLocation(-10000, -10000);
            frame.setVisible(true);
            long start = System.nanoTime();
            try {
                editor.setValue(message);
                scroll.setSize(900, 300);
                scroll.doLayout();
                area.setSize(scroll.getViewport().getWidth(), area.getPreferredSize().height);
                assertTrue(area.getPreferredSize().height > 300, "Payload should wrap into multiple visual rows");
                var canvas = new BufferedImage(900, 300, BufferedImage.TYPE_INT_RGB);
                var graphics = canvas.createGraphics();
                try {
                    graphics.setClip(0, 0, 900, 300);
                    area.selectAll();
                    area.paint(graphics);
                } finally {
                    graphics.dispose();
                }
                assertTrue(message.equals(area.getSelectedText()), "Select All must preserve the full payload");
                var clipboard = new Clipboard("payload regression");
                area.getTransferHandler().exportToClipboard(area, clipboard, TransferHandler.COPY);
                try {
                    assertTrue(message.equals(clipboard.getData(DataFlavor.stringFlavor)),
                            "Copy must preserve the full payload");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                area.replaceSelection("00 ff");
                assertEquals("00 ff", editor.getValue());
                area.getActionMap().get("undo").actionPerformed(null);
                assertTrue(message.equals(editor.getValue()), "Undo must restore the full payload");
                area.getActionMap().get("redo").actionPerformed(null);
                assertEquals("00 ff", editor.getValue());
            } finally {
                frame.dispose();
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            System.out.println("Large WebSocket payload selection/edit: " + elapsed + " ms");
            // Keep timing diagnostic: CI varies by hardware, display backend and stress-JIT settings.
            // Correctness above must not depend on a wall-clock benchmark threshold.
        });
    }
}
