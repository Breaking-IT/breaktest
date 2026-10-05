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

import org.apache.jmeter.config.ClientCertificateConfig;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.Replaceable;
import org.apache.jmeter.gui.ReplaceableField;
import org.apache.jmeter.gui.RowField;
import org.apache.jmeter.gui.SearchArea;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HttpProxyConfiguration;
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
import org.apache.jorphan.util.JOrphanUtils;

@GUIMenuSortOrder(101)
@TestElementMetadata(labelResource = "displayName")
public class WebSocketConnectSampler extends AbstractWebSocketSampler implements org.apache.jmeter.samplers.ChildControllerSampler, Replaceable {
    private static final long serialVersionUID = 1L;
    public static final String RECONNECT = "Close and reconnect";
    public static final String REUSE = "Reuse if connected";
    public static final String FAIL = "Fail if exists";

    public String getExistingSessionAction() {
        return getPropertyAsString("existingSessionAction", RECONNECT);
    }

    public void setExistingSessionAction(String value) {
        setProperty("existingSessionAction", value);
    }

    private static final String COOKIE_MANAGER = "WebSocketConnect.cookieManager";
    private static final String HEADER_MANAGER = "WebSocketConnect.headerManager";
    private static final Set<String> TRANSPORT_HEADERS = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private transient List<WebSocketMatchController> matchControllers = new java.util.ArrayList<>();

