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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSession;
import javax.security.auth.Subject;

import org.apache.hc.client5.http.AuthenticationStrategy;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.RouteInfo;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.auth.AuthCache;
import org.apache.hc.client5.http.auth.AuthExchange;
import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.CredentialsStore;
import org.apache.hc.client5.http.auth.KerberosConfig;
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
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.CookieSpecFactory;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.entity.mime.FileBody;
import org.apache.hc.client5.http.entity.mime.FormBodyPart;
import org.apache.hc.client5.http.entity.mime.FormBodyPartBuilder;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.entity.mime.StringBody;
import org.apache.hc.client5.http.impl.DefaultAuthenticationStrategy;
import org.apache.hc.client5.http.impl.DefaultClientConnectionReuseStrategy;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.LaxRedirectStrategy;
import org.apache.hc.client5.http.impl.auth.BasicAuthCache;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.auth.BasicScheme;
import org.apache.hc.client5.http.impl.auth.BasicSchemeFactory;
import org.apache.hc.client5.http.impl.auth.DigestScheme;
import org.apache.hc.client5.http.impl.auth.DigestSchemeFactory;
import org.apache.hc.client5.http.impl.auth.KerberosScheme;
import org.apache.hc.client5.http.impl.auth.KerberosSchemeFactory;
import org.apache.hc.client5.http.impl.auth.NTLMSchemeFactory;
import org.apache.hc.client5.http.impl.auth.SPNegoSchemeFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.cookie.IgnoreCookieSpecFactory;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.LayeredConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EndpointDetails;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpConnectionMetrics;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.FileEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.http.message.BufferedHeader;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.apache.jmeter.JMeter;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.api.auth.DigestParameters;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.DNSCacheManager;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.hc.LazyLayeredConnectionSocketFactoryHC5;
import org.apache.jmeter.protocol.http.util.ConversionUtils;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.protocol.http.util.SlowHC5PlainConnectionSocketFactory;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.property.CollectionProperty;
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

/** HTTP Sampler using Apache HttpClient 5.x. */
@SuppressWarnings({"removal", "deprecation"})
public class HTTPHC5Impl extends HTTPHCAbstractImpl {

    private static final String CONTEXT_ATTRIBUTE_AUTH_MANAGER = "__jmeter.A_M__";

    private static final String JMETER_VARIABLE_USER_TOKEN = "__jmeter.U_T__"; //$NON-NLS-1$

    static final String CONTEXT_ATTRIBUTE_SAMPLER_RESULT = "__jmeter.S_R__"; //$NON-NLS-1$

    // Holds data used by HTTP request if embedded resource download is enabled
    private static final String CONTEXT_ATTRIBUTE_PARENT_SAMPLE_CLIENT_STATE = "__jmeter.H_T__";

    private static final String CONTEXT_ATTRIBUTE_CLIENT_KEY = "__jmeter.C_K__";

    private static final String CONTEXT_ATTRIBUTE_SENT_BYTES = "__jmeter.S_B__";

    private static final String CONTEXT_ATTRIBUTE_METRICS = "__jmeter.M__";

    private static final String CONTEXT_ATTRIBUTE_RECEIVED_BYTES_BEFORE = "__jmeter.R_B__";

    private static final String CONTEXT_ATTRIBUTE_TLS_VERSION = "__jmeter.T_V__";

    private static final String CONTEXT_ATTRIBUTE_LOCAL_ADDRESS = "__jmeter.L_A__";

    private static final boolean DISABLE_DEFAULT_UA = JMeterUtils.getPropDefault("httpclient5.default_user_agent_disabled", false);

    private static final Logger log = LoggerFactory.getLogger(HTTPHC5Impl.class);

    private static final String HEADER_CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";
    private static final String CONTENT_TRANSFER_ENCODING_8BIT = "8bit";
    private static final String CONTENT_TRANSFER_ENCODING_BINARY = "binary";

    private static final class ManagedCredentialsProvider implements CredentialsStore {
        private final AuthManager authManager;
        private final Credentials proxyCredentials;
        private final AuthScope proxyAuthScope;

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
            log.info("Get creds for {}", authScope);
            if (this.proxyAuthScope != null && authScope.equals(proxyAuthScope)) {
                return proxyCredentials;
            }
            final Authorization authorization = getAuthorizationForAuthScope(authScope);
            if (authorization == null) {
                return null;
            }
            return new UsernamePasswordCredentials(authorization.getUser(), authorization.getPass().toCharArray());
        }

        /**
         * Find the Authorization for the given AuthScope. We can't ask the AuthManager
         * by the URL, as we didn't get the scheme or path of the URL. Therefore we do a
         * best guess on the information we have
         *
         * @param authScope information which destination we want to get credentials for
         * @return matching authorization information entry from the AuthManager
         */
        private Authorization getAuthorizationForAuthScope(AuthScope authScope) {
            if (authScope == null) {
                return null;
            }
            if (authManager == null) {
                log.debug("No authManager found");
                return null;
            }
            for (JMeterProperty authProp : authManager.getAuthObjects()) {
                Object authObject = authProp.getObjectValue();
                if (authObject instanceof Authorization auth) {
                    if (!authScope.getRealm().equals(auth.getRealm())) {
                        continue;
                    }
                    try {
                        URL authUrl = ConversionUtils.toUrl(auth.getURL());
                        if (authUrl.getHost().equals(authScope.getHost()) && getPort(authUrl) == authScope.getPort()) {
                            return auth;
                        }
                    } catch (MalformedURLException e) {
                        log.debug("Invalid URL {} in authManager", auth.getURL());
                    }
                }
            }
            return null;
        }

        private static int getPort(URL url) {
            if (url.getPort() == -1) {
                return url.getProtocol().equals("https") ? 443 : 80;
            }
            return url.getPort();
        }

