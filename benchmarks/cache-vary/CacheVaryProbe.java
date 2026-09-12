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

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLConnection;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.util.JMeterUtils;

/** Focused allocation probe; setup and existing header formatting are outside measurement. */
public class CacheVaryProbe {
    public static void main(String[] args) throws Exception {
        JMeterUtils.setJMeterHome(System.getProperty("user.dir"));
        JMeterUtils.loadJMeterProperties("bin/jmeter.properties");
        String transport = args[0];
        int count = Integer.parseInt(args[1]);
        int bytes = Integer.parseInt(args[2]);
        boolean vary = Boolean.parseBoolean(args[3]);
        var url = URI.create("http://localhost/cache").toURL();
        var result = new HTTPSampleResult();
        result.setURL(url);
        result.setHTTPMethod("GET");
        result.setResponseCode("200");
        StringBuilder headers = new StringBuilder();
        for (int i = 0; i < count; i++) {
            headers.append("X-").append(i).append(": ")
                    .append("a".repeat(bytes / count - (i < 10 ? 6 : 7))).append('\n');
        }
        if (headers.length() != bytes) {
            throw new AssertionError(headers.length());
        }
        result.setRequestHeaders(headers.toString());
        var response = new BasicHttpResponse(200);
        response.setHeader("ETag", "cached");
        if (vary) {
            response.setHeader("Vary", "X-0");
        }
        var connection = new URLConnection(url) {
            @Override
            public void connect() {
            }

            @Override
            public String getHeaderField(String name) {
                Header header = response.getLastHeader(name);
                return header == null ? null : header.getValue();
            }
        };
        var cache = new CacheManager();
        cache.testIterationStart(null);
        Runnable save = transport.equals("hc5")
                ? () -> cache.saveDetails(response, result)
                : () -> cache.saveDetails(connection, result);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < 300_000; i++) {
            save.run();
        }
        for (int round = 0; round < 5; round++) {
            long allocation = bean.getThreadAllocatedBytes(thread);
            long time = System.nanoTime();
            for (int i = 0; i < 100_000; i++) {
                save.run();
            }
            long elapsed = System.nanoTime() - time;
            long allocated = bean.getThreadAllocatedBytes(thread) - allocation;
            System.out.printf(java.util.Locale.ROOT, "DATA,%s,%d,%d,%s,%d,%.1f,%.1f%n",
                    transport, count, bytes, vary, round, elapsed / 100_000.0, allocated / 100_000.0);
        }
        var request = new HttpGet(url.toURI());
        request.addHeader("X-0", "a".repeat(bytes / count - 6));
        cache.setHeaders(url, request);
        if (!"cached".equals(request.getLastHeader("If-None-Match").getValue())) {
            throw new AssertionError("Cache did not retain the validator");
        }
    }
}
