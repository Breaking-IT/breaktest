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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.swing.JTabbedPane;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;

public class SamplerResultTabTest {

    @Test
    public void selectionCreatesEscapedExtractorWithRequestedDefaults() {
        String headers = "X-Token: a.b[1]\r\n";
        String body = "url?x=(a+b)*[1].$^|\\end";
        SampleResult sample = sampleResult(SampleResult.TEXT, headers, body);
        var extractor = SamplerResultTab.extractorForSelection(headers,
                headers.length() + 1, headers.length() + 1 + body.length(), body);
        assertEquals(JMeterUtils.getResString("regex_extractor_title"), extractor.getName());
        assertEquals("", extractor.getRefName());
        assertEquals("$1$", extractor.getTemplate());
        assertEquals(1, extractor.getMatchNumber());
        assertTrue(extractor.isEnabled());
        assertTrue(extractor.isFailOnNoMatch());
        assertTrue(extractor.useBody());
        assertEquals("url\\?x\\=\\(a\\+b\\)\\*\\[1\\]\\.\\$\\^\\|\\\\end", extractor.getRegex());
        // No capture group is added; use group zero to verify literal matching.
        extractor.setTemplate("$0$");
        assertEquals(Arrays.asList(body), extractor.extractForTesting(sample));

        var headerExtractor = SamplerResultTab.extractorForSelection(headers, 9, 15, "a.b[1]");
        assertTrue(headerExtractor.useHeaders());
        assertEquals("a\\.b\\[1\\]", headerExtractor.getRegex());
        assertEquals("$1$", headerExtractor.getTemplate());
        headerExtractor.setTemplate("$0$");
        assertEquals(Arrays.asList("a.b[1]"), headerExtractor.extractForTesting(sample));
        sample.setResponseHeaders("X-Token: axb1\r\n");
        assertTrue(headerExtractor.extractForTesting(sample).isEmpty());
        assertTrue(SamplerResultTab.extractorForSelection("", 0, body.length(), body).useBody());
        assertNull(SamplerResultTab.extractorForSelection(headers, 0, headers.length() + 5, "mixed"));
        assertNull(SamplerResultTab.extractorForSelection(headers, 0, 0, ""));
    }

    @Test
    public void selectionCreatesLiteralAssertionForItsResponseSection() {
        String headers = "X-Token: a.b[1]\r\n";
        SampleResult sample = sampleResult(SampleResult.TEXT, headers, "body (value)");
        var headerAssertion = SamplerResultTab.assertionForSelection(headers, 9, 15, "a.b[1]");
        assertTrue(headerAssertion.isTestFieldResponseHeaders());
        assertFalse(headerAssertion.getResult(sample).isFailure());
        sample.setResponseHeaders("X-Token: axb1\r\n");
        assertTrue(headerAssertion.getResult(sample).isFailure());

        var bodyAssertion = SamplerResultTab.assertionForSelection(headers,
                headers.length() + 1, headers.length() + 13, "body (value)");
        assertTrue(bodyAssertion.isTestFieldResponseData());
        assertFalse(bodyAssertion.getResult(sample).isFailure());
        assertNull(SamplerResultTab.assertionForSelection(headers, 0, headers.length() + 5, "mixed"));
        assertNull(SamplerResultTab.assertionForSelection(headers, 0, 0, ""));
    }

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
    public void textRendererDoesNotPopulateHiddenRenderedDocument() {
        RenderAsText renderer = initializedRenderer();
        SampleResult result = sampleResult(SampleResult.TEXT, "HTTP/1.1 200 OK\n", "hello");

        renderer.setSamplerResult(result);
        renderer.setupTabPane();
        selectResponseTab(renderer);
        renderer.renderResult(result);

        assertEquals(0, renderer.results.getDocument().getLength());
        assertEquals("HTTP/1.1 200 OK\n\nhello", renderer.responseDataText());
    }

    @Test
    public void loadingWithheldBodyUsesPlainTextAndAddsSafeLineBreaks() {
        RenderAsText renderer = initializedRenderer();
        String body = "{\"value\":\"" + "x".repeat(10_000_001) + "\"}";
        SampleResult result = sampleResult(SampleResult.TEXT, "HTTP/1.1 200 OK\n", body);
        result.setContentType("application/json");

        renderer.setSamplerResult(result);
        renderer.setupTabPane();
        selectResponseTab(renderer);
        renderer.renderResult(result);

        assertTrue(
                renderer.responseDataText().contains("view_results_body_too_long_single_line"),
                renderer::responseDataText);
        renderer.loadResponseBody();

        assertEquals(SyntaxConstants.SYNTAX_STYLE_NONE, renderer.responseDataSyntaxStyle());
        assertFalse(hasLineLongerThan(renderer.responseDataText(), 100_000));
        String displayedBody = renderer.responseDataText().substring("HTTP/1.1 200 OK\n\n".length());
        assertEquals(body, displayedBody.replace("\n", ""));
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

    @Test
    public void wrappingTableRendererDoesNotInstallKerningDocumentListener() {
        SamplerResultTab.WrappingTableCellRenderer renderer =
                new SamplerResultTab.WrappingTableCellRenderer();

        assertFalse(hasKerningDocumentListener(renderer));
        renderer.updateUI();
        assertFalse(hasKerningDocumentListener(renderer));
    }

    private static boolean hasKerningDocumentListener(SamplerResultTab.WrappingTableCellRenderer renderer) {
        return Arrays.stream(renderer.getPropertyChangeListeners("document"))
                .anyMatch(listener -> listener.getClass().getSimpleName().equals("DisableKerningForLargeTexts"));
    }

    private static boolean hasLineLongerThan(String text, int maxLineLength) {
        return Arrays.stream(text.split("\\R", -1))
                .anyMatch(line -> line.length() > maxLineLength);
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
