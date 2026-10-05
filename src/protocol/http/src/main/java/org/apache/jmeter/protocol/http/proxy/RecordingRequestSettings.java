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

package org.apache.jmeter.protocol.http.proxy;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/** Settings belonging to the request, independent of subsequent recorder edits. */
record RecordingRequestSettings(JMeterTreeNode target, String prefix, String samplerType, int namingMode,
        String format, boolean graphQL, boolean storeExchanges, int grouping, boolean autoRedirects,
        boolean followRedirects, boolean keepAlive, boolean images, boolean ignoreErrors, long transactionGapMillis) {
    static RecordingRequestSettings capture(ProxyControl recorder) {
        return new RecordingRequestSettings(recorder.captureTarget(), recorder.getPrefixHTTPSampleName(),
                recorder.getSamplerTypeName(), recorder.getHTTPSampleNamingMode(), recorder.getHttpSampleNameFormat(),
                recorder.getDetectGraphQLRequest(), recorder.getStoreRecordedExchanges(), recorder.getGroupingMode(),
                recorder.getSamplerRedirectAutomatically(), recorder.getSamplerFollowRedirects(), recorder.getUseKeepalive(),
                recorder.getSamplerDownloadImages(), recorder.getIgnoreHttpErrors(), transactionGapMillis(recorder));
    }
    static long transactionGapMillis(ProxyControl recorder) {
        String pause = recorder.getProxyPauseHTTPSample().trim();
        if (!pause.isEmpty()) {
            try {
                return Math.max(0, Long.parseLong(pause));
            } catch (NumberFormatException ignored) {
                // Use the recorder's default if a saved setting is invalid.
            }
        }
        return Math.max(0, org.apache.jmeter.util.JMeterUtils.getPropDefault("proxy.pause", 5000));
    }
}
