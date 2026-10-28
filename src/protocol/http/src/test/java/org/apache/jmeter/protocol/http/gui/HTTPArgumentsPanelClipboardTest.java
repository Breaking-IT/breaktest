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

package org.apache.jmeter.protocol.http.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.util.List;

import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.junit.jupiter.api.Test;

class HTTPArgumentsPanelClipboardTest {
    @Test
    void copiedRowsPreserveAllParameterColumns() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ClipboardPanel source = new ClipboardPanel();
            Arguments original = new Arguments();
            HTTPArgument date = new HTTPArgument("Date", "2026-10-27");
            date.setAlwaysEncoded(false);
            date.setUseEquals(true);
            original.addArgument(date);
            HTTPArgument disabled = new HTTPArgument("filter", "a=b&c=d");
            disabled.setEnabled(false);
            disabled.setAlwaysEncoded(true);
            disabled.setContentType("application/json; charset=UTF-8");
            disabled.setUseEquals(true);
            original.addArgument(disabled);
            HTTPArgument empty = new HTTPArgument("empty", "");
            empty.setAlwaysEncoded(false);
            empty.setUseEquals(false);
            original.addArgument(empty);
            source.configure(original);

            List<Argument> pasted = new ClipboardPanel().parse(source.copyRows());
            assertEquals(3, pasted.size());
            for (int i = 0; i < pasted.size(); i++) {
                HTTPArgument expected = (HTTPArgument) original.getArgument(i);
                HTTPArgument actual = (HTTPArgument) pasted.get(i);
                assertEquals(expected.getName(), actual.getName());
                assertEquals(expected.getValue(), actual.getValue());
                assertEquals(expected.isEnabled(), actual.isEnabled());
                assertEquals(expected.isAlwaysEncoded(), actual.isAlwaysEncoded());
                assertEquals(expected.getContentType(), actual.getContentType());
                assertEquals(expected.isUseEquals(), actual.isUseEquals());
            }
        });
    }

    @Test
    void keepsPlainTextAndLegacyClipboardFormats() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ClipboardPanel panel = new ClipboardPanel();
            List<Argument> query = panel.parse("Date=2026-10-27&Brand=STAATSLOTERIJ");
            assertEquals(2, query.size());
            assertEquals("Date", query.get(0).getName());
            assertEquals("2026-10-27", query.get(0).getValue());
            assertEquals("Brand", query.get(1).getName());
            HTTPArgument legacy = (HTTPArgument) panel.parse("name\t\ttrue\tfalse").get(0);
            assertEquals("name", legacy.getName());
            assertEquals("", legacy.getValue());
            assertEquals(true, legacy.isAlwaysEncoded());
            assertEquals(false, legacy.isUseEquals());
            Argument booleanName = panel.parse("true\tDate").get(0);
            assertEquals("true", booleanName.getName());
            assertEquals("Date", booleanName.getValue());
            Argument literal = panel.parse("url\thttps://example.test/?a=b&c=d\r\n").get(0);
            assertEquals("https://example.test/?a=b&c=d", literal.getValue());
        });
    }

    private static class ClipboardPanel extends HTTPArgumentsPanel {
        List<Argument> parse(String text) {
            return createArgumentsFromClipboard(text, "\n|&", "\t|=");
        }

        String copyRows() {
            var table = getTable();
            table.selectAll();
            Clipboard clipboard = new Clipboard("parameter regression");
            table.getTransferHandler().exportToClipboard(table, clipboard, TransferHandler.COPY);
            try {
                return (String) clipboard.getData(DataFlavor.stringFlavor);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
