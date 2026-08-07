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

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.table.TableRowSorter;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class SearchTreePanelTest {

    @Test
    void tableSearchUsesTreeSearchOptionsAndResetRestoresRows() {
        ResultTableModel model = new ResultTableModel(null, null,
                DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC));
        model.setRows(List.of(
                new ResultTableModel.ResultTableRow(sample("Accounts", "first response"), 0),
                new ResultTableModel.ResultTableRow(sample("Login", "SECOND response"), 0)));
        TableRowSorter<ResultTableModel> sorter = new TableRowSorter<>(model);
        AtomicBoolean tableMode = new AtomicBoolean(true);
        SearchTreePanel searchPanel = new SearchTreePanel(
                new SearchableTreeNode(sample("Root", ""), null), model, sorter, tableMode::get);

        assertTrue(searchPanel.search("second", false, false));
        assertEquals(1, sorter.getViewRowCount());
        assertEquals("Login", model.sampleAt(sorter.convertRowIndexToModel(0)).getSampleLabel());

        assertFalse(searchPanel.search("second", true, false));
        assertEquals(0, sorter.getViewRowCount());

        assertTrue(searchPanel.search("Acc.*", true, true));
        assertEquals(1, sorter.getViewRowCount());

        searchPanel.resetSearch();
        assertEquals(2, sorter.getViewRowCount());
    }

    private static SampleResult sample(String label, String response) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(label);
        result.setResponseData(response, null);
        return result;
    }
}
