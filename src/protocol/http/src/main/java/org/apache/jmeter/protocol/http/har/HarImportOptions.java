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

package org.apache.jmeter.protocol.http.har;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.jmeter.recording.RecordingStorageMode;

/**
 * Conversion options for the HAR import wizard. The transaction delay options
 * mirror the BreakTest Transaction Controller's delay modes.
 */
public class HarImportOptions {

    public static final String FIXED_DELAY_VARIABLE = "ThinkTime";
    public static final String MIN_DELAY_VARIABLE = "ThinkTimeMin";
    public static final String MAX_DELAY_VARIABLE = "ThinkTimeMax";

    private static final Pattern DELAY_VALUE = Pattern.compile("(?:0|[1-9]\\d*)|(?:\\$\\{[^{}\\s]+})");

    /** How each transaction's think-time delay is configured. */
    public enum DelayMode {
        /** Reproduce the recorded gap between transactions, with a +/- random spread. */
        AS_RECORDED,
        /** A single fixed delay applied to every transaction. */
        FIXED,
        /** A uniform random delay between min and max. */
        RANDOM,
        /** A Gaussian random delay between min and max. */
        GAUSSIAN,
        /** No delay between transactions. */
        NONE
    }

    private boolean continueOnError = false;
    private boolean ignoreErrors = true;
    private boolean addIndex = false;
    private boolean detectDynamicUrls = true;
    private RecordingStorageMode recordingStorageMode = RecordingStorageMode.ALL;

    /** New transaction is started when idle gap exceeds this many seconds. */
    private int idleTimeSeconds = 4;

    private DelayMode delayMode = DelayMode.AS_RECORDED;
    /** Random spread (percent) applied to the recorded delay in AS_RECORDED mode. */
    private int recordedRandomPercent = 50;
    /** Fixed delay in milliseconds (FIXED mode). */
    private String fixedDelay = "1000";
    /** Minimum delay in milliseconds (RANDOM / GAUSSIAN modes). */
    private String delayMin = "500";
    /** Maximum delay in milliseconds (RANDOM / GAUSSIAN modes). */
    private String delayMax = "2000";
    /** Store configured think times once on the Test Plan and reference them from transactions. */
    private boolean useDelayVariables;

    // Timeouts are always applied via HTTP Request Defaults; not exposed in the wizard.
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 60;

    // Whether to add test-plan-level config elements; disabled when one already exists.
    private boolean includeViewResultsTree = true;
    private boolean includeCookieManager = true;
    private boolean includeHttpDefaults = true;

    public boolean isContinueOnError() {
        return continueOnError;
    }

    public void setContinueOnError(boolean continueOnError) {
        this.continueOnError = continueOnError;
    }

    public boolean isIgnoreErrors() {
        return ignoreErrors;
    }

    public void setIgnoreErrors(boolean ignoreErrors) {
        this.ignoreErrors = ignoreErrors;
    }

    public boolean isAddIndex() {
        return addIndex;
    }

    public void setAddIndex(boolean addIndex) {
        this.addIndex = addIndex;
    }

    public boolean isDetectDynamicUrls() {
        return detectDynamicUrls;
    }

    public void setDetectDynamicUrls(boolean detectDynamicUrls) {
        this.detectDynamicUrls = detectDynamicUrls;
    }

    public RecordingStorageMode getRecordingStorageMode() {
        return recordingStorageMode;
    }

    public void setRecordingStorageMode(RecordingStorageMode recordingStorageMode) {
        this.recordingStorageMode = recordingStorageMode;
    }

    public int getIdleTimeSeconds() {
        return idleTimeSeconds;
    }

    public void setIdleTimeSeconds(int idleTimeSeconds) {
        this.idleTimeSeconds = idleTimeSeconds;
    }

    public DelayMode getDelayMode() {
        return delayMode;
    }

    public void setDelayMode(DelayMode delayMode) {
        this.delayMode = delayMode;
    }

    public int getRecordedRandomPercent() {
        return recordedRandomPercent;
    }

