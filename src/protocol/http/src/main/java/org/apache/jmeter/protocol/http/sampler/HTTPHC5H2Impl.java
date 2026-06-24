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
import java.nio.channels.SelectionKey;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;

import org.apache.hc.client5.http.AuthenticationStrategy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.EndpointInfo;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.RouteInfo;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.CredentialsStore;
import org.apache.hc.client5.http.auth.NTCredentials;
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
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.auth.BasicSchemeFactory;
import org.apache.hc.client5.http.impl.auth.DigestSchemeFactory;
import org.apache.hc.client5.http.impl.auth.NTLMSchemeFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.cookie.IgnoreCookieSpecFactory;
import org.apache.hc.client5.http.impl.nio.DefaultAsyncClientConnectionOperator;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.nio.AsyncClientConnectionManager;
import org.apache.hc.client5.http.nio.AsyncClientConnectionOperator;
import org.apache.hc.client5.http.nio.AsyncConnectionEndpoint;
import org.apache.hc.client5.http.nio.ManagedAsyncClientConnection;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.concurrent.BasicFuture;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.RequestNotExecutedException;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.message.BufferedHeader;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.H2ConnectionException;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.IOSessionListener;
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
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.JsseSSLManager;
import org.apache.jmeter.util.SSLManager;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.jorphan.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HTTP/2 sampler implementation using Apache HttpClient 5.x async transport. */
@SuppressWarnings("deprecation")
public final class HTTPHC5H2Impl extends HTTPHC5Impl {

    private static final Logger log = LoggerFactory.getLogger(HTTPHC5H2Impl.class);

    private static final String CONTEXT_ATTRIBUTE_LOCAL_ADDRESS = "__jmeter.L_A_H2__";

    private static final String CONTEXT_ATTRIBUTE_CONNECT_RECORDED = "__jmeter.C_R_H2__";

    private static final String CONTEXT_ATTRIBUTE_CONNECTION_MANAGER = "__jmeter.C_M_H2__";

    private static final String CONTEXT_ATTRIBUTE_NETWORK_ENDPOINT = "__jmeter.N_E_H2__";

    private static final int RETRY_COUNT = JMeterUtils.getPropDefault("httpclient5.retrycount", 0);

    private static final boolean REQUEST_SENT_RETRY_ENABLED =
            JMeterUtils.getPropDefault("httpclient5.request_sent_retry_enabled", false);

    private static final int TIME_TO_LIVE = JMeterUtils.getPropDefault("httpclient5.time_to_live", -1);

    private static final String HTTP2_IO_THREAD_COUNT = "httpclient5.http2.io_thread_count";

    private static final int DEFAULT_HTTP2_IO_THREAD_COUNT = 1;

    private static final String HTTP2_CLOSED_SESSION_RETRY_COUNT =
            "httpsampler.hc5.h2.closed_session_retry_count";

    private static final int CLOSED_SESSION_RETRY_COUNT = Math.max(0,
            JMeterUtils.getPropDefault(HTTP2_CLOSED_SESSION_RETRY_COUNT, 1));

    private static final String HTTP2_DEBUG_CONNECTION_CLOSURE =
            "httpsampler.hc5.h2.debug_connection_closure";

