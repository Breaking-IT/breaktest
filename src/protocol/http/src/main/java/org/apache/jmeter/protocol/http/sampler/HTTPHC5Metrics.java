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

package org.apache.jmeter.protocol.http.sampler;

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpRequest;

final class HTTPHC5Metrics {
    private HTTPHC5Metrics() {
    }

    static long estimateSentBytes(HttpRequest request, String protocol) {
        if (request == null) {
            return 0;
        }
        String requestUri = request.getRequestUri();
        long bytes = (long) request.getMethod().length()
                + 1L
                + (requestUri == null || requestUri.isEmpty() ? 1L : requestUri.length())
                + 1L
                + protocol.length()
                + 2L;
        for (Header header : request.getHeaders()) {
            String value = header.getValue();
            bytes += (long) header.getName().length() + 2L + (value == null ? 0L : value.length()) + 2L;
        }
        bytes += 2L;
        if (request instanceof ClassicHttpRequest classicRequest) {
            HttpEntity entity = classicRequest.getEntity();
            if (entity != null && entity.getContentLength() > 0) {
                bytes += entity.getContentLength();
            }
        }
        return bytes;
    }
}
