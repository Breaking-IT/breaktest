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

import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.StringUtilities;

/**
 * Factory to return the appropriate HTTPSampler for use with classes that need
 * an HTTPSampler; also creates the implementations for use with HTTPSamplerProxy.
 *
 */
public final class HTTPSamplerFactory {

    // Legacy sampler names accepted when loading old test plans and properties.
    static final String LEGACY_HTTP_SAMPLER_JAVA = "HTTPSampler"; //$NON-NLS-1$
    static final String LEGACY_HTTP_SAMPLER_APACHE = "HTTPSampler2"; //$NON-NLS-1$

    //+ JMX implementation attribute values (also displayed in GUI) - do not change
    public static final String IMPL_HTTP_CLIENT5 = "HttpClient5";  // $NON-NLS-1$

    static final String LEGACY_IMPL_JAVA = "Java"; // $NON-NLS-1$
    private static final String LEGACY_IMPL_HTTP_CLIENT3_1 = "HttpClient3.1"; // $NON-NLS-1$
    private static final String LEGACY_IMPL_HTTP_CLIENT4 = "HttpClient4";  // $NON-NLS-1$
    //- JMX

    public static final String DEFAULT_CLASSNAME =
        JMeterUtils.getPropDefault("jmeter.httpsampler", ""); //$NON-NLS-1$ //$NON-NLS-2$

    private HTTPSamplerFactory() {
        // Not intended to be instantiated
    }

    /**
     * Create a new instance of the default sampler
     *
     * @return instance of default sampler
     */
    public static HTTPSamplerBase newInstance() {
        return newInstance(DEFAULT_CLASSNAME);
    }

    /**
     * Create a new instance of the required sampler type.
     * Legacy sampler names are accepted when loading old test plans, however all
     * HTTP requests now use the HttpClient 5 implementation.
     *
     * @param alias legacy sampler name or IMPL_HTTP_CLIENT5
     * @return the appropriate sampler
     */
    public static HTTPSamplerBase newInstance(String alias) {
        if (StringUtilities.isBlank(alias)) {
            return new HTTPSamplerProxy();
        }
        if (isKnownImplementation(alias)) {
            return new HTTPSamplerProxy(IMPL_HTTP_CLIENT5);
        }
        throw new IllegalArgumentException("Unknown sampler type: '" + alias+"'");
    }

    public static String[] getImplementations(){
        return new String[]{IMPL_HTTP_CLIENT5};
    }

    /** Compatibility preference used only when the script has no saved upgrade choice. */
    private static final boolean PREFER_HTTP3_FOR_DEFAULT =
            JMeterUtils.getPropDefault("httpsampler.http3.prefer_for_default", true); //$NON-NLS-1$

    /**
     * JMeter property, default true: cleartext ({@code http://}) requests whose HTTP version is
     * left to negotiation use the HTTP/1.1 implementation instead of the negotiating one. See
     * {@link #usesHttp11ForCleartext(String, HTTPSamplerBase, URL)}.
     */
    static final String CLEARTEXT_HTTP11_IMPL = "httpsampler.cleartext.http11_impl"; //$NON-NLS-1$

    /**
     * Whether a request to {@code url} should use {@link HTTPHC5Impl} although the sampler
     * leaves the HTTP version to negotiation. HttpClient5 negotiates HTTP/2 only through TLS
     * ALPN, so a negotiated cleartext request always speaks HTTP/1.1. The classic HTTP/1.1
     * client sends it with far fewer thread hand-offs than the async negotiating client, which
     * runs an I/O reactor per virtual user.
     *
     * @param impl the configured implementation name
     * @param base the sampler
     * @param url  the URL about to be sampled; redirects and embedded resources may differ
     *             from the sampler's own protocol
     * @return true when the HTTP/1.1 implementation should send this request
     */
    public static boolean usesHttp11ForCleartext(String impl, HTTPSamplerBase base, URL url) {
        return usesHttp11ForCleartext(impl, base, url, JMeterUtils.getPropDefault(CLEARTEXT_HTTP11_IMPL, true));
    }

