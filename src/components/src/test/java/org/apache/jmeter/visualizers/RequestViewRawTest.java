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

import java.awt.Container;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;

public class RequestViewRawTest {

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
            assertTrue(replay.isEnabled());
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