        @Override
        public void clear() {
            log.debug("clear creds");
        }
    }

    private static final class PreemptiveAuthRequestInterceptor implements HttpRequestInterceptor {
        @Override
        public void process(HttpRequest request, EntityDetails entityDetails, HttpContext context) throws HttpException, IOException {
            HttpClientContext localContext = HttpClientContext.adapt(context);
            AuthManager authManager = (AuthManager) localContext.getAttribute(CONTEXT_ATTRIBUTE_AUTH_MANAGER);
            if (authManager == null) {
                Credentials credentials = null;
                HttpClientKey key = (HttpClientKey) localContext.getAttribute(CONTEXT_ATTRIBUTE_CLIENT_KEY);
                if (key == null) {
                    return;
                }
                AuthScope authScope = null;
                CredentialsStore credentialsProvider = (CredentialsStore) localContext.getCredentialsProvider();
                if (key.hasProxy && !(key.proxyUser == null || key.proxyUser.isEmpty())) {
                    authScope = new AuthScope(key.proxyHost, key.proxyPort);
                    credentials = credentialsProvider.getCredentials(authScope, context);
                }
                credentialsProvider.clear();
                if (credentials != null) {
                    credentialsProvider.setCredentials(authScope, credentials);
                }
                return;
            }
            URI requestURI = null;
            if (request instanceof HttpUriRequest httpUriRequest) {
                try {
                    requestURI = httpUriRequest.getUri();
                } catch (final URISyntaxException ignore) { // NOSONAR
                    // NOOP
                }
            } else {
                try {
                    requestURI = request.getUri();
                } catch (final URISyntaxException ignore) { // NOSONAR
                    // NOOP
                }
            }
            if(requestURI != null) {
                HttpHost targetHost = localContext.getHttpRoute().getTargetHost();
                URL url;
                if(requestURI.isAbsolute()) {
                    url = requestURI.toURL();
                } else {
                    url = ConversionUtils.toUrl(targetHost.getSchemeName(), targetHost.getHostName(), targetHost.getPort(),
                            requestURI.getPath());
                }
                Authorization authorization =
                        authManager.getAuthForURL(url);
                CredentialsStore credentialsProvider = (CredentialsStore) localContext.getCredentialsProvider();
                if(authorization != null) {
                    AuthCache authCache = localContext.getAuthCache();
                    if(authCache == null) {
                        authCache = new BasicAuthCache();
                        localContext.setAuthCache(authCache);
                    }
                    setupCredentials(authorization, url, credentialsProvider);
                    addPreemptiveBasicHeader(request, authorization);
                    AuthExchange authExchange = localContext.getAuthExchange(targetHost);
                    if (authExchange == null || authExchange.getAuthScheme() == null) {
                        AuthScope authScope = new AuthScope(targetHost.getSchemeName(), targetHost.getHostName(),
                                targetHost.getPort(), authorization.getRealm(), null);
                        Credentials creds = credentialsProvider.getCredentials(authScope, context);
                        if (creds != null) {
                            fillAuthCache(targetHost, authorization, authCache, authScope, creds);
                        }
                    }
                } else {
                    credentialsProvider.clear();
                }
            }
        }

        private static void addPreemptiveBasicHeader(HttpRequest request, Authorization authorization) {
            @SuppressWarnings("deprecation")
            Mechanism basicDigest = Mechanism.BASIC_DIGEST;
            if (request.containsHeader("Authorization") ||
                    (authorization.getMechanism() != basicDigest && authorization.getMechanism() != Mechanism.BASIC)) {
                return;
            }
            String userPassword = authorization.getUser() + ":" + authorization.getPass();
            String encoded = Base64.getEncoder().encodeToString(userPassword.getBytes(StandardCharsets.UTF_8));
            request.addHeader("Authorization", "Basic " + encoded);
        }

        /**
         * @param targetHost
         * @param authorization
         * @param authCache
         * @param authScope
         */
        private static void fillAuthCache(HttpHost targetHost, Authorization authorization, AuthCache authCache,
                AuthScope authScope, Credentials credentials) {
            @SuppressWarnings("deprecation")
            Mechanism basicDigest = Mechanism.BASIC_DIGEST;
            if(authorization.getMechanism() == basicDigest ||
                    authorization.getMechanism() == Mechanism.BASIC) {
                BasicScheme basicAuth = new BasicScheme();
                basicAuth.initPreemptive(credentials);
                authCache.put(targetHost, basicAuth);
            } else if (authorization.getMechanism() == Mechanism.DIGEST) {
                JMeterVariables vars = JMeterContextService.getContext().getVariables();
                DigestParameters digestParameters = (DigestParameters)
                        vars.getObject(DIGEST_PARAMETERS);
                if(digestParameters!=null) {
                    DigestScheme digestAuth = (DigestScheme) authCache.get(targetHost);
                    if(digestAuth == null) {
                        digestAuth = new DigestScheme();
                    }
                    digestAuth.initPreemptive(credentials, authScope.getRealm(), digestParameters.getNonce());
                    authCache.put(targetHost, digestAuth);
                }
            } else if (authorization.getMechanism() == Mechanism.KERBEROS) {
                KerberosScheme kerberosScheme = new KerberosScheme();
                authCache.put(targetHost, kerberosScheme);
            }
        }
    }

    private static void setupCredentials(Authorization auth, URL url,
            CredentialsStore credentialsProvider) {
        String username = auth.getUser();
        String realm = auth.getRealm();
        String domain = auth.getDomain();
        if (log.isDebugEnabled()){
            log.debug("{} > D={} R={} M={}", username, domain, realm, auth.getMechanism());
        }
        if(Mechanism.KERBEROS.equals(auth.getMechanism())) {
            credentialsProvider.setCredentials(new AuthScope(null, null, -1, null, null), new Credentials() {
                @Override
                public java.security.Principal getUserPrincipal() {
                    return null;
                }

                @Override
                public char[] getPassword() {
                    return null;
                }
            });
        } else {
            credentialsProvider.setCredentials(
                new AuthScope(url.getProtocol(), url.getHost(), url.getPort(), realm.isEmpty() ? null : realm, null),
                new UsernamePasswordCredentials(username, auth.getPass().toCharArray()));
        }
    }

    private static final class JMeterDefaultHttpClientConnectionOperator extends DefaultHttpClientConnectionOperator {

        private JMeterDefaultHttpClientConnectionOperator(Lookup<ConnectionSocketFactory> socketFactoryRegistry, SchemePortResolver schemePortResolver,
                DnsResolver dnsResolver) {
            super(socketFactoryRegistry, schemePortResolver, dnsResolver);
        }
        @Override
        public void connect(ManagedHttpClientConnection conn, HttpHost endpointHost, NamedEndpoint endpointName, Path unixDomainSocket,
                InetSocketAddress localAddress, Timeout connectTimeout, SocketConfig socketConfig, Object attachment,
                HttpContext context) throws IOException {
            try {
                super.connect(conn, endpointHost, endpointName, unixDomainSocket, localAddress, connectTimeout, socketConfig, attachment,
                        context);
            } finally {
                SampleResult sample =
                        (SampleResult) context.getAttribute(HTTPHC5Impl.CONTEXT_ATTRIBUTE_SAMPLER_RESULT);
                if (sample != null) {
                    sample.connectEnd();
                }
            }
        }
    }

    private static final class JMeterDefaultRoutePlanner extends DefaultRoutePlanner {
        private final HttpHost proxy;

        private JMeterDefaultRoutePlanner(HttpHost proxy) {
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

    private static final int RETRY_COUNT = JMeterUtils.getPropDefault("httpclient5.retrycount", 0);

    private static final boolean REQUEST_SENT_RETRY_ENABLED =
            JMeterUtils.getPropDefault("httpclient5.request_sent_retry_enabled", false);

    private static final int IDLE_TIMEOUT = JMeterUtils.getPropDefault("httpclient5.idletimeout", 0);

    private static final int VALIDITY_AFTER_INACTIVITY_TIMEOUT = JMeterUtils.getPropDefault("httpclient5.validate_after_inactivity", 4900);

    private static final int TIME_TO_LIVE = JMeterUtils.getPropDefault("httpclient5.time_to_live", -1);

    private static final boolean BASIC_AUTH_PREEMPTIVE = JMeterUtils.getPropDefault("httpclient5.auth.preemptive", true);

    private static final KerberosConfig KERBEROS_CONFIG = KerberosConfig.custom()
            .setStripPort(AuthManager.STRIP_PORT)
            .setUseCanonicalHostname(AuthManager.USE_CANONICAL_HOST_NAME)
            .build();

    private static final Pattern PORT_PATTERN = Pattern.compile("\\d+"); // only used in .matches(), no need for anchors

    @SuppressWarnings("UnnecessaryAnonymousClass")
    private static final ConnectionKeepAliveStrategy IDLE_STRATEGY = new DefaultConnectionKeepAliveStrategy(){
        @Override
        public TimeValue getKeepAliveDuration(HttpResponse response, HttpContext context) {
            TimeValue duration = super.getKeepAliveDuration(response, context);
            if ((duration == null || duration.compareTo(TimeValue.ZERO_MILLISECONDS) <= 0) && IDLE_TIMEOUT > 0) {// none found by the superclass
                log.debug("Setting keepalive to {}", IDLE_TIMEOUT);
                return TimeValue.ofMilliseconds(IDLE_TIMEOUT);
            }
            return duration; // return the super-class value
        }

    };

    private static final String DIGEST_PARAMETERS = DigestParameters.VARIABLE_NAME;
    private static final HttpRequestInterceptor PREEMPTIVE_AUTH_INTERCEPTOR = new PreemptiveAuthRequestInterceptor();

    // see  https://stackoverflow.com/questions/26166469/measure-bandwidth-usage-with-apache-httpcomponents-httpclient
    private static final HttpRequestExecutor REQUEST_EXECUTOR = new HttpRequestExecutor() {
        @Override
        public ClassicHttpResponse execute(
                final ClassicHttpRequest request,
                final HttpClientConnection conn,
                final HttpContext context) throws IOException, HttpException {
            HttpConnectionMetrics metrics = conn.getEndpointDetails();
            long sentBytesBefore = metrics.getSentBytesCount();
            long receivedBytesBefore = metrics.getReceivedBytesCount();
            ClassicHttpResponse response = super.execute(request, conn, context);
            long sentBytesCount = metrics.getSentBytesCount() - sentBytesBefore;
            context.setAttribute(CONTEXT_ATTRIBUTE_SENT_BYTES, sentBytesCount);
            context.setAttribute(CONTEXT_ATTRIBUTE_RECEIVED_BYTES_BEFORE, receivedBytesBefore);
            context.setAttribute(CONTEXT_ATTRIBUTE_METRICS, metrics);
            if (conn instanceof ManagedHttpClientConnection managedConnection) {
                SSLSession sslSession = managedConnection.getSSLSession();
                if (sslSession != null) {
                    context.setAttribute(CONTEXT_ATTRIBUTE_TLS_VERSION, sslSession.getProtocol());
                }
            }
            log.debug("Sent {} bytes", sentBytesCount);
            return response;
        }
    };

    protected static boolean shouldRecordNetworkEndpoints(boolean successful) {
        return !successful || !JMeter.isNonGUI() || log.isDebugEnabled();
    }

    private static void recordNetworkEndpointsIfNeeded(HttpContext context, boolean successful) {
        if (!shouldRecordNetworkEndpoints(successful)) {
            return;
        }
        Object metrics = context.getAttribute(CONTEXT_ATTRIBUTE_METRICS);
        if (!(metrics instanceof EndpointDetails endpointDetails)) {
            return;
        }
        if (endpointDetails == null) {
            return;
        }
        SampleResult sample = (SampleResult) context.getAttribute(CONTEXT_ATTRIBUTE_SAMPLER_RESULT);
        if (sample == null) {
            return;
        }
        sample.setLocalEndpoint(formatEndpoint(endpointDetails.getLocalAddress()));
        sample.setDestinationEndpoint(formatEndpoint(endpointDetails.getRemoteAddress()));
    }

    protected static String formatEndpoint(SocketAddress socketAddress) {
        if (!(socketAddress instanceof InetSocketAddress inetSocketAddress)) {
            return socketAddress == null ? "" : socketAddress.toString();
        }
        InetAddress address = inetSocketAddress.getAddress();
        String hostAddress = address == null ? inetSocketAddress.getHostString() : address.getHostAddress();
        if (hostAddress.indexOf(':') >= 0) {
            hostAddress = "[" + hostAddress + "]";
        }
        return hostAddress + ":" + inetSocketAddress.getPort();
    }

    protected static String formatProtocolVersion(ProtocolVersion protocolVersion) {
        if (protocolVersion == null) {
            return "";
        }
        if ("HTTP".equals(protocolVersion.getProtocol())
                && protocolVersion.getMajor() == 2
                && protocolVersion.getMinor() == 0) {
            return "HTTP/2";
        }
        return protocolVersion.toString();
    }

    /**
     * 1 HttpClient instance per combination of (HttpClient,HttpClientKey)
     */
    private static final ThreadLocal<Map<HttpClientKey, HttpClientState>>
            HTTPCLIENTS_CACHE_PER_THREAD_AND_HTTPCLIENTKEY = new InheritableThreadLocal<>() {
        @Override
        protected Map<HttpClientKey, HttpClientState> initialValue() {
            return new HashMap<>(5);
        }
    };

    private static final class HttpClientState {
        private final CloseableHttpClient client;
        private final PoolingHttpClientConnectionManager connectionManager;
        private final HttpHost proxyHost;
        private AuthExchange proxyAuthExchange;

        private HttpClientState(CloseableHttpClient client, PoolingHttpClientConnectionManager connectionManager,
                HttpHost proxyHost) {
            this.client = client;
            this.connectionManager = connectionManager;
            this.proxyHost = proxyHost;
        }

        private CloseableHttpClient getClient() {
            return client;
        }

        private PoolingHttpClientConnectionManager getConnectionManager() {
            return connectionManager;
        }

        private HttpHost getProxyHost() {
            return proxyHost;
        }

        private AuthExchange getProxyAuthExchange() {
            return proxyAuthExchange;
        }

        private void setProxyAuthExchange(AuthExchange proxyAuthExchange) {
            this.proxyAuthExchange = proxyAuthExchange;
        }
    }

    /**
     * CONNECTION_SOCKET_FACTORY changes if we want to simulate Slow connection
     */
    private static final ConnectionSocketFactory CONNECTION_SOCKET_FACTORY;

    private static final ViewableFileBody[] EMPTY_FILE_BODIES = new ViewableFileBody[0];

    static {
        log.info("HTTP request retry count = {}", RETRY_COUNT);

        // Set up HTTP scheme override if necessary
        if (CPS_HTTP > 0) {
            log.info("Setting up HTTP SlowProtocol, cps={}", CPS_HTTP);
            CONNECTION_SOCKET_FACTORY = new SlowHC5PlainConnectionSocketFactory(CPS_HTTP);
        } else {
            CONNECTION_SOCKET_FACTORY = PlainConnectionSocketFactory.getSocketFactory();
        }
    }

    private volatile HttpUriRequest currentRequest; // Accessed from multiple threads

    protected HTTPHC5Impl(HTTPSamplerBase testElement) {
        super(testElement);
    }

    public static final class HttpGetWithEntity extends HttpUriRequestBase {

        public HttpGetWithEntity(final URI uri) {
            super(HTTPConstants.GET, uri);
        }
    }

    public static final class HttpDelete extends HttpUriRequestBase {

        public HttpDelete(final URI uri) {
            super(HTTPConstants.DELETE, uri);
        }
    }

    public static final class HttpWebdavHC5 extends HttpUriRequestBase {
        public HttpWebdavHC5(final String davMethod, final URI uri) {
            super(davMethod, uri);
        }
    }

    @Override
    protected HTTPSampleResult sample(URL url, String method,
            boolean areFollowingRedirect, int frameDepth) {

        if (log.isDebugEnabled()) {
            log.debug("Start : sample {} method {} followingRedirect {} depth {}",
                    url, method, areFollowingRedirect, frameDepth);
        }
        JMeterVariables jMeterVariables = JMeterContextService.getContext().getVariables();

        HTTPSampleResult res = createSampleResult(url, method);

        CloseableHttpClient httpClient = null;
        HttpUriRequestBase httpRequest = null;
        HttpContext localContext = new BasicHttpContext();
        HttpClientContext clientContext = HttpClientContext.adapt(localContext);
        clientContext.setAttribute(CONTEXT_ATTRIBUTE_AUTH_MANAGER, getAuthManager());
        HttpClientKey key = createHttpClientKey(url);
        HttpClientState clientState;
        try {
            clientState = setupClient(key, jMeterVariables, clientContext);
            httpClient = clientState.getClient();
            URI uri = url.toURI();
            httpRequest = createHttpRequest(uri, method, areFollowingRedirect);
            setupRequest(url, httpRequest, res); // can throw IOException
            setupPreemptiveBasicAuth(url, httpRequest);
            setupLocalAddress(localContext);
        } catch (Exception e) {
            res.sampleStart();
            res.sampleEnd();
            errorResult(e, res);
            return res;
        }

        setupClientContextBeforeSample(jMeterVariables, localContext);

        res.sampleStart();

        final CacheManager cacheManager = getCacheManager();
        if (cacheManager != null && HTTPConstants.GET.equalsIgnoreCase(method) && cacheManager.inCache(url, httpRequest.getHeaders())) {
            return updateSampleResultForResourceInCache(res);
        }
        CloseableHttpResponse httpResponse = null;
        try {
            currentRequest = httpRequest;
            handleMethod(method, res, httpRequest, localContext);
            // store the SampleResult in LocalContext to compute connect time
            localContext.setAttribute(CONTEXT_ATTRIBUTE_SAMPLER_RESULT, res);
            // perform the sample
            httpResponse =
                    executeRequest(httpClient, httpRequest, localContext, url);
            saveProxyAuth(clientState, clientContext);
            if (log.isDebugEnabled()) {
                log.debug("Headers in request before:{}", Arrays.asList(httpRequest.getHeaders()));
            }
            // Needs to be done after execute to pick up all the headers
            final HttpRequest request = (HttpRequest) localContext.getAttribute(HttpCoreContext.HTTP_REQUEST);
            if (log.isDebugEnabled()) {
                log.debug("Headers in request after:{}, in localContext#request:{}",
                        Arrays.asList(httpRequest.getHeaders()),
                        Arrays.asList(request.getHeaders()));
            }
            extractClientContextAfterSample(jMeterVariables, localContext);
            // We've finished with the request, so we can add the LocalAddress to it for display
            if (localAddress != null) {
                request.addHeader(HEADER_LOCAL_ADDRESS, localAddress.toString());
            }
            res.setRequestHeaders(getAllHeadersExceptCookie(request));

            Header contentType = httpResponse.getLastHeader(HTTPConstants.HEADER_CONTENT_TYPE);
            if (contentType != null){
                String ct = contentType.getValue();
                res.setContentType(ct);
                res.setEncodingAndType(ct);
            }
            // Set the response code before reading the body so STORE_ON_ERROR can decide
            // whether to keep the body (overwritten with the same value below for clarity)
            StatusLine statusLine = new StatusLine(httpResponse);
            int statusCode = statusLine.getStatusCode();
            res.setResponseCode(Integer.toString(statusCode));
            res.setProtocolVersion(formatProtocolVersion(statusLine.getProtocolVersion()));
            Object tlsVersion = localContext.getAttribute(CONTEXT_ATTRIBUTE_TLS_VERSION);
            if (tlsVersion instanceof String version) {
                res.setTlsVersion(version);
            }
            boolean successful = isSuccessCode(statusCode);
            recordNetworkEndpointsIfNeeded(localContext, successful);

            HttpEntity entity = httpResponse.getEntity();
            long bodyBytes = 0;
            if (entity == null) {
                res.latencyEnd();
                res.setResponseData(new byte[0]);
            } else {
                try (InputStream instream = entity.getContent()) {
                    org.apache.jorphan.io.CountingInputStream counterStream =
                            new org.apache.jorphan.io.CountingInputStream(instream);
                    String contentEncoding = entity.getContentEncoding();
                    readResponse(res, counterStream, entity.getContentLength(), contentEncoding);
                    bodyBytes = counterStream.getBytesRead();
                }
            }

            res.sampleEnd(); // Done with the sampling proper.
            currentRequest = null;

            // Now collect the results into the HTTPSampleResult:
            res.setResponseCode(Integer.toString(statusCode));
            res.setResponseMessage(statusLine.getReasonPhrase());
            res.setSuccessful(successful);
            res.setResponseHeaders(getResponseHeaders(httpResponse));
            if (res.isRedirect()) {
                final Header headerLocation = httpResponse.getLastHeader(HTTPConstants.HEADER_LOCATION);
                if (headerLocation == null) { // HTTP protocol violation, but avoids NPE
                    throw new IllegalArgumentException("Missing location header in redirect for " + httpRequest.getRequestUri());
                }
                String redirectLocation = headerLocation.getValue();
                res.setRedirectLocation(redirectLocation);
            }

            // record some sizes to allow HTTPSampleResult.getBytes() with different options
            long headerBytes =
                (long)res.getResponseHeaders().length()   // condensed length (without \r)
              + (long) httpResponse.getHeaders().length // Add \r for each header
              + 1L // Add \r for initial header
              + 2L; // final \r\n before data
            res.setHeadersSize((int)headerBytes);
            res.setBodySize(bodyBytes);
            Long sentBytes = (Long) localContext.getAttribute(CONTEXT_ATTRIBUTE_SENT_BYTES);
            long sent = sentBytes == null || sentBytes <= 0 ? HTTPHC5Metrics.estimateSentBytes(request, "HTTP/1.1") : sentBytes;
            res.setSentBytes(sent);
            if (log.isDebugEnabled()) {
                long total = res.getHeadersSize() + res.getBodySizeAsLong();
                log.debug("ResponseHeadersSize={} Content-Length={} Total={}",
                        res.getHeadersSize(), res.getBodySizeAsLong(), total);
            }

            // If we redirected automatically, the URL may have changed
            if (getAutoRedirects()) {
                HttpRequest req = (HttpRequest) localContext.getAttribute(HttpCoreContext.HTTP_REQUEST);
                URI redirectURI;
                try {
                    redirectURI = req.getUri();
                } catch (URISyntaxException e) {
                    throw new IllegalArgumentException("Invalid redirect URI", e);
                }
                if (redirectURI.isAbsolute()) {
                    res.setURL(redirectURI.toURL());
                } else {
                    RouteInfo route = clientContext.getHttpRoute();
                    if (route != null) {
                        HttpHost target = route.getTargetHost();
                        res.setURL(ConversionUtils.toUrl(ConversionUtils.toUrl(target.toURI()), redirectURI.toString()));
                    } else {
                        res.setURL(ConversionUtils.toUrl(res.getURL(), redirectURI.toString()));
                    }
                }
            }

            // Store any cookies received in the cookie manager:
            saveConnectionCookies(httpResponse, res.getURL(), getCookieManager());

            // Save cache information
            if (cacheManager != null){
                cacheManager.saveDetails(httpResponse, res);
            }

            // Follow redirects and download page resources if appropriate:
            res = resultProcessing(areFollowingRedirect, frameDepth, res);
            if(!isSuccessCode(statusCode)) {
                EntityUtils.consumeQuietly(httpResponse.getEntity());
            }

        } catch (IOException e) {
            log.debug("IOException", e);
            if (res.getEndTime() == 0) {
                res.sampleEnd();
            }
           // pick up headers if failed to execute the request
            if (res.getRequestHeaders() != null) {
                log.debug("Overwriting request old headers: {}", res.getRequestHeaders());
            }
            res.setRequestHeaders(getAllHeadersExceptCookie((HttpRequest) localContext.getAttribute(HttpCoreContext.HTTP_REQUEST)));
            recordNetworkEndpointsIfNeeded(localContext, false);
            errorResult(e, res);
            return res;
        } catch (RuntimeException e) {
            log.debug("RuntimeException", e);
            if (res.getEndTime() == 0) {
                res.sampleEnd();
            }
            recordNetworkEndpointsIfNeeded(localContext, false);
            errorResult(e, res);
            return res;
        } finally {
            JOrphanUtils.closeQuietly(httpResponse);
            currentRequest = null;
            JMeterContextService.getContext().getSamplerContext().remove(CONTEXT_ATTRIBUTE_PARENT_SAMPLE_CLIENT_STATE);
        }
        return res;
    }

    /**
     * Associate Proxy state to thread
     * @param clientState {@link HttpClientState}
     */
    private static void saveProxyAuth(
            HttpClientState clientState,
            HttpClientContext clientContext) {
        HttpHost proxyHost = clientState.getProxyHost();
        if (proxyHost != null) {
            clientState.setProxyAuthExchange(clientContext.getAuthExchange(proxyHost));
        }
    }

    /**
     * Store Proxy auth state of clientState
     * @param clientState {@link HttpClientState} May be null if first request
     */
    private static void setupProxyAuthState(HttpClientState clientState, HttpClientContext clientContext) {
        if (clientState != null) {
            AuthExchange proxyAuthExchange = clientState.getProxyAuthExchange();
            HttpHost proxyHost = clientState.getProxyHost();
            if (proxyHost != null && proxyAuthExchange != null) {
                clientContext.setAuthExchange(proxyHost, proxyAuthExchange);
            }
        }
    }

    /**
     * @param uri {@link URI}
     * @param method HTTP Method
     * @param areFollowingRedirect Are we following redirects
     * @return {@link HttpUriRequestBase}
     */
    private HttpUriRequestBase createHttpRequest(URI uri, String method, boolean areFollowingRedirect) {
        HttpUriRequestBase result;
        if (method.equals(HTTPConstants.POST)) {
            result = new HttpPost(uri);
        } else if (method.equals(HTTPConstants.GET)) {
            // Some servers fail if Content-Length is equal to 0
            // so to avoid this we use HttpGet when there is no body (Content-Length will not be set)
            // otherwise we use HttpGetWithEntity
            if ( !areFollowingRedirect
                    && ((!hasArguments() && getSendFileAsPostBody())
                    || getSendParameterValuesAsPostBody()) ) {
                result = new HttpGetWithEntity(uri);
            } else {
                result = new HttpGet(uri);
            }
        } else if (method.equals(HTTPConstants.PUT)) {
            result =  new HttpPut(uri);
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
            throw new IllegalArgumentException("Unexpected method: '"+method+"'");
        }
        return result;
    }

    void setupPreemptiveBasicAuth(URL url, HttpUriRequestBase httpRequest) {
        AuthManager authManager = getAuthManager();
        if (!BASIC_AUTH_PREEMPTIVE || authManager == null || httpRequest.containsHeader("Authorization")) {
            return;
        }
        Authorization authorization = authManager.getAuthForURL(url);
        if (authorization != null) {
            PreemptiveAuthRequestInterceptor.addPreemptiveBasicHeader(httpRequest, authorization);
        }
    }

    /**
     * Store in JMeter Variables the UserToken so that the SSL context is reused
     * See <a href="https://bz.apache.org/bugzilla/show_bug.cgi?id=57804">Bug 57804</a>
     * @param jMeterVariables {@link JMeterVariables}
     * @param localContext {@link HttpContext}
     */
    private static void extractClientContextAfterSample(JMeterVariables jMeterVariables, HttpContext localContext) {
        Object userToken = localContext.getAttribute(HttpClientContext.USER_TOKEN);
        if(userToken != null) {
            log.debug("Extracted from HttpContext user token:{} storing it as JMeter variable:{}", userToken, JMETER_VARIABLE_USER_TOKEN);
            // During recording JMeterContextService.getContext().getVariables() is null
            if (jMeterVariables != null) {
                jMeterVariables.putObject(JMETER_VARIABLE_USER_TOKEN, userToken);
            }
        }
    }

    /**
     * Configure the UserToken so that the SSL context is reused
     * See <a href="https://bz.apache.org/bugzilla/show_bug.cgi?id=57804">Bug 57804</a>
     * @param jMeterVariables {@link JMeterVariables}
     * @param localContext {@link HttpContext}
     */
    private static void setupClientContextBeforeSample(JMeterVariables jMeterVariables, HttpContext localContext) {
        Object userToken = null;
        // During recording JMeterContextService.getContext().getVariables() is null
        if(jMeterVariables != null) {
            userToken = jMeterVariables.getObject(JMETER_VARIABLE_USER_TOKEN);
        }
        if(userToken != null) {
            log.debug("Found user token:{} as JMeter variable:{}, storing it in HttpContext", userToken, JMETER_VARIABLE_USER_TOKEN);
            localContext.setAttribute(HttpClientContext.USER_TOKEN, userToken);
        } else {
            // It would be better to create a ClientSessionManager that would compute this value
            // for now it can be Thread.currentThread().getName() but must be changed when we would change
            // the Thread per User model
            String userId = Thread.currentThread().getName();
            log.debug("Storing in HttpContext the user token: {}", userId);
            localContext.setAttribute(HttpClientContext.USER_TOKEN, userId);
        }
    }

    private void setupLocalAddress(HttpContext localContext) throws IOException {
        final InetAddress inetAddr = getIpSourceAddress();
        if (inetAddr != null) {
            localContext.setAttribute(CONTEXT_ATTRIBUTE_LOCAL_ADDRESS, inetAddr);
        } else if (localAddress != null) {
            localContext.setAttribute(CONTEXT_ATTRIBUTE_LOCAL_ADDRESS, localAddress);
        }
    }

    /**
     * Setup Body of request if different from GET.
     * Field HTTPSampleResult#queryString of result is modified in the 2 cases
     *
     * @param method       String HTTP method
     * @param result       {@link HTTPSampleResult}
     * @param httpRequest  {@link HttpUriRequestBase}
     * @param localContext {@link HttpContext}
     * @throws IOException when posting data fails due to I/O
     */
    protected void handleMethod(String method, HTTPSampleResult result,
            HttpUriRequestBase httpRequest, HttpContext localContext) throws IOException {
        // Handle the various methods
        if (canHaveEntity(method, httpRequest)) {
            String entityBody = setupHttpEntityEnclosingRequestData(httpRequest);
            result.setQueryString(entityBody);
        }
    }

    private static boolean canHaveEntity(String method, HttpUriRequestBase httpRequest) {
        return httpRequest instanceof HttpGetWithEntity ||
                httpRequest instanceof HttpDelete ||
                HTTPConstants.POST.equals(method) ||
                HTTPConstants.PUT.equals(method) ||
                HTTPConstants.PATCH.equals(method);
    }

    /**
     * Create HTTPSampleResult filling url, method and SampleLabel.
     * Monitor field is computed calling isMonitor()
     * @param url URL
     * @param method HTTP Method
     * @return {@link HTTPSampleResult}
     */
    protected HTTPSampleResult createSampleResult(URL url, String method) {
        HTTPSampleResult res = new HTTPSampleResult();

        configureSampleLabel(res, url);
        res.setHTTPMethod(method);
        res.setURL(url);

        return res;
    }

    /**
     * Execute request either as is or under PrivilegedAction
     * if a Subject is available for url
     * @param httpClient the {@link CloseableHttpClient} to be used to execute the httpRequest
     * @param httpRequest the {@link HttpRequest} to be executed
     * @param localContext th {@link HttpContext} to be used for execution
     * @param url the target url (will be used to look up a possible subject for the execution)
     * @return the result of the execution of the httpRequest
     * @throws IOException
     */
    private CloseableHttpResponse executeRequest(final CloseableHttpClient httpClient,
            final HttpUriRequestBase httpRequest, final HttpContext localContext, final URL url)
            throws IOException {
        AuthManager authManager = getAuthManager();
        if (authManager != null) {
            Subject subject = authManager.getSubjectForUrl(url);
            if (subject != null) {
                try {
                    return Subject.doAs(subject,
                            (PrivilegedExceptionAction<CloseableHttpResponse>) () ->
                                    httpClient.execute(httpRequest, localContext));
                } catch (PrivilegedActionException e) {
                    log.error("Can't execute httpRequest with subject: {}", subject, e);
                    throw new IllegalArgumentException("Can't execute httpRequest with subject:" + subject, e);
                }
            }
        }
        return httpClient.execute(httpRequest, localContext);
    }

    /**
     * Holder class for all fields that define an HttpClient instance;
     * used as the key to the ThreadLocal map of HttpClient instances.
     */
    private static final class HttpClientKey {

        private final String protocol;
        private final String authority;
        private final boolean hasProxy;
        private final String proxyScheme;
        private final String proxyHost;
        private final int proxyPort;
        private final String proxyUser;
        private final String proxyPass;

        private final int hashCode; // Always create hash because we will always need it

        /**
         * @param url URL Only protocol and url authority are used (protocol://[user:pass@]host:[port])
         * @param hasProxy has proxy
         * @param proxyScheme scheme
         * @param proxyHost proxy host
         * @param proxyPort proxy port
         * @param proxyUser proxy user
         * @param proxyPass proxy password
         */
        private HttpClientKey(URL url, boolean hasProxy, String proxyScheme, String proxyHost,
                int proxyPort, String proxyUser, String proxyPass) {
            // N.B. need to separate protocol from authority otherwise http://server would match https://erver (<= sic, not typo error)
            // could use separate fields, but simpler to combine them
            this.protocol = url.getProtocol();
            this.authority = url.getAuthority();
            this.hasProxy = hasProxy;
            this.proxyScheme = proxyScheme;
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.proxyUser = proxyUser;
            this.proxyPass = proxyPass;
            this.hashCode = getHash();
        }

        private int getHash() {
            int hash = 17;
            hash = hash*31 + (hasProxy ? 1 : 0);
            if (hasProxy) {
                hash = hash*31 + getHash(proxyScheme);
                hash = hash*31 + getHash(proxyHost);
                hash = hash*31 + proxyPort;
                hash = hash*31 + getHash(proxyUser);
                hash = hash*31 + getHash(proxyPass);
            }
            hash = hash*31 + getHash(protocol);
            hash = hash*31 + getHash(authority);
            return hash;
        }

        // Allow for null strings
        private static int getHash(String s) {
            return s == null ? 0 : s.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof HttpClientKey other)) {
                return false;
            }
            if (!Objects.equals(authority, other.authority) ||
                    !Objects.equals(protocol, other.protocol) ||
                    hasProxy != other.hasProxy) {
                return false;
            }
            if (!hasProxy) {
                // No proxy, so don't check proxy fields
                return true;
            }
            return
                this.proxyPort == other.proxyPort &&
                Objects.equals(proxyScheme, other.proxyScheme) &&
                this.proxyHost.equals(other.proxyHost) &&
                this.proxyUser.equals(other.proxyUser) &&
                this.proxyPass.equals(other.proxyPass);
        }

        @Override
        public int hashCode(){
            return hashCode;
        }

        // For debugging
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(protocol);
            sb.append("://");
            sb.append(authority);
            if (hasProxy) {
                sb.append(" via ");
                sb.append(proxyUser);
                sb.append('@');
                sb.append(proxyScheme);
                sb.append("://");
                sb.append(proxyHost);
                sb.append(':');
                sb.append(proxyPort);
            }
            return sb.toString();
        }
    }

    private HttpClientState setupClient(HttpClientKey key, JMeterVariables jMeterVariables,
            HttpClientContext clientContext) throws GeneralSecurityException {
        Map<HttpClientKey, HttpClientState> mapHttpClientPerHttpClientKey =
                HTTPCLIENTS_CACHE_PER_THREAD_AND_HTTPCLIENTKEY.get();
        clientContext.setAttribute(CONTEXT_ATTRIBUTE_CLIENT_KEY, key);
        CloseableHttpClient httpClient = null;
        HttpClientState clientState = null;
        boolean concurrentDwn = this.testElement.isConcurrentDwn();
        Map<String, Object> samplerContext = JMeterContextService.getContext().getSamplerContext();
        if(concurrentDwn) {
            clientState = (HttpClientState)
                    samplerContext.get(CONTEXT_ATTRIBUTE_PARENT_SAMPLE_CLIENT_STATE);
        }
        if (clientState == null) {
            clientState = mapHttpClientPerHttpClientKey.get(key);
        }

        if(clientState != null) {
            httpClient = clientState.getClient();
        }
        resetStateIfNeeded(clientState, jMeterVariables, clientContext, mapHttpClientPerHttpClientKey);

        if (httpClient == null) { // One-time init for this client
            DNSCacheManager dnsCacheManager = this.testElement.getDNSResolver();
            DnsResolver resolver;
            if (dnsCacheManager == null) {
                resolver = SystemDefaultDnsResolver.INSTANCE;
            } else {
                resolver = new DnsResolver() {
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
            Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory> create().
                    register("https", new LazyLayeredConnectionSocketFactoryHC5()).
                    register("http", CONNECTION_SOCKET_FACTORY).
                    build();

            // Modern browsers use more connections per host than the current httpclient default (2)
            // when using parallel download the httpclient and connection manager are shared by the downloads threads
            // to be realistic JMeter must set an higher value to DefaultMaxPerRoute
            PoolingHttpClientConnectionManager pHCCM =
                    new PoolingHttpClientConnectionManager(
                            new JMeterDefaultHttpClientConnectionOperator(registry, null, resolver),
                            null, null, TimeValue.ofMilliseconds(TIME_TO_LIVE), null);
            pHCCM.setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setTimeToLive(TIME_TO_LIVE, TimeUnit.MILLISECONDS)
                    .setValidateAfterInactivity(VALIDITY_AFTER_INACTIVITY_TIMEOUT, TimeUnit.MILLISECONDS)
                    .build());

            if(concurrentDwn) {
                try {
                    int maxConcurrentDownloads = Integer.parseInt(this.testElement.getConcurrentPool());
                    pHCCM.setDefaultMaxPerRoute(Math.max(maxConcurrentDownloads, pHCCM.getDefaultMaxPerRoute()));
                } catch (NumberFormatException nfe) {
                   // no need to log -> will be done by the sampler
                }
            }

            CookieSpecFactory cookieSpecProvider = new IgnoreCookieSpecFactory();
            Lookup<CookieSpecFactory> cookieSpecRegistry = RegistryBuilder.<CookieSpecFactory>create()
                    .register(StandardCookieSpec.IGNORE, cookieSpecProvider)
                    .build();

            HttpClientBuilder builder = HttpClients.custom().setConnectionManager(pHCCM).
                    setRequestExecutor(REQUEST_EXECUTOR).
                    setDefaultCookieSpecRegistry(cookieSpecRegistry).
                    setRedirectStrategy(new LaxRedirectStrategy()).
                    setRetryStrategy(new DefaultHttpRequestRetryStrategy(RETRY_COUNT, TimeValue.ZERO_MILLISECONDS) {
                        @Override
                        protected boolean handleAsIdempotent(HttpRequest request) {
                            return REQUEST_SENT_RETRY_ENABLED || super.handleAsIdempotent(request);
                        }
                    }).
                    setConnectionReuseStrategy(DefaultClientConnectionReuseStrategy.INSTANCE).
                    setProxyAuthenticationStrategy(getProxyAuthStrategy());
            if(DISABLE_DEFAULT_UA) {
                builder.disableDefaultUserAgent();
            }
            Lookup<AuthSchemeFactory> authSchemeRegistry =
                    RegistryBuilder.<AuthSchemeFactory>create()
                        .register(StandardAuthScheme.BASIC, new BasicSchemeFactory())
                        .register(StandardAuthScheme.DIGEST, new DigestSchemeFactory())
                        .register(StandardAuthScheme.NTLM, new NTLMSchemeFactory())
                        .register(StandardAuthScheme.SPNEGO, new SPNegoSchemeFactory(KERBEROS_CONFIG, resolver))
                        .register(StandardAuthScheme.KERBEROS, new KerberosSchemeFactory(KERBEROS_CONFIG, resolver))
                        .build();
            builder.setDefaultAuthSchemeRegistry(authSchemeRegistry);

            if (IDLE_TIMEOUT > 0) {
                builder.setKeepAliveStrategy(IDLE_STRATEGY);
            }

            // Set up proxy details
            AuthScope proxyAuthScope = null;
            NTCredentials proxyCredentials = null;
            HttpHost proxy = null;
            if (key.hasProxy) {
                proxy = new HttpHost(key.proxyScheme, key.proxyHost, key.proxyPort);

                CredentialsStore credsProvider = new BasicCredentialsProvider();
                if (!key.proxyUser.isEmpty()) {
                    proxyAuthScope = new AuthScope(key.proxyHost, key.proxyPort);
                    proxyCredentials = new NTCredentials(key.proxyUser, key.proxyPass.toCharArray(), LOCALHOST, PROXY_DOMAIN);
                    credsProvider.setCredentials(
                            proxyAuthScope,
                            proxyCredentials);
                }
                builder.setDefaultCredentialsProvider(credsProvider);
            }
            builder.setRoutePlanner(new JMeterDefaultRoutePlanner(proxy));
            builder.disableContentCompression(); // Disable automatic decompression
            if(BASIC_AUTH_PREEMPTIVE) {
                builder.addRequestInterceptorFirst(PREEMPTIVE_AUTH_INTERCEPTOR);
            } else {
                builder.setDefaultCredentialsProvider(new ManagedCredentialsProvider(getAuthManager(), proxyAuthScope, proxyCredentials));
            }
            httpClient = builder.build();
            if (log.isDebugEnabled()) {
                log.debug("Created new HttpClient: @{} {}", System.identityHashCode(httpClient), key);
            }
            clientState = new HttpClientState(httpClient, pHCCM, proxy);
            mapHttpClientPerHttpClientKey.put(key, clientState); // save the agent for next time round
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Reusing the HttpClient: @{} {}", System.identityHashCode(httpClient),key);
            }
        }
        setupProxyAuthState(clientState, clientContext);

        if(concurrentDwn) {
            samplerContext.put(CONTEXT_ATTRIBUTE_PARENT_SAMPLE_CLIENT_STATE, clientState);
        }
        return clientState;
    }

    protected AuthenticationStrategy getProxyAuthStrategy() {
        return DefaultAuthenticationStrategy.INSTANCE;
    }

    private HttpClientKey createHttpClientKey(URL url) {
        final String host = url.getHost();
        String proxyScheme = getProxyScheme();
        String proxyHost = getProxyHost();
        int proxyPort = getProxyPortInt();
        String proxyPass = getProxyPass();
        String proxyUser = getProxyUser();

        // static proxy is the globally define proxy eg command line or properties
        boolean useStaticProxy = isStaticProxy(host);
        // dynamic proxy is the proxy defined for this sampler
        boolean useDynamicProxy = isDynamicProxy(proxyHost, proxyPort);
        boolean useProxy = useStaticProxy || useDynamicProxy;

        // if both dynamic and static are used, the dynamic proxy has priority over static
        if(!useDynamicProxy) {
            proxyScheme = PROXY_SCHEME;
            proxyHost = PROXY_HOST;
            proxyPort = PROXY_PORT;
            proxyUser = PROXY_USER;
            proxyPass = PROXY_PASS;
        }

        // Lookup key - must agree with all the values used to create the HttpClient.
        return new HttpClientKey(url, useProxy, proxyScheme, proxyHost, proxyPort, proxyUser, proxyPass);
    }

    /**
     * Reset SSL State. <br/>
     * In order to do that we need to:
     * <ul>
     *  <li>Call resetContext() on SSLManager</li>
     *  <li>Close current Idle or Expired connections that hold SSL State</li>
     *  <li>Remove HttpClientContext.USER_TOKEN from {@link HttpClientContext}</li>
     * </ul>
     * @param jMeterVariables {@link JMeterVariables}
     * @param clientContext {@link HttpClientContext}
     * @param mapHttpClientPerHttpClientKey Map of {@link HttpClientState} holding {@link CloseableHttpClient} and {@link PoolingHttpClientConnectionManager}
     */
    private static void resetStateIfNeeded(
            HttpClientState clientState,
            JMeterVariables jMeterVariables,
            HttpClientContext clientContext,
            Map<HttpClientKey, ? extends HttpClientState> mapHttpClientPerHttpClientKey) {
        if (resetStateOnThreadGroupIteration.get()) {
            closeCurrentConnections(mapHttpClientPerHttpClientKey);
            clientContext.removeAttribute(HttpClientContext.USER_TOKEN);
            clientContext.getAuthExchanges().clear();
            if (clientState != null) {
                clientState.setProxyAuthExchange(null);
            }
            jMeterVariables.remove(JMETER_VARIABLE_USER_TOKEN);
            ((JsseSSLManager) SSLManager.getInstance()).resetContext();
            resetStateOnThreadGroupIteration.set(false);
        }
    }

    /**
     * @param mapHttpClientPerHttpClientKey
     */
    private static void closeCurrentConnections(
            Map<HttpClientKey, ? extends HttpClientState> mapHttpClientPerHttpClientKey) {
        for (HttpClientState clientState :
                mapHttpClientPerHttpClientKey.values()) {
            PoolingHttpClientConnectionManager poolingHttpClientConnectionManager = clientState.getConnectionManager();
            poolingHttpClientConnectionManager.closeExpired();
            poolingHttpClientConnectionManager.closeIdle(TimeValue.ofMicroseconds(1L));
        }
    }

    /**
     * Setup following elements on httpRequest:
     * <ul>
     * <li>ConnRoutePNames.LOCAL_ADDRESS enabling IP-SPOOFING</li>
     * <li>Socket and connection timeout</li>
     * <li>Redirect handling</li>
     * <li>Keep Alive header or Connection Close</li>
     * <li>Calls setConnectionHeaders to setup headers</li>
     * <li>Calls setConnectionCookie to setup Cookie</li>
     * </ul>
     *
     * @param url         {@link URL} of the request
     * @param httpRequest http request for the request
     * @param res         sample result to set cookies on
     * @throws IOException if hostname/ip to use could not be figured out
     */
    protected void setupRequest(URL url, HttpUriRequestBase httpRequest, HTTPSampleResult res)
        throws IOException {
        RequestConfig.Builder rCB = RequestConfig.custom();
        int rto = getResponseTimeout();
        if (rto > 0){
            rCB.setResponseTimeout(Timeout.ofMilliseconds(rto));
        }

        int cto = getConnectTimeout();
        if (cto > 0){
            rCB.setConnectTimeout(Timeout.ofMilliseconds(cto));
        }

        rCB.setRedirectsEnabled(getAutoRedirects());
        rCB.setMaxRedirects(HTTPSamplerBase.MAX_REDIRECTS);
        httpRequest.setConfig(rCB.build());
        // a well-behaved browser is supposed to send 'Connection: close'
        // with the last request to an HTTP server. Instead, most browsers
        // leave it to the server to close the connection after their
        // timeout period. Leave it to the JMeter user to decide.
        if (getUseKeepAlive()) {
            httpRequest.setHeader(HTTPConstants.HEADER_CONNECTION, HTTPConstants.KEEP_ALIVE);
        } else {
            httpRequest.setHeader(HTTPConstants.HEADER_CONNECTION, HTTPConstants.CONNECTION_CLOSE);
        }

        setConnectionHeaders(httpRequest, url, getHeaderManager(), getCacheManager());

        String cookies = setConnectionCookie(httpRequest, url, getCookieManager());

        if (res != null) {
            if (StringUtilities.isNotEmpty(cookies)) {
                res.setCookies(cookies);
            } else {
                // During recording Cookie Manager doesn't handle cookies
                res.setCookies(getOnlyCookieFromHeaders(httpRequest));
            }
        }

    }

    /**
     * Set any default request headers to include
     *
     * @param request the HttpRequest to be used
     */
    protected void setDefaultRequestHeaders(HttpRequest request) {
     // Method left empty here, but allows subclasses to override
    }

    /**
     * Gets the ResponseHeaders
     *
     * @param response containing the headers
     * @return string containing the headers, one per line
     */
    private static String getResponseHeaders(HttpResponse response) {
        Header[] rh = response.getHeaders();

        StringBuilder headerBuf = new StringBuilder(40 * (rh.length+1));
        headerBuf.append(new StatusLine(response));// header[0] is not the status line...
        headerBuf.append("\n"); // $NON-NLS-1$

        for (Header responseHeader : rh) {
            writeHeader(headerBuf, responseHeader);
        }
        return headerBuf.toString();
    }

    /**
     * Write header to headerBuffer in an optimized way
     * @param headerBuffer {@link StringBuilder}
     * @param header {@link Header}
     */
    private static void writeHeader(StringBuilder headerBuffer, Header header) {
        if(header instanceof BufferedHeader bufferedHeader) {
            CharArrayBuffer buffer = bufferedHeader.getBuffer();
            headerBuffer.append(buffer.array(), 0, buffer.length()).append('\n'); // $NON-NLS-1$
        }
        else {
            headerBuffer.append(header.getName())
            .append(": ") // $NON-NLS-1$
            .append(header.getValue())
            .append('\n'); // $NON-NLS-1$
        }
    }

    /**
     * Extracts all the required cookies for that particular URL request and
     * sets them in the <code>HttpMethod</code> passed in.
     *
     * @param request <code>HttpRequest</code> for the request
     * @param url <code>URL</code> of the request
     * @param cookieManager the <code>CookieManager</code> containing all the cookies
     * @return a String containing the cookie details (for the response)
     * May be null
     */
    protected String setConnectionCookie(HttpRequest request, URL url, CookieManager cookieManager) {
        String cookieHeader = null;
        if (cookieManager != null) {
            cookieHeader = cookieManager.getCookieHeaderForURL(url);
            if (cookieHeader != null) {
                request.setHeader(HTTPConstants.HEADER_COOKIE, cookieHeader);
            }
        }
        return cookieHeader;
    }

    /**
     * Extracts all the required non-cookie headers for that particular URL request and
     * sets them in the <code>HttpMethod</code> passed in
     *
     * @param request       <code>HttpRequest</code> which represents the request
     * @param url           <code>URL</code> of the URL request
     * @param headerManager the <code>HeaderManager</code> containing all the cookies
     *                      for this <code>UrlConfig</code>
     * @param cacheManager  the CacheManager (may be null)
     */
    protected static void setConnectionHeaders(HttpUriRequestBase request, URL url, HeaderManager headerManager, CacheManager cacheManager) {
        if (headerManager != null) {
            CollectionProperty headers = headerManager.getHeaders();
            if (headers != null) {
                for (JMeterProperty jMeterProperty : headers) {
                    org.apache.jmeter.protocol.http.control.Header header
                            = (org.apache.jmeter.protocol.http.control.Header)
                            jMeterProperty.getObjectValue();
                    String headerName = header.getName();
                    // Don't allow override of Content-Length
                    if (!HTTPConstants.HEADER_CONTENT_LENGTH.equalsIgnoreCase(headerName)) {
                        String headerValue = header.getValue();
                        if (HTTPConstants.HEADER_HOST.equalsIgnoreCase(headerName)) {
                            int port = getPortFromHostHeader(headerValue, url.getPort());
                            // remove any port specification
                            headerValue = headerValue.replaceFirst(":\\d+$", ""); // $NON-NLS-1$ $NON-NLS-2$
                            if (port != -1 && port == url.getDefaultPort()) {
                                port = -1; // no need to specify the port if it is the default
                            }
                            if(port == -1) {
                                request.addHeader(HEADER_HOST, headerValue);
                            } else {
                                request.addHeader(HEADER_HOST, headerValue+":"+port);
                            }
                        } else {
                            request.addHeader(headerName, headerValue);
                        }
                    }
                }
            }
        }
        if (cacheManager != null) {
            cacheManager.setHeaders(url, request);
        }
    }

    /**
     * Get port from the value of the Host header, or return the given
     * defaultValue
     *
     * @param hostHeaderValue value of the http Host header
     * @param defaultValue    value to be used, when no port could be extracted from
     *                        hostHeaderValue
     * @return integer representing the port for the host header
     */
    private static int getPortFromHostHeader(String hostHeaderValue, int defaultValue) {
        String[] hostParts = hostHeaderValue.split(":");
        if (hostParts.length > 1) {
            String portString = hostParts[hostParts.length - 1];
            if (PORT_PATTERN.matcher(portString).matches()) {
                return Integer.parseInt(portString);
            }
        }
        return defaultValue;
    }

    /**
     * Get all the request headers except Cookie for the <code>HttpRequest</code>
     *
     * @param method <code>HttpMethod</code> which represents the request
     * @return the headers as a string
     */
    private static String getAllHeadersExceptCookie(HttpRequest method) {
        return getFromHeadersMatchingPredicate(method, ALL_EXCEPT_COOKIE);
    }

    /**
     * Get only Cookie header for the <code>HttpRequest</code>
     *
     * @param method <code>HttpMethod</code> which represents the request
     * @return the headers as a string
     */
    private static String getOnlyCookieFromHeaders(HttpRequest method) {
        String cookieHeader= getFromHeadersMatchingPredicate(method, ONLY_COOKIE).trim();
        if(!cookieHeader.isEmpty()) {
            return cookieHeader.substring(HTTPConstants.HEADER_COOKIE_IN_REQUEST.length()).trim();
        }
        return "";
    }


    /**
     * Get only cookies from request headers for the <code>HttpRequest</code>
     *
     * @param method <code>HttpMethod</code> which represents the request
     * @return the headers as a string
     */
    private static String getFromHeadersMatchingPredicate(HttpRequest method, Predicate<? super String> predicate) {
        if(method != null) {
            // Get all the request headers
            StringBuilder hdrs = new StringBuilder(150);
            Header[] requestHeaders = method.getHeaders();
            for (Header requestHeader : requestHeaders) {
                // Get header if it matches predicate
                if (predicate.test(requestHeader.getName())) {
                    writeHeader(hdrs, requestHeader);
                }
            }

            return hdrs.toString();
        }
        return ""; ////$NON-NLS-1$
    }

    private static Charset toCharset(String charset) {
        return StringUtilities.isEmpty(charset) ? StandardCharsets.ISO_8859_1 : Charset.forName(charset);
    }

    // Helper class so we can generate request data without dumping entire file contents
    private static class ViewableFileBody extends FileBody {
        private static final byte[] CONTENTS_OMITTED =
                "<actual file content, not shown here>".getBytes(StandardCharsets.UTF_8);
        private boolean hideFileData;

        private ViewableFileBody(File file, ContentType contentType, Charset charset) {
            // Note: keep the historical JMeter filename encoding behavior for multipart uploads.
            // See https://issues.apache.org/jira/browse/HTTPCLIENT-293
            super(file, contentType, encodeFilename(file.getName(), charset));
            hideFileData = false;
        }

        private static String encodeFilename(String fileName, Charset charset) {
            return ConversionUtils.percentEncode(
                    ConversionUtils.encodeWithEntities(fileName, charset));
        }

        @Override
        public void writeTo(final OutputStream out) throws IOException {
            if (hideFileData) {
                out.write(CONTENTS_OMITTED);
            } else {
                super.writeTo(out);
            }
        }
    }

    /**
     * @param entityEnclosingRequest {@link ClassicHttpRequest}
     * @return String body sent if computable
     * @throws IOException if sending the data fails due to I/O
     */
    protected String setupHttpEntityEnclosingRequestData(ClassicHttpRequest entityEnclosingRequest)  throws IOException {
        // Buffer to hold the post body, except file content
        StringBuilder postedBody = new StringBuilder(1000);
        HTTPFileArg[] files = getHTTPFiles();

        final String contentEncoding = getContentEncoding();
        Charset charset = Charset.forName(contentEncoding);
        final boolean haveContentEncoding = true;

        // Check if we should do a multipart/form-data or an
        // application/x-www-form-urlencoded post request
        if(getUseMultipart()) {
            if (entityEnclosingRequest.getHeaders(HTTPConstants.HEADER_CONTENT_TYPE).length > 0) {
                log.info(
                        "Content-Header is set already on the request! Will be replaced by a Multipart-Header. Old headers: {}",
                        Arrays.asList(entityEnclosingRequest.getHeaders(HTTPConstants.HEADER_CONTENT_TYPE)));
                entityEnclosingRequest.removeHeaders(HTTPConstants.HEADER_CONTENT_TYPE);
            }

            // doBrowserCompatibleMultipart means "use charset for encoding MIME headers",
            // while RFC6532 means "use UTF-8 for encoding MIME headers"
            boolean doBrowserCompatibleMultipart = getDoBrowserCompatibleMultipart();
            if(log.isDebugEnabled()) {
                log.debug("Building multipart with:getDoBrowserCompatibleMultipart(): {}, with charset:{}, haveContentEncoding:{}",
                        doBrowserCompatibleMultipart, charset, haveContentEncoding);
            }
            // Write the request to our own stream
            MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
            multipartEntityBuilder.setCharset(charset);
            if (doBrowserCompatibleMultipart) {
                multipartEntityBuilder.setLaxMode();
            } else {
                // Use UTF-8 for encoding header names and values
                multipartEntityBuilder.setMode(HttpMultipartMode.EXTENDED);
            }
            // Create the parts
            // Add any parameters
            for (JMeterProperty jMeterProperty : getArguments().getEnabledArguments()) {
                HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
                String parameterName = arg.getName();
                if (arg.isSkippable(parameterName)) {
                    continue;
                }
                ContentType contentType;
                if (arg.getContentType().indexOf(';') >= 0) {
                    // assume, that the content type contains charset info
                    // don't add another charset and use parse to cope with the semicolon
                    contentType = ContentType.parse(arg.getContentType());
                } else {
                    contentType = ContentType.create(arg.getContentType(), charset);
                }
                StringBody stringBody = new StringBody(arg.getValue(), contentType);
                FormBodyPartBuilder formPartBuilder = FormBodyPartBuilder.create(parameterName, stringBody);
                if (!doBrowserCompatibleMultipart) {
                    formPartBuilder.addField(HEADER_CONTENT_TRANSFER_ENCODING, CONTENT_TRANSFER_ENCODING_8BIT);
                }
                FormBodyPart formPart = formPartBuilder.build();
                multipartEntityBuilder.addPart(formPart);
            }

            // Add any files
            // Cannot retrieve parts once added to the MultiPartEntity, so have to save them here.
            ViewableFileBody[] fileBodies = new ViewableFileBody[files.length];
            for (int i=0; i < files.length; i++) {
                HTTPFileArg file = files[i];

                File reservedFile = FileServer.getFileServer().getResolvedFile(file.getPath());
                Charset filenameCharset = doBrowserCompatibleMultipart ? charset : StandardCharsets.UTF_8;
                fileBodies[i] = new ViewableFileBody(reservedFile, ContentType.parse(file.getMimeType()), filenameCharset);
                FormBodyPartBuilder formPartBuilder = FormBodyPartBuilder.create(file.getParamName(), fileBodies[i]);
                if (!doBrowserCompatibleMultipart) {
                    formPartBuilder.addField(HEADER_CONTENT_TRANSFER_ENCODING, CONTENT_TRANSFER_ENCODING_BINARY);
                }
                multipartEntityBuilder.addPart(formPartBuilder.build());
            }

            HttpEntity entity = multipartEntityBuilder.build();
            entityEnclosingRequest.setHeader(HTTPConstants.HEADER_CONTENT_TYPE, getMultipartContentType(entity));
            entityEnclosingRequest.setEntity(entity);
            writeEntityToSB(postedBody, entity, fileBodies, contentEncoding);
        } else { // not multipart
            // Check if the header manager had a content type header
            // This allows the user to specify their own content-type for a POST request
            Header contentTypeHeader = entityEnclosingRequest.getFirstHeader(HTTPConstants.HEADER_CONTENT_TYPE);
            boolean hasContentTypeHeader = contentTypeHeader != null && StringUtilities.isNotEmpty(contentTypeHeader.getValue());
            // If there are no arguments, we can send a file as the body of the request
            // TODO: needs a multiple file upload scenario
            if(!hasArguments() && getSendFileAsPostBody()) {
                // If getSendFileAsPostBody returned true, it's sure that file is not null
                HTTPFileArg file = files[0];
                if(!hasContentTypeHeader) {
                    // Allow the mimetype of the file to control the content type
                    if (StringUtilities.isNotEmpty(file.getMimeType())) {
                        entityEnclosingRequest.setHeader(HTTPConstants.HEADER_CONTENT_TYPE, file.getMimeType());
                    }
                    else if(ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
                        entityEnclosingRequest.setHeader(HTTPConstants.HEADER_CONTENT_TYPE, HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED);
                    }
                }
                FileEntity fileRequestEntity = new FileEntity(FileServer.getFileServer().getResolvedFile(file.getPath()), (ContentType) null);
                entityEnclosingRequest.setEntity(fileRequestEntity);

                // We just add placeholder text for file content
                postedBody.append("<actual file content, not shown here>");
            } else {
                // In a post request which is not multipart, we only support
                // parameters, no file upload is allowed

                // If none of the arguments have a name specified, we
                // just send all the values as the post body
                if(getSendParameterValuesAsPostBody()) {
                    // Allow the mimetype of the file to control the content type
                    // This is not obvious in GUI if you are not uploading any files,
                    // but just sending the content of nameless parameters
                    // TODO: needs a multiple file upload scenario
                    if(!hasContentTypeHeader) {
                        HTTPFileArg file = files.length > 0? files[0] : null;
                        if(file != null && StringUtilities.isNotEmpty(file.getMimeType())) {
                            entityEnclosingRequest.setHeader(HTTPConstants.HEADER_CONTENT_TYPE, file.getMimeType());
                        }
                        else if(ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
                            entityEnclosingRequest.setHeader(HTTPConstants.HEADER_CONTENT_TYPE, HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED);
                        }
                    }

                    // Just append all the parameter values, and use that as the post body
                    StringBuilder postBody = new StringBuilder();
                    for (JMeterProperty jMeterProperty : getArguments().getEnabledArguments()) {
                        HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
                        postBody.append(arg.getEncodedValue(contentEncoding));
                    }
                    // Let StringEntity perform the encoding
                    StringEntity requestEntity = new StringEntity(postBody.toString(), toCharset(contentEncoding));
                    entityEnclosingRequest.setEntity(requestEntity);
                    postedBody.append(postBody.toString());
                } else {
                    // It is a normal post request, with parameter names and values
                    // Set the content type
                    if(!hasContentTypeHeader && ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
                        entityEnclosingRequest.setHeader(HTTPConstants.HEADER_CONTENT_TYPE, HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED);
                    }
                    UrlEncodedFormEntity entity = createUrlEncodedFormEntity(contentEncoding);
                    entityEnclosingRequest.setEntity(entity);
                    writeEntityToSB(postedBody, entity, EMPTY_FILE_BODIES, contentEncoding);
                }
            }
        }
        return postedBody.toString();
    }

    private static String getMultipartContentType(HttpEntity entity) {
        String contentType = entity.getContentType();
        String boundary = ContentType.parse(contentType).getParameter("boundary");
        if (boundary == null) {
            return contentType;
        }
        return ContentType.MULTIPART_FORM_DATA.getMimeType() + "; boundary=" + boundary;
    }

    /**
     * @param postedBody
     * @param entity
     * @param fileBodies Array of {@link ViewableFileBody}
     * @param contentEncoding
     * @throws IOException
     * @throws UnsupportedEncodingException
     */
    private static void writeEntityToSB(final StringBuilder postedBody, final HttpEntity entity,
            final ViewableFileBody[] fileBodies, final String contentEncoding)
                    throws IOException {
        if (entity.isRepeatable()){
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for(ViewableFileBody fileBody : fileBodies){
                fileBody.hideFileData = true;
            }
            entity.writeTo(bos);
            for(ViewableFileBody fileBody : fileBodies){
                fileBody.hideFileData = false;
            }
            bos.flush();
            // We get the posted bytes using the encoding used to create it
            postedBody.append(bos.toString(
                    contentEncoding == null ? SampleResult.DEFAULT_HTTP_ENCODING
                    : contentEncoding));
            bos.close();
        } else {
            postedBody.append("<Entity was not repeatable, cannot view what was sent>"); // $NON-NLS-1$
        }
    }

    /**
     * Creates the entity data to be sent.
     * <p>
     * If there is a file entry with a non-empty MIME type we use that to
     * set the request Content-Type header, otherwise we default to whatever
     * header is present from a Header Manager.
     * <p>
     * If the content charset {@link #getContentEncoding()} is null or empty
     * we use the HC5 default content charset, ISO-8859-1.
     *
     * @param entity to be processed, e.g. PUT or PATCH
     * @return the entity content, may be empty
     * @throws  UnsupportedEncodingException for invalid charset name
     * @throws IOException cannot really occur for ByteArrayOutputStream methods
     */
    protected String sendEntityData(ClassicHttpRequest entity) throws IOException {
        boolean hasEntityBody = false;

        final HTTPFileArg[] files = getHTTPFiles();
        // Allow the mimetype of the file to control the content type
        // This is not obvious in GUI if you are not uploading any files,
        // but just sending the content of nameless parameters
        final HTTPFileArg file = files.length > 0? files[0] : null;
        String contentTypeValue;
        if(file != null && StringUtilities.isNotEmpty(file.getMimeType())) {
            contentTypeValue = file.getMimeType();
            entity.setHeader(HEADER_CONTENT_TYPE, contentTypeValue); // we provide the MIME type here
        }

        // Check for local contentEncoding (charset) override; fall back to default for content body
        // we do this here rather so we can use the same charset to retrieve the data
        final String charset = getContentEncoding();

        // Only create this if we are overriding whatever default there may be
        // If there are no arguments, we can send a file as the body of the request
        if(!hasArguments() && getSendFileAsPostBody()) {
            hasEntityBody = true;

            // If getSendFileAsPostBody returned true, it's sure that file is not null
            File reservedFile = FileServer.getFileServer().getResolvedFile(files[0].getPath());
            FileEntity fileRequestEntity = new FileEntity(reservedFile, (ContentType) null); // no need for content-type here
            entity.setEntity(fileRequestEntity);
        }
        // If none of the arguments have a name specified, we
        // just send all the values as the entity body
        else if(getSendParameterValuesAsPostBody()) {
            hasEntityBody = true;

            // Just append all the parameter values, and use that as the entity body
            Arguments arguments = getArguments();
            StringBuilder entityBodyContent = new StringBuilder(arguments.getArgumentCount()*15);
            for (JMeterProperty jMeterProperty : arguments) {
                HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
                // Note: if "Encoded?" is not selected, arg.getEncodedValue is equivalent to arg.getValue
                if (charset != null) {
                    entityBodyContent.append(arg.getEncodedValue(charset));
                } else {
                    entityBodyContent.append(arg.getEncodedValue());
                }
            }
            StringEntity requestEntity = new StringEntity(entityBodyContent.toString(), toCharset(charset));
            entity.setEntity(requestEntity);
        } else if (hasArguments()) {
            hasEntityBody = true;
            entity.setEntity(createUrlEncodedFormEntity(getContentEncoding()));
        }
        // Check if we have any content to send for body
        if(hasEntityBody) {
            // If the request entity is repeatable, we can send it first to
            // our own stream, so we can return it
            final HttpEntity entityEntry = entity.getEntity();
            // Buffer to hold the entity body
            StringBuilder entityBody = new StringBuilder(65);
            writeEntityToSB(entityBody, entityEntry, EMPTY_FILE_BODIES, charset);
            return entityBody.toString();
        }
        return ""; // may be the empty string
    }

    /**
     * Create UrlEncodedFormEntity from parameters
     * @param urlContentEncoding Content encoding may be null or empty
     * @return {@link UrlEncodedFormEntity}
     * @throws UnsupportedEncodingException
     */
    private UrlEncodedFormEntity createUrlEncodedFormEntity(final String urlContentEncoding) throws UnsupportedEncodingException {
        // It is a normal request, with parameter names and values
        // Add the parameters
        List<NameValuePair> nvps = new ArrayList<>();
        for (JMeterProperty jMeterProperty: getArguments().getEnabledArguments()) {
            HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
            // The HTTPClient always urlencodes both name and value,
            // so if the argument is already encoded, we have to decode
            // it before adding it to the post request
            String parameterName = arg.getName();
            if (arg.isSkippable(parameterName)) {
                continue;
            }
            String parameterValue = arg.getValue();
            if (!arg.isAlwaysEncoded()) {
                // The value is already encoded by the user
                // Must decode the value now, so that when the
                // httpclient encodes it, we end up with the same value
                // as the user had entered.
                parameterName = URLDecoder.decode(parameterName, urlContentEncoding);
                parameterValue = URLDecoder.decode(parameterValue, urlContentEncoding);
            }
            // Add the parameter, httpclient will urlencode it
            nvps.add(new BasicNameValuePair(parameterName, parameterValue));
        }
        return new UrlEncodedFormEntity(nvps, toCharset(urlContentEncoding));
    }

    private static void saveConnectionCookies(HttpResponse method, URL u, CookieManager cookieManager) {
        if (cookieManager != null) {
            Header[] hdrs = method.getHeaders(HTTPConstants.HEADER_SET_COOKIE);
            for (Header hdr : hdrs) {
                cookieManager.addCookieFromHeader(hdr.getValue(),u);
            }
        }
    }

    @Override
    protected void notifyFirstSampleAfterLoopRestart() {
        log.debug("notifyFirstSampleAfterLoopRestart called "
                + "with config(httpclient.reset_state_on_thread_group_iteration={})",
                RESET_STATE_ON_THREAD_GROUP_ITERATION);
        JMeterVariables jMeterVariables = JMeterContextService.getContext().getVariables();
        if (jMeterVariables.isSameUserOnNextIteration()) {
            log.debug("Thread Group is configured to simulate a returning visitor on each iteration, ignoring property value {}",
                    RESET_STATE_ON_THREAD_GROUP_ITERATION);
            resetStateOnThreadGroupIteration.set(false);
        } else {
            log.debug("Thread Group is configured to simulate a new visitor on each iteration, using property value {}",
                    RESET_STATE_ON_THREAD_GROUP_ITERATION);
            resetStateOnThreadGroupIteration.set(RESET_STATE_ON_THREAD_GROUP_ITERATION);
        }
        log.debug("Thread state will be reset ?: {}", RESET_STATE_ON_THREAD_GROUP_ITERATION);
    }

    @Override
    protected void threadFinished() {
        log.debug("Thread Finished");
        closeThreadLocalConnections();
    }

    private static void closeThreadLocalConnections() {
        // Does not need to be synchronised, as all access is from same thread
        Map<HttpClientKey, HttpClientState>
            mapHttpClientPerHttpClientKey = HTTPCLIENTS_CACHE_PER_THREAD_AND_HTTPCLIENTKEY.get();
        if (mapHttpClientPerHttpClientKey != null ) {
            for (HttpClientState clientState : mapHttpClientPerHttpClientKey.values() ) {
                JOrphanUtils.closeQuietly(clientState.getClient());
                JOrphanUtils.closeQuietly(clientState.getConnectionManager());
            }
            mapHttpClientPerHttpClientKey.clear();
        }
    }

    @Override
    public boolean interrupt() {
        HttpUriRequest request = currentRequest;
        if (request != null) {
            currentRequest = null; // don't try twice
            try {
                request.abort();
            } catch (UnsupportedOperationException e) {
                log.warn("Could not abort pending request", e);
            }
        }
        return request != null;
    }

}
