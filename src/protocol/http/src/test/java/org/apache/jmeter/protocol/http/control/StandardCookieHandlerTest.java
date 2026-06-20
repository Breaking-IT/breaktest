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

import org.junit.jupiter.api.Test;

class StandardCookieHandlerTest {
    @Test
    void shouldAcceptParentDomainCookieWithoutLeadingDot() throws Exception {
        CookieManager cookieManager = new CookieManager();
        StandardCookieHandler handler = new StandardCookieHandler();
        URL url = URI.create("https://staatsloterij.lotteries-acc.nl/_nuxt-assets/logo.svg").toURL();

        handler.addCookieFromHeader(cookieManager, true,
                "__cf_bm=value; HttpOnly; SameSite=None; Secure; Path=/; Domain=lotteries-acc.nl; Max-Age=60",
                url);

        assertEquals(1, cookieManager.getCookieCount());
        assertEquals("__cf_bm=value", handler.getCookieHeaderForURL(cookieManager.getCookies(), url, true));
    }

    @Test
    void shouldStoreCookieForDifferentPathOnSameOrigin() throws Exception {
        CookieManager cookieManager = new CookieManager();
        StandardCookieHandler handler = new StandardCookieHandler();
        URL loginUrl = URI.create("https://nedwin.sand-box.nl/inloggen?iss=https%3A%2F%2Finloggen.sand-box.nl%2F").toURL();
        URL callbackUrl = URI.create("https://nedwin.sand-box.nl/callback").toURL();

        handler.addCookieFromHeader(cookieManager, true,
                ".AspNetCore.OpenIdConnect.Nonce=value; expires=Sat, 20 Jun 2036 15:59:33 GMT; path=/callback; secure; samesite=none; httponly",
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
        URL callbackUrl = URI.create("https://nedwin.sand-box.nl/callback").toURL();
        URL callbackChildUrl = URI.create("https://nedwin.sand-box.nl/callback/continue").toURL();
        URL callbackSiblingUrl = URI.create("https://nedwin.sand-box.nl/callback2").toURL();

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
