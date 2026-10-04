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

package org.apache.jmeter.protocol.sse;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;

/** An HTTP request whose response is received as a stream of server-sent events. */
public class SseSampler extends HTTPSamplerProxy {
    private static final long serialVersionUID = 1L;

    /** Keep quiet streams open without a read timeout when creating a new request. */
    public static final String DEFAULT_RESPONSE_TIMEOUT = "0";
    public static final String RECONNECT = "Close and reconnect";
    public static final String REUSE = "Reuse if connected";
    public static final String FAIL = "Fail if exists";
    public static final String EVENT_NAME = "Prefix + event name";
    public static final String FIXED_NAME = "Fixed name";

    @Override
    public boolean isSseEnabled() {
        return true;
    }
}
