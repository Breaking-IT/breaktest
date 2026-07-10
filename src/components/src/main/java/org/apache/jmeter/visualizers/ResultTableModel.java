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

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import javax.swing.Icon;
import javax.swing.table.AbstractTableModel;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;

class ResultTableModel extends AbstractTableModel {
    private static final long serialVersionUID = -2584806147216198190L;

    static final int STATUS = 0;
    static final int TIMESTAMP = 1;
    static final int THREAD_GROUP = 2;
    static final int THREAD_NAME = 3;
    static final int LABEL = 4;
    static final int TIME = 5;
    static final int LATENCY = 6;
    static final int CONNECT_TIME = 7;
    static final int REQUEST_SIZE = 8;
    static final int RESPONSE_SIZE = 9;
    static final int URL = 10;

    static final String[] COLUMNS = {
            "", // $NON-NLS-1$
            "view_results_table_timestamp", // $NON-NLS-1$
            "view_results_table_thread_group", // $NON-NLS-1$
            "table_visualizer_thread_name", // $NON-NLS-1$
            "sampler_label", // $NON-NLS-1$
            "view_results_table_time", // $NON-NLS-1$
            "table_visualizer_latency", // $NON-NLS-1$
            "table_visualizer_connect", // $NON-NLS-1$
            "view_results_table_request_size", // $NON-NLS-1$
            "view_results_table_response_size", // $NON-NLS-1$
            "view_results_table_url" // $NON-NLS-1$
    };

    private final Icon successIcon;
    private final Icon failureIcon;
    private final DateTimeFormatter timestampFormat;
    private List<ResultTableRow> rows = Collections.emptyList();

    ResultTableModel(Icon successIcon, Icon failureIcon, DateTimeFormatter timestampFormat) {
        this.successIcon = successIcon;
        this.failureIcon = failureIcon;
        this.timestampFormat = timestampFormat;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return column == STATUS ? "" : JMeterUtils.getResString(COLUMNS[column]); // $NON-NLS-1$
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return switch (columnIndex) {
        case STATUS -> Icon.class;
        case TIME, LATENCY, CONNECT_TIME, REQUEST_SIZE, RESPONSE_SIZE -> Long.class;
        default -> Object.class;
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        ResultTableRow row = rows.get(rowIndex);
        SampleResult sample = row.sample();
        return switch (columnIndex) {
        case STATUS -> sample.isSuccessful() ? successIcon : failureIcon;
        case TIMESTAMP -> timestampFormat.format(Instant.ofEpochMilli(sample.getStartTime()));
        case THREAD_GROUP -> ViewResultsFullVisualizer.threadGroupName(sample.getThreadName());
        case THREAD_NAME -> sample.getThreadName();
        case LABEL -> row.label();
        case TIME -> sample.getTime();
        case LATENCY -> sample.getLatency();
        case CONNECT_TIME -> sample.getConnectTime();
        case REQUEST_SIZE -> sample.getSentBytes();
        case RESPONSE_SIZE -> sample.getHeadersSize() + sample.getBodySizeAsLong();
        case URL -> sample.getUrlAsString();
        default -> ""; // $NON-NLS-1$
        };
    }

    void setRows(List<ResultTableRow> rows) {
        this.rows = List.copyOf(rows);
        fireTableDataChanged();
    }

    SampleResult sampleAt(int row) {
        return rows.get(row).sample();
    }

    static boolean[] defaultVisibleColumns() {
        boolean[] columns = new boolean[COLUMNS.length];
        columns[STATUS] = true;
        columns[TIMESTAMP] = true;
        columns[LABEL] = true;
        columns[TIME] = true;
        columns[LATENCY] = true;
        columns[URL] = true;
        return columns;
    }

    static record ResultTableRow(SampleResult sample, int depth) {
        String label() {
            return "  ".repeat(depth) + sample.getSampleLabel(); // $NON-NLS-1$
        }
    }
}
