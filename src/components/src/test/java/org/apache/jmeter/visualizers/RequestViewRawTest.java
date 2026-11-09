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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.jmeter.gui.util.HttpProtocolDisplay;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RequestViewRawTest {

    @Test
    void rawRequestToolbarFitsTheActualViewWhenSearchInputShrinksToZero() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RequestViewRaw view = new RequestViewRaw();
            view.init();
            view.setDiffButtonVisible(true);
            JPanel panel = view.getPanel();
            panel.setFont(UIManager.getFont("Label.font"));
            JToolBar toolbar = findToolbar(panel);
            assertNull(SwingUtilities.getAncestorOfClass(JScrollPane.class, toolbar),
                    "The toolbar must resize with the view, not sit in a scrolling preferred-width panel");
            JTextField input = (JTextField) toolbar.getComponent(0);
            int actionsWidth = toolbar.getPreferredSize().width - input.getPreferredSize().width;
            for (int extraWidth : new int[]{300, 30, 0, 300}) {
                panel.setSize(actionsWidth + extraWidth, 400);
                layoutTree(panel);
                assertEquals(panel.getWidth(), toolbar.getWidth());
                assertEquals(extraWidth, input.getWidth());
                for (Component action : toolbar.getComponents()) {
                    if (action != input && action.isVisible()) {
                        assertEquals(action.getPreferredSize().width, action.getWidth());
                        assertTrue(action.getX() + action.getWidth() <= panel.getWidth());
                    }
                }
            }
        });
    }

    private static JToolBar findToolbar(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JToolBar toolbar) {
                return toolbar;
            }
            if (child instanceof Container nested) {
                JToolBar toolbar = findToolbar(nested);
                if (toolbar != null) {
                    return toolbar;
                }
            }
        }
        return null;
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    @Test
    public void formatsHttp2RequestWithPseudoHeadersAndLowercaseHeaderNames() throws Exception {
        HttpLikeSampleResult result = new HttpLikeSampleResult();
        result.setURL(URI.create("https://example.invalid:8443/http2?q=a%20b").toURL());
        result.setResponseHeaders("HTTP/2.0 200 OK\nContent-Type: text/plain\n");
        result.setRequestHeaders("""
                User-Agent: Test
                Accept: text/plain
                """);
        result.cookies = "session=abc";

        assertEquals("""
                GET https://example.invalid:8443/http2?q=a%20b HTTP/2
                :authority: example.invalid:8443
                :method: GET
                :path: /http2?q=a%20b
                :scheme: https
                accept: text/plain
                cookie: session=abc
                user-agent: Test
                """, RequestViewRaw.formatRequest(result));
    }

    @Test
    public void preservesConventionalHeaderCasingWithoutHttp2() throws Exception {
        HttpLikeSampleResult result = new HttpLikeSampleResult();
        result.setURL(URI.create("https://example.invalid/http1").toURL());
        result.setResponseHeaders("HTTP/1.1 200 OK\nContent-Type: text/plain\n");
        result.setRequestHeaders("Accept: text/plain\n");
        result.cookies = "session=abc";

        assertEquals("""
                GET https://example.invalid/http1 HTTP/1.1
                Accept: text/plain
                Cookie: session=abc
                """, RequestViewRaw.formatRequest(result));
    }

    @Test
    void preservesWebSocketDisplayScheme() throws Exception {
        for (String scheme : java.util.List.of("ws", "wss")) {
            HttpLikeSampleResult result = new HttpLikeSampleResult() {
                @Override
                public String getUrlAsString() {
                    return scheme + "://example.invalid/chat";
                }
            };
            result.setURL(URI.create("https://example.invalid/chat").toURL());
            result.setProtocolVersion("HTTP/1.1");
            assertEquals("GET " + scheme + "://example.invalid/chat HTTP/1.1\n",
                    RequestViewRaw.formatRequest(result));
        }
    }

    @Test
    void requestActionsFollowSelectionAndClear() throws Exception {
        HttpLikeSampleResult result = new HttpLikeSampleResult();
        result.setURL(URI.create("https://example.invalid/").toURL());
        SwingUtilities.invokeAndWait(() -> {
            RequestViewRaw view = new RequestViewRaw();
            view.init();
            List<JButton> buttons = buttons(view.getPanel());
            JButton replay = buttons.stream().filter(button -> button.getText().equals(
                    JMeterUtils.getResString("view_results_request_replay"))).findFirst().orElseThrow();
            JButton copy = buttons.stream().filter(button -> button.getText().equals(
                    JMeterUtils.getResString("view_results_request_copy_curl"))).findFirst().orElseThrow();
            assertFalse(replay.isEnabled());
            assertFalse(copy.isEnabled());
            view.setSamplerResult(result);
            assertFalse(replay.isEnabled(), "The components module has no HTTP replay provider");
            assertTrue(copy.isEnabled());
            view.setSamplerResult(new SampleResult());
            assertFalse(replay.isEnabled());
            assertFalse(copy.isEnabled());
            view.setSamplerResult(result);
            view.clearData();
            assertFalse(replay.isEnabled());
            assertFalse(copy.isEnabled());
        });
    }

    @Test
    void prettyPrintFormatsOnlyTheBodyAndResetsOnSelection() throws Exception {
        HttpLikeSampleResult result = new HttpLikeSampleResult();
        result.setURL(URI.create("https://example.invalid/").toURL());
        result.setRequestHeaders("content-type: application/json\n");
        result.setContentType("text/html");
        result.body = "{\"value\":{\"nested\":true}}";
        SwingUtilities.invokeAndWait(() -> {
            RequestViewRaw view = new RequestViewRaw();
            view.init();
            JButton pretty = buttons(view.getPanel()).stream().filter(button -> button.getText().equals(
                    JMeterUtils.getResString("view_results_pretty_print"))).findFirst().orElseThrow();
            JSyntaxTextArea text = findTextArea(view.getPanel());
            assertFalse(pretty.isEnabled());
            view.setSamplerResult(result);
            String raw = text.getText();
            assertTrue(pretty.isEnabled());
            pretty.doClick();
            String prefix = raw.substring(0, raw.indexOf("\n\n") + 2);
            assertEquals(prefix + RenderAsJSON.prettyJSON(result.body), text.getText());
            assertEquals("{\"value\":{\"nested\":true}}", result.getQueryString());
            view.setSamplerResult(result);
            assertEquals(raw, text.getText());
            result.body = "";
            view.setSamplerResult(result);
            assertFalse(pretty.isEnabled());
            view.clearData();
            assertFalse(pretty.isEnabled());
            assertEquals("", text.getText());
        });
    }

    @Test
    void prettyPrintXmlPreservesXmlAndPlainTextIsUnchanged() {
        HttpLikeSampleResult result = new HttpLikeSampleResult();
        result.setRequestHeaders("Content-Type: application/xml\n");
        result.setContentType("text/html");
        result.body = "<root><value>hello</value></root>";
        String pretty = RequestViewRaw.formatRequest(result, true);
        assertTrue(pretty.contains("<root>"));
        assertTrue(pretty.contains("<value>hello</value>"));
        assertFalse(pretty.contains("<html>"));
        result.setRequestHeaders("Content-Type: text/plain\n");
        result.body = "plain text";
        assertEquals(RequestViewRaw.formatRequest(result), RequestViewRaw.formatRequest(result, true));
    }

    private static JSyntaxTextArea findTextArea(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JSyntaxTextArea area) {
                return area;
            }
            if (child instanceof Container container) {
                JSyntaxTextArea area = findTextArea(container);
                if (area != null) {
                    return area;
                }
            }
        }
        return null;
    }

    @Test
    void replayDeliversTheCompleteResultOnTheEventThread() throws Exception {
        SampleResult original = new SampleResult();
        original.setSourceTestElementPath(List.of(
                new SampleResult.TestElementPathEntry("sampler", "original", 0)));
        SampleResult response = new SampleResult();
        response.setResponseCode("503");
        response.setSuccessful(false);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<SampleResult> actual = new AtomicReference<>();
        AtomicReference<Boolean> onEventThread = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            RequestViewRaw view = new RequestViewRaw();
            view.init();
            view.setSamplerResult(original);
            try {
                var replay = RequestViewRaw.class.getDeclaredField("selectedReplay");
                replay.setAccessible(true);
                replay.set(view, (Callable<SampleResult>) () -> response);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
            view.setReplayResultConsumer(result -> {
                actual.set(result);
                onEventThread.set(SwingUtilities.isEventDispatchThread());
                delivered.countDown();
            });
            JButton replay = buttons(view.getPanel()).stream().filter(button -> button.getText().equals(
                    JMeterUtils.getResString("view_results_request_replay"))).findFirst().orElseThrow();
            assertTrue(replay.isEnabled());
            replay.doClick();
        });
        assertTrue(delivered.await(10, TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertSame(response, actual.get());
        assertEquals(Boolean.TRUE, onEventThread.get());
        assertEquals(original.getSourceTestElementPath(), response.getSourceTestElementPath());
    }

    @Test
    void canonicalizesRecordedAndReplayedProtocolLabels() {
        for (String[] versions : new String[][] {
                {"http/1.1", "HTTP/1.1"}, {"http/2", "HTTP/2"}, {"http/2.0", "HTTP/2"},
                {"http/3", "HTTP/3"}, {"http/3.0", "HTTP/3"}}) {
            SampleResult result = new SampleResult();
            result.setProtocolVersion(versions[0]);
            assertEquals(versions[1], RequestViewRaw.getHttpProtocolVersion(result));
            result.setProtocolVersion("");
            result.setResponseHeaders(versions[0] + " 200\nX-Test: http/3.0\n");
            assertEquals(versions[1], RequestViewRaw.getHttpProtocolVersion(result));
            RenderAsText renderer = new RenderAsText();
            renderer.setSamplerResult(result);
            renderer.setupTabPane();
            assertEquals(versions[1] + " 200\nX-Test: http/3.0\n", renderer.replayedResponseText());
        }
    }

    @Test
    void responseNormalizationPreservesNonDuplicateStatusHeaders() {
        for (String headers : List.of("HTTP/3 200\n:status: 404\n", "HTTP/1.1 200 OK\n:status: 200\n",
                ":status: 200\n", "HTTP/2 200\nX-Status: 200\n")) {
            assertEquals(headers, HttpProtocolDisplay.responseHeaders(headers));
        }
        assertEquals("HTTP/3 200\r\nX-Test: value\r\n",
                HttpProtocolDisplay.responseHeaders("http/3.0 200\r\n:status: 200\r\nX-Test: value\r\n"));
    }

    @Test
    void brokenReplayProviderDoesNotPreventRequestViewInitialization(@TempDir Path directory) throws Exception {
        Path service = directory.resolve("META-INF/services/" + RequestReplay.class.getName());
        Files.createDirectories(service.getParent());
        Files.writeString(service, "missing.plugin.ReplayProvider\n" + HealthyReplayProvider.class.getName() + "\n");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            RequestViewRaw view = new RequestViewRaw();
            view.init();
            view.setSamplerResult(new SampleResult());
            view.setReplayResultConsumer(result -> { });
            JButton replay = buttons(view.getPanel()).stream().filter(button -> button.getText().equals(
                    JMeterUtils.getResString("view_results_request_replay"))).findFirst().orElseThrow();
            assertTrue(replay.isEnabled(), "Healthy providers must still load after a broken provider");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    public static class HealthyReplayProvider implements RequestReplay {
        @Override
        public Callable<SampleResult> prepare(SampleResult result) {
            return SampleResult::new;
        }
    }

    private static List<JButton> buttons(Container parent) {
        List<JButton> buttons = new ArrayList<>();
        for (var child : parent.getComponents()) {
            if (child instanceof JButton button) {
                buttons.add(button);
            } else if (child instanceof Container container) {
                buttons.addAll(buttons(container));
            }
        }
        return buttons;
    }

    public static class HttpLikeSampleResult extends SampleResult {
        private String body = "";
        private String cookies = ""; // $NON-NLS-1$

        public String getHTTPMethod() {
            return "GET"; // $NON-NLS-1$
        }

        public String getQueryString() {
            return body;
        }

        public String getCookies() {
            return cookies;
        }
    }
}
