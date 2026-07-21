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
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javax.swing.Icon;
import javax.swing.table.AbstractTableModel;

import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.StringUtilities;

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
    static final int RECEIVED_BYTES = 9;
    static final int COMPRESSION = 10;
    static final int DIFF_PERCENT = 11;
    static final int URL = 12;

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
            "view_results_table_received_bytes", // $NON-NLS-1$
            "view_results_table_compression", // $NON-NLS-1$
            "view_results_table_diff_percent", // $NON-NLS-1$
            "view_results_table_url" // $NON-NLS-1$
    };

    private final Icon successIcon;
    private final Icon failureIcon;
    private final DateTimeFormatter timestampFormat;
    private final Function<SampleResult, Optional<String>> recordedResponseBody;
    private final IdentityHashMap<SampleResult, Optional<Double>> responseBodyDiffs = new IdentityHashMap<>();
    private List<ResultTableRow> rows = Collections.emptyList();
    private boolean responseBodyDiffEnabled;

    ResultTableModel(Icon successIcon, Icon failureIcon, DateTimeFormatter timestampFormat) {
        this(successIcon, failureIcon, timestampFormat, ResultTableModel::recordedResponseBody);
    }

    ResultTableModel(Icon successIcon, Icon failureIcon, DateTimeFormatter timestampFormat,
            Function<SampleResult, Optional<String>> recordedResponseBody) {
        this.successIcon = successIcon;
        this.failureIcon = failureIcon;
        this.timestampFormat = timestampFormat;
        this.recordedResponseBody = recordedResponseBody;
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
        case TIME, LATENCY, CONNECT_TIME, REQUEST_SIZE, RECEIVED_BYTES -> Long.class;
        case DIFF_PERCENT -> Double.class;
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
        case RECEIVED_BYTES -> sample.getBytesAsLong();
        case COMPRESSION -> compressionType(sample);
        case DIFF_PERCENT -> responseBodyDiffEnabled
                ? responseBodyDiffs.computeIfAbsent(sample, this::calculateResponseBodyDiff).orElse(null)
                : null;
        case URL -> sample.getUrlAsString();
        default -> ""; // $NON-NLS-1$
        };
    }

    void setRows(List<ResultTableRow> rows) {
        this.rows = List.copyOf(rows);
        Set<SampleResult> currentSamples = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ResultTableRow row : rows) {
            currentSamples.add(row.sample());
        }
        responseBodyDiffs.keySet().removeIf(sample -> !currentSamples.contains(sample));
        fireTableDataChanged();
    }

    void setResponseBodyDiffEnabled(boolean enabled) {
        if (responseBodyDiffEnabled == enabled) {
            return;
        }
        responseBodyDiffEnabled = enabled;
        if (!enabled) {
            responseBodyDiffs.clear();
        }
        fireTableDataChanged();
    }

    boolean isResponseBodyDiffEnabled() {
        return responseBodyDiffEnabled;
    }

    private Optional<Double> calculateResponseBodyDiff(SampleResult sample) {
        if (SampleResult.BINARY.equals(sample.getDataType()) || sample.getResponseData().length == 0) {
            return Optional.empty();
        }
        String replayed = sample.getResponseDataAsString();
        if (StringUtilities.isEmpty(replayed)) {
            return Optional.empty();
        }
        return recordedResponseBody.apply(sample)
                .filter(StringUtilities::isNotEmpty)
                .map(recorded -> differencePercentage(recorded, replayed));
    }

    private static Optional<String> recordedResponseBody(SampleResult sample) {
        return RecordedHarExchangeResolver.resolveFor(sample)
                .exchange()
                .map(RecordedHarExchangeResolver.RecordedExchange::responseBody)
                .filter(StringUtilities::isNotEmpty);
    }

    static String compressionType(SampleResult sample) {
        Set<String> compressionTypes = new LinkedHashSet<>();
        for (String line : sample.getResponseHeaders().lines().toList()) {
            int separator = line.indexOf(':');
            if (separator < 0 || !"content-encoding".equalsIgnoreCase(line.substring(0, separator).trim())) { // $NON-NLS-1$
                continue;
            }
            for (String value : line.substring(separator + 1).split(",")) { // $NON-NLS-1$
                String encoding = value.trim().toLowerCase(Locale.ROOT);
                switch (encoding) {
                case "gzip", "x-gzip" -> compressionTypes.add("gzip"); // $NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                case "deflate", "br", "zstd" -> compressionTypes.add(encoding); // $NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                default -> {
                    // Only show compression encodings supported by the result table.
                }
                }
            }
        }
        return String.join("+", compressionTypes); // $NON-NLS-1$
    }

    static double differencePercentage(String recorded, String replayed) {
        String normalizedRecorded = normalizeLineEndings(recorded);
        String normalizedReplayed = normalizeLineEndings(replayed);
        if (normalizedRecorded.equals(normalizedReplayed)) {
            return 0D;
        }
        List<String> recordedLines = List.of(normalizedRecorded.split("\n", -1)); // $NON-NLS-1$
        List<String> replayedLines = List.of(normalizedReplayed.split("\n", -1)); // $NON-NLS-1$
        int longestLineCount = Math.max(recordedLines.size(), replayedLines.size());
        if (longestLineCount == 1) {
            return changedCharacters(normalizedRecorded, normalizedReplayed) * 100D
                    / Math.max(normalizedRecorded.length(), normalizedReplayed.length());
        }
        int editDistance = lineEditDistance(recordedLines, replayedLines);
        double changedLines = (Math.abs(recordedLines.size() - replayedLines.size()) + editDistance) / 2D;
        return Math.min(100D, changedLines * 100D / longestLineCount);
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n'); // $NON-NLS-1$ //$NON-NLS-2$
    }

    private static int lineEditDistance(List<String> recorded, List<String> replayed) {
        int maximumDistance = recorded.size() + replayed.size();
        int offset = maximumDistance;
        int[] furthestRecordedIndex = new int[maximumDistance * 2 + 1];
        for (int distance = 0; distance <= maximumDistance; distance++) {
            for (int diagonal = -distance; diagonal <= distance; diagonal += 2) {
                int recordedIndex;
                if (diagonal == -distance || (diagonal != distance
                        && furthestRecordedIndex[offset + diagonal - 1]
                                < furthestRecordedIndex[offset + diagonal + 1])) {
                    recordedIndex = furthestRecordedIndex[offset + diagonal + 1];
                } else {
                    recordedIndex = furthestRecordedIndex[offset + diagonal - 1] + 1;
                }
                int replayedIndex = recordedIndex - diagonal;
                while (recordedIndex < recorded.size() && replayedIndex < replayed.size()
                        && recorded.get(recordedIndex).equals(replayed.get(replayedIndex))) {
                    recordedIndex++;
                    replayedIndex++;
                }
                furthestRecordedIndex[offset + diagonal] = recordedIndex;
                if (recordedIndex >= recorded.size() && replayedIndex >= replayed.size()) {
                    return distance;
                }
            }
        }
        return maximumDistance;
    }

    private static int changedCharacters(String recorded, String replayed) {
        int prefix = 0;
        int maximumPrefix = Math.min(recorded.length(), replayed.length());
        while (prefix < maximumPrefix && recorded.charAt(prefix) == replayed.charAt(prefix)) {
            prefix++;
        }
        int recordedEnd = recorded.length();
        int replayedEnd = replayed.length();
        while (recordedEnd > prefix && replayedEnd > prefix
                && recorded.charAt(recordedEnd - 1) == replayed.charAt(replayedEnd - 1)) {
            recordedEnd--;
            replayedEnd--;
        }
        int recordedChangedLength = recordedEnd - prefix;
        int replayedChangedLength = replayedEnd - prefix;
        int changed = Math.abs(recordedChangedLength - replayedChangedLength);
        int overlappingChangedLength = Math.min(recordedChangedLength, replayedChangedLength);
        for (int i = 0; i < overlappingChangedLength; i++) {
            if (recorded.charAt(prefix + i) != replayed.charAt(prefix + i)) {
                changed++;
            }
        }
        return changed;
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
        columns[RECEIVED_BYTES] = true;
        columns[COMPRESSION] = true;
        columns[DIFF_PERCENT] = true;
        columns[URL] = true;
        return columns;
    }

    static record ResultTableRow(SampleResult sample, int depth) {
        String label() {
            return "  ".repeat(depth) + sample.getSampleLabel(); // $NON-NLS-1$
        }
    }
}
