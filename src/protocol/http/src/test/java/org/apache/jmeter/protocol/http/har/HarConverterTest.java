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

package org.apache.jmeter.protocol.http.har;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link HarConverter} end-to-end against a hand-built HAR, covering
 * the behaviours ported from the BreakTest Python converter.
 */
public class HarConverterTest {

    // Entry 0 (home) is standalone; entries 1 and 2 overlap (parallel HTTP/2);
    // entry 3 is an OPTIONS preflight; entry 4 is a POST that recorded a 500;
    // entry 5 came from the browser cache and must be skipped.
    private static final String HAR = "{\"log\":{\"entries\":["
            + entry("2021-01-01T00:00:00.000Z", 50, "GET", "https://api.example.com/home?q=hello%20world",
                    "[{\"name\":\"q\",\"value\":\"hello world\"}]",
                    "[{\"name\":\":authority\",\"value\":\"api.example.com\"},"
                    + "{\"name\":\"host\",\"value\":\"api.example.com\"},"
                    + "{\"name\":\"cookie\",\"value\":\"a=b\"},"
                    + "{\"name\":\"content-length\",\"value\":\"0\"},"
                    + "{\"name\":\"accept-language\",\"value\":\"en\"},"
                    + "{\"name\":\"user-agent\",\"value\":\"UA\"},"
                    + "{\"name\":\"x-entry0\",\"value\":\"0\"}]",
                    null, 200) + ","
            + entry("2021-01-01T00:00:00.100Z", 500, "GET", "https://api.example.com/logo.png", "[]",
                    commonHeaders("x-entry1", "1"), null, 200) + ","
            + entry("2021-01-01T00:00:00.200Z", 500, "GET", "https://cdn.example.com/app.js", "[]",
                    commonHeadersOnly(), null, 200) + ","
            + entry("2021-01-01T00:00:00.800Z", 50, "OPTIONS", "https://api.example.com/submit", "[]",
                    "[{\"name\":\"accept-language\",\"value\":\"en\"}]", null, 204) + ","
            + entry("2021-01-01T00:00:00.900Z", 50, "POST", "https://api.example.com/submit", "[]",
                    commonHeaders("x-entry4", "4"),
                    "{\"mimeType\":\"application/json\",\"text\":\"{\\\"a\\\":1}\"}", 500) + ","
            + cachedEntry("2021-01-01T00:00:01.000Z", "GET", "https://api.example.com/cached")
            + "]}}";

    private HashTree tree;

    @BeforeEach
    void convert() {
        List<HarEntry> entries = parse();
        HarImportOptions options = new HarImportOptions();
        HarConverter converter = new HarConverter(entries, options, "test.har", "abc123");
        tree = converter.convert(Set.of("api.example.com", "cdn.example.com"));
    }

    @Test
    void buildsTestPlanLevelConfigElements() {
        assertNotNull(findByType(tree, ResultCollector.class), "View Results Tree");
        TestElement defaults = findByName(tree, "HTTP Request Defaults");
        assertNotNull(defaults);
        assertEquals("10000", defaults.getPropertyAsString("HTTPSampler.connect_timeout"));
        assertEquals("60000", defaults.getPropertyAsString("HTTPSampler.response_timeout"));
    }

    @Test
    void threadGroupCarriesHarMetadataAndDefaults() {
        ThreadGroup tg = (ThreadGroup) findByType(tree, ThreadGroup.class);
        assertNotNull(tg);
        assertEquals("test.har", tg.getPropertyAsString("BreakTest.har.filename"));
        assertEquals("abc123", tg.getPropertyAsString("BreakTest.har.md5"));
        assertEquals("startnextloop", tg.getPropertyAsString("ThreadGroup.on_sample_error"));
    }

