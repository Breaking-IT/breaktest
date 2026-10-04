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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.recording.RecordedWebSocketMessage;

/**
 * A single parsed HAR {@code log.entries[]} element, holding just the fields
 * the {@link HarConverter} needs. Mirrors the dict access used by the BreakTest
 * Python {@code har2jmx.py} converter.
 */
public class HarEntry {

    /** A name/value pair as found in HAR headers, query string, and form params. */
    public static class NameValue {
        private final String name;
        private final String value;
        private final String fileName;
        private final String contentType;
        private final byte[] fileContent;
        private final String resourceName;

        public NameValue(String name, String value) {
            this(name, value, "", "", null);
        }

        public NameValue(String name, String value, String fileName, String contentType, byte[] fileContent) {
            this(name, value, fileName, contentType, fileContent, localFileName(fileName));
        }

        public NameValue(String name, String value, String fileName, String contentType, byte[] fileContent,
                String resourceName) {
            this.name = name == null ? "" : name;
            this.value = value == null ? "" : value;
            this.fileName = fileName == null ? "" : fileName;
            this.contentType = contentType == null ? "" : contentType;
            this.fileContent = fileContent == null ? null : fileContent.clone();
            this.resourceName = resourceName;
        }

        public String getResourceName() {
            return resourceName;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public String getFileName() {
            return fileName;
        }

        public String getContentType() {
            return contentType;
        }

        public boolean isFileUpload() {
            return !fileName.isBlank();
        }

        public boolean hasFileContent() {
            return fileContent != null;
        }

        public byte[] getFileContent() {
            return fileContent == null ? null : fileContent.clone();
        }
    }

