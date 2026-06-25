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

import java.nio.charset.StandardCharsets;

import javax.swing.JTabbedPane;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

public class SamplerResultTabTest {

    @Test
    public void replayedResponseDiffTextIncludesBodyBeforeResponseTabRenders() {
        RenderAsText renderer = initializedRenderer();
        SampleResult result = sampleResult(SampleResult.TEXT, "HTTP/1.1 200 OK\nContent-Type: text/plain\n", "hello");

        renderer.setSamplerResult(result);
        renderer.setupTabPane();

        assertEquals("HTTP/1.1 200 OK\nContent-Type: text/plain\n\nhello", renderer.replayedResponseText());
    }

    @Test
    public void replayedResponseDiffTextOmitsBinaryBodyBeforeResponseTabRenders() {
        RenderAsText renderer = initializedRenderer();
        SampleResult result = sampleResult(SampleResult.BINARY, "HTTP/1.1 200 OK\nContent-Type: image/png\n", "PNG");

        renderer.setSamplerResult(result);
        renderer.setupTabPane();

        assertEquals("HTTP/1.1 200 OK\nContent-Type: image/png\n", renderer.replayedResponseText());
    }

    private static RenderAsText initializedRenderer() {
        RenderAsText renderer = new RenderAsText();
        renderer.setRightSide(new JTabbedPane());
        renderer.init();
        return renderer;
    }

    private static SampleResult sampleResult(String dataType, String headers, String body) {
        SampleResult result = new SampleResult();
        result.setDataType(dataType);
        result.setResponseHeaders(headers);
        result.setResponseData(body, StandardCharsets.UTF_8.name());
        return result;
    }
}
