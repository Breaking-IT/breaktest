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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.TimeUnit;

public final class HttpClientPoolTestSupport {
    private HttpClientPoolTestSupport() { }

    public static void awaitIdleConnection(int port) throws InterruptedException {
        var stats = HTTPHC5H2Impl.connectionPoolStats(port);
        assertNotNull(stats, "Expected the test server's connection pool");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (stats.getAvailable() != 1 && System.nanoTime() < deadline) {
            Thread.sleep(5);
            stats = HTTPHC5H2Impl.connectionPoolStats(port);
            assertNotNull(stats, "Connection pool disappeared while waiting for an idle connection");
        }
        assertEquals(1, stats.getAvailable(), "HTTP connection must be idle before asserting socket reuse");
    }
}
