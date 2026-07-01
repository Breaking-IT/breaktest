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

package org.apache.jmeter.visualizers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

public class ViewResultsFullVisualizerTest {

    @Test
    public void visualTreeNeedsSourcePathButNotVariableSnapshots() {
        ViewResultsFullVisualizer visualizer = new ViewResultsFullVisualizer();

        assertTrue(visualizer.needsSampleResultSourcePath());
        assertFalse(visualizer.needsSampleResultVariables());
        assertFalse(visualizer.needsSampleResultMetadata());
    }

    @Test
    public void responseTimeoutStackTraceIsCompactedForDisplay() throws Exception {
        SampleResult result = new SampleResult();
        result.setDataType(SampleResult.TEXT);
        result.setDataEncoding(StandardCharsets.UTF_8.name());
        result.setURL(URI.create("https://whitehouse.gov/").toURL());
        result.setResponseData("""
                java.io.InterruptedIOException: Timeout blocked waiting for input (30 MILLISECONDS)
                \tat org.apache.hc.core5.http.nio.support.classic.ClassicToAsyncResponseConsumer.blockWaiting(ClassicToAsyncResponseConsumer.java:119)
                \tat org.apache.hc.client5.http.impl.compat.ClassicToAsyncAdaptor.doExecute(ClassicToAsyncAdaptor.java:85)
                """, StandardCharsets.UTF_8.name());

        assertEquals("Response timeout after 30 ms waiting for response: "
                + "local IP unavailable -> https://whitehouse.gov:443",
                ViewResultsFullVisualizer.getResponseAsString(result));
    }

    @Test
    public void nonTimeoutStackTraceIsNotCompactedForDisplay() {
        SampleResult result = new SampleResult();
        result.setDataType(SampleResult.TEXT);
        result.setDataEncoding(StandardCharsets.UTF_8.name());
        String stackTrace = """
                java.lang.IllegalStateException: unexpected
                \tat example.Test.run(Test.java:1)
                """;
        result.setResponseData(stackTrace, StandardCharsets.UTF_8.name());

        assertEquals(stackTrace, ViewResultsFullVisualizer.getResponseAsString(result));
    }
}
