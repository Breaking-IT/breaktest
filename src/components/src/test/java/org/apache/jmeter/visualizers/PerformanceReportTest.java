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

import java.util.List;

import javax.swing.JLabel;
import javax.swing.JTable;

import org.apache.jmeter.gui.util.MenuInfo;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jorphan.gui.ObjectTableModel;
import org.junit.jupiter.api.Test;

public class PerformanceReportTest {

    @Test
    public void tableModelExposesErrorCountAndPercentiles() {
        ObjectTableModel model = PerformanceReport.createObjectTableModel();
        PerformanceReport.PerformanceReportData calculator = new PerformanceReport.PerformanceReportData("failed sample");
        calculator.setTrackResponseTimeDistribution(true);
        SampleResult failedSample = new SampleResult();
        failedSample.setSuccessful(false);
        failedSample.setStampAndTime(1000, 250);

        calculator.addSample(failedSample);
        model.addRow(calculator);

        assertTrue(model.checkFunctors(null, getClass()));
        assertEquals(1L, model.getValueAt(0, PerformanceReport.ERROR_COUNT_COLUMN));
        assertEquals(Long.class, model.getColumnClass(PerformanceReport.ERROR_COUNT_COLUMN));
        assertEquals(250L, model.getValueAt(0, 4));
    }

    @Test
    public void tableModelIsNotEditableToAllowLabelDoubleClickNavigation() {
        ObjectTableModel model = PerformanceReport.createObjectTableModel();

        assertFalse(model.isCellEditable(0, PerformanceReport.LABEL_COLUMN));
    }

    @Test
    public void rowStoresFirstSampleSourcePathForJumpNavigation() {
        PerformanceReport.PerformanceReportData row = new PerformanceReport.PerformanceReportData("sample");
        SampleResult sample = new SampleResult();
        List<SampleResult.TestElementPathEntry> sourcePath = List.of(
                new SampleResult.TestElementPathEntry("thread.Group", "Thread Group", 0),
                new SampleResult.TestElementPathEntry("debug.Sampler", "Sample", 0));
        sample.setSuccessful(true);
        sample.setStampAndTime(1000, 100);
        sample.setSourceTestElementPath(sourcePath);

        row.addSample(sample);

        assertEquals(sourcePath, row.getSourceTestElementPath());
    }

    @Test
    public void columnsUseRequestedOrderAndResourceCostMarkers() {
        String[] columns = PerformanceReport.getColumnKeys();

        assertEquals("sampler_label", columns[0]);
        assertEquals("aggregate_report_count", columns[1]);
        assertEquals("average", columns[2]);
        assertEquals("aggregate_report_min", columns[3]);
        assertEquals("performance_report_p50", columns[4]);
        assertEquals("performance_report_p75", columns[5]);
        assertEquals("performance_report_p90", columns[6]);
        assertEquals("performance_report_p95", columns[7]);
        assertEquals("performance_report_p99", columns[8]);
        assertEquals("performance_report_max", columns[9]);
        assertEquals("performance_report_stddev", columns[10]);
        assertEquals("aggregate_report_error_count", columns[11]);
        assertEquals("aggregate_report_error%", columns[12]);
        assertEquals("performance_report_rate", columns[13]);

        assertTrue(PerformanceReport.isResourceIntensiveColumn(4));
        assertTrue(PerformanceReport.isResourceIntensiveColumn(5));
        assertTrue(PerformanceReport.isResourceIntensiveColumn(6));
        assertTrue(PerformanceReport.isResourceIntensiveColumn(7));
        assertTrue(PerformanceReport.isResourceIntensiveColumn(8));
        assertFalse(PerformanceReport.isResourceIntensiveColumn(PerformanceReport.ERROR_COUNT_COLUMN));
    }

    @Test
    public void defaultColumnsKeepTableLean() {
        String[] columns = PerformanceReport.getColumnKeys();

        for (int i = 0; i < columns.length; i++) {
            boolean expected = "sampler_label".equals(columns[i])
                    || "aggregate_report_count".equals(columns[i])
                    || "average".equals(columns[i])
                    || "aggregate_report_min".equals(columns[i])
                    || "performance_report_max".equals(columns[i])
                    || "aggregate_report_error_count".equals(columns[i])
                    || "aggregate_report_error%".equals(columns[i])
                    || "performance_report_rate".equals(columns[i])
                    || "aggregate_report_bandwidth".equals(columns[i])
                    || "aggregate_report_sent_bytes_per_sec".equals(columns[i]);
            assertEquals(expected, PerformanceReport.isDefaultSelectedColumn(i), columns[i]);
        }
    }

    @Test
    public void emptyMetricsAreBlankExceptSampleCount() {
        ObjectTableModel model = PerformanceReport.createObjectTableModel();
        model.addRow(new PerformanceReport.PerformanceReportData("TOTAL"));

        assertEquals(0L, model.getValueAt(0, 1));
        assertEquals(null, model.getValueAt(0, 2));
        assertEquals(null, model.getValueAt(0, 8));
        assertEquals(null, model.getValueAt(0, PerformanceReport.ERROR_COUNT_COLUMN));
    }