    @Test
    void commonHeadersAreHoistedToThreadGroupLevel() {
        HashTree tgTree = subtreeOf(tree, findByType(tree, ThreadGroup.class));
        HeaderManager common = (HeaderManager) findChildByName(tgTree, "Common Headers");
        assertNotNull(common, "Common Headers manager present");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < common.getHeaders().size(); i++) {
            names.add(common.get(i).getName());
        }
        assertTrue(names.contains("accept-language"), "shared header hoisted");
        assertTrue(names.contains("user-agent"), "shared header hoisted");
        assertTrue(names.stream().noneMatch(n -> n.equalsIgnoreCase("host")), "host excluded");
        assertTrue(names.stream().noneMatch(n -> n.startsWith(":")), "pseudo-headers excluded");
    }

    @Test
    void singleTransactionWithDisabledDelayAndParallelTiming() {
        TransactionController tc = (TransactionController) findByType(tree, TransactionController.class);
        assertNotNull(tc);
        assertEquals("01_Transaction", tc.getName());
        assertEquals("Disabled", tc.getPropertyAsString("TransactionController.delayMode"));
        assertEquals("Disabled", tc.getPropertyAsString("TransactionController.pacingMode"));
        assertEquals("total_include_timers", tc.getPropertyAsString("TransactionController.timingMode"));
    }

    @Test
    void overlappingHttp2RequestsBecomeParallelController() {
        ParallelController pc = (ParallelController) findByType(tree, ParallelController.class);
        assertNotNull(pc, "parallel controller for overlapping requests");
        assertEquals(100, pc.getMaxParallel(), "all HTTP/2 -> max 100");
        List<HTTPSamplerProxy> samplers = new ArrayList<>();
        collect(subtreeOf(tree, pc), HTTPSamplerProxy.class, samplers);
        assertEquals(2, samplers.size(), "two overlapping samplers grouped");
    }

    @Test
    void cachedEntryIsSkippedAndPreflightIsNamed() {
        List<HTTPSamplerProxy> samplers = new ArrayList<>();
        collect(tree, HTTPSamplerProxy.class, samplers);
        assertEquals(5, samplers.size(), "6 entries, cached one skipped");
        assertTrue(samplers.stream().anyMatch(s -> s.getName().endsWith("_preflight")), "OPTIONS -> _preflight");
        assertTrue(samplers.stream().noneMatch(s -> s.getPath().contains("cached")), "cached skipped");
    }

    @Test
    void unmarkedChromiumMemoryCacheReuseIsSkippedWithoutLosingOriginalHeaders() throws Exception {
        String url = "https://www.fedex.com/content/dam/fedex-com/common/sprite-placeholder.png";
        String fullHeaders = "[{\"name\":\"accept\",\"value\":\"image/*\"},"
                + "{\"name\":\"user-agent\",\"value\":\"Browser\"},"
                + "{\"name\":\"sec-fetch-dest\",\"value\":\"image\"}]";
        String sparseCacheHeaders = "[{\"name\":\"Referer\","
                + "\"value\":\"https://www.fedex.com/en-us/home.html\"}]";
        String har = "{\"log\":{\"entries\":["
                + entry("2026-07-21T04:45:00.000Z", 180, "GET", url, "[]",
                        fullHeaders, null, 200) + ","
                + unmarkedMemoryCacheEntry("2026-07-21T04:45:28.000Z", url, sparseCacheHeaders)
                + "]}}";

        List<HarEntry> parsed = HarParser.parse(har.getBytes(StandardCharsets.UTF_8));
        assertNull(parsed.get(0).getFromCache());
        assertEquals("memory", parsed.get(1).getFromCache());

        HashTree converted = new HarConverter(
                parsed, new HarImportOptions(), "fedex.har", "abc123")
                .convert(Set.of("www.fedex.com"));
        List<HTTPSamplerProxy> samplers = new ArrayList<>();
        collect(converted, HTTPSamplerProxy.class, samplers);
        assertEquals(1, samplers.size());
        assertEquals("0", samplers.get(0).getPropertyAsString("BreakTest.har.entryIndex"));

        HeaderManager common = (HeaderManager) findByName(converted, "Common Headers");
        assertNotNull(common);
        assertEquals(3, common.getHeaders().size(), "full network request headers remain available");
    }

    @Test
    void homeSamplerHasArgumentsMetadataAndNativeHeaderOnly() {
        HTTPSamplerProxy home = sampler("/home");
        assertEquals(HTTPSamplerProxy.class.getName(), home.getPropertyAsString(TestElement.TEST_CLASS));
        assertEquals("api.example.com", home.getDomain());
        assertEquals("GET", home.getMethod());
        assertEquals(false, home.getFollowRedirects());
        assertEquals("0", home.getPropertyAsString("BreakTest.har.entryIndex"));
        assertEquals("https://api.example.com/home?q=hello%20world",
                home.getPropertyAsString("BreakTest.har.requestUrl"));

        // Unique headers are stored natively on the sampler, not as a child manager.
        List<Header> nativeHeaders = home.getNativeHeaderList();
        assertEquals(1, nativeHeaders.size(), "only the non-common header, stored natively");
        assertEquals("x-entry0", nativeHeaders.get(0).getName());
        HashTree homeTree = subtreeOf(tree, findByName(tree, "/home"));
        assertNull(findChildByType(homeTree, HeaderManager.class), "no child header manager");
    }

    @Test
    void postWithRecordedErrorGetsRawBodyAndIgnoreAssertion() {
        // Both the OPTIONS preflight and the POST share the /submit path; pick the POST.
        HTTPSamplerProxy post = (HTTPSamplerProxy) findByName(tree, "/submit");
        assertEquals("POST", post.getMethod());
        assertTrue(post.getPostBodyRaw(), "raw body for POST text");
        HashTree postTree = subtreeOf(tree, findByName(tree, "/submit"));
        ResponseAssertion assertion = (ResponseAssertion) findChildByType(postTree, ResponseAssertion.class);
        assertNotNull(assertion, "ignore-error assertion present");
        assertEquals("Ignore HTTP-500", assertion.getName());
        assertTrue(assertion.getAssumeSuccess());
    }

    @Test
    void secondTransactionAsRecordedDelayUsesRecordedGap() {
        // Two requests 20s apart -> a second transaction. Default mode AS_RECORDED
        // reproduces the ~19950ms gap with a +/-50% spread; the first stays disabled.
        List<TransactionController> tcs = twoTransactionControllers(new HarImportOptions());
        assertEquals(2, tcs.size(), "idle gap creates a second transaction");
        assertEquals("Disabled", tcs.get(0).getPropertyAsString("TransactionController.delayMode"),
                "no delay before the first transaction");
        TransactionController second = tcs.get(1);
        assertEquals("Random", second.getPropertyAsString("TransactionController.delayMode"));
        assertEquals("9975", second.getPropertyAsString("TransactionController.delayMin"));
        assertEquals("29925", second.getPropertyAsString("TransactionController.delayMax"));
    }

    @Test
    void fixedDelayModeAppliesConfiguredDelay() {
        HarImportOptions options = new HarImportOptions();
        options.setDelayMode(HarImportOptions.DelayMode.FIXED);
        options.setFixedDelayMs(3000);
        List<TransactionController> tcs = twoTransactionControllers(options);
        assertEquals("Disabled", tcs.get(0).getPropertyAsString("TransactionController.delayMode"));
        TransactionController second = tcs.get(1);
        assertEquals("Fixed", second.getPropertyAsString("TransactionController.delayMode"));
        assertEquals("3000", second.getPropertyAsString("TransactionController.fixedDelay"));
    }

    @Test
    void fixedDelayModeAcceptsJMeterVariable() {
        HarImportOptions options = new HarImportOptions();
        options.setDelayMode(HarImportOptions.DelayMode.FIXED);
        options.setFixedDelay("${thinkTime}");

        TransactionController second = twoTransactionControllers(options).get(1);

        assertEquals("Fixed", second.getPropertyAsString("TransactionController.delayMode"));
        assertEquals("${thinkTime}", second.getPropertyAsString("TransactionController.fixedDelay"));
    }

    @Test
    void sharedRangeDelaysCreateAndReferenceTestPlanVariables() {
        HarImportOptions options = new HarImportOptions();
        options.setDelayMode(HarImportOptions.DelayMode.RANDOM);
        options.setDelayMin("250");
        options.setDelayMax("${externalMaximum}");
        options.setUseDelayVariables(true);

        TransactionController second = twoTransactionControllers(options).get(1);
        assertEquals("${ThinkTimeMin}",
                second.getPropertyAsString("TransactionController.delayMin"));
        assertEquals("${externalMaximum}",
                second.getPropertyAsString("TransactionController.delayMax"));

        TestPlan testPlan = new TestPlan();
        testPlan.getArguments().addArgument("unrelated", "keep-me");
        testPlan.getArguments().addArgument(HarImportOptions.MIN_DELAY_VARIABLE, "old");
        HarImportAction.applyDelayVariables(testPlan, options);

        assertEquals("keep-me", testPlan.getUserDefinedVariables().get("unrelated"));
        assertEquals("250", testPlan.getUserDefinedVariables().get(HarImportOptions.MIN_DELAY_VARIABLE));
        assertFalse(testPlan.getUserDefinedVariables().containsKey(HarImportOptions.MAX_DELAY_VARIABLE));
    }

    @Test
    void delayValidationAcceptsNumbersAndVariables() {
        assertTrue(HarImportOptions.isValidDelay("0"));
        assertTrue(HarImportOptions.isValidDelay(" 1500 "));
        assertTrue(HarImportOptions.isValidDelay("${thinkTime}"));
        assertFalse(HarImportOptions.isValidDelay("${}"));
        assertFalse(HarImportOptions.isValidDelay("-1"));
        assertFalse(HarImportOptions.isValidDelay("1.5"));
    }

    @Test
    void explicitBrowserTransactionsOverrideIdleGapGrouping() throws Exception {
        String har = "{\"log\":{\"entries\":["
                + explicitTransaction(entry("2021-01-01T00:00:00.000Z", 50, "GET",
                        "https://api.example.com/login", "[]", commonHeadersOnly(), null, 200),
                        "transaction-1", "01_Login") + ","
                + explicitTransaction(entry("2021-01-01T00:00:00.100Z", 50, "POST",
                        "https://api.example.com/login", "[]", commonHeadersOnly(), null, 200),
                        "transaction-1", "01_Login") + ","
                + explicitTransaction(entry("2021-01-01T00:00:00.200Z", 50, "GET",
                        "https://api.example.com/search", "[]", commonHeadersOnly(), null, 200),
                        "transaction-2", "02_Search")
                + "]}}";

        HashTree explicit = new HarConverter(
                HarParser.parse(har.getBytes(StandardCharsets.UTF_8)),
                new HarImportOptions(), "browser.har", "md5")
                .convert(Set.of("api.example.com"));
        List<TransactionController> transactions = new ArrayList<>();
        collect(explicit, TransactionController.class, transactions);

        assertEquals(2, transactions.size());
        assertEquals("01_Login", transactions.get(0).getName());
        assertEquals("02_Search", transactions.get(1).getName());
    }

    @Test
    void explicitTransactionRemainsWholeAcrossLongIdleGap() throws Exception {
        String har = "{\"log\":{\"entries\":["
                + explicitTransaction(entry("2021-01-01T00:00:00.000Z", 50, "GET",
                        "https://api.example.com/a", "[]", commonHeadersOnly(), null, 200),
                        "transaction-1", "Long business action") + ","
                + explicitTransaction(entry("2021-01-01T00:00:20.000Z", 50, "GET",
                        "https://api.example.com/b", "[]", commonHeadersOnly(), null, 200),
                        "transaction-1", "Long business action")
                + "]}}";

        HashTree explicit = new HarConverter(
                HarParser.parse(har.getBytes(StandardCharsets.UTF_8)),
                new HarImportOptions(), "browser.har", "md5")
                .convert(Set.of("api.example.com"));
        List<TransactionController> transactions = new ArrayList<>();
        collect(explicit, TransactionController.class, transactions);

        assertEquals(1, transactions.size());
        assertEquals("Long business action", transactions.get(0).getName());
    }

    @Test
    void explicitTransactionNameCannotEvaluateJMeterVariables() throws Exception {
        String har = "{\"log\":{\"entries\":["
                + explicitTransaction(entry("2021-01-01T00:00:00.000Z", 50, "GET",
                        "https://api.example.com/a", "[]", commonHeadersOnly(), null, 200),
                        "transaction-1", "${tenant} login")
                + "]}}";

        HashTree explicit = new HarConverter(
                HarParser.parse(har.getBytes(StandardCharsets.UTF_8)),
                new HarImportOptions(), "browser.har", "md5")
                .convert(Set.of("api.example.com"));
        TransactionController transaction =
                (TransactionController) findByType(explicit, TransactionController.class);

        assertEquals("{tenant} login", transaction.getName());
    }

    @Test
    void gaussianDelayModeUsesMinMax() {
        HarImportOptions options = new HarImportOptions();
        options.setDelayMode(HarImportOptions.DelayMode.GAUSSIAN);
        options.setDelayMinMs(200);
        options.setDelayMaxMs(800);
        TransactionController second = twoTransactionControllers(options).get(1);
        assertEquals("Gaussian Random", second.getPropertyAsString("TransactionController.delayMode"));
        assertEquals("200", second.getPropertyAsString("TransactionController.delayMin"));
        assertEquals("800", second.getPropertyAsString("TransactionController.delayMax"));
    }

    @Test
    void configElementsSkippedWhenRequested() {
        HarImportOptions options = new HarImportOptions();
        options.setIncludeViewResultsTree(false);
        options.setIncludeCookieManager(false);
        options.setIncludeHttpDefaults(false);
        HashTree t = new HarConverter(parse(), options, "test.har", "abc123")
                .convert(Set.of("api.example.com", "cdn.example.com"));
        assertNull(findByType(t, ResultCollector.class), "no View Results Tree");
        assertNull(findByType(t, CookieManager.class), "no Cookie Manager");
        assertNull(findByName(t, "HTTP Request Defaults"), "no HTTP Request Defaults");
        assertNotNull(findByType(t, ThreadGroup.class), "Thread Group still added");
    }

    private static List<TransactionController> twoTransactionControllers(HarImportOptions options) {
        String har = "{\"log\":{\"entries\":["
                + entry("2021-01-01T00:00:00.000Z", 50, "GET", "https://api.example.com/a", "[]",
                        commonHeadersOnly(), null, 200) + ","
                + entry("2021-01-01T00:00:20.000Z", 50, "GET", "https://api.example.com/b", "[]",
                        commonHeadersOnly(), null, 200)
                + "]}}";
        List<HarEntry> parsed;
        try {
            parsed = HarParser.parse(har.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        HashTree twoTx = new HarConverter(parsed, options, "x.har", "m").convert(Set.of("api.example.com"));
        List<TransactionController> tcs = new ArrayList<>();
        collect(twoTx, TransactionController.class, tcs);
        return tcs;
    }

    @Test
    void unselectedHostnamesAreDropped() {
        List<HarEntry> entries = parse();
        HarConverter converter = new HarConverter(entries, new HarImportOptions(), "test.har", "abc123");
        HashTree onlyApi = converter.convert(Set.of("api.example.com"));
        List<HTTPSamplerProxy> samplers = new ArrayList<>();
        collect(onlyApi, HTTPSamplerProxy.class, samplers);
        assertTrue(samplers.stream().noneMatch(s -> "cdn.example.com".equals(s.getDomain())),
                "cdn host filtered out");
        assertNull(findByName(onlyApi, "/app.js"));
    }

    @Test
    void uuidPathReferencesGetCommentFromEarlierResponse() throws Exception {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        String har = "{\"log\":{\"entries\":["
                + entry("2021-01-01T00:00:00.000Z", 50, "GET", "https://api.example.com/bootstrap", "[]",
                        commonHeadersOnly(), null, 200, "{\\\"id\\\":\\\"" + uuid + "\\\"}") + ","
                + entry("2021-01-01T00:00:00.100Z", 50, "GET",
                        "https://api.example.com/api/orders/" + uuid + "/details", "[]",
                        commonHeadersOnly(), null, 200)
                + "]}}";
        HashTree t = new HarConverter(
                HarParser.parse(har.getBytes(StandardCharsets.UTF_8)), new HarImportOptions(), "test.har", "abc123")
                .convert(Set.of("api.example.com"));

        HTTPSamplerProxy sampler =
                (HTTPSamplerProxy) findByName(t, "/api/orders/" + uuid + "/details");
        assertNotNull(sampler);
        assertEquals("Reference detected in https://api.example.com/bootstrap", sampler.getComment());
    }

    @Test
    void queryUuidReferencesGetCommentFromEarlierResponse() throws Exception {
        String uuid = "123e4567-e89b-12d3-a456-426614174001";
        String har = "{\"log\":{\"entries\":["
                + entry("2021-01-01T00:00:00.000Z", 50, "GET", "https://api.example.com/bootstrap", "[]",
                        commonHeadersOnly(), null, 200, "{\\\"requestId\\\":\\\"" + uuid + "\\\"}") + ","
                + entry("2021-01-01T00:00:00.100Z", 50, "GET",
                        "https://api.example.com/api/orders?requestId=" + uuid,
                        "[{\"name\":\"requestId\",\"value\":\"" + uuid + "\"}]",
                        commonHeadersOnly(), null, 200)
                + "]}}";
        HashTree t = new HarConverter(
                HarParser.parse(har.getBytes(StandardCharsets.UTF_8)), new HarImportOptions(), "test.har", "abc123")
                .convert(Set.of("api.example.com"));

        HTTPSamplerProxy sampler = (HTTPSamplerProxy) findByName(t, "/api/orders");
        assertNotNull(sampler);
        assertEquals("Reference detected in https://api.example.com/bootstrap", sampler.getComment());
    }

    @Test
    void postBodyOpaqueReferencesGetCommentFromEarlierResponse() throws Exception {
        String token = "run_AbC123xYz987654";
        String har = "{\"log\":{\"entries\":["
                + entry("2021-01-01T00:00:00.000Z", 50, "GET", "https://api.example.com/bootstrap", "[]",
                        commonHeadersOnly(), null, 200, "{\\\"runToken\\\":\\\"" + token + "\\\"}") + ","
                + entry("2021-01-01T00:00:00.100Z", 50, "POST", "https://api.example.com/api/run", "[]",
                        commonHeadersOnly(),
                        "{\"mimeType\":\"application/json\",\"text\":\"{\\\"runToken\\\":\\\"" + token + "\\\"}\"}",
                        200)
                + "]}}";
        HashTree t = new HarConverter(
                HarParser.parse(har.getBytes(StandardCharsets.UTF_8)), new HarImportOptions(), "test.har", "abc123")
                .convert(Set.of("api.example.com"));

        HTTPSamplerProxy sampler = (HTTPSamplerProxy) findByName(t, "/api/run");
        assertNotNull(sampler);
        assertEquals("Reference detected in https://api.example.com/bootstrap", sampler.getComment());
    }

    @Test
    void parserRejectsCompressedHarContent() {
        assertThrows(IOException.class,
                () -> HarParser.parse(new byte[] {(byte) 0x1f, (byte) 0x8b, 0x08, 0x00}));
        assertThrows(IOException.class,
                () -> HarParser.parse(new byte[] {0x50, 0x4b, 0x03, 0x04}));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private HTTPSamplerProxy sampler(String path) {
        List<HTTPSamplerProxy> samplers = new ArrayList<>();
        collect(tree, HTTPSamplerProxy.class, samplers);
        return samplers.stream().filter(s -> s.getPath().startsWith(path)).findFirst().orElseThrow();
    }

    private static List<HarEntry> parse() {
        try {
            return HarParser.parse(HAR.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TestElement findByType(HashTree tree, Class<?> type) {
        List<TestElement> all = new ArrayList<>();
        collect(tree, TestElement.class, all);
        return all.stream().filter(type::isInstance).findFirst().orElse(null);
    }

    private static TestElement findByName(HashTree tree, String name) {
        List<TestElement> all = new ArrayList<>();
        collect(tree, TestElement.class, all);
        return all.stream().filter(e -> name.equals(e.getName())).findFirst().orElse(null);
    }

    private static TestElement findChildByName(HashTree tree, String name) {
        for (Object o : tree.list()) {
            if (o instanceof TestElement te && name.equals(te.getName())) {
                return te;
            }
        }
        return null;
    }

    private static TestElement findChildByType(HashTree tree, Class<?> type) {
        for (Object o : tree.list()) {
            if (type.isInstance(o)) {
                return (TestElement) o;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> void collect(HashTree tree, Class<T> type, List<T> out) {
        if (tree == null) {
            return;
        }
        for (Object o : tree.list()) {
            if (type.isInstance(o)) {
                out.add((T) o);
            }
            collect(tree.getTree(o), type, out);
        }
    }

    /** Recursively find {@code target}'s subtree anywhere in the tree. */
    private static HashTree subtreeOf(HashTree tree, Object target) {
        for (Object o : tree.list()) {
            HashTree child = tree.getTree(o);
            if (o == target) {
                return child;
            }
            HashTree deep = subtreeOf(child, target);
            if (deep != null) {
                return deep;
            }
        }
        return null;
    }

    private static String commonHeaders(String extraName, String extraValue) {
        return "[{\"name\":\"accept-language\",\"value\":\"en\"},"
                + "{\"name\":\"user-agent\",\"value\":\"UA\"},"
                + "{\"name\":\"" + extraName + "\",\"value\":\"" + extraValue + "\"}]";
    }

    private static String commonHeadersOnly() {
        return "[{\"name\":\"accept-language\",\"value\":\"en\"},{\"name\":\"user-agent\",\"value\":\"UA\"}]";
    }

    private static String explicitTransaction(String entry, String id, String name) {
        return "{\"_breaktest\":{\"transactionId\":\"" + id
                + "\",\"transactionName\":\"" + name + "\"}," + entry.substring(1);
    }

    private static String entry(String started, int time, String method, String url, String queryString,
            String headers, String postData, int status) {
        return entry(started, time, method, url, queryString, headers, postData, status, "");
    }

    private static String entry(String started, int time, String method, String url, String queryString,
            String headers, String postData, int status, String responseText) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"_protocol\":\"h2\",\"serverIPAddress\":\"1.2.3.4\",");
        sb.append("\"startedDateTime\":\"").append(started).append("\",");
        sb.append("\"time\":").append(time).append(",");
        sb.append("\"timings\":{\"send\":1,\"wait\":1,\"receive\":1},");
        sb.append("\"request\":{\"method\":\"").append(method).append("\",");
        sb.append("\"url\":\"").append(url).append("\",\"httpVersion\":\"h2\",");
        sb.append("\"queryString\":").append(queryString).append(",");
        sb.append("\"headers\":").append(headers);
        if (postData != null) {
            sb.append(",\"postData\":").append(postData);
        }
        sb.append("},");
        sb.append("\"response\":{\"status\":").append(status).append(",\"headers\":[],");
        sb.append("\"content\":{\"text\":\"").append(responseText).append("\"}}}");
        return sb.toString();
    }

    private static String cachedEntry(String started, String method, String url) {
        // Real cache hits still carry request headers; only network timing is absent.
        return "{\"_protocol\":\"h2\",\"_fromCache\":\"memory\","
                + "\"startedDateTime\":\"" + started + "\",\"time\":0,\"timings\":{},"
                + "\"request\":{\"method\":\"" + method + "\",\"url\":\"" + url
                + "\",\"httpVersion\":\"h2\",\"queryString\":[],\"headers\":" + commonHeadersOnly() + "},"
                + "\"response\":{\"status\":200,\"headers\":[],\"content\":{\"text\":\"\"}}}";
    }

    private static String unmarkedMemoryCacheEntry(String started, String url, String headers) {
        return "{\"_protocol\":\"h2\",\"serverIPAddress\":\"1.2.3.4\","
                + "\"startedDateTime\":\"" + started + "\",\"time\":0.02,"
                + "\"timings\":{\"send\":0,\"wait\":10,\"receive\":0},"
                + "\"request\":{\"method\":\"GET\",\"url\":\"" + url + "\","
                + "\"httpVersion\":\"h2\",\"queryString\":[],\"headers\":" + headers + "},"
                + "\"response\":{\"status\":200,\"headers\":[],\"bodySize\":0,"
                + "\"content\":{\"size\":8189,\"text\":\"cached body\"}}}";
    }
}
