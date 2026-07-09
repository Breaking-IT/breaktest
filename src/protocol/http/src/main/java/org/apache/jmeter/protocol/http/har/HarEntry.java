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

import java.util.ArrayList;
import java.util.List;

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

        public NameValue(String name, String value) {
            this.name = name == null ? "" : name;
            this.value = value == null ? "" : value;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }
    }

    /** The {@code request.postData} object. */
    public static class PostData {
        private final String mimeType;
        private final String text;
        private final List<NameValue> params;

        public PostData(String mimeType, String text, List<NameValue> params) {
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

        public List<NameValue> getParams() {
            return params;
        }
    }

    /** Original 0-based position of this entry in the HAR, before filter/sort. */
    private int originalIndex;

    private String method = "GET";
    private String url = "";
    private String protocol = "";
    private String fromCache;
    private String serverIpAddress;
    /** Raw {@code startedDateTime} string, kept for BreakTest HAR metadata. */
    private String startedDateTime = "";

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