    private static final boolean DEBUG_CONNECTION_CLOSURE =
            JMeterUtils.getPropDefault(HTTP2_DEBUG_CONNECTION_CLOSURE, false);

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
        int attempt = 0;
        for (;;) {
            HTTPSampleResult res = createSampleResult(url, method);
            HttpClientContext clientContext = HttpClientContext.create();
            HttpUriRequestBase httpRequest;
            HttpClientState clientState;
            try {
                HttpVersionPolicy versionPolicy = versionPolicy();
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
                    && cacheManager.inCache(url, httpRequest.getHeaders())) {
                return updateSampleResultForResourceInCache(res);
            }

            try {
                currentRequest = httpRequest;
                handleMethod(method, res, httpRequest, clientContext);
                removeHttp1HopByHopHeaders(httpRequest);
                clientContext.setAttribute(HTTPHC5Impl.CONTEXT_ATTRIBUTE_SAMPLER_RESULT, res);
                clientContext.setAttribute(CONTEXT_ATTRIBUTE_CONNECTION_MANAGER, clientState.getConnectionManager());
                clientState.getClient().execute(httpRequest, clientContext, httpResponse -> {
                    fillSampleResult(res, httpRequest, clientContext, clientState, httpResponse);
                    return null;
                });
                updateUrlAfterRedirect(clientContext, res);
                HttpResponse response = clientContext.getResponse();
                if (cacheManager != null && response != null) {
                    cacheManager.saveDetails(response, res);
                }
                return resultProcessing(areFollowingRedirect, frameDepth, res);
            } catch (IOException | RuntimeException e) {
                log.debug("HTTP/2 sample failed", e);
                if (res.getEndTime() == 0) {
                    res.sampleEnd();
                }
                if (shouldRetryClosedSessionFailure(e, method, attempt)) {
                    attempt++;
                    currentRequest = null;
                    clientState.getConnectionManager().closeConnections();
                    log.debug("Retrying HTTP/2 sample after closed session failure; attempt {}/{} {} {}",
                            attempt, CLOSED_SESSION_RETRY_COUNT, method, url, e);
                    continue;
                }
                res.setRequestHeaders(getAllHeadersExceptCookie(
                        clientContext.getRequest()));
                recordNetworkEndpointsIfNeeded(clientContext, res, false);
                errorResult(e, res);
                return res;
            } finally {
                currentRequest = null;
            }
        }
    }

    HttpVersionPolicy versionPolicy() {
        return testElement.isHttp2Protocol() ? HttpVersionPolicy.FORCE_HTTP_2 : HttpVersionPolicy.NEGOTIATE;
    }

    private static boolean shouldRetryClosedSessionFailure(
            Exception exception,
            String method,
            int attempt) {
        return attempt < CLOSED_SESSION_RETRY_COUNT
                && isRetryableMethod(method)
                && isClosedSessionFailure(exception);
    }

    private static boolean isRetryableMethod(String method) {
        return REQUEST_SENT_RETRY_ENABLED
                || HTTPConstants.GET.equalsIgnoreCase(method)
                || HTTPConstants.HEAD.equalsIgnoreCase(method)
                || HTTPConstants.OPTIONS.equalsIgnoreCase(method)
                || HTTPConstants.PUT.equalsIgnoreCase(method)
                || HTTPConstants.DELETE.equalsIgnoreCase(method)
                || HTTPConstants.TRACE.equalsIgnoreCase(method);
    }

    private static boolean isClosedSessionFailure(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConnectionClosedException
                    || current instanceof RequestNotExecutedException) {
                return true;
            }
            if (current instanceof H2ConnectionException && "Stream closed".equals(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private void fillSampleResult(
            HTTPSampleResult res,
            HttpUriRequestBase httpRequest,
            HttpClientContext clientContext,
            HttpClientState clientState,
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
        res.setProtocolVersion(formatProtocolVersion(statusLine.getProtocolVersion()));
        boolean successful = isSuccessCode(statusCode);
        recordNetworkEndpointsIfNeeded(clientContext, res, successful, clientState);
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
        res.setSuccessful(successful);
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
            clientState = createClientState(key);
            clients.put(key, clientState);
            return clientState;
        }
    }

    private HttpClientState createClientState(HttpClientKey key) throws GeneralSecurityException {
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
        H2RouteReuseConnectionManager connectionManager = createConnectionManager(resolver, key);
        CloseableHttpAsyncClient asyncClient = createClient(
                key,
                connectionManager,
                proxy,
                credentialsProvider,
                cookieSpecRegistry,
                authSchemeRegistry);
        asyncClient.start();
        CloseableHttpClient client = HttpAsyncClients.classic(asyncClient, responseTimeout());
        log.debug("Created new HTTP/2 HttpClient: @{} {}", System.identityHashCode(client), key);
        return new HttpClientState(client, asyncClient, connectionManager);
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
            HttpClientKey key,
            H2RouteReuseConnectionManager connectionManager,
            HttpHost proxy,
            CredentialsStore credentialsProvider,
            Lookup<CookieSpecFactory> cookieSpecRegistry,
            Lookup<org.apache.hc.client5.http.auth.AuthSchemeFactory> authSchemeRegistry) {
        HttpAsyncClientBuilder builder = HttpAsyncClients.custom()
                .setConnectionManager(connectionManager)
                .setConnectionManagerShared(false)
                .setIOReactorConfig(createIOReactorConfig())
                .setDefaultCookieSpecRegistry(cookieSpecRegistry)
                .setRedirectStrategy(new LaxRedirectStrategy())
                .setRetryStrategy(createRetryStrategy())
                .setUserTokenHandler((route, context) -> null)
                .setProxyAuthenticationStrategy(getProxyAuthStrategy())
                .setRoutePlanner(new H2RoutePlanner(proxy))
                .setDefaultCredentialsProvider(credentialsProvider)
                .setDefaultAuthSchemeRegistry(authSchemeRegistry)
                .disableContentCompression();
        if (DEBUG_CONNECTION_CLOSURE) {
            builder.setIOSessionListener(new H2ConnectionClosureDebugSessionListener(key.toString()));
        }
        return builder.build();
    }

    private static H2RouteReuseConnectionManager createConnectionManager(
            DnsResolver resolver,
            HttpClientKey key)
            throws GeneralSecurityException {
        PoolingAsyncClientConnectionManager poolingConnectionManager =
                JMeterPoolingAsyncClientConnectionManagerBuilder.newBuilder()
                        .setDnsResolver(resolver)
                        .setTlsStrategy(createTlsStrategy())
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setTimeToLive(TimeValue.ofMilliseconds(TIME_TO_LIVE))
                                .build())
                        .setDefaultTlsConfig(TlsConfig.custom()
                                .setVersionPolicy(key.versionPolicy)
                                .build())
                        .setMessageMultiplexing(true)
                        .build();
        return new H2RouteReuseConnectionManager(poolingConnectionManager, key.toString());
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
                            rememberNetworkEndpoint(endpointHost, context, result);
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

    private static final class H2RouteReuseConnectionManager implements AsyncClientConnectionManager {
        private final PoolingAsyncClientConnectionManager delegate;
        private final String authority;
        private final ConcurrentMap<HttpRoute, RouteLeaseState> routeStates = new ConcurrentHashMap<>();
        private final ConcurrentMap<AsyncConnectionEndpoint, HttpRoute> endpointRoutes = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, NetworkEndpoint> networkEndpointsByFirstHop = new ConcurrentHashMap<>();

        private H2RouteReuseConnectionManager(PoolingAsyncClientConnectionManager delegate, String authority) {
            this.delegate = delegate;
            this.authority = authority;
        }

        @Override
        public Future<AsyncConnectionEndpoint> lease(String id, HttpRoute route, Object state,
                Timeout requestTimeout, FutureCallback<AsyncConnectionEndpoint> callback) {
            RouteLeaseState routeState = routeStates.computeIfAbsent(route, ignored -> new RouteLeaseState());
            delegate.setMaxPerRoute(route, routeState.maxConnections(delegate.getDefaultMaxPerRoute()));
            logConnectionClosureDebug(
                    "manager lease-start authority={} id={} route={} state={} requestTimeout={} protocol={} thread={}",
                    authority, id, route, state, requestTimeout, routeState.protocol(), Thread.currentThread().getName());
            try {
                routeState.awaitLeaseTurn(requestTimeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                warnConnectionClosureDebug(
                        "manager lease-interrupted authority={} id={} route={} requestTimeout={} thread={} exception={}",
                        authority, id, route, requestTimeout, Thread.currentThread().getName(), exceptionSummary(e));
                BasicFuture<AsyncConnectionEndpoint> future = new BasicFuture<>(callback);
                future.failed(e);
                return future;
            } catch (TimeoutException e) {
                warnConnectionClosureDebug(
                        "manager lease-timeout authority={} id={} route={} requestTimeout={} thread={} exception={}",
                        authority, id, route, requestTimeout, Thread.currentThread().getName(), exceptionSummary(e));
                BasicFuture<AsyncConnectionEndpoint> future = new BasicFuture<>(callback);
                future.failed(e);
                return future;
            }
            return delegate.lease(id, route, state, requestTimeout, new FutureCallback<>() {
                @Override
                public void completed(AsyncConnectionEndpoint endpoint) {
                    endpointRoutes.put(endpoint, route);
                    routeState.leaseCompleted(endpoint);
                    logConnectionClosureDebug(
                            "manager lease-completed authority={} id={} route={} endpoint={} protocol={} thread={}",
                            authority, id, route, endpointIdentity(endpoint), routeState.protocol(),
                            Thread.currentThread().getName());
                    if (callback != null) {
                        callback.completed(endpoint);
                    }
                }

                @Override
                public void failed(Exception ex) {
                    routeState.releaseLeaseTurn();
                    warnConnectionClosureDebug(
                            "manager lease-failed authority={} id={} route={} thread={} exception={}",
                            authority, id, route, Thread.currentThread().getName(), exceptionSummary(ex));
                    if (callback != null) {
                        callback.failed(ex);
                    }
                }

                @Override
                public void cancelled() {
                    routeState.releaseLeaseTurn();
                    warnConnectionClosureDebug(
                            "manager lease-cancelled authority={} id={} route={} thread={}",
                            authority, id, route, Thread.currentThread().getName());
                    if (callback != null) {
                        callback.cancelled();
                    }
                }
            });
        }

        @Override
        public void release(AsyncConnectionEndpoint endpoint, Object state, TimeValue keepAlive) {
            logConnectionClosureDebug(
                    "manager release authority={} route={} endpoint={} state={} keepAlive={} thread={}",
                    authority, endpointRoutes.get(endpoint), endpointIdentity(endpoint), state, keepAlive,
                    Thread.currentThread().getName());
            delegate.release(endpoint, state, keepAlive);
        }

        @Override
        public Future<AsyncConnectionEndpoint> connect(AsyncConnectionEndpoint endpoint,
                ConnectionInitiator connectionInitiator, Timeout connectTimeout, Object attachment,
                HttpContext context, FutureCallback<AsyncConnectionEndpoint> callback) {
            HttpRoute route = endpointRoutes.get(endpoint);
            RouteLeaseState routeState = route == null ? null : routeStates.get(route);
            logConnectionClosureDebug(
                    "manager connect-start authority={} route={} endpoint={} connectTimeout={} attachment={} thread={}",
                    authority, route, endpointIdentity(endpoint), connectTimeout, attachment,
                    Thread.currentThread().getName());
            return delegate.connect(endpoint, connectionInitiator, connectTimeout, attachment, context,
                    new FutureCallback<>() {
                        @Override
                        public void completed(AsyncConnectionEndpoint connectedEndpoint) {
                            connectCompleted(route, routeState, connectedEndpoint);
                            if (callback != null) {
                                callback.completed(connectedEndpoint);
                            }
                        }

                        @Override
                        public void failed(Exception ex) {
                            connectFailed(route, routeState, endpoint, ex);
                            if (callback != null) {
                                callback.failed(ex);
                            }
                        }

                        @Override
                        public void cancelled() {
                            connectCancelled(route, routeState, endpoint);
                            if (callback != null) {
                                callback.cancelled();
                            }
                        }
                    });
        }

        private void connectCompleted(
                HttpRoute route,
                RouteLeaseState routeState,
                AsyncConnectionEndpoint connectedEndpoint) {
            if (route != null) {
                endpointRoutes.put(connectedEndpoint, route);
            }
            if (routeState != null) {
                routeState.connectCompleted(connectedEndpoint);
                delegate.setMaxPerRoute(route, routeState.maxConnections(delegate.getDefaultMaxPerRoute()));
            }
            logConnectionClosureDebug(
                    "manager connect-completed authority={} route={} endpoint={} protocol={} thread={}",
                    authority, route, endpointIdentity(connectedEndpoint),
                    routeState == null ? RouteProtocol.UNKNOWN : routeState.protocol(),
                    Thread.currentThread().getName());
        }

        private void connectFailed(
                HttpRoute route,
                RouteLeaseState routeState,
                AsyncConnectionEndpoint endpoint,
                Exception ex) {
            if (routeState != null) {
                routeState.releaseLeaseTurn();
            }
            warnConnectionClosureDebug(
                    "manager connect-failed authority={} route={} endpoint={} thread={} exception={}",
                    authority, route, endpointIdentity(endpoint), Thread.currentThread().getName(),
                    exceptionSummary(ex));
        }

        private void connectCancelled(
                HttpRoute route,
                RouteLeaseState routeState,
                AsyncConnectionEndpoint endpoint) {
            if (routeState != null) {
                routeState.releaseLeaseTurn();
            }
            warnConnectionClosureDebug(
                    "manager connect-cancelled authority={} route={} endpoint={} thread={}",
                    authority, route, endpointIdentity(endpoint), Thread.currentThread().getName());
        }

        @Override
        public void upgrade(AsyncConnectionEndpoint endpoint, Object attachment, HttpContext context) {
            logConnectionClosureDebug(
                    "manager upgrade authority={} route={} endpoint={} attachment={} thread={}",
                    authority, endpointRoutes.get(endpoint), endpointIdentity(endpoint), attachment,
                    Thread.currentThread().getName());
            delegate.upgrade(endpoint, attachment, context);
        }

        @Override
        public void upgrade(AsyncConnectionEndpoint endpoint, Object attachment, HttpContext context,
                FutureCallback<AsyncConnectionEndpoint> callback) {
            logConnectionClosureDebug(
                    "manager upgrade-async authority={} route={} endpoint={} attachment={} thread={}",
                    authority, endpointRoutes.get(endpoint), endpointIdentity(endpoint), attachment,
                    Thread.currentThread().getName());
            delegate.upgrade(endpoint, attachment, context, callback);
        }

        @Override
        public void close() {
            warnConnectionClosureDebug("manager close localClose=true authority={} thread={}",
                    authority, Thread.currentThread().getName());
            delegate.close();
        }

        @Override
        public void close(CloseMode closeMode) {
            warnConnectionClosureDebug("manager close localClose=true authority={} closeMode={} thread={}",
                    authority, closeMode, Thread.currentThread().getName());
            delegate.close(closeMode);
        }

        void closeConnections() {
            for (AsyncConnectionEndpoint endpoint : endpointRoutes.keySet()) {
                warnConnectionClosureDebug(
                        "manager closeConnections endpoint-close localClose=true authority={} route={} endpoint={} closeMode={} thread={}",
                        authority, endpointRoutes.get(endpoint), endpointIdentity(endpoint), CloseMode.GRACEFUL,
                        Thread.currentThread().getName());
                endpoint.close(CloseMode.GRACEFUL);
            }
            warnConnectionClosureDebug(
                    "manager closeConnections closeIdle localClose=true authority={} endpointCount={} routeCount={} thread={}",
                    authority, endpointRoutes.size(), routeStates.size(), Thread.currentThread().getName());
            delegate.closeIdle(TimeValue.ZERO_MILLISECONDS);
            routeStates.clear();
            endpointRoutes.clear();
            networkEndpointsByFirstHop.clear();
        }

        void rememberNetworkEndpoint(HttpHost firstHop, NetworkEndpoint endpoint) {
            if (firstHop != null && endpoint != null && endpoint.isComplete()) {
                networkEndpointsByFirstHop.put(endpointKey(firstHop), endpoint);
            }
        }

        NetworkEndpoint networkEndpointFor(RouteInfo route) {
            HttpHost firstHop = firstHop(route);
            return firstHop == null ? null : networkEndpointsByFirstHop.get(endpointKey(firstHop));
        }
    }

    private static void rememberNetworkEndpoint(
            HttpHost endpointHost,
            HttpContext context,
            ManagedAsyncClientConnection connection) {
        NetworkEndpoint endpoint = NetworkEndpoint.from(connection);
        if (!endpoint.isComplete()) {
            return;
        }
        if (context != null) {
            context.setAttribute(CONTEXT_ATTRIBUTE_NETWORK_ENDPOINT, endpoint);
            Object manager = context.getAttribute(CONTEXT_ATTRIBUTE_CONNECTION_MANAGER);
            if (manager instanceof H2RouteReuseConnectionManager connectionManager) {
                connectionManager.rememberNetworkEndpoint(endpointHost, endpoint);
            }
        }
    }

    private static void recordNetworkEndpointsIfNeeded(
            HttpClientContext clientContext,
            SampleResult sample,
            boolean successful) {
        recordNetworkEndpointsIfNeeded(clientContext, sample, successful, null);
    }

    private static void recordNetworkEndpointsIfNeeded(
            HttpClientContext clientContext,
            SampleResult sample,
            boolean successful,
            HttpClientState clientState) {
        if (!shouldRecordNetworkEndpoints(successful)) {
            return;
        }
        NetworkEndpoint endpoint = networkEndpointFromContext(clientContext);
        if (endpoint == null && clientState != null) {
            endpoint = clientState.getConnectionManager().networkEndpointFor(clientContext.getHttpRoute());
        }
        if (endpoint == null || !endpoint.isComplete()) {
            return;
        }
        sample.setLocalEndpoint(endpoint.localEndpoint);
        sample.setDestinationEndpoint(endpoint.destinationEndpoint);
        sample.setTlsVersion(endpoint.tlsVersion);
    }

    private static NetworkEndpoint networkEndpointFromContext(HttpContext context) {
        if (context == null) {
            return null;
        }
        Object endpoint = context.getAttribute(CONTEXT_ATTRIBUTE_NETWORK_ENDPOINT);
        return endpoint instanceof NetworkEndpoint networkEndpoint ? networkEndpoint : null;
    }

    private static HttpHost firstHop(RouteInfo route) {
        if (route == null) {
            return null;
        }
        HttpHost proxy = route.getProxyHost();
        return proxy == null ? route.getTargetHost() : proxy;
    }

    private static String endpointKey(HttpHost host) {
        return host.toURI();
    }

    private static void logConnectionClosureDebug(String message, Object... args) {
        if (DEBUG_CONNECTION_CLOSURE) {
            log.info("[H2 closure debug] " + message, args);
        }
    }

    private static void warnConnectionClosureDebug(String message, Object... args) {
        if (DEBUG_CONNECTION_CLOSURE) {
            log.warn("[H2 closure debug] " + message, args);
        }
    }

    private static String formatSession(IOSession session) {
        if (session == null) {
            return "<null-session>";
        }
        try {
            return "sessionId=" + session.getId()
                    + " status=" + session.getStatus()
                    + " local=" + formatEndpoint(session.getLocalAddress())
                    + " remote=" + formatEndpoint(session.getRemoteAddress())
                    + " eventMask=" + formatEventMask(session.getEventMask())
                    + " timeout=" + session.getSocketTimeout()
                    + " lastRead=" + session.getLastReadTime()
                    + " lastWrite=" + session.getLastWriteTime()
                    + " hasCommands=" + session.hasCommands();
        } catch (RuntimeException e) {
            return "sessionId=" + session.getId() + " <format-error " + e.getClass().getName() + ": "
                    + e.getMessage() + '>';
        }
    }

    private static String formatEventMask(int eventMask) {
        if (eventMask == 0) {
            return "0";
        }
        StringBuilder buffer = new StringBuilder();
        appendEventMask(buffer, eventMask, SelectionKey.OP_CONNECT, "CONNECT");
        appendEventMask(buffer, eventMask, SelectionKey.OP_ACCEPT, "ACCEPT");
        appendEventMask(buffer, eventMask, SelectionKey.OP_READ, "READ");
        appendEventMask(buffer, eventMask, SelectionKey.OP_WRITE, "WRITE");
        int knownMask = SelectionKey.OP_CONNECT | SelectionKey.OP_ACCEPT | SelectionKey.OP_READ | SelectionKey.OP_WRITE;
        int unknownMask = eventMask & ~knownMask;
        if (unknownMask != 0) {
            if (buffer.length() > 0) {
                buffer.append('|');
            }
            buffer.append("0x").append(Integer.toHexString(unknownMask));
        }
        return buffer.toString();
    }

    private static void appendEventMask(StringBuilder buffer, int eventMask, int value, String name) {
        if ((eventMask & value) == 0) {
            return;
        }
        if (buffer.length() > 0) {
            buffer.append('|');
        }
        buffer.append(name);
    }

    private static String endpointIdentity(AsyncConnectionEndpoint endpoint) {
        if (endpoint == null) {
            return "<null-endpoint>";
        }
        try {
            return endpoint.getClass().getSimpleName()
                    + '@' + Integer.toHexString(System.identityHashCode(endpoint))
                    + " connected=" + endpoint.isConnected()
                    + " info=" + endpoint.getInfo();
        } catch (RuntimeException e) {
            return endpoint.getClass().getSimpleName()
                    + '@' + Integer.toHexString(System.identityHashCode(endpoint))
                    + " <format-error " + e.getClass().getName() + ": " + e.getMessage() + '>';
        }
    }

    private static String exceptionSummary(Exception ex) {
        if (ex == null) {
            return "<null-exception>";
        }
        return ex.getClass().getName()
                + ": " + ex.getMessage()
                + " connectionClosed=" + (ex instanceof ConnectionClosedException);
    }

    private static final class H2ConnectionClosureDebugSessionListener implements IOSessionListener {
        private final String authority;

        private H2ConnectionClosureDebugSessionListener(String authority) {
            this.authority = authority;
        }

        @Override
        public void connected(IOSession session) {
            logConnectionClosureDebug("session connected authority={} thread={} {}",
                    authority, Thread.currentThread().getName(), formatSession(session));
        }

        @Override
        public void startTls(IOSession session) {
            logConnectionClosureDebug("session startTls authority={} thread={} {}",
                    authority, Thread.currentThread().getName(), formatSession(session));
        }

        @Override
        public void inputReady(IOSession session) {
            logConnectionClosureDebug("session inputReady authority={} thread={} {}",
                    authority, Thread.currentThread().getName(), formatSession(session));
        }

        @Override
        public void outputReady(IOSession session) {
            logConnectionClosureDebug("session outputReady authority={} thread={} {}",
                    authority, Thread.currentThread().getName(), formatSession(session));
        }

        @Override
        public void timeout(IOSession session) {
            warnConnectionClosureDebug("session timeout authority={} thread={} {}",
                    authority, Thread.currentThread().getName(), formatSession(session));
        }

        @Override
        public void exception(IOSession session, Exception ex) {
            warnConnectionClosureDebug("session exception authority={} thread={} {} exception={}",
                    authority, Thread.currentThread().getName(), formatSession(session), exceptionSummary(ex));
        }

        @Override
        public void disconnected(IOSession session) {
            warnConnectionClosureDebug("session disconnected authority={} thread={} {}",
                    authority, Thread.currentThread().getName(), formatSession(session));
        }
    }

    private static final class NetworkEndpoint {
        private final String localEndpoint;
        private final String destinationEndpoint;
        private final String tlsVersion;

        private NetworkEndpoint(String localEndpoint, String destinationEndpoint, String tlsVersion) {
            this.localEndpoint = localEndpoint;
            this.destinationEndpoint = destinationEndpoint;
            this.tlsVersion = tlsVersion;
        }

        private static NetworkEndpoint from(ManagedAsyncClientConnection connection) {
            if (connection == null) {
                return new NetworkEndpoint("", "", "");
            }
            SSLSession sslSession = connection.getSSLSession();
            return new NetworkEndpoint(
                    formatEndpoint(connection.getLocalAddress()),
                    formatEndpoint(connection.getRemoteAddress()),
                    sslSession == null ? "" : sslSession.getProtocol());
        }

        private boolean isComplete() {
            return !localEndpoint.isEmpty() && !destinationEndpoint.isEmpty();
        }
    }

    private enum RouteProtocol {
        UNKNOWN,
        HTTP_1,
        HTTP_2
    }

    private static final class RouteLeaseState {
        private RouteProtocol protocol = RouteProtocol.UNKNOWN;
        private boolean leaseTurnInProgress;

        synchronized void awaitLeaseTurn(Timeout timeout) throws InterruptedException, TimeoutException {
            if (protocol == RouteProtocol.HTTP_1) {
                return;
            }
            long deadline = 0;
            if (timeout != null && timeout.isEnabled()) {
                long timeoutMillis = timeout.toMilliseconds();
                if (timeoutMillis == 0) {
                    if (leaseTurnInProgress) {
                        throw new TimeoutException("Timed out waiting for HTTP/2 route lease turn");
                    }
                } else {
                    deadline = System.currentTimeMillis() + timeoutMillis;
                }
            }
            while (leaseTurnInProgress) {
                if (deadline > 0) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        throw new TimeoutException("Timed out waiting for HTTP/2 route lease turn");
                    }
                    wait(remaining);
                } else {
                    wait();
                }
            }
            leaseTurnInProgress = true;
        }

        synchronized void leaseCompleted(AsyncConnectionEndpoint endpoint) {
            RouteProtocol endpointProtocol = protocol(endpoint);
            if (endpointProtocol != RouteProtocol.UNKNOWN) {
                protocol = endpointProtocol;
                releaseLeaseTurn();
            } else if (endpoint.isConnected()) {
                releaseLeaseTurn();
            }
        }

        synchronized void connectCompleted(AsyncConnectionEndpoint endpoint) {
            RouteProtocol endpointProtocol = protocol(endpoint);
            if (endpointProtocol != RouteProtocol.UNKNOWN) {
                protocol = endpointProtocol;
            }
            releaseLeaseTurn();
        }

        synchronized int maxConnections(int defaultMaxConnections) {
            return protocol == RouteProtocol.HTTP_1 ? defaultMaxConnections : 1;
        }

        synchronized RouteProtocol protocol() {
            return protocol;
        }

        synchronized void releaseLeaseTurn() {
            leaseTurnInProgress = false;
            notifyAll();
        }

        private static RouteProtocol protocol(AsyncConnectionEndpoint endpoint) {
            EndpointInfo endpointInfo = endpoint.getInfo();
            ProtocolVersion protocol = endpointInfo == null ? null : endpointInfo.getProtocol();
            if (HttpVersion.HTTP_2_0.equals(protocol)) {
                return RouteProtocol.HTTP_2;
            }
            if (protocol != null) {
                return RouteProtocol.HTTP_1;
            }
            return RouteProtocol.UNKNOWN;
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

    IOReactorConfig createIOReactorConfig() {
        IOReactorConfig.Builder builder = IOReactorConfig.custom()
                .setIoThreadCount(http2IoThreadCount())
                .setSoKeepAlive(getUseKeepAlive());
        int responseTimeout = getResponseTimeout();
        if (responseTimeout > 0) {
            builder.setSoTimeout(Timeout.ofMilliseconds(responseTimeout));
        }
        return builder.build();
    }

    private static int http2IoThreadCount() {
        return Math.max(1, JMeterUtils.getPropDefault(HTTP2_IO_THREAD_COUNT, DEFAULT_HTTP2_IO_THREAD_COUNT));
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
                .register(StandardAuthScheme.NTLM, new NTLMSchemeFactory())
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
        if (!StringUtilities.isEmpty(PROXY_DOMAIN)) {
            return new NTCredentials(key.proxyUser, key.proxyPass.toCharArray(), LOCALHOST, PROXY_DOMAIN);
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
        JMeterVariables jMeterVariables = JMeterContextService.getContext().getVariables();
        if (jMeterVariables == null || !jMeterVariables.isSameUserOnNextIteration()) {
            resetThreadLocalConnections();
        }
    }

    @Override
    protected void threadFinished() {
        closeThreadLocalConnections();
    }

    private static void resetThreadLocalConnections() {
        if (!RESET_STATE_ON_THREAD_GROUP_ITERATION) {
            return;
        }
        Map<HttpClientKey, HttpClientState> clients = HTTPCLIENTS_CACHE_PER_JMETER_THREAD.get(getJMeterThreadCacheKey());
        if (clients == null) {
            return;
        }
        synchronized (clients) {
            for (HttpClientState clientState : clients.values()) {
                clientState.getConnectionManager().closeConnections();
            }
        }
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
        private final H2RouteReuseConnectionManager connectionManager;

        private HttpClientState(
                CloseableHttpClient client,
                CloseableHttpAsyncClient asyncClient,
                H2RouteReuseConnectionManager connectionManager) {
            this.client = client;
            this.asyncClient = asyncClient;
            this.connectionManager = connectionManager;
        }

        private CloseableHttpClient getClient() {
            return client;
        }

        private CloseableHttpAsyncClient getAsyncClient() {
            return asyncClient;
        }

        private H2RouteReuseConnectionManager getConnectionManager() {
            return connectionManager;
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
        private final List<AuthManagerCredential> authManagerCredentials;
        private final AuthScope proxyAuthScope;
        private final Credentials proxyCredentials;

        private ManagedCredentialsProvider(AuthManager authManager, AuthScope proxyAuthScope, Credentials proxyCredentials) {
            this.authManagerCredentials = createAuthManagerCredentials(authManager);
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
            AuthManagerCredential authManagerCredential = getAuthorizationForAuthScope(authScope);
            return authManagerCredential == null ? null : authManagerCredential.credentials;
        }

        private AuthManagerCredential getAuthorizationForAuthScope(AuthScope authScope) {
            if (authScope == null) {
                return null;
            }
            for (AuthManagerCredential authManagerCredential : authManagerCredentials) {
                if (authManagerCredential.matches(authScope)) {
                    return authManagerCredential;
                }
            }
            return null;
        }

        @Override
        public void clear() {
            log.debug("Clear creds");
        }
    }
}