    // package-private so tests can exercise the opt-out path. The HTTP/3 upgrade preference does
    // not matter here: HTTP/3 is only reached from an https origin's Alt-Svc advertisement.
    static boolean usesHttp11ForCleartext(String impl, HTTPSamplerBase base, URL url, boolean enabled) {
        return enabled
                && HTTPConstants.PROTOCOL_HTTP.equalsIgnoreCase(url.getProtocol())
                && StringUtilities.isBlank(base.getHttpProtocol())
                && (StringUtilities.isBlank(impl) || isKnownImplementation(impl));
    }

    /**
     * JMeter property, default true: once TLS negotiation (ALPN) with a host has settled on
     * HTTP/1.1, later requests to that host that leave the HTTP version to negotiation use the
     * HTTP/1.1 implementation. The wire protocol stays the same; the classic client just sends it
     * with less CPU than the HTTP/2-capable async client. Forgotten at the start of each test run.
     */
    static final String TLS_HTTP11_IMPL = "httpsampler.tls.http11_impl_after_alpn"; //$NON-NLS-1$

    private static final ConcurrentMap<String, Boolean> HTTP11_ONLY_ORIGINS = new ConcurrentHashMap<>();
    private static volatile long http11OriginsTestStart = Long.MIN_VALUE;

    private static ConcurrentMap<String, Boolean> http11OnlyOrigins() {
        long testStart = JMeterContextService.getTestStartTime();
        if (testStart != http11OriginsTestStart) {
            synchronized (HTTP11_ONLY_ORIGINS) {
                if (testStart != http11OriginsTestStart) {
                    // A server may have gained HTTP/2 since the previous run.
                    HTTP11_ONLY_ORIGINS.clear();
                    http11OriginsTestStart = testStart;
                }
            }
        }
        return HTTP11_ONLY_ORIGINS;
    }

    private static boolean negotiatesOverTls(String impl, HTTPSamplerBase base, URL url) {
        return HTTPConstants.PROTOCOL_HTTPS.equalsIgnoreCase(url.getProtocol())
                && StringUtilities.isBlank(base.getHttpProtocol())
                && (StringUtilities.isBlank(impl) || isKnownImplementation(impl))
                && JMeterUtils.getPropDefault(TLS_HTTP11_IMPL, true);
    }

    /**
     * Remembers what a negotiated TLS request to {@code url} ended up speaking, so that later
     * requests to an HTTP/1.1-only host can skip the negotiating client.
     *
     * @param impl            the configured implementation name
     * @param base            the sampler
     * @param url             the URL that was sampled
     * @param protocolVersion the protocol the response used, as reported on the sample result
     * @param responseHeaders the response headers; a host that advertises an alternative service
     *                        (HTTP/3 through Alt-Svc) must keep reaching the negotiating client
     */
    static void recordNegotiatedProtocol(String impl, HTTPSamplerBase base, URL url, String protocolVersion,
            String responseHeaders) {
        if (protocolVersion != null && protocolVersion.startsWith("HTTP/1.") //$NON-NLS-1$
                && negotiatesOverTls(impl, base, url)
                && (responseHeaders == null
                        || !responseHeaders.toLowerCase(java.util.Locale.ROOT).contains("alt-svc:"))) { //$NON-NLS-1$
            http11OnlyOrigins().put(url.getAuthority(), Boolean.TRUE);
        }
    }

    // VisibleForTesting
    static void forgetNegotiatedProtocols() {
        HTTP11_ONLY_ORIGINS.clear();
    }

    /**
     * Whether the HTTP/1.1 implementation should send a request to {@code url} although the
     * sampler leaves the HTTP version to negotiation: always for cleartext (see
     * {@link #usesHttp11ForCleartext(String, HTTPSamplerBase, URL)}), and for TLS once the host
     * is known to speak only HTTP/1.1.
     *
     * @param impl the configured implementation name
     * @param base the sampler
     * @param url  the URL about to be sampled
     * @return true when the HTTP/1.1 implementation should send this request
     */
    public static boolean usesHttp11Impl(String impl, HTTPSamplerBase base, URL url) {
        return usesHttp11ForCleartext(impl, base, url)
                || (negotiatesOverTls(impl, base, url) && http11OnlyOrigins().containsKey(url.getAuthority()));
    }

