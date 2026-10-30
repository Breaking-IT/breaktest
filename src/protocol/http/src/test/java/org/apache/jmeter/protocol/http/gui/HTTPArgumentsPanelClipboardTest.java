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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

            assertRoundTrip(original, new ClipboardPanel().parse(source.copyRows()));
        });
    }

    @Test
    void copiedRowsPreserveMultiLineTabAndQuotedValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ClipboardPanel source = new ClipboardPanel();
            Arguments original = new Arguments();
            original.addArgument(new HTTPArgument("body", "{\r\n  \"a\": 1,\n  \"b\": \"x\ty\"\n}"));
            original.addArgument(new HTTPArgument("quoted", "\"leading quote"));
            original.addArgument(new HTTPArgument("after", "plain"));
            source.configure(original);

            assertRoundTrip(original, new ClipboardPanel().parse(source.copyRows()));
        });
    }

    @Test
    void copiedRowsIgnoreOnScreenColumnOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ClipboardPanel source = new ClipboardPanel();
            Arguments original = new Arguments();
            original.addArgument(new HTTPArgument("Date", "2026-10-27"));
            source.configure(original);
            source.moveColumn(2, 0);
            source.moveColumn(5, 1);

            assertRoundTrip(original, new ClipboardPanel().parse(source.copyRows()));
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
            assertTrue(legacy.isAlwaysEncoded());
            assertFalse(legacy.isUseEquals());
            Argument booleanName = panel.parse("true\tDate").get(0);
            assertEquals("true", booleanName.getName());
            assertEquals("Date", booleanName.getValue());
            Argument literal = panel.parse("url\thttps://example.test/?a=b&c=d\r\n").get(0);
            assertEquals("https://example.test/?a=b&c=d", literal.getValue());
            HTTPArgument notTableRow = (HTTPArgument) panel.parse("true\tq\tv\tx\ttext/plain\tyes").get(0);
            assertEquals("true", notTableRow.getName());
            assertEquals("q", notTableRow.getValue());
            assertTrue(notTableRow.isUseEquals());
        });
    }

    @Test
    void parsesFormatPerLine() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<Argument> mixed = new ClipboardPanel().parse("a\t1\r\nb=2&c=3\r\nd=4\r\n");
            assertEquals(4, mixed.size());
            assertEquals("a", mixed.get(0).getName());
            assertEquals("1", mixed.get(0).getValue());
            assertEquals("b", mixed.get(1).getName());
            assertEquals("2", mixed.get(1).getValue());
            assertEquals("c", mixed.get(2).getName());
            assertEquals("3", mixed.get(2).getValue());
            assertEquals("d", mixed.get(3).getName());
            assertEquals("4", mixed.get(3).getValue());
        });
    }

    @Test
    void literalLeadingQuotesDoNotConsumeFollowingCellsOrRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ClipboardPanel panel = new ClipboardPanel();
            for (String literal : List.of("\"leading quote", "\"quote\"suffix", "\"escaped \"\"quote")) {
                List<Argument> values = panel.parse("message\t" + literal + "\nDate\t2026-10-27\n");
                assertEquals(2, values.size());
                assertEquals(literal, values.get(0).getValue());
                assertEquals("Date", values.get(1).getName());
                assertEquals("2026-10-27", values.get(1).getValue());

                List<Argument> names = panel.parse(literal + "\tvalue\r\nDate\t2026-10-27\r\n");
                assertEquals(2, names.size());
                assertEquals(literal, names.get(0).getName());
                assertEquals("value", names.get(0).getValue());
                assertEquals("Date", names.get(1).getName());
            }
        });
    }

    private static void assertRoundTrip(Arguments original, List<Argument> pasted) {
        assertEquals(original.getArgumentCount(), pasted.size());
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
    }

    private static class ClipboardPanel extends HTTPArgumentsPanel {
        List<Argument> parse(String text) {
            return createArgumentsFromClipboard(text, CLIPBOARD_LINE_DELIMITERS, CLIPBOARD_ARG_DELIMITERS);
        }

        void moveColumn(int from, int to) {
            getTable().moveColumn(from, to);
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
