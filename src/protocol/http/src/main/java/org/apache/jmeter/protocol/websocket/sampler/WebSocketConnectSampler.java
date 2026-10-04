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

package org.apache.jmeter.protocol.websocket.sampler;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.SamplePackage;

@TestElementMetadata(labelResource = "displayName")
public class WebSocketConnectSampler extends AbstractWebSocketSampler {
    private static final long serialVersionUID = 1L;
    private static final String COOKIE_MANAGER = "WebSocketConnect.cookieManager";
    private static final String HEADER_MANAGER = "WebSocketConnect.headerManager";
    private static final Set<String> TRANSPORT_HEADERS = Set.of("connection", "content-length", "expect", "host", "upgrade");

    @Override
    protected void execute(SampleResult result) throws Exception {
        URI uri = URI.create(getUrl());
        if (!("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("A ws:// or wss:// URL without fragment or user information is required");
        }
        WebSocketSession session = new WebSocketSession(getSessionName(), getCountIncoming(),
                getFailOnDisconnect(), getIgnoreControlFrames(), getTextFilter(), getBinaryFilter(),
                getMaxMessageBytes(), publisher());
        WebSocketSessions sessions = WebSocketSessions.current();
        sessions.add(getSessionName(), session);
        active(session);
        try {
            var client = sessions.client(getSessionName(), session, uri);
            WebSocket.Builder builder = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(getTimeout()));
            // Resolve variable-backed cookies on the virtual user's thread, before
            // the JDK starts the handshake on its transport threads.
            for (Header header : requestHeaders(uri)) {
                builder.header(header.getName(), header.getValue());
            }
            WebSocketHandshakeCookies bridge = (WebSocketHandshakeCookies) client.cookieHandler().orElseThrow();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(getTimeout());
            try (var capture = bridge.begin(uri, getTimeout())) {
                await(builder.buildAsync(uri, session), Math.max(1, deadline - System.nanoTime()),
                        java.util.concurrent.TimeUnit.NANOSECONDS);
                // Import on the owner thread: CookieManager can also publish COOKIE_* variables.
                storeCookies(capture.cookies(), WebSocketHandshakeCookies.httpUri(uri));
            }
            result.setSamplerData(getUrl());
            result.setResponseCode("101");
        } catch (Exception e) {
            sessions.remove(getSessionName(), session);
            Throwable cause = e;
            while ((cause instanceof java.util.concurrent.ExecutionException
                    || cause instanceof java.util.concurrent.CompletionException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof java.net.http.WebSocketHandshakeException handshake) {
                var response = handshake.getResponse();
                storeCookies(response.headers().allValues("Set-Cookie"), response.uri());
            }
            throw e;
        }
    }

    private void storeCookies(List<String> headers, URI uri) throws MalformedURLException {
        if (getProperty(COOKIE_MANAGER).getObjectValue() instanceof CookieManager cookies) {
            for (String header : headers) {
                cookies.addCookieFromHeader(header, uri.toURL());
            }
        }
    }

    @Override
    public boolean applies(ConfigTestElement configElement) {
        return configElement instanceof CookieManager || configElement instanceof HeaderManager;
    }

    @Override
    public void addTestElement(TestElement element) {
        if (element instanceof CookieManager) {
            setProperty(new TestElementProperty(COOKIE_MANAGER, element));
        } else if (element instanceof HeaderManager incoming) {
            Object existing = getProperty(HEADER_MANAGER).getObjectValue();
            HeaderManager merged = existing instanceof HeaderManager manager ? manager.merge(incoming) : incoming;
            setProperty(new TestElementProperty(HEADER_MANAGER, merged));
        } else {
            super.addTestElement(element);
        }
    }

    public List<Header> getHeaders() {
        List<Header> headers = new ArrayList<>();
        if (getProperty("headers") instanceof CollectionProperty collection) {
            for (JMeterProperty property : collection) {
                headers.add((Header) property.getObjectValue());
            }
        }
        return headers;
    }

    public void setHeaders(List<Header> headers) {
        if (headers == null || headers.isEmpty()) {
            removeProperty("headers");
        } else {
            setProperty(new CollectionProperty("headers", headers));
        }
    }

    List<Header> requestHeaders(URI uri) throws MalformedURLException {
        HeaderManager effective = new HeaderManager();
        getHeaders().forEach(effective::add);
        if (getProperty(HEADER_MANAGER).getObjectValue() instanceof HeaderManager scoped) {
            HeaderManager compatible = new HeaderManager();
            for (int i = 0; i < scoped.size(); i++) {
                Header header = scoped.get(i);
                String name = header.getName().toLowerCase(Locale.ROOT);
                if (!TRANSPORT_HEADERS.contains(name) && !name.startsWith("sec-websocket-")) {
                    compatible.add(header);
                }
            }
            effective = effective.merge(compatible);
        }
        String cookies = cookieHeader(uri);
        List<Header> headers = new ArrayList<>();
        for (int i = 0; i < effective.size(); i++) {
            Header header = effective.get(i);
            String name = header.getName();
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (TRANSPORT_HEADERS.contains(lowerName) || lowerName.startsWith("sec-websocket-")) {
                throw new IllegalArgumentException("WebSocket transport controls header: " + name);
            }
            // Match the HTTP sampler: Cookie Manager replaces an explicit Cookie
            // header when the manager has applicable cookies for this destination.
            if (!"cookie".equals(lowerName) || cookies == null || cookies.isEmpty()) {
                headers.add(new Header(name, header.getValue()));
            }
        }
        if (cookies != null && !cookies.isEmpty()) {
            headers.add(new Header("Cookie", cookies));
        }
        return headers;
    }

    String cookieHeader(URI uri) throws MalformedURLException {
        Object manager = getProperty(COOKIE_MANAGER).getObjectValue();
        if (!(manager instanceof CookieManager cookies)) {
            return null;
        }
        // CookieManager understands HTTP URLs. Change only the scheme, retaining
        // the raw authority/path/query so escaped paths and IPv6 are not rewritten.
        String scheme = "wss".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
        URI httpUri = URI.create(scheme + uri.toString().substring(uri.getScheme().length()));
        return cookies.getCookieHeaderForURL(httpUri.toURL());
    }

    private static Consumer<SampleResult> publisher() {
        JMeterContext context = JMeterContextService.getContext();
        SamplePackage pack = (SamplePackage) context.getVariables().getObject(JMeterThread.PACKAGE_OBJECT);
        List<SampleListener> listeners = pack == null ? List.of() : pack.getSampleListeners().stream()
                .map(listener -> listener instanceof NoThreadClone ? listener
                        : (SampleListener) ((TestElement) listener).clone()).toList();
        WebSocketSessions registry = WebSocketSessions.current();
        JMeterContext callbackContext = JMeterContextService.createContext();
        callbackContext.setVariables(context.getVariables());
        callbackContext.setThread(context.getThread());
        callbackContext.setThreadNum(context.getThreadNum());
        callbackContext.setThreadGroup(context.getThreadGroup());
        callbackContext.setEngine(context.getEngine());
        callbackContext.setSamplingStarted(context.isSamplingStarted());
        AbstractThreadGroup threadGroup = context.getThreadGroup();
        String group = threadGroup == null ? "" : threadGroup.getName();
        List<SampleResult.TestElementPathEntry> sourcePath = pack == null ? List.of() : pack.getSourceTestElementPath();
        String threadName = context.getThread() == null ? Thread.currentThread().getName() : context.getThread().getThreadName();
        JMeterVariables snapshot = new JMeterVariables();
        for (int i = 0; i < SampleEvent.getVarCount(); i++) {
            String key = SampleEvent.getVarName(i);
            String value = context.getVariables().get(key);
            if (value != null) {
                snapshot.put(key, value);
            }
        }
        ListenerNotifier notifier = new ListenerNotifier();
        return result -> {
            if (callbackContext.getThread() != null && !callbackContext.getThread().isRunning()) {
                return;
            }
            result.setThreadName(threadName);
            result.setAllThreads(JMeterContextService.getNumberOfThreads());
            result.setGroupThreads(threadGroup == null ? 0 : threadGroup.getNumberOfThreads());
            result.setSourceTestElementPath(sourcePath);
            registry.notifyListeners(() -> {
                JMeterContext previous = JMeterContextService.getContext();
                callbackContext.setPreviousResult(result);
                JMeterContextService.replaceContext(callbackContext);
                try {
                    notifier.notifyListeners(new SampleEvent(result, group, snapshot), listeners);
                } finally {
                    JMeterContextService.replaceContext(previous);
                }
            });
        };
    }

    public String getUrl() {
        return getPropertyAsString("url", "ws://localhost:8080/");
    }

    public void setUrl(String value) {
        setProperty("url", value);
    }

    public boolean getCountIncoming() {
        return getPropertyAsBoolean("countIncoming", true);
    }

    public void setCountIncoming(boolean value) {
        setProperty("countIncoming", value);
    }

    public boolean getFailOnDisconnect() {
        return getPropertyAsBoolean("failOnDisconnect", true);
    }

    public void setFailOnDisconnect(boolean value) {
        setProperty("failOnDisconnect", value);
    }

    public boolean getIgnoreControlFrames() {
        return getPropertyAsBoolean("ignoreControlFrames", true);
    }

    public void setIgnoreControlFrames(boolean value) {
        setProperty("ignoreControlFrames", value);
    }

    public String getTextFilter() {
        return getPropertyAsString("textFilter");
    }

    public void setTextFilter(String value) {
        setProperty("textFilter", value);
    }

    public String getBinaryFilter() {
        return getPropertyAsString("binaryFilter");
    }

    public void setBinaryFilter(String value) {
        setProperty("binaryFilter", value);
    }

    public int getMaxMessageBytes() {
        return getPropertyAsInt("maxMessageBytes", 1048576);
    }

    public void setMaxMessageBytes(int value) {
        setProperty("maxMessageBytes", value);
    }
}
