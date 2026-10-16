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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.net.URI;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

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

    public static class HttpLikeSampleResult extends SampleResult {
        private String cookies = ""; // $NON-NLS-1$

        public String getHTTPMethod() {
            return "GET"; // $NON-NLS-1$
        }

        public String getQueryString() {
            return ""; // $NON-NLS-1$
        }

        public String getCookies() {
            return cookies;
        }
    }
}
