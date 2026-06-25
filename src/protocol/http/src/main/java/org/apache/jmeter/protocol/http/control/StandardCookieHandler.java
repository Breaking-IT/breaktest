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

package org.apache.jmeter.protocol.http.control;

import java.net.HttpCookie;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StandardCookieHandler implements CookieHandler {
    private static final Logger log = LoggerFactory.getLogger(StandardCookieHandler.class);

    // Needed by CookiePanel
    public static final String DEFAULT_POLICY_NAME = "standard"; // NOSONAR

    private static final String[] AVAILABLE_POLICIES = new String[]{
        DEFAULT_POLICY_NAME,
        "ignoreCookies"
    };

    private final transient boolean ignoreCookies;

    /**
     * Default constructor that uses {@link StandardCookieHandler#DEFAULT_POLICY_NAME}
     */
    public StandardCookieHandler() {
        this(DEFAULT_POLICY_NAME);
    }

    public StandardCookieHandler(String policy) {
        super();
        this.ignoreCookies = "ignoreCookies".equalsIgnoreCase(policy);
    }

    @Override
    @SuppressWarnings("JavaUtilDate")
    public void addCookieFromHeader(CookieManager cookieManager,
            boolean checkCookies, String cookieHeader, URL url) {
            boolean debugEnabled = log.isDebugEnabled();
            if (debugEnabled) {
                log.debug("Received Cookie: {} From: {}", cookieHeader, url.toExternalForm());
            }
            List<HttpCookie> cookies;
            try {
                cookies = HttpCookie.parse(cookieHeader);
            } catch (IllegalArgumentException e) {
                log.error("Unable to add the cookie", e);
                return;
            }
            if (cookies == null) {
                return;
            }
            for (HttpCookie cookie : cookies) {
                try {
                    if (checkCookies) {
                        if (!canStore(cookie, url)) {
                            log.info("Not storing invalid cookie: <{}> for URL {} ({})",
                                cookieHeader, url, "URI mismatch");
                            continue;
                        }
                    }
                    long maxAge = cookie.getMaxAge();
                    long exp = maxAge < 0 ? 0 : System.currentTimeMillis() + maxAge * 1000;
                    Cookie newCookie = new Cookie(
                            cookie.getName(),
                            cookie.getValue(),
                            cookie.getDomain() == null ? url.getHost() : cookie.getDomain(),
                            cookie.getPath() == null ? defaultPath(url) : cookie.getPath(),
                            cookie.getSecure(),
                            exp / 1000,
                            cookie.getPath() != null,
                            cookie.getDomain() != null,
                            cookie.getVersion());

                    // Store session cookies as well as unexpired ones
                    if (exp == 0 || exp >= System.currentTimeMillis()) {
                        cookieManager.add(newCookie); // Has its own debug log; removes matching cookies
                    } else {
                        cookieManager.removeMatchingCookies(newCookie);
                        if (debugEnabled){
                            log.info("Dropping expired Cookie: {}", newCookie);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    log.warn(cookieHeader+e.getLocalizedMessage());
                }
            }
    }

    @Override
    public String getCookieHeaderForURL(CollectionProperty cookiesCP, URL url,
            boolean allowVariableCookie) {
        if (ignoreCookies) {
            return null;
        }
        List<HttpCookie> c =
                getCookiesForUrl(cookiesCP, url, allowVariableCookie);

        boolean debugEnabled = log.isDebugEnabled();
        if (debugEnabled){
            log.debug("Found {} cookies for {}", c.size(), url);
        }
        if (c.isEmpty()) {
            return null;
        }
        StringBuilder sbHdr = new StringBuilder();
        for (HttpCookie cookie : c) {
            if (!sbHdr.isEmpty()) {
                sbHdr.append("; ");
            }
            sbHdr.append(cookie.getName()).append('=').append(cookie.getValue());
        }

        return sbHdr.toString();
    }

    /**
     * Get array of valid HttpClient cookies for the URL
     *
     * @param cookiesCP property with all available cookies
     * @param url the target URL
     * @param allowVariableCookie flag whether cookies may contain jmeter variables
     * @return array of HttpClient cookies
     *
     */
    List<HttpCookie> getCookiesForUrl(
            CollectionProperty cookiesCP, URL url, boolean allowVariableCookie) {
        List<HttpCookie> cookies = new ArrayList<>();

        for (JMeterProperty jMeterProperty : cookiesCP) {
            Cookie jmcookie = (Cookie) jMeterProperty.getObjectValue();
            // Set to running version, to allow function evaluation for the cookie values (bug 28715)
            if (allowVariableCookie) {
                jmcookie.setRunningVersion(true);
            }
            if (matches(jmcookie, url)) {
                cookies.add(makeCookie(jmcookie));
            }
            if (allowVariableCookie) {
                jmcookie.setRunningVersion(false);
            }
        }
        return cookies;
    }

    /**
     * Create an HttpClient cookie from a JMeter cookie
     */
    @SuppressWarnings("JavaUtilDate")
    private static HttpCookie makeCookie(Cookie jmc) {
        long exp = jmc.getExpiresMillis();
        HttpCookie ret = new HttpCookie(jmc.getName(), jmc.getValue());
        if (jmc.isDomainSpecified()) {
            ret.setDomain(jmc.getDomain());
        }
        if (jmc.isPathSpecified()) {
            ret.setPath(jmc.getPath());
        }
        ret.setMaxAge(exp > 0 ? Math.max(0, (exp - System.currentTimeMillis()) / 1000) : -1);
        ret.setSecure(jmc.getSecure());
        ret.setVersion(jmc.getVersion());
        return ret;
    }

    private static boolean matches(Cookie cookie, URL url) {
        String protocol = url.getProtocol();
        if (cookie.getSecure() && !HTTPSamplerBase.isSecure(protocol)) {
            return false;
        }
        String domain = cookie.getDomain();
        String host = url.getHost();
        if (cookie.isDomainSpecified()) {
            if (!domainMatches(domain, host)) {
                return false;
            }
        } else if (!host.equalsIgnoreCase(domain)) {
            return false;
        }
        String cookiePath = cookie.getPath();
        return cookiePath == null || cookiePath.isEmpty() || pathMatches(url.getPath(), cookiePath);
    }

    private static boolean canStore(HttpCookie cookie, URL url) {
        String protocol = url.getProtocol();
        if (cookie.getSecure() && !HTTPSamplerBase.isSecure(protocol)) {
            return false;
        }
        String domain = cookie.getDomain();
        return domain == null || domainMatches(domain, url.getHost());
    }

    private static boolean pathMatches(String requestPath, String cookiePath) {
        String normalizedRequestPath = requestPath == null || requestPath.isEmpty() ? "/" : requestPath;
        if (normalizedRequestPath.equals(cookiePath)) {
            return true;
        }
        if (!normalizedRequestPath.startsWith(cookiePath)) {
            return false;
        }
        return cookiePath.endsWith("/")
                || normalizedRequestPath.length() > cookiePath.length()
                && normalizedRequestPath.charAt(cookiePath.length()) == '/';
    }

    private static boolean domainMatches(String domain, String host) {
        String normalizedDomain = domain.toLowerCase(Locale.ROOT);
        if (normalizedDomain.startsWith(".")) {
            normalizedDomain = normalizedDomain.substring(1);
        }
        if (normalizedDomain.endsWith(".")) {
            normalizedDomain = normalizedDomain.substring(0, normalizedDomain.length() - 1);
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return normalizedHost.equals(normalizedDomain) || normalizedHost.endsWith("." + normalizedDomain);
    }

    private static String defaultPath(URL url) {
        String path = url.getPath();
        int lastSlash = path.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : path.substring(0, lastSlash);
    }

    @Override
    public String getDefaultPolicy() {
        return DEFAULT_POLICY_NAME;
    }

    @Override
    public String[] getPolicies() {
        return AVAILABLE_POLICIES;
    }
}
