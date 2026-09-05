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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.apache.jmeter.extractor.gui.RegexExtractorGui;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.junit.jupiter.api.Test;

/**
 * Headers configured directly on the sampler (HTTPSampler.headers): effective manager
 * merging with scoped Header Managers, folding helpers, search tokens and replace.
 */
public class HTTPSamplerNativeHeadersTest {

    private static HTTPSamplerProxy newSampler() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("Request");
        sampler.setDomain("example.com");
        sampler.setMethod("GET");
        sampler.setPath("/api");
        return sampler;
    }

    private static HeaderManager manager(Header... headers) {
        HeaderManager manager = new HeaderManager();
        manager.setName("scoped");
        for (Header header : headers) {
            manager.add(header);
        }
        return manager;
    }

    private static String valueOf(HeaderManager manager, String name) {
        Header header = manager.getFirstHeaderNamed(name);
        return header == null ? null : header.getValue();
    }

    @Test
    public void effectiveManagerIsNullWithoutAnyHeaders() {
        assertNull(newSampler().getEffectiveHeaderManager());
    }

    @Test
    public void effectiveManagerReturnsScopedManagerWhenNoNativeHeaders() {
        HTTPSamplerProxy sampler = newSampler();
        HeaderManager scoped = manager(new Header("Accept", "application/json"));
        sampler.addTestElement(scoped);

        assertSame(sampler.getHeaderManager(), sampler.getEffectiveHeaderManager());
    }

    @Test
    public void effectiveManagerContainsNativeHeadersWhenNoScopedManager() {
        HTTPSamplerProxy sampler = newSampler();
        sampler.setNativeHeaders(Arrays.asList(new Header("X-Api-Key", "secret")));

        HeaderManager effective = sampler.getEffectiveHeaderManager();
        assertEquals(1, effective.size());
        assertEquals("secret", valueOf(effective, "X-Api-Key"));
    }

    @Test
    public void nativeHeadersWinOverScopedManagerCaseInsensitively() {
        HTTPSamplerProxy sampler = newSampler();
        sampler.setNativeHeaders(Arrays.asList(new Header("Accept", "application/json")));
        sampler.addTestElement(manager(
                new Header("ACCEPT", "text/html"),
                new Header("User-Agent", "BreakTest")));

        HeaderManager effective = sampler.getEffectiveHeaderManager();
        assertEquals(2, effective.size());
        assertEquals("application/json", valueOf(effective, "Accept"));
        assertEquals("BreakTest", valueOf(effective, "User-Agent"));
    }

    @Test
    public void addNativeHeadersIfAbsentKeepsExistingAndAppendsNew() {
        HTTPSamplerProxy sampler = newSampler();
        sampler.setNativeHeaders(Arrays.asList(new Header("Accept", "application/json")));

        sampler.addNativeHeadersIfAbsent(manager(
                new Header("accept", "text/html"),
                new Header("X-Trace", "1")));

        List<Header> headers = sampler.getNativeHeaderList();
        assertEquals(2, headers.size());
        assertEquals("application/json", headers.get(0).getValue());
        assertEquals("X-Trace", headers.get(1).getName());
    }

    @Test
    public void addNativeHeadersIfAbsentKeepsDuplicatesWithinOneManager() {
        // A single manager may hold repeated names; all of them used to be sent
        HTTPSamplerProxy sampler = newSampler();
        sampler.addNativeHeadersIfAbsent(manager(
                new Header("Cookie", "a=1"),
                new Header("Cookie", "b=2")));

        assertEquals(2, sampler.getNativeHeaderList().size());
    }

    @Test
    public void emptyNativeHeadersRemoveTheProperty() {
        HTTPSamplerProxy sampler = newSampler();
        sampler.setNativeHeaders(Arrays.asList(new Header("Accept", "application/json")));
        sampler.setNativeHeaders(List.of());

        assertNull(sampler.getNativeHeaders());
        assertNull(sampler.getPropertyOrNull(HTTPSamplerBase.HEADERS));
    }

    @Test
    public void searchableTokensContainHeaderNamesAndValues() {
        HTTPSamplerProxy sampler = newSampler();
        sampler.setNativeHeaders(Arrays.asList(new Header("X-Api-Key", "super-secret-token")));

        List<String> tokens = sampler.getSearchableTokens();
        assertTrue(tokens.contains("X-Api-Key"), "header name should be searchable");
        assertTrue(tokens.contains("super-secret-token"), "header value should be searchable");
    }

    @Test
    public void replaceCoversNativeHeaderValues() throws Exception {
        HTTPSamplerProxy sampler = newSampler();
        sampler.setNativeHeaders(Arrays.asList(new Header("Authorization", "Bearer abc123")));

        int replaced = sampler.replace("abc123", "${token}", true);

        assertTrue(replaced >= 1, "expected the header value to be replaced");
        assertEquals("Bearer ${token}", sampler.getNativeHeaderList().get(0).getValue());
        assertFalse(sampler.getSearchableTokens().contains("Bearer abc123"));
    }

    @Test
    public void replaceLiteralIgnoresArgumentNamesAndExistingVariables() {
        HTTPSamplerProxy sampler = newSampler();
        sampler.addArgument("code", "${oauth_code}");

        int replaced = sampler.replaceLiteral("code", "${oauth_code_g1}");

        assertEquals(0, replaced);
        assertEquals("code", sampler.getArguments().getArgument(0).getName());
        assertEquals("${oauth_code}", sampler.getArguments().getArgument(0).getValue());
    }

    @Test
    public void regexHelperCountsOnlyValuesThatWillActuallyBeReplaced() throws Exception {
        HTTPSamplerProxy sampler = newSampler();
        sampler.setName("token");
        sampler.setComment("token");
        sampler.setProperty("BreakTest.recordedResponse", "token");
        sampler.addArgument("token", "${token}");
        sampler.setNativeHeaders(List.of(new Header("token", "${token}")));
        var count = RegexExtractorGui.class.getDeclaredMethod("countOccurrences", TestElement.class, String.class);
        count.setAccessible(true);
        assertEquals(0, count.invoke(null, sampler, "token"));
        sampler.setPath("/token/token");
        sampler.addArgument("token", "token");
        sampler.setNativeHeaders(List.of(new Header("token", "token ${token}")));
        assertEquals(4, count.invoke(null, sampler, "token"));
        // The preview must not modify the real request or nested header/argument values.
        assertEquals("/token/token", sampler.getPath());
        assertEquals("token", sampler.getArguments().getArgument(1).getValue());
        assertEquals("token ${token}", sampler.getNativeHeaderList().get(0).getValue());
        assertEquals(4, sampler.replaceLiteral("token", "${extracted}"));
        assertEquals(0, count.invoke(null, sampler, "token"));
        assertEquals("token", sampler.getName());
        assertEquals("token", sampler.getArguments().getArgument(1).getName());
        assertEquals("token", sampler.getNativeHeaderList().get(0).getName());
        assertEquals("token", sampler.getPropertyAsString("BreakTest.recordedResponse"));
    }

    @Test
    public void replaceableFieldsIncludeParameterAndHeaderNamesAndValues() {
        HTTPSamplerProxy sampler = newSampler();
        sampler.addArgument("old-parameter", "old-value");
        sampler.setNativeHeaders(List.of(new Header("Old-Header", "old-header-value")));

        var fields = sampler.getReplaceableFields();
        fields.stream()
                .filter(field -> field.value().equals("old-parameter"))
                .findFirst()
                .orElseThrow()
                .setValue("new-parameter");
        fields.stream()
                .filter(field -> field.value().equals("Old-Header"))
                .findFirst()
                .orElseThrow()
                .setValue("New-Header");

        assertEquals("new-parameter", sampler.getArguments().getArgument(0).getName());
        assertEquals("New-Header", sampler.getNativeHeaderList().get(0).getName());
        assertTrue(fields.stream().anyMatch(field -> field.value().equals("old-value")));
        assertTrue(fields.stream().anyMatch(field -> field.value().equals("old-header-value")));
    }

    @Test
    public void headerManagerExposesHeaderNamesAndValuesAsReplaceable() {
        HeaderManager headerManager = manager(new Header("Old-Header", "old-value"));

        var fields = headerManager.getReplaceableFields();

        assertTrue(fields.stream().anyMatch(field -> field.value().equals("Old-Header")));
        assertTrue(fields.stream().anyMatch(field -> field.value().equals("old-value")));
    }
}
