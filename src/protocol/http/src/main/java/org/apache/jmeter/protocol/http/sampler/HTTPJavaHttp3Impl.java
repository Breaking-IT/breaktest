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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.JsseSSLManager;
import org.apache.jmeter.util.SSLManager;
import org.apache.jorphan.io.CountingInputStream;
import org.apache.jorphan.util.StringUtilities;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP/3 sampler implementation using the JDK {@code java.net.http.HttpClient}.
 * <p>
 * <b>Beta:</b> HTTP/3 sampling is a beta feature. The underlying JDK QUIC stack shipped
 * with Java 26 (JEP 517) and has not yet accumulated long-run, high-concurrency mileage.
 * <p>
 * Requires a Java 26+ runtime (JEP 517); {@link HTTPSamplerFactory} only selects this
 * implementation when {@link Http3RuntimeSupport#isHttp3Supported()} is true, and falls
 * back to the HTTP/2 implementation otherwise.
 * <p>
 * For explicit HTTP/3 samplers, requests are sent HTTP/3-only by default
 * ({@code Http3DiscoveryMode.HTTP_3_URI_ONLY}), mirroring the "force HTTP/2" semantics of
 * the HTTP/2 protocol choice; {@code httpsampler.http3.force_http3=false} relaxes this to
 * {@code Http3DiscoveryMode.ANY} (attempt HTTP/3 directly, fall back to TCP). When
 * preferred for default-protocol samplers, {@code Http3DiscoveryMode.ALT_SVC} is used:
 * browser-like TCP first, upgrading to HTTP/3 once the server advertises it.
 * <p>
 * Known limitations compared to the HttpClient 5 implementations:
 * <ul>
 * <li>no proxy support (requests fail fast when a proxy is configured rather than silently
 * bypassing it)</li>
 * <li>no multipart/form-data uploads</li>
 * <li>authentication is limited to preemptive Basic</li>
 * <li>no Cache Manager integration</li>
 * <li>when the JMeter SSL manager context is rejected by the JDK QUIC stack (Java 26
 * requires the built-in trust manager), the default JVM TLS context is used instead:
 * keystore client certificates and lenient certificate trust then do not apply</li>
 * <li>connect time is not reported separately; sent/received byte counts are
 * application-layer estimates, not QUIC wire bytes</li>
 * <li>keep-alive flags are ignored (connection reuse is inherent to HTTP/3)</li>
 * </ul>
 */
final class HTTPJavaHttp3Impl extends HTTPHCAbstractImpl {

    private static final Logger log = LoggerFactory.getLogger(HTTPJavaHttp3Impl.class);

    /**
     * When true (default), explicit HTTP/3 samplers send requests HTTP/3-only and fail if
     * the server does not answer over HTTP/3. When false, HTTP/3 is attempted directly
     * with fallback to HTTP/2 or HTTP/1.1 over TCP.
     */
    private static final boolean FORCE_HTTP3 =
            JMeterUtils.getPropDefault("httpsampler.http3.force_http3", true); //$NON-NLS-1$

    /**
     * Headers the JDK HttpClient refuses to set on a request, plus hop-by-hop headers
     * that are meaningless for HTTP/3 (the HTTP/2 implementation strips the same set).
     */
    private static final Set<String> DISALLOWED_HEADERS = Set.of(
            "connection", //$NON-NLS-1$
            "content-length", //$NON-NLS-1$
            "expect", //$NON-NLS-1$
            "host", //$NON-NLS-1$
            "upgrade", //$NON-NLS-1$
            "keep-alive", //$NON-NLS-1$
            "proxy-connection", //$NON-NLS-1$
            "transfer-encoding", //$NON-NLS-1$
            "te"); //$NON-NLS-1$

    private static final ConcurrentMap<Object, Map<Http3ClientKey, HttpClient>>
            HTTPCLIENTS_CACHE_PER_JMETER_THREAD = new ConcurrentHashMap<>();

    private static final java.util.concurrent.atomic.AtomicBoolean SSL_CONTEXT_FALLBACK_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private volatile @Nullable CompletableFuture<HttpResponse<InputStream>> currentCall;

    /** How the client should establish HTTP/3 exchanges. */
    enum Http3Discovery {
        /** HTTP/3-only ({@code HTTP_3_URI_ONLY}); fails when the server has no HTTP/3. */
        HTTP3_ONLY,
        /** Try HTTP/3 directly, racing a TCP connection as fallback ({@code ANY}). */
        PREFER_HTTP3,
        /**
         * Browser-like: first request per origin over TCP (HTTP/2 or HTTP/1.1), later
         * requests upgrade to HTTP/3 once the server advertises it ({@code ALT_SVC}).
         */
        ALT_SVC_UPGRADE
    }

    private final Http3Discovery discovery;

    /** Creates the implementation for an explicit HTTP/3 protocol selection. */
    HTTPJavaHttp3Impl(HTTPSamplerBase testElement) {
        this(testElement, FORCE_HTTP3 ? Http3Discovery.HTTP3_ONLY : Http3Discovery.PREFER_HTTP3);
    }

    HTTPJavaHttp3Impl(HTTPSamplerBase testElement, Http3Discovery discovery) {
        super(testElement);
        this.discovery = discovery;
    }

    @Override
    protected HTTPSampleResult sample(URL url, String method, boolean areFollowingRedirect, int frameDepth) {
        log.debug("Start HTTP/3 sample {} method {} followingRedirect {} depth {}",
                url, method, areFollowingRedirect, frameDepth);
        HTTPSampleResult res = new HTTPSampleResult();
        configureSampleLabel(res, url);
        res.setHTTPMethod(method);
        res.setURL(url);

        HttpClient client;
        HttpRequest request;
        long requestBodyBytes;
        try {
            checkUnsupportedConfiguration(url);
            client = setupClient();
            RequestData requestData = createRequest(url, method, areFollowingRedirect, res);
            request = requestData.request();
            requestBodyBytes = requestData.bodyBytes();
            res.setRequestHeaders(formatRequestHeaders(request));
        } catch (Exception e) {
            res.sampleStart();
            res.sampleEnd();
            return errorResult(e, res);
        }

        res.sampleStart();
        try {
            CompletableFuture<HttpResponse<InputStream>> call =
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            currentCall = call;
            HttpResponse<InputStream> response = call.get();
            fillSampleResult(res, request, response, requestBodyBytes);
            return resultProcessing(areFollowingRedirect, frameDepth, res);
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (HTTPSamplerBase.isExpectedTimeout(cause)) {
                log.debug("HTTP/3 sample timed out: {}", cause.toString());
            } else {
                log.debug("HTTP/3 sample failed", cause);
            }
            if (res.getEndTime() == 0) {
                res.sampleEnd();
            }
            return errorResult(cause, res);
        } finally {
            currentCall = null;
        }
    }

    /**
     * Rejects sampler configurations this implementation cannot honor, rather than
     * silently ignoring them.
     */
    private void checkUnsupportedConfiguration(URL url) {
        if (isStaticProxy(url.getHost()) || isDynamicProxy(getProxyHost(), getProxyPortInt())) {
            throw new IllegalStateException(
                    "The HTTP/3 implementation does not support proxies. "
                    + "Remove the proxy configuration or use HTTP/2 for this sampler.");
        }
        if (getUseMultipart()) {
            throw new IllegalStateException(
                    "The HTTP/3 implementation does not support multipart/form-data uploads. "
                    + "Use HTTP/2 for this sampler.");
        }
    }

    private HttpClient setupClient() throws Exception {
        Http3ClientKey key = new Http3ClientKey(
                getConnectTimeout(),
                getAutoRedirects(),
                getIpSourceAddress());
        Map<Http3ClientKey, HttpClient> clients = HTTPCLIENTS_CACHE_PER_JMETER_THREAD
                .computeIfAbsent(getJMeterThreadCacheKey(), ignored -> new HashMap<>(3));
        synchronized (clients) {
            HttpClient client = clients.get(key);
            if (client != null) {
                return client;
            }
            try {
                client = buildClient(key, ((JsseSSLManager) SSLManager.getInstance()).getContext());
            } catch (RuntimeException e) {
                if (!isQuicIncompatibleSslContextFailure(e)) {
                    throw e;
                }
                // The JDK QUIC stack (as of Java 26) only accepts SSL contexts using the
                // built-in trust manager; JMeter's SSL manager wraps trust managers to be
                // lenient with self-signed certificates, which QUIC rejects.
                warnSslContextFallbackOnce();
                client = buildClient(key, null);
            }
            log.debug("Created new HTTP/3 HttpClient: @{} {}", System.identityHashCode(client), key);
            clients.put(key, client);
            return client;
        }
    }

    private static HttpClient buildClient(Http3ClientKey key, javax.net.ssl.@Nullable SSLContext sslContext) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(Http3RuntimeSupport.HTTP_3)
                .followRedirects(key.autoRedirect()
                        ? HttpClient.Redirect.NORMAL
                        : HttpClient.Redirect.NEVER);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        if (key.connectTimeout() > 0) {
            builder.connectTimeout(Duration.ofMillis(key.connectTimeout()));
        }
        InetAddress localAddr = key.localAddress();
        if (localAddr != null) {
            builder.localAddress(localAddr);
        }
        return builder.build();
    }

    private static boolean isQuicIncompatibleSslContextFailure(RuntimeException e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            // Compiled against Java 21, so the JDK 26 exception type is matched by name
            if ("java.net.http.UnsupportedProtocolVersionException".equals(current.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    private static void warnSslContextFallbackOnce() {
        if (SSL_CONTEXT_FALLBACK_WARNED.compareAndSet(false, true)) {
            log.warn("The JMeter SSL manager context is not usable with the JDK QUIC/HTTP/3 stack "
                    + "(it requires the built-in JDK trust manager). HTTP/3 samplers use the default "
                    + "JVM TLS context instead: JMeter keystore client certificates and lenient "
                    + "certificate trust do not apply to HTTP/3 requests.");
        }
    }

    private static Object getJMeterThreadCacheKey() {
        Object thread = JMeterContextService.getContext().getThread();
        return thread == null ? Thread.currentThread() : thread;
    }

    private record RequestData(HttpRequest request, long bodyBytes) {}

    private RequestData createRequest(URL url, String method, boolean areFollowingRedirect, HTTPSampleResult res)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(url.toURI());
        int responseTimeout = getResponseTimeout();
        if (responseTimeout > 0) {
            builder.timeout(Duration.ofMillis(responseTimeout));
        }
        applyDiscoveryMode(builder);
        boolean hasContentTypeHeader = setConnectionHeaders(builder, getHeaderManager());
        setConnectionCookie(builder, url, res);
        setupPreemptiveBasicAuth(builder, url);
        long bodyBytes = setupBody(builder, method, areFollowingRedirect, hasContentTypeHeader, res);
        return new RequestData(builder.build(), bodyBytes);
    }

    /**
     * Sets the H3_DISCOVERY request option explicitly rather than relying on the JDK
     * default (which is the nondeterministic {@code ANY} race) so protocol selection is
     * predictable for load modeling.
     */
    private void applyDiscoveryMode(HttpRequest.Builder builder) throws ReflectiveOperationException {
        if (Http3RuntimeSupport.SET_OPTION == null) {
            return;
        }
        Object mode = switch (discovery) {
            case HTTP3_ONLY -> Http3RuntimeSupport.HTTP_3_URI_ONLY;
            case PREFER_HTTP3 -> Http3RuntimeSupport.ANY;
            case ALT_SVC_UPGRADE -> Http3RuntimeSupport.ALT_SVC;
        };
        try {
            Http3RuntimeSupport.SET_OPTION.invoke(builder, Http3RuntimeSupport.H3_DISCOVERY, mode);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    /**
     * Adds the Header Manager headers to the request, skipping headers the JDK client
     * refuses to set and hop-by-hop headers that do not apply to HTTP/3.
     *
     * @return true when a Content-Type header was set
     */
    private static boolean setConnectionHeaders(HttpRequest.Builder builder, @Nullable HeaderManager headerManager) {
        boolean hasContentTypeHeader = false;
        if (headerManager == null) {
            return false;
        }
        CollectionProperty headers = headerManager.getHeaders();
        if (headers == null) {
            return false;
        }
        for (JMeterProperty jMeterProperty : headers) {
            org.apache.jmeter.protocol.http.control.Header header =
                    (org.apache.jmeter.protocol.http.control.Header) jMeterProperty.getObjectValue();
            String name = header.getName();
            if (StringUtilities.isBlank(name) || DISALLOWED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            builder.header(name, header.getValue());
            if (HTTPConstants.HEADER_CONTENT_TYPE.equalsIgnoreCase(name)
                    && StringUtilities.isNotEmpty(header.getValue())) {
                hasContentTypeHeader = true;
            }
        }
        return hasContentTypeHeader;
    }

    private void setConnectionCookie(HttpRequest.Builder builder, URL url, HTTPSampleResult res) {
        CookieManager cookieManager = getCookieManager();
        if (cookieManager == null) {
            return;
        }
        String cookieHeader = cookieManager.getCookieHeaderForURL(url);
        if (cookieHeader != null) {
            builder.setHeader(HTTPConstants.HEADER_COOKIE, cookieHeader);
            res.setCookies(cookieHeader);
        }
    }

    private void setupPreemptiveBasicAuth(HttpRequest.Builder builder, URL url) {
        AuthManager authManager = getAuthManager();
        if (authManager == null) {
            return;
        }
        String authHeader = authManager.getAuthHeaderForURL(url);
        if (authHeader != null) {
            builder.setHeader(HTTPConstants.HEADER_AUTHORIZATION, authHeader);
        }
    }

    /**
     * Configures the request body, mirroring the non-multipart logic of the HttpClient 5
     * implementation, and records the posted body in {@link HTTPSampleResult#setQueryString}.
     *
     * @return the body size in bytes (0 for no body)
     */
    private long setupBody(HttpRequest.Builder builder, String method, boolean areFollowingRedirect,
            boolean hasContentTypeHeader, HTTPSampleResult res) throws IOException {
        boolean bodyCapableMethod = HTTPConstants.POST.equals(method)
                || HTTPConstants.PUT.equals(method)
                || HTTPConstants.PATCH.equals(method)
                || HTTPConstants.DELETE.equals(method);
        boolean sendBodyDespiteMethod = !areFollowingRedirect
                && ((!hasArguments() && getSendFileAsPostBody()) || getSendParameterValuesAsPostBody());
        if (!bodyCapableMethod && !sendBodyDespiteMethod) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
            return 0;
        }

        Charset charset = charset();
        HttpRequest.BodyPublisher bodyPublisher;
        if (!hasArguments() && getSendFileAsPostBody()) {
            HTTPFileArg file = getHTTPFiles()[0];
            if (!hasContentTypeHeader && StringUtilities.isNotEmpty(file.getMimeType())) {
                builder.setHeader(HTTPConstants.HEADER_CONTENT_TYPE, file.getMimeType());
            }
            bodyPublisher = HttpRequest.BodyPublishers.ofFile(
                    FileServer.getFileServer().getResolvedFile(file.getPath()).toPath());
            res.setQueryString("<actual file content, not shown here>");
        } else if (getSendParameterValuesAsPostBody()) {
            if (!hasContentTypeHeader) {
                HTTPFileArg[] files = getHTTPFiles();
                HTTPFileArg file = files.length > 0 ? files[0] : null;
                if (file != null && StringUtilities.isNotEmpty(file.getMimeType())) {
                    builder.setHeader(HTTPConstants.HEADER_CONTENT_TYPE, file.getMimeType());
                } else if (ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
                    builder.setHeader(HTTPConstants.HEADER_CONTENT_TYPE,
                            HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED);
                }
            }
            StringBuilder postBody = new StringBuilder();
            for (JMeterProperty jMeterProperty : getArguments().getEnabledArguments()) {
                org.apache.jmeter.protocol.http.util.HTTPArgument arg =
                        (org.apache.jmeter.protocol.http.util.HTTPArgument) jMeterProperty.getObjectValue();
                postBody.append(arg.getEncodedValue(charset.name()));
            }
            bodyPublisher = HttpRequest.BodyPublishers.ofString(postBody.toString(), charset);
            res.setQueryString(postBody.toString());
        } else if (hasArguments()) {
            if (!hasContentTypeHeader) {
                builder.setHeader(HTTPConstants.HEADER_CONTENT_TYPE,
                        HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED);
            }
            String formBody = testElement.getQueryString(charset.name());
            bodyPublisher = HttpRequest.BodyPublishers.ofString(formBody, charset);
            res.setQueryString(formBody);
        } else {
            bodyPublisher = HttpRequest.BodyPublishers.noBody();
        }
        builder.method(method, bodyPublisher);
        return Math.max(0, bodyPublisher.contentLength());
    }

    private Charset charset() {
        String contentEncoding = getContentEncoding();
        return StringUtilities.isBlank(contentEncoding) ? StandardCharsets.UTF_8 : Charset.forName(contentEncoding);
    }

    private void fillSampleResult(HTTPSampleResult res, HttpRequest request,
            HttpResponse<InputStream> response, long requestBodyBytes) throws IOException {
        int statusCode = response.statusCode();
        String protocolVersion = formatVersion(response.version());
        res.setResponseCode(Integer.toString(statusCode));
        // HTTP/2 and HTTP/3 have no reason phrase in the status line
        res.setResponseMessage("");
        res.setProtocolVersion(protocolVersion);
        Map<String, List<String>> responseHeaders = response.headers().map();
        responseHeaders.entrySet().stream()
                .filter(e -> HTTPConstants.HEADER_CONTENT_TYPE.equalsIgnoreCase(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .findFirst()
                .ifPresent(ct -> {
                    res.setContentType(ct);
                    res.setEncodingAndType(ct);
                });
        res.setSentBytes(estimateSentBytes(request, protocolVersion, requestBodyBytes));

        long bodyBytes = 0;
        InputStream body = response.body();
        if (body == null) {
            // readResponse normally records latency on the first body byte
            res.latencyEnd();
            res.setResponseData(new byte[0]);
        } else {
            try (CountingInputStream countingStream = new CountingInputStream(body)) {
                long contentLength = response.headers()
                        .firstValueAsLong(HTTPConstants.HEADER_CONTENT_LENGTH).orElse(0);
                String contentEncodingHeader = response.headers()
                        .firstValue(HTTPConstants.HEADER_CONTENT_ENCODING).orElse(null);
                readResponse(res, countingStream, contentLength, contentEncodingHeader);
                bodyBytes = countingStream.getBytesRead();
            }
        }
        res.sampleEnd();
        res.setSuccessful(isSuccessCode(statusCode));
        String responseHeadersText = formatResponseHeaders(protocolVersion, statusCode, responseHeaders);
        res.setResponseHeaders(responseHeadersText);
        res.setHeadersSize(responseHeadersText.length());
        res.setBodySize(bodyBytes);
        try {
            // With client-level auto-redirect the final URI may differ from the sampled one
            res.setURL(response.uri().toURL());
        } catch (IOException | RuntimeException e) {
            log.debug("Could not update sample URL from response URI {}", response.uri(), e);
        }
        if (res.isRedirect()) {
            String location = response.headers().firstValue(HTTPConstants.HEADER_LOCATION)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Missing location header in redirect for " + request.uri()));
            res.setRedirectLocation(location);
        }
        saveConnectionCookies(response, res.getURL(), getCookieManager());
    }

    static String formatVersion(HttpClient.Version version) {
        // Enum names are HTTP_1_1, HTTP_2, HTTP_3, ...
        String name = version.name();
        if (!name.startsWith("HTTP_")) { //$NON-NLS-1$
            return name;
        }
        return "HTTP/" + name.substring("HTTP_".length()).replace('_', '.'); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String formatRequestHeaders(HttpRequest request) {
        StringBuilder headers = new StringBuilder(150);
        request.headers().map().forEach((name, values) -> {
            if (ALL_EXCEPT_COOKIE.test(name)) {
                for (String value : values) {
                    headers.append(name).append(": ").append(value).append('\n');
                }
            }
        });
        return headers.toString();
    }

    private static String formatResponseHeaders(String protocolVersion, int statusCode,
            Map<String, List<String>> responseHeaders) {
        StringBuilder headers = new StringBuilder(40 * (responseHeaders.size() + 1));
        headers.append(protocolVersion).append(' ').append(statusCode).append('\n');
        responseHeaders.forEach((name, values) -> {
            for (String value : values) {
                headers.append(name).append(": ").append(value).append('\n');
            }
        });
        return headers.toString();
    }

    /**
     * Application-layer estimate of the bytes sent for the request; QUIC wire bytes
     * (framing, encryption overhead, retransmissions) are not accounted for.
     */
    private static long estimateSentBytes(HttpRequest request, String protocolVersion, long requestBodyBytes) {
        long headerBytes = 0;
        for (Map.Entry<String, List<String>> header : request.headers().map().entrySet()) {
            for (String value : header.getValue()) {
                headerBytes += header.getKey().length() + value.length() + 4L; // ": " + CRLF
            }
        }
        String path = request.uri().getRawPath();
        String query = request.uri().getRawQuery();
        long requestLineBytes = request.method().length() + 1L
                + (path == null || path.isEmpty() ? 1 : path.length())
                + (query == null ? 0 : query.length() + 1L)
                + 1L + protocolVersion.length() + 2L;
        return requestLineBytes + headerBytes + 2L + requestBodyBytes;
    }

    private static void saveConnectionCookies(HttpResponse<?> response, URL url,
            @Nullable CookieManager cookieManager) {
        if (cookieManager == null) {
            return;
        }
        for (String setCookieHeader : response.headers().allValues(HTTPConstants.HEADER_SET_COOKIE)) {
            cookieManager.addCookieFromHeader(setCookieHeader, url);
        }
    }

    @Override
    protected void notifyFirstSampleAfterLoopRestart() {
        if (!RESET_STATE_ON_THREAD_GROUP_ITERATION) {
            return;
        }
        JMeterVariables jMeterVariables = JMeterContextService.getContext().getVariables();
        if (jMeterVariables == null || !jMeterVariables.isSameUserOnNextIteration()) {
            // New virtual user: drop cached clients so new QUIC connections are established
            closeClients(HTTPCLIENTS_CACHE_PER_JMETER_THREAD.remove(getJMeterThreadCacheKey()));
        }
    }

    @Override
    protected void threadFinished() {
        closeClients(HTTPCLIENTS_CACHE_PER_JMETER_THREAD.remove(getJMeterThreadCacheKey()));
    }

    private static void closeClients(@Nullable Map<Http3ClientKey, HttpClient> clients) {
        if (clients == null) {
            return;
        }
        synchronized (clients) {
            for (HttpClient client : clients.values()) {
                // shutdownNow instead of close: do not block thread teardown on in-flight exchanges
                client.shutdownNow();
            }
            clients.clear();
        }
    }

    @Override
    public boolean interrupt() {
        CompletableFuture<HttpResponse<InputStream>> call = currentCall;
        currentCall = null;
        if (call != null) {
            call.cancel(true);
        }
        return call != null;
    }

    private record Http3ClientKey(int connectTimeout, boolean autoRedirect, @Nullable InetAddress localAddress) {

        @Override
        public String toString() {
            return "connectTimeout=" + connectTimeout + " autoRedirect=" + autoRedirect
                    + (localAddress == null ? "" : " localAddress=" + localAddress);
        }
    }
}
