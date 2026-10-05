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

import java.io.Serializable;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.har.HarEntry;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;

/** A captured sampler and immutable timing/selection metadata retained until review. */
public final class RecordedSampler implements Serializable {
    private static final long serialVersionUID = 1L;
    final HTTPSamplerBase sampler;
    final transient TestElement[] testElements;
    final JMeterTreeNode target;
    final String prefix;
    final int groupingMode;
    long transactionGapMillis = 5000;
    final long recordedAt;
    final long sequence;
    final boolean failed;
    final String diagnostic;
    final HarEntry entry;
    final Authorization authorization;

    RecordedSampler(HTTPSamplerBase sampler, TestElement[] testElements, JMeterTreeNode target,
            String prefix, int groupingMode, SampleResult result, boolean transportFailure, Authorization authorization) {
        this.sampler = sampler;
        this.authorization = authorization;
        this.testElements = testElements;
        this.target = target;
        this.prefix = prefix;
        this.groupingMode = groupingMode;
        recordedAt = result.getStartTime();
        sequence = result instanceof HttpProxyTransport.Capture capture ? capture.sequence() : 0;
        failed = transportFailure || sampler.getComment().startsWith("Replay conversion failed:");
        diagnostic = sampler.getComment();
        entry = new HarEntry();
        entry.setUrl(result.getUrlAsString());
        entry.setMethod(sampler.getMethod());
        entry.setProtocol(result.getProtocolVersion());
        entry.setStartMs(result instanceof HttpProxyTransport.Capture capture ? capture.startedAt() : (double) recordedAt);
        entry.setEndMs(result instanceof HttpProxyTransport.Capture capture
                ? capture.finishedAt() : (double) Math.max(recordedAt, result.getEndTime()));
        // Incomplete captures and event streams do not define the duration of a user action.
        // The native exchange retains the actual transfer times and bytes.
        if (failed || result.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) {
            entry.setEndMs(entry.getStartMs());
        }
        if (result instanceof HttpProxyTransport.Capture capture && capture.webSocket != null) {
            entry.setWebSocket(true);
            entry.setEndMs(Math.max(entry.getStartMs(), capture.webSocketHandshakeEnd));
            entry.setStartedDateTime(java.time.Instant.ofEpochMilli(result.getStartTime()).toString());
            entry.setWebSocketMessages(capture.webSocket.messages());
            entry.setClientCloseOffset(capture.webSocket.clientCloseOffset());
            for (var header : sampler.getNativeHeaderList()) {
                entry.getRequestHeaders().add(new HarEntry.NameValue(header.getName(), header.getValue()));
            }
        }
        if (result instanceof HttpProxyTransport.Capture capture && capture.sse != null) {
            entry.setServerSentEvents(true);
            entry.setEndMs(Math.max(entry.getStartMs(), capture.sseHandshakeEnd));
        }
        entry.setResponseRedirectUrl(result instanceof HTTPSampleResult http ? http.getRedirectLocation() : "");
        try {
            entry.setResponseStatus(Integer.parseInt(result.getResponseCode()));
        } catch (NumberFormatException ignored) {
            entry.setResponseStatus(0);
        }
    }

    public HarEntry entry() { return entry; }
    public boolean failed() { return failed; }
    public String diagnostic() { return diagnostic; }
    public long recordedAt() { return recordedAt; }
    public long sequence() { return sequence; }
}
