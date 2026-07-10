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

import org.apache.jmeter.recording.RecordingStorageMode;

/**
 * Conversion options for the HAR import wizard. The transaction delay options
 * mirror the BreakTest Transaction Controller's delay modes.
 */
public class HarImportOptions {

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
    private long fixedDelayMs = 1000;
    /** Minimum delay in milliseconds (RANDOM / GAUSSIAN modes). */
    private long delayMinMs = 500;
    /** Maximum delay in milliseconds (RANDOM / GAUSSIAN modes). */
    private long delayMaxMs = 2000;

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
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }

    public long getDelayMinMs() {
        return delayMinMs;
    }

    public void setDelayMinMs(long delayMinMs) {
        this.delayMinMs = delayMinMs;
    }

    public long getDelayMaxMs() {
        return delayMaxMs;
    }

    public void setDelayMaxMs(long delayMaxMs) {
        this.delayMaxMs = delayMaxMs;
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