    @Override
    public boolean acceptsChildController(org.apache.jmeter.control.Controller controller) {
        return controller instanceof WebSocketMatchController;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // Equal properties do not make two tree nodes the same handler.
    public void addChildController(org.apache.jmeter.control.Controller controller) {
        if (!(controller instanceof WebSocketMatchController match)) {
            throw new IllegalArgumentException("WebSocket Connect only accepts WebSocket Match controllers");
        }
        if (matchControllers.stream().noneMatch(existing -> existing == match)) {
            matchControllers.add(match);
        }
    }

    @Override
    public List<org.apache.jmeter.control.Controller> createDefaultChildControllers() {
        WebSocketMatchController match = new WebSocketMatchController();
        match.setName("WebSocket Match");
        match.setProperty(TestElement.GUI_CLASS, org.apache.jmeter.testbeans.gui.TestBeanGUI.class.getName());
        match.setProperty(TestElement.TEST_CLASS, WebSocketMatchController.class.getName());
        return List.of(match);
    }

    @Override
    public Object clone() {
        WebSocketConnectSampler copy = (WebSocketConnectSampler) super.clone();
        copy.matchControllers = new java.util.ArrayList<>(matchControllers);
        return copy;
    }

    @Override
    protected SampleResult createSampleResult() {
        return new HTTPSampleResult();
    }

    @Override
    protected void execute(SampleResult result) throws Exception {
        URI uri = URI.create(getUrl());
        if (!("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("A ws:// or wss:// URL without fragment or user information is required");
        }
        Consumer<SampleResult> publish = publisher();
        WebSocketMessageHandlers handlers = new WebSocketMessageHandlers(matchControllers, getSessionName(), publish);
        WebSocketSession session = new WebSocketSession(getSessionName(), getCountIncoming(),
                getFailOnDisconnect(), getIgnoreControlFrames(), getTextFilter(), getBinaryFilter(),
                getMaxMessageBytes(), publish);
        session.setHandlers(handlers);
        WebSocketSessions sessions = WebSocketSessions.current();
        if (!sessions.connect(getSessionName(), session, getExistingSessionAction())) {
            result.setSamplerData("Reused WebSocket session: " + getSessionName());
            result.setResponseMessage("Existing WebSocket connection reused; no handshake performed");
            return;
        }
        HTTPSampleResult httpResult = (HTTPSampleResult) result;
        httpResult.setHTTPMethod("GET");
        httpResult.setURL(WebSocketHandshakeCookies.httpUri(uri).toURL());
        httpResult.setDisplayUrl(uri.toString());
        httpResult.setProtocolVersion("HTTP/1.1");
        httpResult.setSamplerData("GET " + httpResult.getUrlAsString() + "\n");
        active(session);
        try {
            handlers.start();
            var route = HttpProxyConfiguration.resolve(this, uri);
            var client = sessions.client(getSessionName(), session, uri, route,
                    (ClientCertificateConfig) getProperty("WebSocket.client_certificate").getObjectValue());
            WebSocket.Builder builder = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(getTimeout()));
            // Resolve variable-backed cookies on the virtual user's thread, before
            // the JDK starts the handshake on its transport threads.
            List<String> subprotocols = new ArrayList<>();
            for (Header header : requestHeaders(uri)) {
                if ("Sec-WebSocket-Protocol".equalsIgnoreCase(header.getName())) {
                    for (String protocol : header.getValue().split(",", -1)) {
                        subprotocols.add(protocol.trim());
                    }
                } else {
                    builder.header(header.getName(), header.getValue());
                }
            }
            if (!subprotocols.isEmpty()) {
                builder.subprotocols(subprotocols.get(0), subprotocols.subList(1, subprotocols.size()).toArray(String[]::new));
            }
            WebSocketHandshakeCookies bridge = (WebSocketHandshakeCookies) client.cookieHandler().orElseThrow();
            try (var capture = bridge.begin(uri)) {
                try {
                    await(capture.start(() -> builder.buildAsync(uri, session)));
                    // Import on the owner thread: CookieManager can also publish COOKIE_* variables.
                    storeCookies(capture.cookies(), WebSocketHandshakeCookies.httpUri(uri));
                } finally {
                    // Copy before closing the capture, including the request on a rejected handshake.
                    httpResult.setRequestHeaders(capture.requestHeaders());
                    if (!capture.responseHeaders().isEmpty()) {
                        httpResult.setResponseHeaders("HTTP/1.1 101 Switching Protocols\n" + capture.responseHeaders());
                    }
                }
            }
            result.setResponseCode("101");
            result.setResponseMessage("Switching Protocols");
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
        return configElement instanceof CookieManager || configElement instanceof HeaderManager
                || isHttpDefaults(configElement);
    }

    private static boolean isHttpDefaults(TestElement element) {
        return "org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui"
                .equals(element.getPropertyAsString(TestElement.GUI_CLASS));
    }

    @Override
    public void addTestElement(TestElement element) {
        if (element instanceof ClientCertificateConfig config) {
            if (!config.isInherit() && getProperty("WebSocket.client_certificate").getObjectValue() == null) {
                setProperty(new TestElementProperty("WebSocket.client_certificate", config));
            }
        } else if (element instanceof CookieManager) {
            setProperty(new TestElementProperty(COOKIE_MANAGER, element));
        } else if (element instanceof HeaderManager incoming) {
            Object existing = getProperty(HEADER_MANAGER).getObjectValue();
            HeaderManager merged = existing instanceof HeaderManager manager ? manager.merge(incoming) : incoming;
            setProperty(new TestElementProperty(HEADER_MANAGER, merged));
        } else if (isHttpDefaults(element)) {
            HttpProxyConfiguration.merge(this, element);
        } else {
            super.addTestElement(element);
        }
    }

    @Override
    public List<ReplaceableField> getReplaceableFields() {
        List<ReplaceableField> fields = new ArrayList<>();
        fields.add(new ReplaceableField("URL", this::getUrl, this::setUrl, SearchArea.PATH));
        for (Header header : getHeaders()) {
            fields.add(new ReplaceableField("Header name", header::getName, header::setName, SearchArea.HEADERS, RowField.NAME));
            fields.add(new ReplaceableField("Header value", header::getValue, header::setValue, SearchArea.HEADERS, RowField.VALUE));
        }
        return fields;
    }

    @Override
    public int replace(String regex, String replaceBy, boolean caseSensitive) throws Exception {
        int totalReplaced = 0;
        for (ReplaceableField field : getReplaceableFields()) {
            totalReplaced += JOrphanUtils.replaceValue(
                    regex, replaceBy, caseSensitive, field.value(), field::setValue);
        }
        return totalReplaced;
    }

    @Override
    public int replaceLiteral(String literal, String replaceBy) {
        int totalReplaced = JOrphanUtils.replaceLiteralValue(literal, replaceBy, true, getUrl(), this::setUrl);
        // Correlation replaces request values, preserving header names and existing variables.
        for (Header header : getHeaders()) {
            totalReplaced += JOrphanUtils.replaceLiteralValue(
                    literal, replaceBy, true, header.getValue(), header::setValue);
        }
        return totalReplaced;
    }

    @Override
    protected SearchArea searchAreaForProperty(String propertyName) {
        return switch (propertyName) {
            case "url" -> SearchArea.PATH;
            case "headers" -> SearchArea.HEADERS;
            default -> super.searchAreaForProperty(propertyName);
        };
    }

    @Override
    public List<String> getSearchableTokens() {
        List<String> tokens = super.getSearchableTokens();
        addHeaderSearchTokens(tokens);
        return tokens;
    }

    @Override
    public List<String> getSearchableTokens(Set<SearchArea> areas) {
        List<String> tokens = super.getSearchableTokens(areas);
        if (areas.size() != SearchArea.values().length
                && areas.contains(SearchArea.HEADERS)) {
            addHeaderSearchTokens(tokens);
        }
        return tokens;
    }

    private void addHeaderSearchTokens(List<String> tokens) {
        for (Header header : getHeaders()) {
            tokens.add(header.getName());
            tokens.add(header.getValue());
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
                if (!TRANSPORT_HEADERS.contains(name) && (!name.startsWith("sec-websocket-") || "sec-websocket-protocol".equals(name))) {
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
            if (TRANSPORT_HEADERS.contains(lowerName) || (lowerName.startsWith("sec-websocket-") && !"sec-websocket-protocol".equals(lowerName))) {
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