    public static HTTPAbstractImpl getImplementation(String impl, HTTPSamplerBase base){
        return getImplementation(impl, base, Http3RuntimeSupport.isHttp3Supported(), PREFER_HTTP3_FOR_DEFAULT);
    }

    // package-private so tests can exercise both the supported and unsupported runtime paths
    static HTTPAbstractImpl getImplementation(String impl, HTTPSamplerBase base, boolean http3Supported) {
        return getImplementation(impl, base, http3Supported, PREFER_HTTP3_FOR_DEFAULT);
    }

    // package-private so tests can exercise the prefer-HTTP/3-for-default paths
    static HTTPAbstractImpl getImplementation(String impl, HTTPSamplerBase base,
            boolean http3Supported, boolean preferHttp3ForDefault) {
        if (HTTPSamplerBase.PROTOCOL_FILE.equals(base.getProtocol())) {
            return new HTTPFileImpl(base);
        }
        if (!StringUtilities.isBlank(impl) && !isKnownImplementation(impl)) {
            throw new IllegalArgumentException("Unknown implementation type: '"+impl+"'");
        }
        if (base instanceof HTTPSamplerProxy proxy && proxy.isSseEnabled()) {
            if (base.isHttp3Protocol()) {
                throw new IllegalArgumentException("SSE supports HTTP/1.1 and HTTP/2; select either or the default protocol");
            }
            return base.isHttp11Protocol() || base.isHttp10Protocol() ? new HTTPHC5Impl(base) : new HTTPHC5H2Impl(base);
        }
        if (base.isHttp3Protocol()) {
            if (http3Supported) {
                return new HTTPJavaHttp3Impl(base);
            }
            // The upgrade checkbox may come from unrelated Defaults; it cannot mark a strict profile.
            Http3RuntimeSupport.warnHttp3FallbackOnce();
            return new HTTPHC5H2Impl(base);
        }
        boolean upgrade = base.getPropertyAsBoolean(HTTPSamplerBase.HTTP3_UPGRADE, preferHttp3ForDefault);
        // The scheme may come from a full URL path or a variable and the impl is cached, so the
        // implementation checks each request's scheme; plain HTTP stays on HC5.
        if (upgrade && http3Supported
                && StringUtilities.isBlank(base.getHttpProtocol())
                && supportedByJavaHttp3Client(base)) {
            // Browser-like: first request per origin over TCP, upgrade to HTTP/3 on Alt-Svc
            return new HTTPJavaHttp3Impl(base, HTTPJavaHttp3Impl.Http3Discovery.ALT_SVC_UPGRADE);
        }
        if (!base.isHttp11Protocol() && !base.isHttp10Protocol()) {
            return new HTTPHC5H2Impl(base);
        }
        return new HTTPHC5Impl(base);
    }

    /**
     * Whether a default-protocol sampler can be routed to the JDK HTTP/3 client without
     * losing configured behavior: multipart uploads and proxies are HttpClient5-only.
     */
    private static boolean supportedByJavaHttp3Client(HTTPSamplerBase base) {
        if (base.getUseMultipart()) {
            return false;
        }
        boolean dynamicProxy = StringUtilities.isNotBlank(base.getProxyHost()) && base.getProxyPortInt() > 0;
        return !dynamicProxy && !HTTPHCAbstractImpl.PROXY_DEFINED;
    }

    public static String normalizeImplementation(String impl) {
        return StringUtilities.isBlank(impl) ? "" : IMPL_HTTP_CLIENT5;
    }

    private static boolean isKnownImplementation(String impl) {
        return IMPL_HTTP_CLIENT5.equals(impl)
                || LEGACY_IMPL_HTTP_CLIENT4.equals(impl)
                || LEGACY_HTTP_SAMPLER_APACHE.equals(impl)
                || LEGACY_IMPL_HTTP_CLIENT3_1.equals(impl)
                || LEGACY_HTTP_SAMPLER_JAVA.equals(impl)
                || LEGACY_IMPL_JAVA.equals(impl);
    }

}
