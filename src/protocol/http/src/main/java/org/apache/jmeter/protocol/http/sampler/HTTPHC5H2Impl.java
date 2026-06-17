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
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.AuthenticationStrategy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.RouteInfo;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.CredentialsStore;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpOptions;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpTrace;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.cookie.CookieSpecFactory;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.impl.DefaultAuthenticationStrategy;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.LaxRedirectStrategy;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.auth.BasicSchemeFactory;
import org.apache.hc.client5.http.impl.auth.DigestSchemeFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.cookie.IgnoreCookieSpecFactory;
import org.apache.hc.client5.http.impl.nio.DefaultAsyncClientConnectionOperator;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.nio.AsyncClientConnectionOperator;
import org.apache.hc.client5.http.nio.ManagedAsyncClientConnection;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.message.BufferedHeader;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.DNSCacheManager;
import org.apache.jmeter.protocol.http.util.ConversionUtils;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.JsseSSLManager;
import org.apache.jmeter.util.SSLManager;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.jorphan.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTP/2 sampler implementation using Apache HttpClient 5.x async transport. */
public final class HTTPHC5H2Impl extends HTTPHC5Impl {

    private static final Logger log = LoggerFactory.getLogger(HTTPHC5H2Impl.class);

    private static final String CONTEXT_ATTRIBUTE_LOCAL_ADDRESS = "__jmeter.L_A_H2__";

    private static final String CONTEXT_ATTRIBUTE_CONNECT_RECORDED = "__jmeter.C_R_H2__";

    private static final int RETRY_COUNT = JMeterUtils.getPropDefault("httpclient5.retrycount", 0);

    private static final boolean REQUEST_SENT_RETRY_ENABLED =
            JMeterUtils.getPropDefault("httpclient5.request_sent_retry_enabled", false);

    private static final int TIME_TO_LIVE = JMeterUtils.getPropDefault("httpclient5.time_to_live", 60000);