    static String localFileName(String recordedName) {
        String name = recordedName == null ? "" : recordedName;
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}/\\\\]", "_");
        return name.isBlank() || ".".equals(name) || "..".equals(name) ? "upload.bin" : name;
    }

    /** The {@code request.postData} object. */
    public static class PostData {
        private final String mimeType;
        private final String text;
        private final List<NameValue> params;
        private final String encoding;
        private final long bodySize;
        private final boolean complete;
        private boolean capturedUploadContent;

        public PostData(String mimeType, String text, List<NameValue> params) {
            this(mimeType, text, params, "");
        }

        public PostData(String mimeType, String text, List<NameValue> params, String encoding) {
            this(mimeType, text, params, encoding, -1, true);
        }

        public PostData(String mimeType, String text, List<NameValue> params, String encoding,
                long bodySize, boolean complete) {
            this.bodySize = bodySize;
            this.complete = complete;
            this.encoding = encoding;
            this.mimeType = mimeType;
            this.text = text;
            this.params = params == null ? new ArrayList<>() : params;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getText() {
            return text;
        }

        public boolean hasCapturedUploadContent() {
            return capturedUploadContent;
        }

        public void setCapturedUploadContent(boolean capturedUploadContent) {
            this.capturedUploadContent = capturedUploadContent;
        }

        public long getBodySize() {
            return bodySize;
        }

        public boolean isComplete() {
            return complete;
        }

        public String getEncoding() {
            return encoding;
        }

        public List<NameValue> getParams() {
            return params;
        }
    }

    /** Original 0-based position of this entry in the HAR, before filter/sort. */
    private int originalIndex;
    private boolean webSocket;
    private List<RecordedWebSocketMessage> webSocketMessages = List.of();
    private HarEntry webSocketConnection;
    private RecordedWebSocketMessage outgoingMessage;
    private BigDecimal clientCloseOffset;
    private boolean webSocketClose;

    public BigDecimal getClientCloseOffset() {
        return clientCloseOffset;
    }

    public void setClientCloseOffset(BigDecimal offset) {
        clientCloseOffset = offset;
    }

    boolean isWebSocketClose() {
        return webSocketClose;
    }

    static HarEntry webSocketClose(HarEntry connection, int index) {
        HarEntry close = webSocketEvent(connection, connection.clientCloseOffset, index);
        close.webSocketClose = true;
        close.clientCloseOffset = connection.clientCloseOffset;
        return close;
    }

    public List<RecordedWebSocketMessage> getWebSocketMessages() {
        return webSocketMessages;
    }

    public void setWebSocketMessages(List<RecordedWebSocketMessage> messages) {
        webSocketMessages = List.copyOf(messages);
    }

    HarEntry getWebSocketConnection() {
        return webSocketConnection;
    }

    RecordedWebSocketMessage getOutgoingMessage() {
        return outgoingMessage;
    }

    static HarEntry webSocketSend(HarEntry connection, RecordedWebSocketMessage message, int index) {
        HarEntry send = webSocketEvent(connection, message.relativeTimeMs(), index);
        send.outgoingMessage = message;
        return send;
    }

    private static HarEntry webSocketEvent(HarEntry connection, BigDecimal relativeTimeMs, int index) {
        HarEntry send = new HarEntry();
        send.webSocketConnection = connection;
        send.originalIndex = index;
        send.url = connection.url;
        send.webSocket = true;
        send.hasPositiveTiming = true;
        send.startMs = Math.max(org.apache.jmeter.recording.HarTimestamp.parse(connection.startedDateTime)
                .map(java.time.Instant::toEpochMilli).orElse(0L)
                + relativeTimeMs.doubleValue(), connection.endMs);
        send.endMs = send.startMs;
        return send;
    }


    public boolean isWebSocket() {
        return webSocket;
    }

    public void setWebSocket(boolean webSocket) {
        this.webSocket = webSocket;
    }


    private String method = "GET";
    private String url = "";
    private String protocol = "";
    private String fromCache;
    private String serverIpAddress;
    /** Raw {@code startedDateTime} string, kept for BreakTest HAR metadata. */
    private String startedDateTime = "";

    /** Optional explicit transaction metadata written by the BreakTest browser recorder. */
    private String transactionId = "";
    private String transactionName = "";

    /** Effective request start time (started + queue/blocking offset), epoch millis. */
    private double startMs;
    /** Effective request end time (started + total time), epoch millis. */
    private double endMs;

    /** True when {@code time} or any network timing is &gt; 0 (see should_skip_har_entry). */
    private boolean hasPositiveTiming;

    private final List<NameValue> requestHeaders = new ArrayList<>();
    private final List<NameValue> queryString = new ArrayList<>();
    private PostData postData;

    private int responseStatus;
    private String responseRedirectUrl = "";
    private final List<NameValue> responseHeaders = new ArrayList<>();
    private String responseContentText = "";

    public int getOriginalIndex() {
        return originalIndex;
    }

    public void setOriginalIndex(int originalIndex) {
        this.originalIndex = originalIndex;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method == null ? "GET" : method;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol == null ? "" : protocol;
    }

    public String getFromCache() {
        return fromCache;
    }

    public void setFromCache(String fromCache) {
        this.fromCache = fromCache;
    }

    public String getServerIpAddress() {
        return serverIpAddress;
    }

    public void setServerIpAddress(String serverIpAddress) {
        this.serverIpAddress = serverIpAddress;
    }

    public String getStartedDateTime() {
        return startedDateTime;
    }

    public void setStartedDateTime(String startedDateTime) {
        this.startedDateTime = startedDateTime == null ? "" : startedDateTime;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId == null ? "" : transactionId;
    }

    public String getTransactionName() {
        return transactionName;
    }

    public void setTransactionName(String transactionName) {
        this.transactionName = transactionName == null ? "" : transactionName;
    }

    public double getStartMs() {
        return startMs;
    }

    public void setStartMs(double startMs) {
        this.startMs = startMs;
    }

    public double getEndMs() {
        return endMs;
    }

    public void setEndMs(double endMs) {
        this.endMs = endMs;
    }

    public boolean hasPositiveTiming() {
        return hasPositiveTiming;
    }

    public void setHasPositiveTiming(boolean hasPositiveTiming) {
        this.hasPositiveTiming = hasPositiveTiming;
    }

    public List<NameValue> getRequestHeaders() {
        return requestHeaders;
    }

    public List<NameValue> getQueryString() {
        return queryString;
    }

    public PostData getPostData() {
        return postData;
    }

    public void setPostData(PostData postData) {
        this.postData = postData;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(int responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseRedirectUrl() {
        return responseRedirectUrl;
    }

    public void setResponseRedirectUrl(String responseRedirectUrl) {
        this.responseRedirectUrl = responseRedirectUrl == null ? "" : responseRedirectUrl;
    }

    public List<NameValue> getResponseHeaders() {
        return responseHeaders;
    }

    public String getResponseContentText() {
        return responseContentText;
    }

    public void setResponseContentText(String responseContentText) {
        this.responseContentText = responseContentText == null ? "" : responseContentText;
    }
}
