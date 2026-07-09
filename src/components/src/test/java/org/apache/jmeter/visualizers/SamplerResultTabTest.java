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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.swing.JTabbedPane;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;

public class SamplerResultTabTest {

    @Test
    public void clearDataBeforeInitDoesNotThrow() {
        RenderAsText renderer = new RenderAsText();

        assertDoesNotThrow(renderer::clearData);
    }

    @Test
    public void setupTabPaneInitializesRendererWhenInitWasNotCalled() {
        RenderAsText renderer = new RenderAsText();
        renderer.setRightSide(new JTabbedPane());
        SampleResult result = sampleResult(SampleResult.TEXT, "HTTP/1.1 200 OK\n", "hello");

        renderer.setSamplerResult(result);

        assertDoesNotThrow(renderer::setupTabPane);
        assertEquals("HTTP/1.1 200 OK\n\nhello", renderer.replayedResponseText());
    }

    @Test
    public void htmlRendererCanRenderBeforeExplicitInit() {
        RenderAsHTML renderer = new RenderAsHTML();
        renderer.setRightSide(new JTabbedPane());
        SampleResult result = sampleResult(SampleResult.TEXT, "HTTP/1.1 200 OK\n",
                "<html><body>hello</body></html>");

        assertDoesNotThrow(() -> renderer.renderResult(result));
    }

    @Test
    public void htmlRendererStripsHttpHeadersStoredInResponseBody() {
        SampleResult result = sampleResult(SampleResult.TEXT, "",
                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html><body>hello</body></html>");

        assertEquals("<html><body>hello</body></html>", RenderAsHTML.htmlBodyFrom(result));
    }

    @Test
    public void htmlRendererUsesRenderedResponseView() {
        RenderAsHTML renderer = initializedHtmlRenderer();
        SampleResult result = sampleResult(SampleResult.TEXT, "HTTP/1.1 200 OK\n",
                "<html><body>hello</body></html>");

        renderer.setSamplerResult(result);
        renderer.setupTabPane();
        selectResponseTab(renderer);
        renderer.renderResult(result);
        renderer.showPreferredResponseView();

        assertTrue(renderer.isRenderedResponseViewVisible());
    }

    @Test
    public void textRendererUsesRawResponseView() {
        RenderAsText renderer = initializedRenderer();
        SampleResult result = sampleResult(SampleResult.TEXT, "HTTP/1.1 200 OK\n", "hello");

        renderer.setSamplerResult(result);
        renderer.setupTabPane();
        selectResponseTab(renderer);
        renderer.renderResult(result);
        renderer.showPreferredResponseView();

        assertFalse(renderer.isRenderedResponseViewVisible());
    }

    @Test
    public void initCanBeCalledTwiceWithoutDuplicatingTabs() {
        RenderAsText renderer = new RenderAsText();
        JTabbedPane rightSide = new JTabbedPane();
        renderer.setRightSide(rightSide);

        renderer.init();
        renderer.init();

        assertEquals(1, rightSide.getTabCount());
    }

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

    @Test
    public void samplerResultTabShowsUrlWhenSampleHasUrl() throws Exception {
        RenderAsText renderer = initializedRenderer();
        SampleResult result = sampleResult(SampleResult.TEXT, "HTTP/1.1 200 OK\n", "hello");
        result.setURL(URI.create("https://example.invalid/orders?id=1").toURL());

        renderer.setSamplerResult(result);
        renderer.setupTabPane();
        renderer.rightSide.setSelectedIndex(0);
        renderer.renderSelectedTab();

        assertTrue(renderer.samplerResultText().contains("https://example.invalid/orders?id=1"));
    }

    private static RenderAsText initializedRenderer() {
        RenderAsText renderer = new RenderAsText();
        renderer.setRightSide(new JTabbedPane());
        renderer.init();
        return renderer;
    }

    private static RenderAsHTML initializedHtmlRenderer() {
        RenderAsHTML renderer = new RenderAsHTML();
        renderer.setRightSide(new JTabbedPane());
        renderer.init();
        return renderer;
    }

    private static void selectResponseTab(SamplerResultTab renderer) {
        int responseTab = renderer.rightSide.indexOfTab(JMeterUtils.getResString("view_results_tab_response"));
        renderer.rightSide.setSelectedIndex(responseTab);
    }

    private static SampleResult sampleResult(String dataType, String headers, String body) {
        SampleResult result = new SampleResult();
        result.setDataType(dataType);
        result.setResponseHeaders(headers);
        result.setResponseData(body, StandardCharsets.UTF_8.name());
        return result;
    }

}