    public void setRecordedRandomPercent(int recordedRandomPercent) {
        this.recordedRandomPercent = recordedRandomPercent;
    }

    public long getFixedDelayMs() {
        return Long.parseLong(fixedDelay);
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelay = Long.toString(fixedDelayMs);
    }

    public String getFixedDelay() {
        return fixedDelay;
    }

    public void setFixedDelay(String fixedDelay) {
        this.fixedDelay = normalizeDelay(fixedDelay);
    }

    public long getDelayMinMs() {
        return Long.parseLong(delayMin);
    }

    public void setDelayMinMs(long delayMinMs) {
        this.delayMin = Long.toString(delayMinMs);
    }

    public String getDelayMin() {
        return delayMin;
    }

    public void setDelayMin(String delayMin) {
        this.delayMin = normalizeDelay(delayMin);
    }

    public long getDelayMaxMs() {
        return Long.parseLong(delayMax);
    }

    public void setDelayMaxMs(long delayMaxMs) {
        this.delayMax = Long.toString(delayMaxMs);
    }

    public String getDelayMax() {
        return delayMax;
    }

    public void setDelayMax(String delayMax) {
        this.delayMax = normalizeDelay(delayMax);
    }

    public boolean isUseDelayVariables() {
        return useDelayVariables;
    }

    public void setUseDelayVariables(boolean useDelayVariables) {
        this.useDelayVariables = useDelayVariables;
    }

    public String getEffectiveFixedDelay() {
        return useDelayVariables && isLiteralDelay(fixedDelay)
                ? variableReference(FIXED_DELAY_VARIABLE) : fixedDelay;
    }

    public String getEffectiveDelayMin() {
        return useDelayVariables && isLiteralDelay(delayMin)
                ? variableReference(MIN_DELAY_VARIABLE) : delayMin;
    }

    public String getEffectiveDelayMax() {
        return useDelayVariables && isLiteralDelay(delayMax)
                ? variableReference(MAX_DELAY_VARIABLE) : delayMax;
    }

    public Map<String, String> getDelayVariables() {
        Map<String, String> variables = new LinkedHashMap<>();
        if (!useDelayVariables) {
            return variables;
        }
        if (delayMode == DelayMode.FIXED) {
            putLiteralVariable(variables, FIXED_DELAY_VARIABLE, fixedDelay);
        } else if (delayMode == DelayMode.RANDOM || delayMode == DelayMode.GAUSSIAN) {
            putLiteralVariable(variables, MIN_DELAY_VARIABLE, delayMin);
            putLiteralVariable(variables, MAX_DELAY_VARIABLE, delayMax);
        }
        return variables;
    }

    public static boolean isValidDelay(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        if (!DELAY_VALUE.matcher(normalized).matches()) {
            return false;
        }
        if (normalized.startsWith("${")) {
            return true;
        }
        try {
            Long.parseLong(normalized);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String normalizeDelay(String value) {
        return value == null ? "" : value.trim();
    }

    private static String variableReference(String name) {
        return "${" + name + "}";
    }

    private static boolean isLiteralDelay(String value) {
        return !value.startsWith("${");
    }

    private static void putLiteralVariable(Map<String, String> variables, String name, String value) {
        if (isLiteralDelay(value)) {
            variables.put(name, value);
        }
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public boolean isIncludeViewResultsTree() {
        return includeViewResultsTree;
    }

    public void setIncludeViewResultsTree(boolean includeViewResultsTree) {
        this.includeViewResultsTree = includeViewResultsTree;
    }

    public boolean isIncludeCookieManager() {
        return includeCookieManager;
    }

    public void setIncludeCookieManager(boolean includeCookieManager) {
        this.includeCookieManager = includeCookieManager;
    }

    public boolean isIncludeHttpDefaults() {
        return includeHttpDefaults;
    }

    public void setIncludeHttpDefaults(boolean includeHttpDefaults) {
        this.includeHttpDefaults = includeHttpDefaults;
    }
}
