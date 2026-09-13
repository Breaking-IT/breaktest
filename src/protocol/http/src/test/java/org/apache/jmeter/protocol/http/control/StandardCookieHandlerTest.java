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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.net.URL;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

class StandardCookieHandlerTest {
    @Test
    void shouldAcceptParentDomainCookieWithoutLeadingDot() throws Exception {
        CookieManager cookieManager = new CookieManager();
        StandardCookieHandler handler = new StandardCookieHandler();
        URL url = URI.create("https://product.example.test/_nuxt-assets/logo.svg").toURL();

        handler.addCookieFromHeader(cookieManager, true,
                "__cf_bm=value; HttpOnly; SameSite=None; Secure; Path=/; Domain=example.test; Max-Age=60",
                url);

        assertEquals(1, cookieManager.getCookieCount());
        assertEquals("__cf_bm=value", handler.getCookieHeaderForURL(cookieManager.getCookies(), url, true));
    }

    @Test
    void shouldNotSendHostOnlyCookieToAnotherHost() throws Exception {
        CookieManager cookieManager = new CookieManager();
        StandardCookieHandler handler = new StandardCookieHandler();
        URL sourceUrl = URI.create("https://www.example.test/login").toURL();
        URL sameHostUrl = URI.create("https://www.example.test/statics/portal-header.js").toURL();
        URL otherHostUrl = URI.create("https://app.other.test/statics/portal-header.js").toURL();

        handler.addCookieFromHeader(cookieManager, true,
                "__Host-portal.redirect=https%253A%252F%252Fproduct.example.test%252F; path=/; secure; samesite=lax",
                sourceUrl);

        assertEquals(1, cookieManager.getCookieCount());
        assertEquals("__Host-portal.redirect=https%253A%252F%252Fproduct.example.test%252F",
                handler.getCookieHeaderForURL(cookieManager.getCookies(), sameHostUrl, true));
        assertNull(handler.getCookieHeaderForURL(cookieManager.getCookies(), otherHostUrl, true));
    }

    @Test
    void shouldNotSendHostOnlyCookieToSubdomain() throws Exception {
        CookieManager cookieManager = new CookieManager();
        StandardCookieHandler handler = new StandardCookieHandler();
        URL sourceUrl = URI.create("https://www.example.test/login").toURL();
        URL subdomainUrl = URI.create("https://product.example.test/").toURL();

        handler.addCookieFromHeader(cookieManager, true,
                "__Host-portal.redirect=value; path=/; secure; samesite=lax",
                sourceUrl);

        assertEquals(1, cookieManager.getCookieCount());
        assertNull(handler.getCookieHeaderForURL(cookieManager.getCookies(), subdomainUrl, true));
    }

    @Test
    void shouldStoreCookieForDifferentPathOnSameOrigin() throws Exception {
        CookieManager cookieManager = new CookieManager();
        StandardCookieHandler handler = new StandardCookieHandler();
        URL loginUrl = URI.create("https://app.other.test/login?iss=https%3A%2F%2Flogin.other.test%2F").toURL();
        URL callbackUrl = URI.create("https://app.other.test/callback").toURL();

        handler.addCookieFromHeader(cookieManager, true,
                ".AspNetCore.OpenIdConnect.Nonce=value; expires="
                        + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusDays(1))
                        + "; path=/callback; secure; samesite=none; httponly",
                loginUrl);

        assertEquals(1, cookieManager.getCookieCount());
        assertEquals(".AspNetCore.OpenIdConnect.Nonce=value",
                handler.getCookieHeaderForURL(cookieManager.getCookies(), callbackUrl, true));
        assertNull(handler.getCookieHeaderForURL(cookieManager.getCookies(), loginUrl, true));
    }

    @Test
    void shouldUseRfcPathMatchingWhenSendingCookies() throws Exception {
        CookieManager cookieManager = new CookieManager();
        StandardCookieHandler handler = new StandardCookieHandler();
        URL callbackUrl = URI.create("https://app.other.test/callback").toURL();
        URL callbackChildUrl = URI.create("https://app.other.test/callback/continue").toURL();
        URL callbackSiblingUrl = URI.create("https://app.other.test/callback2").toURL();

        handler.addCookieFromHeader(cookieManager, true,
                "nonce=value; path=/callback; secure; httponly",
                callbackUrl);

        assertEquals("nonce=value", handler.getCookieHeaderForURL(cookieManager.getCookies(), callbackUrl, true));
        assertEquals("nonce=value", handler.getCookieHeaderForURL(cookieManager.getCookies(), callbackChildUrl, true));
        assertNull(handler.getCookieHeaderForURL(cookieManager.getCookies(), callbackSiblingUrl, true));
    }

    @Test
    void shouldMapLegacyImplementationNameToStandardHandler() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setImplementation("org.apache.jmeter.protocol.http.control.HC4CookieHandler");

        assertEquals(StandardCookieHandler.class.getName(), cookieManager.getImplementation());
        cookieManager.testStarted();
        assertInstanceOf(StandardCookieHandler.class, cookieManager.getCookieHandler());
    }
}