    @Test
    public void canIgnoreErrorsForResponseTimeMetrics() {
        PerformanceReport.PerformanceReportData row = new PerformanceReport.PerformanceReportData("mixed");
        SampleResult successful = new SampleResult();
        successful.setSuccessful(true);
        successful.setStampAndTime(1000, 100);
        successful.setConnectTime(40);
        SampleResult failed = new SampleResult();
        failed.setSuccessful(false);
        failed.setStampAndTime(2000, 900);
        failed.setConnectTime(300);

        row.addSample(successful);
        row.addSample(failed);
        assertEquals(500L, row.getMeanAsNumber());
        assertEquals(170L, row.getAverageConnectTime());

        row.setIgnoreErrorResponseTimes(true);
        assertEquals(100L, row.getMeanAsNumber());
        assertEquals(40L, row.getAverageConnectTime());
        assertEquals(1L, row.getErrorCount());
    }

    @Test
    public void averageConnectTimeIgnoresUnmeasuredZeroValues() {
        PerformanceReport.PerformanceReportData row = new PerformanceReport.PerformanceReportData("connect");
        SampleResult withoutConnectionMeasurement = new SampleResult();
        withoutConnectionMeasurement.setSuccessful(true);
        withoutConnectionMeasurement.setStampAndTime(1000, 100);
        SampleResult withConnectionMeasurement = new SampleResult();
        withConnectionMeasurement.setSuccessful(true);
        withConnectionMeasurement.setStampAndTime(2000, 100);
        withConnectionMeasurement.setConnectTime(80);

        row.addSample(withoutConnectionMeasurement);
        row.addSample(withConnectionMeasurement);

        assertEquals(80L, row.getAverageConnectTime());
    }

    @Test
    public void percentileHistoryIsOnlyTrackedWhenEnabled() {
        PerformanceReport.PerformanceReportData row = new PerformanceReport.PerformanceReportData("lean");
        SampleResult first = new SampleResult();
        first.setSuccessful(true);
        first.setStampAndTime(1000, 100);
        SampleResult second = new SampleResult();
        second.setSuccessful(true);
        second.setStampAndTime(2000, 300);

        row.addSample(first);
        assertNull(row.getPercentPoint(0.50F));
        assertEquals(100L, row.getMeanAsNumber());

        row.setTrackResponseTimeDistribution(true);
        row.addSample(second);

        assertEquals(200L, row.getMeanAsNumber());
        assertEquals(300L, row.getPercentPoint(0.50F));
    }

    @Test
    public void standardDeviationDoesNotRequirePercentileHistory() {
        PerformanceReport.PerformanceReportData row = new PerformanceReport.PerformanceReportData("stddev");
        SampleResult first = new SampleResult();
        first.setSuccessful(true);
        first.setStampAndTime(1000, 100);
        SampleResult second = new SampleResult();
        second.setSuccessful(true);
        second.setStampAndTime(2000, 300);

        row.addSample(first);
        row.addSample(second);

        assertNull(row.getPercentPoint(0.50F));
        assertEquals(100.0D, row.getStandardDeviation());
    }

    @Test
    public void listenerAcceptsFirstSampleWhenErrorCountWasBlank() {
        PerformanceReport report = new PerformanceReport();
        SampleResult successful = new SampleResult();
        successful.setSuccessful(true);
        successful.setSampleLabel("first");
        successful.setStampAndTime(1000, 100);

        report.add(successful);
    }

    @Test
    public void errorCountAndPercentageRenderWithCorrectFormats() {
        JTable table = new JTable(PerformanceReport.createObjectTableModel());
        String[] columns = PerformanceReport.getColumnKeys();
        int throughputColumn = -1;
        for (int i = 0; i < columns.length; i++) {
            if ("performance_report_rate".equals(columns[i])) {
                throughputColumn = i;
                break;
            }
        }

        JLabel errorCount = (JLabel) PerformanceReport.getRenderer(PerformanceReport.ERROR_COUNT_COLUMN)
                .getTableCellRendererComponent(table, 3L, false, false, 0, PerformanceReport.ERROR_COUNT_COLUMN);
        JLabel errorPercentage = (JLabel) PerformanceReport.getRenderer(PerformanceReport.ERROR_COUNT_COLUMN + 1)
                .getTableCellRendererComponent(table, 0.25D, false, false, 0, PerformanceReport.ERROR_COUNT_COLUMN + 1);
        JLabel throughput = (JLabel) PerformanceReport.getRenderer(throughputColumn)
                .getTableCellRendererComponent(table, 12.5D, false, false, 0, throughputColumn);

        assertEquals("3", errorCount.getText());
        assertTrue(errorPercentage.getText().endsWith("%"));
        assertTrue(errorPercentage.getText().contains(".") || errorPercentage.getText().contains(","));
        assertTrue(throughput.getText().contains("/"));
        assertFalse(throughput.getText().contains("%"));
    }

    @Test
    public void listenerMenuOrderPrioritizesPerformanceReport() {
        assertEquals(1, menuSortOrder(ViewResultsFullVisualizer.class));
        assertEquals(2, menuSortOrder(PerformanceReport.class));
        assertEquals(MenuInfo.SORT_ORDER_DEFAULT + 1, menuSortOrder(SummaryReport.class));
        assertEquals(MenuInfo.SORT_ORDER_DEFAULT + 2, menuSortOrder(StatVisualizer.class));
        assertEquals(MenuInfo.SORT_ORDER_DEFAULT + 3, menuSortOrder(TableVisualizer.class));
    }

    private static int menuSortOrder(Class<?> visualizerClass) {
        return new MenuInfo(visualizerClass.getSimpleName(), visualizerClass.getName()).getSortOrder();
    }
}
