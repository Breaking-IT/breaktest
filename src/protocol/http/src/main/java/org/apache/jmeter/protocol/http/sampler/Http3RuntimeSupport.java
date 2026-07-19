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

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Capability detection for HTTP/3 support in the Java runtime.
 * <p>
 * HTTP/3 sampling relies on {@code java.net.http.HttpClient} support introduced by
 * JEP 517 in Java 26 ({@code HttpClient.Version.HTTP_3}, {@code java.net.http.HttpOption}
 * and its {@code H3_DISCOVERY} option). BreakTest compiles against Java 21, so those
 * symbols are resolved reflectively, once, in this holder. Detection is all-or-nothing:
 * either every symbol resolves and {@link #isHttp3Supported()} returns {@code true}, or
 * the runtime is treated as not HTTP/3 capable.
 */
public final class Http3RuntimeSupport {

    private static final Logger log = LoggerFactory.getLogger(Http3RuntimeSupport.class);

    /** {@code HttpClient.Version.HTTP_3}, or null when the runtime has no HTTP/3 support. */
    static final HttpClient.@Nullable Version HTTP_3;

    /** The {@code java.net.http.HttpOption.H3_DISCOVERY} option instance, or null. */
    static final @Nullable Object H3_DISCOVERY;

    /** The {@code Http3DiscoveryMode.HTTP_3_URI_ONLY} enum constant (HTTP/3-only), or null. */
    static final @Nullable Object HTTP_3_URI_ONLY;

    /** The {@code Http3DiscoveryMode.ANY} enum constant (try HTTP/3 first, race TCP), or null. */
    static final @Nullable Object ANY;

    /** The {@code Http3DiscoveryMode.ALT_SVC} enum constant (TCP first, upgrade on Alt-Svc), or null. */
    static final @Nullable Object ALT_SVC;

    /** {@code HttpRequest.Builder#setOption(HttpOption, Object)}, or null. */
    static final @Nullable Method SET_OPTION;

    private static final AtomicBoolean FALLBACK_WARNED = new AtomicBoolean();

    static {
        HttpClient.Version version = null;
        Object discoveryOption = null;
        Object uriOnlyMode = null;
        Object anyMode = null;
        Object altSvcMode = null;
        Method setOption = null;
        try {
            version = HttpClient.Version.valueOf("HTTP_3"); //$NON-NLS-1$
            Class<?> httpOptionClass = Class.forName("java.net.http.HttpOption"); //$NON-NLS-1$
            discoveryOption = httpOptionClass.getField("H3_DISCOVERY").get(null); //$NON-NLS-1$
            Class<?> discoveryModeClass = discoveryModeClass();
            uriOnlyMode = discoveryMode(discoveryModeClass, "HTTP_3_URI_ONLY"); //$NON-NLS-1$
            anyMode = discoveryMode(discoveryModeClass, "ANY"); //$NON-NLS-1$
            altSvcMode = discoveryMode(discoveryModeClass, "ALT_SVC"); //$NON-NLS-1$
            setOption = HttpRequest.Builder.class.getMethod("setOption", httpOptionClass, Object.class); //$NON-NLS-1$
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            log.debug("Java runtime {} has no HTTP/3 support: {}", Runtime.version(), e.toString());
            version = null;
            discoveryOption = null;
            uriOnlyMode = null;
            anyMode = null;
            altSvcMode = null;
            setOption = null;
        }
        HTTP_3 = version;
        H3_DISCOVERY = discoveryOption;
        HTTP_3_URI_ONLY = uriOnlyMode;
        ANY = anyMode;
        ALT_SVC = altSvcMode;
        SET_OPTION = setOption;
    }

    private Http3RuntimeSupport() {
        // Not intended to be instantiated
    }

    private static Class<?> discoveryModeClass() throws ClassNotFoundException {
        try {
            return Class.forName("java.net.http.HttpOption$Http3DiscoveryMode"); //$NON-NLS-1$
        } catch (ClassNotFoundException e) {
            return Class.forName("java.net.http.Http3DiscoveryMode"); //$NON-NLS-1$
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object discoveryMode(Class<?> discoveryModeClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) discoveryModeClass, name);
    }

    /**
     * @return true when the Java runtime (Java 26+) supports HTTP/3 in {@code java.net.http.HttpClient}
     */
    public static boolean isHttp3Supported() {
        return HTTP_3 != null;
    }

    /**
     * Logs the unsupported-runtime warning once per JVM. Called when an HTTP/3 sampler
     * falls back to the HTTP/2 implementation because the runtime lacks HTTP/3 support.
     */
    static void warnHttp3FallbackOnce() {
        if (FALLBACK_WARNED.compareAndSet(false, true)) {
            log.warn("HTTP/3 was selected on an HTTP Request sampler, but this Java runtime ({}) "
                    + "does not support HTTP/3 (Java 26 or later is required). "
                    + "Falling back to the HTTP/2 implementation (protocol negotiation).",
                    Runtime.version());
        }
    }

    static boolean fallbackWarned() {
        return FALLBACK_WARNED.get();
    }

    static void resetFallbackWarningForTests() {
        FALLBACK_WARNED.set(false);
    }
}
