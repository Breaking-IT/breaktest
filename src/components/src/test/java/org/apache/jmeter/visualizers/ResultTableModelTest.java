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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.table.TableRowSorter;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class ResultTableModelTest {

    @Test
    void sortsTimeColumnsNumerically() {
        ResultTableModel model = new ResultTableModel(null, null, DateTimeFormatter.ISO_INSTANT);
        SampleResult slow = sampleWithTime(100);
        SampleResult fast = sampleWithTime(20);
        model.setRows(List.of(
                new ResultTableModel.ResultTableRow(slow, 0),
                new ResultTableModel.ResultTableRow(fast, 0)));
        TableRowSorter<ResultTableModel> sorter = new TableRowSorter<>(model);

        sorter.toggleSortOrder(ResultTableModel.TIME);

        for (int column : List.of(
                ResultTableModel.TIME,
                ResultTableModel.LATENCY,
                ResultTableModel.CONNECT_TIME,
                ResultTableModel.REQUEST_SIZE,
                ResultTableModel.RECEIVED_BYTES)) {
            assertEquals(Long.class, model.getColumnClass(column));
        }
        assertEquals(20L, model.getValueAt(sorter.convertRowIndexToModel(0), ResultTableModel.TIME));
        assertEquals(100L, model.getValueAt(sorter.convertRowIndexToModel(1), ResultTableModel.TIME));
    }

    @Test
    void exposesReceivedBytesCompressionAndOptionalResponseBodyDiff() {
        SampleResult sample = sampleWithTime(20);
        sample.setHeadersSize(20);
        sample.setBodySize(80L);
        sample.setResponseHeaders("HTTP/2 200\nContent-Encoding: br\n");
        sample.setDataType(SampleResult.TEXT);
        sample.setResponseData("hello brave world", StandardCharsets.UTF_8.name());
        ResultTableModel model = new ResultTableModel(
                null, null, DateTimeFormatter.ISO_INSTANT, ignored -> Optional.of("hello world"));
        model.setRows(List.of(new ResultTableModel.ResultTableRow(sample, 0)));

        assertEquals(100L, model.getValueAt(0, ResultTableModel.RECEIVED_BYTES));
        assertEquals("br", model.getValueAt(0, ResultTableModel.COMPRESSION));
        assertNull(model.getValueAt(0, ResultTableModel.DIFF_PERCENT));

        model.setResponseBodyDiffEnabled(true);

        assertTrue(model.isResponseBodyDiffEnabled());
        assertEquals(35.3D, (Double) model.getValueAt(0, ResultTableModel.DIFF_PERCENT), 0.1D);
    }

    @Test
    void recognizesSupportedCompressionTypesAndLeavesUncompressedResponsesEmpty() {
        SampleResult sample = sampleWithTime(20);
        for (String encoding : List.of("gzip", "deflate", "br", "zstd")) {
            sample.setResponseHeaders("HTTP/1.1 200 OK\ncontent-encoding: " + encoding + "\n");
            assertEquals(encoding, ResultTableModel.compressionType(sample));
        }
        sample.setResponseHeaders("HTTP/1.1 200 OK\nContent-Encoding: x-gzip, br\n");
        assertEquals("gzip+br", ResultTableModel.compressionType(sample));
        sample.setResponseHeaders("HTTP/1.1 200 OK\nContent-Type: text/plain\n");
        assertEquals("", ResultTableModel.compressionType(sample));
    }

    @Test
    void responseBodyDifferenceHandlesEqualChangedAndInsertedContent() {
        assertEquals(0D, ResultTableModel.differencePercentage("same", "same"));
        assertEquals(25D, ResultTableModel.differencePercentage("test", "text"));
        assertEquals(20D, ResultTableModel.differencePercentage("abcd", "abXcd"));
        assertTrue(ResultTableModel.differencePercentage("one\ntwo\nthree", "two\none\nthree") > 0D);
    }

    @Test
    void responseBodyDifferenceNormalizesLineEndingsAndKeepsOneChangedLineSmall() {
        List<String> lines = IntStream.range(0, 8_400)
                .mapToObj(index -> "response line " + index)
                .collect(Collectors.toCollection(ArrayList::new));
        String recorded = String.join("\r\n", lines);
        lines.set(4_200, "response line changed");
        String replayed = String.join("\n", lines);

        double difference = ResultTableModel.differencePercentage(recorded, replayed);

        assertTrue(difference > 0D);
        assertTrue(difference < 0.1D, () -> "one changed line should be below 0.1%, got " + difference);
        assertEquals(0D, ResultTableModel.differencePercentage("one\r\ntwo", "one\ntwo"));
    }

    @Test
    void binaryResponsesDoNotProduceDiffPercentage() {
        SampleResult sample = sampleWithTime(20);
        sample.setDataType(SampleResult.BINARY);
        sample.setResponseData(new byte[] {1, 2, 3});
        ResultTableModel model = new ResultTableModel(
                null, null, DateTimeFormatter.ISO_INSTANT, ignored -> Optional.of("recorded"));
        model.setRows(List.of(new ResultTableModel.ResultTableRow(sample, 0)));
        model.setResponseBodyDiffEnabled(true);

        assertNull(model.getValueAt(0, ResultTableModel.DIFF_PERCENT));
        assertFalse(model.defaultVisibleColumns()[ResultTableModel.REQUEST_SIZE]);
        assertTrue(model.defaultVisibleColumns()[ResultTableModel.RECEIVED_BYTES]);
    }

    @Test
    void missingRecordedBodyDoesNotProduceDiffPercentage() {
        SampleResult sample = sampleWithTime(20);
        sample.setDataType(SampleResult.TEXT);
        sample.setResponseData("replayed", StandardCharsets.UTF_8.name());
        ResultTableModel model = new ResultTableModel(
                null, null, DateTimeFormatter.ISO_INSTANT, ignored -> Optional.empty());
        model.setRows(List.of(new ResultTableModel.ResultTableRow(sample, 0)));
        model.setResponseBodyDiffEnabled(true);

        assertNull(model.getValueAt(0, ResultTableModel.DIFF_PERCENT));
    }

    private static SampleResult sampleWithTime(long time) {
        SampleResult sample = new SampleResult();
        sample.setStampAndTime(1_000, time);
        return sample;
    }
}
