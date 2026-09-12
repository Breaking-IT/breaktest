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

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.BasicHttpResponse;

/** Run with this class as Premain-Class in an agent jar and java.lang opened for String storage. */
public class HeaderRetentionProbe {
    private static Instrumentation instrumentation;

    public static void premain(String args, Instrumentation value) {
        instrumentation = value;
    }

    private static Object field(Object object, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static long size(Object object, Set<Object> seen) throws Exception {
        if (object == null || !seen.add(object)) {
            return 0;
        }
        long bytes = instrumentation.getObjectSize(object);
        Class<?> type = object.getClass();
        if (type.isArray()) {
            if (!type.getComponentType().isPrimitive()) {
                for (int i = 0; i < Array.getLength(object); i++) {
                    bytes += size(Array.get(object, i), seen);
                }
            }
        } else {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                    field.setAccessible(true);
                    bytes += size(field.get(object), seen);
                }
            }
        }
        return bytes;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            System.err.println("baselineResultShallowBytes=" + instrumentation.getObjectSize(new HTTPSampleResult()));
            return;
        }
        System.out.println("totalBytes,protocol,deferred,readHeaders,headerGraphBytes,resultShallowBytes");
        for (int total : new int[] {400, 1600, 8192}) {
            for (String protocol : new String[] {"h1", "h2"}) {
                for (boolean deferred : new boolean[] {false, true}) {
                    for (boolean read : new boolean[] {false, true}) {
                        HeaderCaptureBenchmark fixture = new HeaderCaptureBenchmark();
                        fixture.totalBytes = total;
                        fixture.protocol = protocol;
                        fixture.deferred = deferred;
                        fixture.readers = "unread";
                        fixture.setup();
                        HTTPSampleResult result = new HTTPSampleResult();
                        HTTPHC5Impl.captureRequestHeaders(result, (BasicHttpRequest)
                                field(fixture, HeaderCaptureBenchmark.class, "request"), deferred);
                        BasicHttpResponse response = (BasicHttpResponse)
                                field(fixture, HeaderCaptureBenchmark.class, "response");
                        if ("h1".equals(protocol)) {
                            HTTPHC5Impl.captureResponseHeaders(result, response, deferred);
                        } else {
                            HTTPHC5H2Impl.captureResponseHeaders(result, response, deferred);
                        }
                        if (read) {
                            result.getRequestHeaders();
                            result.getResponseHeaders();
                        }
                        Object requestStorage = deferred ? field(result, HTTPSampleResult.class, "deferredRequestHeaders")
                                : result.getRequestHeaders();
                        Object responseStorage = deferred ? field(result, HTTPSampleResult.class, "deferredResponseHeaders")
                                : result.getResponseHeaders();
                        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                        long bytes = size(requestStorage, seen) + size(responseStorage, seen);
                        System.out.println(total + "," + protocol + "," + deferred + "," + read + "," + bytes
                                + "," + instrumentation.getObjectSize(result));
                    }
                }
            }
        }
    }
}
