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

import java.time.format.DateTimeFormatter;
import java.util.List;

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
                ResultTableModel.RESPONSE_SIZE)) {
            assertEquals(Long.class, model.getColumnClass(column));
        }
        assertEquals(20L, model.getValueAt(sorter.convertRowIndexToModel(0), ResultTableModel.TIME));
        assertEquals(100L, model.getValueAt(sorter.convertRowIndexToModel(1), ResultTableModel.TIME));
    }

    private static SampleResult sampleWithTime(long time) {
        SampleResult sample = new SampleResult();
        sample.setStampAndTime(1_000, time);
        return sample;
    }
}
