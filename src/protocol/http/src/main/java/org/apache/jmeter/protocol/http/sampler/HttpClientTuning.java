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

import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.util.Timeout;
import org.apache.jmeter.util.JMeterUtils;

/**
 * HttpClient settings that decide how much memory and CPU each kept-alive virtual user costs.
 * HttpClient's own defaults suit a few busy connections; a load test holds one or more mostly
 * idle connections per virtual user.
 */
final class HttpClientTuning {

    /**
     * Session buffers of an HTTP/1.1 connection, default 8192 bytes (property
     * {@code httpclient5.http1.buffer_size}). Each connection keeps three buffers of this size
     * for its whole life, and HttpClient's own default of 32 KB costs 96 KB per kept-alive
     * virtual user. They hold header lines and small writes; response bodies are read past them.
     */
    static final Http1Config HTTP1_CONFIG = Http1Config.custom()
            .setBufferSize(Math.max(1024, JMeterUtils.getPropDefault("httpclient5.http1.buffer_size", 8192)))
            .build();

    /**
     * Largest HTTP/2 frame this client accepts, default 16384 (property
     * {@code httpclient5.http2.max_frame_size}): the protocol default, which browsers keep.
     * Every HTTP/2 connection holds an input buffer of this size, so HttpClient's own default of
     * 65536 costs 48 KB more per kept-alive virtual user.
     */
    static final H2Config H2_CONFIG = H2Config.custom()
            .setMaxFrameSize(Math.clamp(JMeterUtils.getPropDefault("httpclient5.http2.max_frame_size", 16384),
                    16384, 16777215))
            .build();

    /**
     * Experimental, default false: TLS buffers of an async connection (about 49 KB) are
     * allocated while data is in flight and released when idle, instead of kept for the
     * connection's life. Saves memory for idle kept-alive users at the cost of allocating them
     * again for every request.
     */
    static final boolean DYNAMIC_TLS_BUFFERS =
            JMeterUtils.getPropDefault("httpclient5.tls.dynamic_buffers", false);

    /**
     * Default true (property {@code httpsampler.hc5.h2.direct_async}): requests without a body
     * go straight through the async client instead of the classic-to-async adaptor, which costs
     * extra I/O cycles per request. See {@link BufferedAsyncResponseConsumer}.
     */
    static final boolean DIRECT_ASYNC =
            JMeterUtils.getPropDefault("httpsampler.hc5.h2.direct_async", true);

    /**
     * Read timeout in milliseconds of a classic (blocking) connection whose sampler sets no
     * response timeout (property {@code httpclient5.default_socket_timeout}), default 180000:
     * HttpClient's own 3 minutes. A limit makes every blocking read of a virtual thread arm
     * and cancel a timer, about one extra scheduler wake-up per request (some 9 us of CPU at
     * 5,000 requests/s); 0 waits without limit, as the async client does, and avoids that.
     * A sampler's response timeout still applies.
     */
    static final SocketConfig CLASSIC_SOCKET_CONFIG = SocketConfig.custom()
            .setSoTimeout(Timeout.ofMilliseconds(
                    Math.max(0, JMeterUtils.getPropDefault("httpclient5.default_socket_timeout", 180_000))))
            .build();

    private HttpClientTuning() {
    }
}