    private static final boolean RESET_STATE_ON_THREAD_GROUP_ITERATION =
            JMeterUtils.getPropDefault("httpclient.reset_state_on_thread_group_iteration", true);

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            HTTPConstants.HEADER_CONNECTION.toLowerCase(Locale.ROOT),
            "keep-alive",
            "proxy-connection",
            "transfer-encoding",
            "upgrade",
            "te");

    private static final ConcurrentMap<Object, Map<HttpClientKey, HttpClientState>>
            HTTPCLIENTS_CACHE_PER_JMETER_THREAD = new ConcurrentHashMap<>();

    private volatile HttpUriRequest currentRequest;

    HTTPHC5H2Impl(HTTPSamplerBase testElement) {
        super(testElement);
    }

    @Override
    protected HTTPSampleResult sample(URL url, String method, boolean areFollowingRedirect, int frameDepth) {
        log.debug("Start HTTP/2 sample {} method {} followingRedirect {} depth {}",
                url, method, areFollowingRedirect, frameDepth);
        HTTPSampleResult res = createSampleResult(url, method);
        HttpClientContext clientContext = HttpClientContext.create();
        HttpUriRequestBase httpRequest;
        HttpClientState clientState;
        try {
            HttpVersionPolicy versionPolicy = HttpVersionPolicy.NEGOTIATE;
            HttpClientKey key = createHttpClientKey(url, versionPolicy);
            clientState = setupClient(key);
            httpRequest = createHttpRequest(url.toURI(), method, areFollowingRedirect);
            setupRequest(url, httpRequest, res);
            removeHttp1HopByHopHeaders(httpRequest);
            setupPreemptiveBasicAuth(url, httpRequest);
            setupLocalAddress(clientContext);
        } catch (Exception e) {
            res.sampleStart();
            res.sampleEnd();
            errorResult(e, res);
            return res;
        }

        res.sampleStart();
        CacheManager cacheManager = getCacheManager();
        if (cacheManager != null && HTTPConstants.GET.equalsIgnoreCase(method)
                && cacheManager.inCache(url, toLegacyHeaders(httpRequest.getHeaders()))) {
            return updateSampleResultForResourceInCache(res);
        }

        try {
            currentRequest = httpRequest;
            handleMethod(method, res, httpRequest, clientContext);
            removeHttp1HopByHopHeaders(httpRequest);
            clientContext.setAttribute(HTTPHC5Impl.CONTEXT_ATTRIBUTE_SAMPLER_RESULT, res);
            clientState.getClient().execute(httpRequest, clientContext, httpResponse -> {
                fillSampleResult(res, httpRequest, clientContext, httpResponse);
                return null;
            });
            updateUrlAfterRedirect(clientContext, res);
            HttpResponse response = clientContext.getResponse();
            if (cacheManager != null && response != null) {
                cacheManager.saveDetails(toLegacyResponse(response), res);
            }
            return resultProcessing(areFollowingRedirect, frameDepth, res);
        } catch (IOException | RuntimeException e) {
            log.debug("HTTP/2 sample failed", e);
            if (res.getEndTime() == 0) {
                res.sampleEnd();
            }
            res.setRequestHeaders(getAllHeadersExceptCookie(
                    clientContext.getRequest()));
            errorResult(e, res);
            return res;
        } finally {
            currentRequest = null;
        }
    }

    private void fillSampleResult(
            HTTPSampleResult res,
            HttpUriRequestBase httpRequest,
            HttpClientContext clientContext,
            ClassicHttpResponse httpResponse) throws IOException {
        HttpRequest request = clientContext.getRequest();
        if (request == null) {
            request = httpRequest;
        }
        res.setRequestHeaders(getAllHeadersExceptCookie(request));
        Header contentType = httpResponse.getLastHeader(HTTPConstants.HEADER_CONTENT_TYPE);
        if (contentType != null) {
            String ct = contentType.getValue();
            res.setContentType(ct);
            res.setEncodingAndType(ct);
        }
        StatusLine statusLine = new StatusLine(httpResponse);
        int statusCode = statusLine.getStatusCode();
        res.setResponseCode(Integer.toString(statusCode));
        res.setSentBytes(HTTPHC5Metrics.estimateSentBytes(request, statusLine.getProtocolVersion().getMajor() >= 2
                ? "HTTP/2"
                : "HTTP/1.1"));
        HttpEntity entity = httpResponse.getEntity();
        long bodyBytes = 0;
        if (entity == null) {
            res.latencyEnd();
            res.setResponseData(new byte[0]);
        } else {
            try (InputStream instream = entity.getContent()) {
                org.apache.jorphan.io.CountingInputStream counterStream =
                        new org.apache.jorphan.io.CountingInputStream(instream);
                readResponse(res, counterStream, entity.getContentLength(), entity.getContentEncoding());
                bodyBytes = counterStream.getBytesRead();
            }
        }
        res.sampleEnd();
        currentRequest = null;
        res.setResponseCode(Integer.toString(statusCode));
        res.setResponseMessage(statusLine.getReasonPhrase());
        res.setSuccessful(isSuccessCode(statusCode));
        res.setResponseHeaders(getResponseHeaders(httpResponse));
        if (res.isRedirect()) {
            Header location = httpResponse.getLastHeader(HTTPConstants.HEADER_LOCATION);
            if (location == null) {
                throw new IllegalArgumentException("Missing location header in redirect for "
                        + httpRequest.getRequestUri());
            }
            res.setRedirectLocation(location.getValue());
        }
        setResponseSizes(res, httpResponse, bodyBytes);
        saveConnectionCookies(httpResponse, res.getURL(), getCookieManager());
    }

    private HttpClientState setupClient(HttpClientKey key) throws GeneralSecurityException {
        Map<HttpClientKey, HttpClientState> clients = getThreadLocalClients();
        synchronized (clients) {
            HttpClientState clientState = clients.get(key);
            if (clientState != null) {
                return clientState;
            }

            DnsResolver resolver = createDnsResolver();
            HttpHost proxy = key.hasProxy ? new HttpHost(key.proxyScheme, key.proxyHost, key.proxyPort) : null;
            Credentials proxyCredentials = createProxyCredentials(key);
            AuthScope proxyAuthScope = proxyCredentials == null ? null : new AuthScope(key.proxyHost, key.proxyPort);
            Lookup<CookieSpecFactory> cookieSpecRegistry = RegistryBuilder.<CookieSpecFactory>create()
                    .register(StandardCookieSpec.IGNORE, new IgnoreCookieSpecFactory())
                    .build();
            CredentialsStore credentialsProvider =
                    new ManagedCredentialsProvider(getAuthManager(), proxyAuthScope, proxyCredentials);
            Lookup<org.apache.hc.client5.http.auth.AuthSchemeFactory> authSchemeRegistry = createAuthSchemeRegistry();
            CloseableHttpAsyncClient asyncClient = createClient(
                    resolver,
                    proxy,
                    credentialsProvider,
                    cookieSpecRegistry,
                    authSchemeRegistry,
                    key.versionPolicy);
            asyncClient.start();
            CloseableHttpClient client = HttpAsyncClients.classic(asyncClient, responseTimeout());
            clientState = new HttpClientState(client, asyncClient);
            clients.put(key, clientState);
            log.debug("Created new HTTP/2 HttpClient: @{} {}", System.identityHashCode(client), key);
            return clientState;
        }
    }

    private static Map<HttpClientKey, HttpClientState> getThreadLocalClients() {
        return HTTPCLIENTS_CACHE_PER_JMETER_THREAD.computeIfAbsent(
                getJMeterThreadCacheKey(),
                ignored -> new HashMap<>(5));
    }

    private static Object getJMeterThreadCacheKey() {
        Object thread = JMeterContextService.getContext().getThread();
        return thread == null ? Thread.currentThread() : thread;
    }

    private CloseableHttpAsyncClient createClient(
            DnsResolver resolver,
            HttpHost proxy,
            CredentialsStore credentialsProvider,
            Lookup<CookieSpecFactory> cookieSpecRegistry,
            Lookup<org.apache.hc.client5.http.auth.AuthSchemeFactory> authSchemeRegistry,
            HttpVersionPolicy versionPolicy)
            throws GeneralSecurityException {
        PoolingAsyncClientConnectionManager connectionManager = JMeterPoolingAsyncClientConnectionManagerBuilder.newBuilder()
                .setDnsResolver(resolver)
                .setTlsStrategy(createTlsStrategy())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setTimeToLive(TimeValue.ofMilliseconds(TIME_TO_LIVE))
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setVersionPolicy(versionPolicy)
                        .build())
                .build();
        CloseableHttpAsyncClient asyncClient = HttpAsyncClients.custom()
                .setConnectionManager(connectionManager)
                .setConnectionManagerShared(false)
                .setIOReactorConfig(createIOReactorConfig())
                .setDefaultCookieSpecRegistry(cookieSpecRegistry)
                .setRedirectStrategy(new LaxRedirectStrategy())
                .setRetryStrategy(createRetryStrategy())
                .setProxyAuthenticationStrategy(getProxyAuthStrategy())
                .setRoutePlanner(new H2RoutePlanner(proxy))
                .setDefaultCredentialsProvider(credentialsProvider)
                .setDefaultAuthSchemeRegistry(authSchemeRegistry)
                .disableContentCompression()
                .build();
        return asyncClient;
    }

    private static final class JMeterPoolingAsyncClientConnectionManagerBuilder
            extends PoolingAsyncClientConnectionManagerBuilder {

        static JMeterPoolingAsyncClientConnectionManagerBuilder newBuilder() {
            return new JMeterPoolingAsyncClientConnectionManagerBuilder();
        }

        @Override
        protected AsyncClientConnectionOperator createConnectionOperator(
                TlsStrategy tlsStrategy,
                SchemePortResolver schemePortResolver,
                DnsResolver dnsResolver) {
            return new JMeterDefaultAsyncClientConnectionOperator(
                    RegistryBuilder.<TlsStrategy>create()
                            .register(URIScheme.HTTPS.getId(), tlsStrategy)
                            .build(),
                    schemePortResolver,
                    dnsResolver);
        }
    }

    private static final class JMeterDefaultAsyncClientConnectionOperator
            extends DefaultAsyncClientConnectionOperator {

        private JMeterDefaultAsyncClientConnectionOperator(
                Lookup<TlsStrategy> tlsStrategyLookup,
                SchemePortResolver schemePortResolver,
                DnsResolver dnsResolver) {
            super(tlsStrategyLookup, schemePortResolver, dnsResolver);
        }

        @Override
        public Future<ManagedAsyncClientConnection> connect(
                ConnectionInitiator connectionInitiator,
                HttpHost endpointHost,
                Path unixDomainSocket,
                NamedEndpoint endpointName,
                SocketAddress localAddress,
                Timeout connectTimeout,
                Object attachment,
                HttpContext context,
                FutureCallback<ManagedAsyncClientConnection> callback) {
            return super.connect(
                    connectionInitiator,
                    endpointHost,
                    unixDomainSocket,
                    endpointName,
                    localAddress,
                    connectTimeout,
                    attachment,
                    context,
                    new FutureCallback<>() {
                        @Override
                        public void completed(ManagedAsyncClientConnection result) {
                            connectEnd(context);
                            callback.completed(result);
                        }

                        @Override
                        public void failed(Exception ex) {
                            callback.failed(ex);
                        }

                        @Override
                        public void cancelled() {
                            callback.cancelled();
                        }
                    });
        }
    }

    private static void connectEnd(HttpContext httpContext) {
        if (httpContext == null || httpContext.getAttribute(CONTEXT_ATTRIBUTE_CONNECT_RECORDED) != null) {
            return;
        }
        SampleResult sample = (SampleResult) httpContext.getAttribute(HTTPHC5Impl.CONTEXT_ATTRIBUTE_SAMPLER_RESULT);
        if (sample != null) {
            sample.connectEnd();
            httpContext.setAttribute(CONTEXT_ATTRIBUTE_CONNECT_RECORDED, true);
        }
    }

    private DnsResolver createDnsResolver() {
        DNSCacheManager dnsCacheManager = testElement.getDNSResolver();
        if (dnsCacheManager == null) {
            return SystemDefaultDnsResolver.INSTANCE;
        }
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                return dnsCacheManager.resolve(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException {
                return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
            }
        };
    }

    private static TlsStrategy createTlsStrategy() throws GeneralSecurityException {
        SSLContext sslContext = ((JsseSSLManager) SSLManager.getInstance()).getContext();
        return ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext)
                .buildAsync();
    }

    private IOReactorConfig createIOReactorConfig() {
        IOReactorConfig.Builder builder = IOReactorConfig.custom()
                .setSoKeepAlive(getUseKeepAlive());
        int responseTimeout = getResponseTimeout();
        if (responseTimeout > 0) {
            builder.setSoTimeout(Timeout.ofMilliseconds(responseTimeout));
        }
        return builder.build();
    }

    private static HttpRequestRetryStrategy createRetryStrategy() {
        return new DefaultHttpRequestRetryStrategy(RETRY_COUNT, TimeValue.ZERO_MILLISECONDS) {
            @Override
            protected boolean handleAsIdempotent(HttpRequest request) {
                return REQUEST_SENT_RETRY_ENABLED || super.handleAsIdempotent(request);
            }
        };
    }

    private static Lookup<org.apache.hc.client5.http.auth.AuthSchemeFactory> createAuthSchemeRegistry() {
        return RegistryBuilder.<org.apache.hc.client5.http.auth.AuthSchemeFactory>create()
                .register(StandardAuthScheme.BASIC, new BasicSchemeFactory())
                .register(StandardAuthScheme.DIGEST, new DigestSchemeFactory())
                .build();
    }

    @Override
    protected AuthenticationStrategy getProxyAuthStrategy() {
        return DefaultAuthenticationStrategy.INSTANCE;
    }

    private Timeout responseTimeout() {
        int timeout = getResponseTimeout();
        return timeout > 0 ? Timeout.ofMilliseconds(timeout) : Timeout.ofDays(365);
    }

    private static Credentials createProxyCredentials(HttpClientKey key) {
        if (!key.hasProxy || StringUtilities.isEmpty(key.proxyUser)) {
            return null;
        }
        return new UsernamePasswordCredentials(key.proxyUser, key.proxyPass.toCharArray());
    }

    private void setupLocalAddress(HttpContext localContext) throws IOException {
        InetAddress inetAddr = getIpSourceAddress();
        if (inetAddr != null) {
            localContext.setAttribute(CONTEXT_ATTRIBUTE_LOCAL_ADDRESS, inetAddr);
        }
    }

    private HttpClientKey createHttpClientKey(URL url, HttpVersionPolicy versionPolicy) {
        String host = url.getHost();
        String proxyScheme = getProxyScheme();
        String proxyHost = getProxyHost();
        int proxyPort = getProxyPortInt();
        String proxyPass = getProxyPass();
        String proxyUser = getProxyUser();

        boolean useStaticProxy = isStaticProxy(host);
        boolean useDynamicProxy = isDynamicProxy(proxyHost, proxyPort);
        boolean useProxy = useStaticProxy || useDynamicProxy;
        if (!useDynamicProxy) {
            proxyScheme = PROXY_SCHEME;
            proxyHost = PROXY_HOST;
            proxyPort = PROXY_PORT;
            proxyUser = PROXY_USER;
            proxyPass = PROXY_PASS;
        }
        return new HttpClientKey(url, useProxy, proxyScheme, proxyHost, proxyPort, proxyUser, proxyPass,
                versionPolicy);
    }

    private HttpUriRequestBase createHttpRequest(URI uri, String method, boolean areFollowingRedirect) {
        HttpUriRequestBase result;
        if (method.equals(HTTPConstants.POST)) {
            result = new HttpPost(uri);
        } else if (method.equals(HTTPConstants.GET)) {
            if (!areFollowingRedirect
                    && ((!hasArguments() && getSendFileAsPostBody()) || getSendParameterValuesAsPostBody())) {
                result = new HttpGetWithEntity(uri);
            } else {
                result = new HttpGet(uri);
            }
        } else if (method.equals(HTTPConstants.PUT)) {
            result = new HttpPut(uri);
        } else if (method.equals(HTTPConstants.HEAD)) {
            result = new HttpHead(uri);
        } else if (method.equals(HTTPConstants.TRACE)) {
            result = new HttpTrace(uri);
        } else if (method.equals(HTTPConstants.OPTIONS)) {
            result = new HttpOptions(uri);
        } else if (method.equals(HTTPConstants.DELETE)) {
            result = new HttpDelete(uri);
        } else if (method.equals(HTTPConstants.PATCH)) {
            result = new HttpPatch(uri);
        } else if (HttpWebdav.isWebdavMethod(method)) {
            result = new HttpWebdavHC5(method, uri);
        } else {
            throw new IllegalArgumentException("Unexpected method: '" + method + "'");
        }
        return result;
    }

    private static void removeHttp1HopByHopHeaders(HttpRequest request) {
        for (Header connectionHeader : request.getHeaders(HTTPConstants.HEADER_CONNECTION)) {
            for (String token : connectionHeader.getValue().split(",")) {
                String headerName = token.trim();
                if (!headerName.isEmpty()) {
                    request.removeHeaders(headerName);
                }
            }
        }
        for (String headerName : HOP_BY_HOP_HEADERS) {
            request.removeHeaders(headerName);
        }
    }

    private static void setResponseSizes(HTTPSampleResult res, HttpResponse response, long bodyBytes) {
        long headerBytes = (long) res.getResponseHeaders().length()
                + (long) response.getHeaders().length
                + 3L;
        res.setHeadersSize((int) headerBytes);
        res.setBodySize(bodyBytes);
    }

    private static void updateUrlAfterRedirect(HttpClientContext clientContext, HTTPSampleResult res) {
        HttpRequest req = clientContext.getRequest();
        if (req == null) {
            return;
        }
        try {
            URI redirectURI = req.getUri();
            if (redirectURI.isAbsolute()) {
                res.setURL(redirectURI.toURL());
                return;
            }
            RouteInfo route = clientContext.getHttpRoute();
            if (route != null) {
                HttpHost target = route.getTargetHost();
                res.setURL(ConversionUtils.toUrl(ConversionUtils.toUrl(target.toURI()), redirectURI.toString()));
            } else {
                res.setURL(ConversionUtils.toUrl(res.getURL(), redirectURI.toString()));
            }
        } catch (URISyntaxException | MalformedURLException e) {
            throw new IllegalArgumentException("Invalid redirect URI", e);
        }
    }

    private static String getResponseHeaders(HttpResponse response) {
        Header[] rh = response.getHeaders();
        StringBuilder headerBuf = new StringBuilder(40 * (rh.length + 1));
        headerBuf.append(new StatusLine(response));
        headerBuf.append('\n');
        for (Header responseHeader : rh) {
            writeHeader(headerBuf, responseHeader);
        }
        return headerBuf.toString();
    }

    private static String getAllHeadersExceptCookie(HttpRequest method) {
        if (method == null) {
            return "";
        }
        StringBuilder hdrs = new StringBuilder(150);
        for (Header requestHeader : method.getHeaders()) {
            if (ALL_EXCEPT_COOKIE.test(requestHeader.getName())) {
                writeHeader(hdrs, requestHeader);
            }
        }
        return hdrs.toString();
    }

    private static void writeHeader(StringBuilder headerBuffer, Header header) {
        if (header instanceof BufferedHeader bufferedHeader) {
            CharArrayBuffer buffer = bufferedHeader.getBuffer();
            headerBuffer.append(buffer.array(), 0, buffer.length()).append('\n');
        } else {
            headerBuffer.append(header.getName())
                    .append(": ")
                    .append(header.getValue())
                    .append('\n');
        }
    }

    private static org.apache.http.Header[] toLegacyHeaders(Header[] headers) {
        org.apache.http.Header[] legacyHeaders = new org.apache.http.Header[headers.length];
        for (int i = 0; i < headers.length; i++) {
            legacyHeaders[i] = new org.apache.http.message.BasicHeader(headers[i].getName(), headers[i].getValue());
        }
        return legacyHeaders;
    }

    private static org.apache.http.HttpResponse toLegacyResponse(HttpResponse response) {
        org.apache.http.message.BasicHttpResponse legacyResponse = new org.apache.http.message.BasicHttpResponse(
                new org.apache.http.ProtocolVersion("HTTP", 2, 0),
                response.getCode(),
                response.getReasonPhrase());
        legacyResponse.setHeaders(toLegacyHeaders(response.getHeaders()));
        return legacyResponse;
    }

    private static void saveConnectionCookies(HttpResponse method, URL u, CookieManager cookieManager) {
        if (cookieManager != null) {
            Header[] hdrs = method.getHeaders(HTTPConstants.HEADER_SET_COOKIE);
            for (Header hdr : hdrs) {
                cookieManager.addCookieFromHeader(hdr.getValue(), u);
            }
        }
    }

    @Override
    protected void notifyFirstSampleAfterLoopRestart() {
        if (RESET_STATE_ON_THREAD_GROUP_ITERATION) {
            closeThreadLocalConnections();
        }
    }

    @Override
    protected void threadFinished() {
        closeThreadLocalConnections();
    }

    private static void closeThreadLocalConnections() {
        Map<HttpClientKey, HttpClientState> clients = HTTPCLIENTS_CACHE_PER_JMETER_THREAD.remove(getJMeterThreadCacheKey());
        if (clients == null) {
            return;
        }
        synchronized (clients) {
            for (HttpClientState clientState : clients.values()) {
                JOrphanUtils.closeQuietly(clientState.getClient());
                JOrphanUtils.closeQuietly(clientState.getAsyncClient());
            }
        }
    }

    @Override
    public boolean interrupt() {
        HttpUriRequest request = currentRequest;
        if (request != null) {
            currentRequest = null;
            try {
                request.abort();
            } catch (UnsupportedOperationException e) {
                log.warn("Could not abort pending HTTP/2 request", e);
            }
        }
        return request != null;
    }

    private static final class H2RoutePlanner extends DefaultRoutePlanner {
        private final HttpHost proxy;

        private H2RoutePlanner(HttpHost proxy) {
            super(null);
            this.proxy = proxy;
        }

        @Override
        protected HttpHost determineProxy(HttpHost target, HttpContext context) {
            return proxy;
        }

        @Override
        protected InetAddress determineLocalAddress(HttpHost firstHop, HttpContext context) {
            return (InetAddress) context.getAttribute(CONTEXT_ATTRIBUTE_LOCAL_ADDRESS);
        }
    }

    private static final class HttpClientState {
        private final CloseableHttpClient client;
        private final CloseableHttpAsyncClient asyncClient;

        private HttpClientState(
                CloseableHttpClient client,
                CloseableHttpAsyncClient asyncClient) {
            this.client = client;
            this.asyncClient = asyncClient;
        }

        private CloseableHttpClient getClient() {
            return client;
        }

        private CloseableHttpAsyncClient getAsyncClient() {
            return asyncClient;
        }
    }

    private static final class HttpClientKey {
        private final String protocol;
        private final String authority;
        private final boolean hasProxy;
        private final String proxyScheme;
        private final String proxyHost;
        private final int proxyPort;
        private final String proxyUser;
        private final String proxyPass;
        private final HttpVersionPolicy versionPolicy;
        private final int hashCode;

        private HttpClientKey(URL url, boolean hasProxy, String proxyScheme, String proxyHost,
                int proxyPort, String proxyUser, String proxyPass, HttpVersionPolicy versionPolicy) {
            this.protocol = url.getProtocol();
            this.authority = url.getAuthority();
            this.hasProxy = hasProxy;
            this.proxyScheme = proxyScheme;
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.proxyUser = proxyUser;
            this.proxyPass = proxyPass;
            this.versionPolicy = versionPolicy;
            this.hashCode = Objects.hash(protocol, authority, hasProxy, proxyScheme, proxyHost, proxyPort,
                    proxyUser, proxyPass, versionPolicy);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof HttpClientKey other)) {
                return false;
            }
            return hasProxy == other.hasProxy
                    && proxyPort == other.proxyPort
                    && Objects.equals(protocol, other.protocol)
                    && Objects.equals(authority, other.authority)
                    && Objects.equals(proxyScheme, other.proxyScheme)
                    && Objects.equals(proxyHost, other.proxyHost)
                    && Objects.equals(proxyUser, other.proxyUser)
                    && Objects.equals(proxyPass, other.proxyPass)
                    && versionPolicy == other.versionPolicy;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return hasProxy
                    ? protocol + "://" + authority + " via " + proxyScheme + "://" + proxyHost + ':' + proxyPort
                            + " " + versionPolicy
                    : protocol + "://" + authority + " " + versionPolicy;
        }
    }

    private static final class ManagedCredentialsProvider implements CredentialsStore {
        private final AuthManager authManager;
        private final AuthScope proxyAuthScope;
        private final Credentials proxyCredentials;

        private ManagedCredentialsProvider(AuthManager authManager, AuthScope proxyAuthScope, Credentials proxyCredentials) {
            this.authManager = authManager;
            this.proxyAuthScope = proxyAuthScope;
            this.proxyCredentials = proxyCredentials;
        }

        @Override
        public void setCredentials(AuthScope authscope, Credentials credentials) {
            log.debug("Store creds {} for {}", credentials, authscope);
        }

        @Override
        public Credentials getCredentials(AuthScope authScope, HttpContext context) {
            if (proxyAuthScope != null && authScope.equals(proxyAuthScope)) {
                return proxyCredentials;
            }
            Authorization authorization = getAuthorizationForAuthScope(authScope);
            if (authorization == null) {
                return null;
            }
            return new UsernamePasswordCredentials(authorization.getUser(), authorization.getPass().toCharArray());
        }

        private Authorization getAuthorizationForAuthScope(AuthScope authScope) {
            if (authScope == null || authManager == null) {
                return null;
            }
            for (JMeterProperty authProp : authManager.getAuthObjects()) {
                Object authObject = authProp.getObjectValue();
                if (authObject instanceof Authorization auth && matches(authScope, auth)) {
                    return auth;
                }
            }
            return null;
        }

        private static boolean matches(AuthScope authScope, Authorization auth) {
            try {
                URL authUrl = ConversionUtils.toUrl(auth.getURL());
                String realm = StringUtilities.isEmpty(auth.getRealm()) ? null : auth.getRealm();
                return Objects.equals(realm, authScope.getRealm())
                        && authUrl.getHost().equals(authScope.getHost())
                        && getPort(authUrl) == authScope.getPort();
            } catch (MalformedURLException e) {
                log.debug("Invalid URL {} in authManager", auth.getURL());
                return false;
            }
        }

        private static int getPort(URL url) {
            if (url.getPort() == -1) {
                return url.getProtocol().equals("https") ? 443 : 80;
            }
            return url.getPort();
        }

        @Override
        public void clear() {
            log.debug("Clear creds");
        }
    }
}
