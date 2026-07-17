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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.assertions.gui.AssertionGui;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.config.gui.ArgumentsPanel;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.control.gui.LoopControlPanel;
import org.apache.jmeter.control.gui.ParallelControllerGui;
import org.apache.jmeter.control.gui.TransactionControllerGui;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.gui.CookiePanel;
import org.apache.jmeter.protocol.http.gui.HeaderPanel;
import org.apache.jmeter.protocol.http.har.HarEntry.NameValue;
import org.apache.jmeter.protocol.http.har.HarEntry.PostData;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.gui.ThreadGroupGui;
import org.apache.jmeter.visualizers.ViewResultsFullVisualizer;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

/**
 * Converts parsed HAR entries into a JMeter test-plan sub-tree, faithfully
 * porting the "breaktest" flavor of the Python {@code har2jmx.py} converter:
 * transactions split by idle gap, parallel-request detection wrapped in
 * {@link ParallelController}s, transaction-level delays, common/per-request
 * header managers, and ignore-error assertions.
 */
public final class HarConverter {

    private static final Set<String> IGNORED_REQUEST_HEADERS =
            Set.of("content-length", "cookie", "host");

    private static final String UNSAFE_CHARS = " <>\"#%{}|\\^~[]`&?+";

    private static final Pattern HEX_TOKEN_PATTERN =
            Pattern.compile("(?<![a-zA-Z0-9])([a-fA-F0-9]{8,})(?![a-zA-Z0-9])");
    private static final Pattern UUID_TOKEN_PATTERN =
            Pattern.compile("(?i)(?<![a-f0-9])([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})(?![a-f0-9])");
    private static final Pattern OPAQUE_TOKEN_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9._~+/=-])([A-Za-z0-9][A-Za-z0-9._~+/=-]{11,})(?![A-Za-z0-9._~+/=-])");
    private static final Pattern JSON_ID_FIELD_PATTERN =
            Pattern.compile("\"([^\"]*(?:id|uuid|token|nonce|csrf|session|key|code)[^\"]*)\"\\s*:\\s*\"?([A-Za-z0-9._~+/=-]{4,})\"?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_TOKEN_PATTERN =
            Pattern.compile("^[0-9]{6,}$");
    private static final Pattern REQUEST_TOKEN_PATTERN =
            Pattern.compile("[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern RESPONSE_TOKEN_PATTERN =
            Pattern.compile("[A-Za-z0-9._~+/=-]{12,}");

    private final List<HarEntry> entries;
    private final HarImportOptions options;
    private final String harName;
    private final String harMd5;

    private int sampleCounter;
    private int parallelCounter;

    public HarConverter(List<HarEntry> entries, HarImportOptions options, String harName, String harMd5) {
        this.entries = entries;
        this.options = options;
        this.harName = harName;
        this.harMd5 = harMd5;
    }

    // ---------------------------------------------------------------------
    // Static hostname helpers (used by the wizard's hostname filter step)
    // ---------------------------------------------------------------------

    /** Unique hostnames across all entries, sorted by base domain then hostname. */
    public static List<String> sortedHostnames(List<HarEntry> entries) {
        Set<String> hostnames = new TreeSet<>();
        for (HarEntry entry : entries) {
            String host = hostnameOf(entry.getUrl());
            if (host != null && !host.isEmpty()) {
                hostnames.add(host);
            }
        }
        List<String> result = new ArrayList<>(hostnames);
        result.sort((a, b) -> {
            int cmp = baseDomain(a).compareToIgnoreCase(baseDomain(b));
            if (cmp != 0) {
                return cmp;
            }
            return a.compareToIgnoreCase(b);
        });
        return result;
    }

    /** The last two labels of a hostname, e.g. {@code api.example.com -> example.com}. */
    public static String baseDomain(String hostname) {
        String[] parts = hostname.split("\\.");
        if (parts.length < 2) {
            return hostname;
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    public static String hostnameOf(String url) {
        ParsedUrl parsed = parseUrl(url);
        return parsed.host;
    }

    // ---------------------------------------------------------------------
    // Conversion entry point
    // ---------------------------------------------------------------------

    /**
     * Build the test-plan sub-tree for the given selected hostnames.
     *
     * @param selectedHostnames hostnames the user chose to keep
     * @return a {@link HashTree} whose top-level nodes (View Results Tree,
     *         Cookie Manager, HTTP Request Defaults, Thread Group) are meant to
     *         be inserted directly under the Test Plan node
     */
    public HashTree convert(Set<String> selectedHostnames) {
        List<HarEntry> kept = new ArrayList<>();
        for (HarEntry entry : entries) {
            String host = hostnameOf(entry.getUrl());
            if (host != null && selectedHostnames.contains(host)) {
                kept.add(entry);
            }
        }
        kept.sort((a, b) -> Double.compare(a.getStartMs(), b.getStartMs()));

        Map<String, String> commonHeaders = findCommonHeaders(kept);
        Set<String> commonHeadersLower = new HashSet<>();
        for (String name : commonHeaders.keySet()) {
            commonHeadersLower.add(name.toLowerCase(Locale.ROOT));
        }

        // ListedHashTree preserves insertion order, which JMeterTreeModel.addSubTree
        // relies on when inserting the samplers into the plan.
        HashTree tree = new ListedHashTree();
        // Test-plan-level config elements are skipped when the plan already has one.
        if (options.isIncludeViewResultsTree()) {
            tree.add(buildResultCollector());
        }
        if (options.isIncludeCookieManager()) {
            tree.add(buildCookieManager());
        }
        if (options.isIncludeHttpDefaults()) {
            tree.add(buildHttpDefaults());
        }

        ThreadGroup threadGroup = buildThreadGroup();
        HashTree threadGroupHt = tree.add(threadGroup);
        if (!commonHeaders.isEmpty()) {
            threadGroupHt.add(buildHeaderManager("Common Headers", commonHeaders));
        }

        List<Transaction> transactions = groupIntoTransactions(kept);
        for (int i = 0; i < transactions.size(); i++) {
            populateTransaction(threadGroupHt, transactions.get(i), commonHeadersLower, i == 0);
        }
        return tree;
    }

    private void populateTransaction(HashTree threadGroupHt, Transaction transaction,
            Set<String> commonHeadersLower, boolean isFirst) {
        long idleMs = idleMillis();
        List<List<HarEntry>> groups = splitParallelGroups(transaction.entries, idleMs);
        boolean hasParallelControllers = groups.stream().anyMatch(group -> group.size() > 1);

        TransactionController controller = buildTransactionController(
                transaction.name, transaction.recordedGapMs, isFirst, hasParallelControllers);
        HashTree transactionHt = threadGroupHt.add(controller);

        for (List<HarEntry> group : groups) {
            HashTree samplerParent = transactionHt;
            if (group.size() > 1) {
                parallelCounter++;
                int maxParallel = allHttp2(group) ? 100 : 6;
                ParallelController parallel =
                        buildParallelController("Parallel Requests " + parallelCounter, maxParallel);
                samplerParent = transactionHt.add(parallel);
            }
            for (HarEntry entry : group) {
                addSampler(samplerParent, entry, commonHeadersLower);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Transaction grouping (idle gap) and think time
    // ---------------------------------------------------------------------

    private static final class Transaction {
        final String name;
        /** Recorded idle gap (ms) before this transaction; 0 for the first. */
        final long recordedGapMs;
        final List<HarEntry> entries;

        Transaction(String name, long recordedGapMs, List<HarEntry> entries) {
            this.name = name;
            this.recordedGapMs = recordedGapMs;
            this.entries = entries;
        }
    }

    private List<Transaction> groupIntoTransactions(List<HarEntry> kept) {
        if (hasExplicitTransactions(kept)) {
            return groupByExplicitTransactions(kept);
        }

        List<Transaction> transactions = new ArrayList<>();
        long idleMs = idleMillis();
        int transactionCounter = 0;
        String currentName = null;
        long currentGapMs = 0;
        List<HarEntry> currentEntries = new ArrayList<>();
        Double previousEnd = null;

        for (HarEntry entry : kept) {
            if (shouldSkip(entry)) {
                continue;
            }
            if (currentName == null) {
                transactionCounter++;
                currentName = String.format(Locale.ROOT, "%02d_Transaction", transactionCounter);
            } else if (previousEnd != null && (entry.getStartMs() - previousEnd) > idleMs) {
                transactions.add(new Transaction(currentName, currentGapMs, currentEntries));
                currentEntries = new ArrayList<>();
                transactionCounter++;
                currentName = String.format(Locale.ROOT, "%02d_Transaction", transactionCounter);
                currentGapMs = (long) Math.max(entry.getStartMs() - previousEnd, 0);
            }
            currentEntries.add(entry);
            previousEnd = entry.getEndMs();
        }
        if (currentName != null && !currentEntries.isEmpty()) {
            transactions.add(new Transaction(currentName, currentGapMs, currentEntries));
        }
        return transactions;
    }

    static boolean hasExplicitTransactions(List<HarEntry> entries) {
        return entries != null && entries.stream()
                .anyMatch(entry -> !entry.getTransactionId().isBlank());
    }

    private static List<Transaction> groupByExplicitTransactions(List<HarEntry> kept) {
        List<Transaction> transactions = new ArrayList<>();
        String currentId = null;
        String currentName = null;
        long currentGapMs = 0;
        int transactionCounter = 0;
        List<HarEntry> currentEntries = new ArrayList<>();
        Double previousEnd = null;

        for (HarEntry entry : kept) {
            if (shouldSkip(entry)) {
                continue;
            }
            String entryId = entry.getTransactionId().isBlank()
                    ? currentId
                    : entry.getTransactionId();
            if (entryId == null) {
                entryId = "unassigned-1";
            }
            if (currentId == null || !currentId.equals(entryId)) {
                if (!currentEntries.isEmpty()) {
                    transactions.add(new Transaction(currentName, currentGapMs, currentEntries));
                }
                currentEntries = new ArrayList<>();
                currentId = entryId;
                transactionCounter++;
                currentName = explicitTransactionName(entry, transactionCounter);
                currentGapMs = previousEnd == null
                        ? 0
                        : (long) Math.max(entry.getStartMs() - previousEnd, 0);
            }
            currentEntries.add(entry);
            previousEnd = entry.getEndMs();
        }
        if (!currentEntries.isEmpty()) {
            transactions.add(new Transaction(currentName, currentGapMs, currentEntries));
        }
        return transactions;
    }

    private static String explicitTransactionName(HarEntry entry, int transactionCounter) {
        String name = entry.getTransactionName().trim();
        if (name.isEmpty()) {
            return String.format(Locale.ROOT, "%02d_Transaction", transactionCounter);
        }
        // Element names are display labels and must not evaluate JMeter variables.
        return name.replace("${", "{");
    }

    private long idleMillis() {
        return (long) options.getIdleTimeSeconds() * 1000L;
    }

    // ---------------------------------------------------------------------
    // Parallel-group splitting and dependency detection
    // ---------------------------------------------------------------------

    private static List<List<HarEntry>> splitParallelGroups(List<HarEntry> transactionEntries, long splitThresholdMs) {
        List<List<HarEntry>> groups = new ArrayList<>();
        if (transactionEntries.isEmpty()) {
            return groups;
        }
        List<HarEntry> sorted = new ArrayList<>(transactionEntries);
        sorted.sort((a, b) -> Double.compare(a.getStartMs(), b.getStartMs()));

        List<HarEntry> currentGroup = new ArrayList<>();
        HarEntry previous = sorted.get(0);
        // Mirror the Python quirk: the first entry seeds a standalone group, and a
        // fresh currentGroup accumulates from the second entry onward.
        groups.add(new ArrayList<>(List.of(sorted.get(0))));

        for (int i = 1; i < sorted.size(); i++) {
            HarEntry entry = sorted.get(i);
            double pauseMs = entry.getStartMs() - previous.getEndMs();
            boolean startsNewController =
                    (pauseMs > 0 && pauseMs < splitThresholdMs) || entryReferencesAny(entry, currentGroup);
            if (!currentGroup.isEmpty() && startsNewController) {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
                currentGroup.add(entry);
            } else {
                currentGroup.add(entry);
            }
            previous = entry;
        }
        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }
        return groups;
    }

    private static boolean entryReferencesAny(HarEntry entry, List<HarEntry> candidates) {
        String requestText = entryRequestText(entry);
        if (requestText.isEmpty()) {
            return false;
        }
        for (HarEntry candidate : candidates) {
            for (String token : referenceTokens(candidate)) {
                if (requestText.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String entryRequestText(HarEntry entry) {
        ParsedUrl url = parseUrl(entry.getUrl());
        List<String> pieces = new ArrayList<>();
        pieces.add(url.path);
        pieces.add(url.query);
        for (NameValue param : entry.getQueryString()) {
            pieces.add(param.getName());
            pieces.add(param.getValue());
        }
        PostData postData = entry.getPostData();
        if (postData != null) {
            pieces.add(postData.getText());
            for (NameValue param : postData.getParams()) {
                pieces.add(param.getName());
                pieces.add(param.getValue());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String piece : pieces) {
            if (piece != null && !piece.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(piece);
            }
        }
        return sb.toString();
    }

    private static Set<String> referenceTokens(HarEntry entry) {
        Set<String> tokens = new HashSet<>();
        Matcher requestMatcher = REQUEST_TOKEN_PATTERN.matcher(entryRequestText(entry));
        while (requestMatcher.find()) {
            tokens.add(requestMatcher.group());
        }
        for (NameValue header : entry.getResponseHeaders()) {
            Matcher m = RESPONSE_TOKEN_PATTERN.matcher(header.getValue());
            while (m.find()) {
                tokens.add(m.group());
            }
        }
        Matcher contentMatcher = RESPONSE_TOKEN_PATTERN.matcher(entry.getResponseContentText());
        while (contentMatcher.find()) {
            tokens.add(contentMatcher.group());
        }
        String path = parseUrl(entry.getUrl()).path;
        String lastSegment = path;
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            lastSegment = path.substring(slash + 1);
        }
        if (lastSegment.length() >= 8) {
            tokens.add(lastSegment);
        }
        return tokens;
    }

    private static boolean allHttp2(List<HarEntry> group) {
        if (group.isEmpty()) {
            return false;
        }
        for (HarEntry entry : group) {
            String protocol = entry.getProtocol();
            if (!protocol.contains("h2") && !protocol.contains("http/2")) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Entry skipping and common headers
    // ---------------------------------------------------------------------

    private static boolean shouldSkip(HarEntry entry) {
        String fromCache = entry.getFromCache();
        if ("memory".equals(fromCache) || "disk".equals(fromCache)) {
            return true;
        }
        if (entry.getServerIpAddress() != null && !entry.getServerIpAddress().isEmpty()) {
            return false;
        }
        return !entry.hasPositiveTiming();
    }

    private static Map<String, String> findCommonHeaders(List<HarEntry> entries) {
        List<HarEntry> nonPreflight = new ArrayList<>();
        for (HarEntry entry : entries) {
            if (!"OPTIONS".equals(entry.getMethod())) {
                nonPreflight.add(entry);
            }
        }
        if (nonPreflight.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> common = new LinkedHashMap<>();
        for (NameValue header : nonPreflight.get(0).getRequestHeaders()) {
            if (isExportableHeader(header.getName())) {
                common.put(header.getName(), header.getValue());
            }
        }
        for (int i = 1; i < nonPreflight.size(); i++) {
            Map<String, String> currentLower = new HashMap<>();
            for (NameValue header : nonPreflight.get(i).getRequestHeaders()) {
                if (isExportableHeader(header.getName())) {
                    currentLower.put(header.getName().toLowerCase(Locale.ROOT), header.getValue());
                }
            }
            common.entrySet().removeIf(e -> {
                String key = e.getKey().toLowerCase(Locale.ROOT);
                return !currentLower.containsKey(key) || !currentLower.get(key).equals(e.getValue());
            });
        }
        return common;
    }

    private static boolean isExportableHeader(String name) {
        return !IGNORED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT)) && !name.startsWith(":");
    }

    // ---------------------------------------------------------------------
    // Sampler and child elements
    // ---------------------------------------------------------------------

    private void addSampler(HashTree parent, HarEntry entry, Set<String> commonHeadersLower) {
        String method = entry.getMethod();
        ParsedUrl url = parseUrl(entry.getUrl());
        String path = url.path;
        String fullPath = path;
        boolean bodyMethod = isBodyMethod(method);
        if (url.query != null && !url.query.isEmpty() && bodyMethod) {
            fullPath = path + "?" + url.query;
        }

        sampleCounter++;
        String name;
        if (options.isAddIndex()) {
            name = path + "-" + String.format(Locale.ROOT, "%03d", sampleCounter);
        } else {
            name = path;
        }
        if ("OPTIONS".equals(method)) {
            name += "_preflight";
        }

        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setProperty(TestElement.GUI_CLASS, HttpTestSampleGui.class.getName());
        sampler.setName(name);
        if (options.isDetectDynamicUrls()) {
            String comment = detectDynamicReferences(entry, url);
            if (!comment.isEmpty()) {
                sampler.setComment(comment);
            }
        }
        sampler.setMethod(method);
        sampler.setDomain(url.host == null ? "" : url.host);
        if (url.port != -1) {
            sampler.setPort(url.port);
        }
        sampler.setProtocol(url.scheme == null ? "" : url.scheme);
        sampler.setPath(fullPath);
        sampler.setFollowRedirects(false);
        sampler.setAutoRedirects(false);
        sampler.setUseKeepAlive(true);
        sampler.setProperty(RecordedHarExchangeResolver.HAR_ENTRY_INDEX, String.valueOf(entry.getOriginalIndex()));
        sampler.setProperty(RecordedHarExchangeResolver.HAR_STARTED_DATE_TIME, entry.getStartedDateTime());
        sampler.setProperty(RecordedHarExchangeResolver.HAR_REQUEST_METHOD, method);
        sampler.setProperty(RecordedHarExchangeResolver.HAR_REQUEST_URL, entry.getUrl());

        Arguments arguments = new Arguments();
        sampler.setArguments(arguments);
        if (!bodyMethod) {
            for (NameValue param : entry.getQueryString()) {
                String decodedName = percentDecode(param.getName());
                String decodedValue = percentDecode(param.getValue());
                boolean alwaysEncode = needsUrlEncoding(decodedName) || !param.getName().equals(decodedName)
                        || needsUrlEncoding(decodedValue) || !param.getValue().equals(decodedValue);
                addHttpArgument(arguments, decodedName, decodedValue, alwaysEncode, true);
            }
        } else if (entry.getPostData() != null) {
            PostData postData = entry.getPostData();
            if (!postData.getParams().isEmpty()) {
                for (NameValue param : postData.getParams()) {
                    String decodedValue = percentDecode(param.getValue());
                    boolean alwaysEncode = needsUrlEncoding(decodedValue) || !param.getValue().equals(decodedValue);
                    addHttpArgument(arguments, param.getName(), decodedValue, alwaysEncode, true);
                }
            } else if (postData.getText() != null) {
                sampler.setPostBodyRaw(true);
                String cleaned = removeInvalidXmlChars(postData.getText());
                addHttpArgument(arguments, "", cleaned, false, false);
            }
        }

        // Per-request headers that aren't in the shared Common Headers manager are
        // stored natively on the HTTP Request (BreakTest feature), not as a child manager.
        List<Header> uniqueHeaders = new ArrayList<>();
        for (NameValue header : entry.getRequestHeaders()) {
            String lower = header.getName().toLowerCase(Locale.ROOT);
            if (!commonHeadersLower.contains(lower) && isExportableHeader(header.getName())) {
                uniqueHeaders.add(new Header(header.getName(), header.getValue()));
            }
        }
        if (!uniqueHeaders.isEmpty()) {
            sampler.setNativeHeaders(uniqueHeaders);
        }

        HashTree samplerHt = parent.add(sampler);

        int status = entry.getResponseStatus();
        if (options.isIgnoreErrors() && status >= 400 && status <= 599) {
            samplerHt.add(buildIgnoreErrorAssertion(status));
        }
    }

    private static void addHttpArgument(Arguments arguments, String name, String value,
            boolean alwaysEncode, boolean useEquals) {
        HTTPArgument argument = new HTTPArgument(name, value, "=");
        argument.setAlwaysEncoded(alwaysEncode);
        argument.setUseEquals(useEquals);
        arguments.addArgument(argument);
    }

    private static HeaderManager buildHeaderManager(String name, Map<String, String> headers) {
        HeaderManager headerManager = new HeaderManager();
        headerManager.setProperty(TestElement.GUI_CLASS, HeaderPanel.class.getName());
        headerManager.setName(name);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            headerManager.add(new Header(header.getKey(), header.getValue()));
        }
        return headerManager;
    }

    private static ResponseAssertion buildIgnoreErrorAssertion(int status) {
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setProperty(TestElement.GUI_CLASS, AssertionGui.class.getName());
        assertion.setName("Ignore HTTP-" + status);
        assertion.setComment("Recorded HTTP-" + status + " response, ignoring it");
        assertion.setTestFieldResponseCode();
        assertion.setToSubstringType();
        assertion.setAssumeSuccess(true);
        return assertion;
    }

    // ---------------------------------------------------------------------
    // Container / config elements
    // ---------------------------------------------------------------------

    private ThreadGroup buildThreadGroup() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setProperty(TestElement.GUI_CLASS, ThreadGroupGui.class.getName());
        threadGroup.setName("Thread Group");
        threadGroup.setProperty(ThreadGroup.ON_SAMPLE_ERROR, ThreadGroup.ON_SAMPLE_ERROR_START_NEXT_LOOP);
        threadGroup.setProperty(ThreadGroup.DELAYED_START, true);
        threadGroup.setNumThreads(1);
        threadGroup.setRampUp(1);
        threadGroup.setScheduler(false);
        threadGroup.setProperty(ThreadGroup.DURATION, "");
        threadGroup.setProperty(ThreadGroup.DELAY, "");
        threadGroup.setIsSameUserOnNextIteration(true);
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_FILENAME, harName == null ? "" : harName);
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_MD5, harMd5 == null ? "" : harMd5);

        LoopController loopController = new LoopController();
        loopController.setProperty(TestElement.GUI_CLASS, LoopControlPanel.class.getName());
        loopController.setName("Loop Controller");
        loopController.setContinueForever(false);
        loopController.setLoops(1);
        threadGroup.setSamplerController(loopController);
        return threadGroup;
    }

    private TransactionController buildTransactionController(String name, long recordedGapMs,
            boolean isFirst, boolean hasParallelControllers) {
        TransactionController controller = new TransactionController();
        controller.setProperty(TestElement.GUI_CLASS, TransactionControllerGui.class.getName());
        controller.setName(name);
        controller.setIncludeTimers(false);
        controller.setGenerateParentSample(true);

        controller.setProperty("TransactionController.timingMode",
                hasParallelControllers ? "total_include_timers" : "sum_child_samples");

        applyDelay(controller, recordedGapMs, isFirst);

        controller.setProperty("TransactionController.pacingMode", "Disabled");
        controller.setProperty("TransactionController.fixedPacing", "0");
        controller.setProperty("TransactionController.pacingMin", "0");
        controller.setProperty("TransactionController.pacingMax", "0");
        return controller;
    }

    /** Configure the transaction's delay from the chosen mode (no delay before the first). */
    private void applyDelay(TransactionController tc, long recordedGapMs, boolean isFirst) {
        if (isFirst) {
            setDisabledDelay(tc);
            return;
        }
        switch (options.getDelayMode()) {
            case NONE -> setDisabledDelay(tc);
            case FIXED -> setFixedDelay(tc, options.getEffectiveFixedDelay());
            case RANDOM -> setRangeDelay(tc, TransactionController.DELAY_RANDOM,
                    options.getEffectiveDelayMin(), options.getEffectiveDelayMax());
            case GAUSSIAN -> setRangeDelay(tc, TransactionController.DELAY_GAUSSIAN_RANDOM,
                    options.getEffectiveDelayMin(), options.getEffectiveDelayMax());
            case AS_RECORDED -> applyRecordedDelay(tc, recordedGapMs);
        }
    }

    private void applyRecordedDelay(TransactionController tc, long recordedGapMs) {
        long delay = Math.max(recordedGapMs, 0);
        int pct = Math.max(options.getRecordedRandomPercent(), 0);
        if (delay <= 0) {
            setDisabledDelay(tc);
        } else if (pct > 0) {
            long min = Math.max((long) (delay * (1 - pct / 100.0)), 0);
            long max = Math.max((long) (delay * (1 + pct / 100.0)), 0);
            setRangeDelay(tc, TransactionController.DELAY_RANDOM, min, max);
        } else {
            setFixedDelay(tc, delay);
        }
    }

    private static void setDisabledDelay(TransactionController tc) {
        tc.setProperty("TransactionController.delayMode", TransactionController.DELAY_DISABLED);
        tc.setProperty("TransactionController.fixedDelay", "0");
        tc.setProperty("TransactionController.delayMin", "0");
        tc.setProperty("TransactionController.delayMax", "0");
    }

    private static void setFixedDelay(TransactionController tc, long delayMs) {
        setFixedDelay(tc, Long.toString(Math.max(delayMs, 0)));
    }

    private static void setFixedDelay(TransactionController tc, String delay) {
        tc.setProperty("TransactionController.delayMode", TransactionController.DELAY_FIXED);
        tc.setProperty("TransactionController.fixedDelay", delay);
        tc.setProperty("TransactionController.delayMin", "0");
        tc.setProperty("TransactionController.delayMax", "0");
    }

    private static void setRangeDelay(TransactionController tc, String mode, long minMs, long maxMs) {
        long min = Math.max(minMs, 0);
        long max = Math.max(maxMs, min);
        setRangeDelay(tc, mode, Long.toString(min), Long.toString(max));
    }

    private static void setRangeDelay(TransactionController tc, String mode, String min, String max) {
        tc.setProperty("TransactionController.delayMode", mode);
        tc.setProperty("TransactionController.fixedDelay", "0");
        tc.setProperty("TransactionController.delayMin", min);
        tc.setProperty("TransactionController.delayMax", max);
    }

    private static ParallelController buildParallelController(String name, int maxParallel) {
        ParallelController controller = new ParallelController();
        controller.setProperty(TestElement.GUI_CLASS, ParallelControllerGui.class.getName());
        controller.setName(name);
        controller.setMaxParallel(maxParallel);
        return controller;
    }

    private static CookieManager buildCookieManager() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setProperty(TestElement.GUI_CLASS, CookiePanel.class.getName());
        cookieManager.setName("HTTP Cookie Manager");
        cookieManager.setClearEachIteration(true);
        cookieManager.setControlledByThread(false);
        return cookieManager;
    }

    private ConfigTestElement buildHttpDefaults() {
        ConfigTestElement defaults = new ConfigTestElement();
        defaults.setProperty(TestElement.GUI_CLASS, HttpDefaultsGui.class.getName());
        defaults.setName("HTTP Request Defaults");
        defaults.setProperty("HTTPSampler.concurrentPool", "6");
        defaults.setProperty("HTTPSampler.connect_timeout",
                Integer.toString(options.getConnectTimeoutSeconds() * 1000));
        defaults.setProperty("HTTPSampler.response_timeout",
                Integer.toString(options.getReadTimeoutSeconds() * 1000));
        Arguments arguments = new Arguments();
        arguments.setProperty(TestElement.GUI_CLASS, ArgumentsPanel.class.getName());
        arguments.setName("User Defined Variables");
        defaults.setProperty(new TestElementProperty(HTTPSamplerBase.ARGUMENTS, arguments));
        return defaults;
    }

    private static ResultCollector buildResultCollector() {
        ResultCollector resultCollector = new ResultCollector();
        resultCollector.setProperty(TestElement.GUI_CLASS, ViewResultsFullVisualizer.class.getName());
        resultCollector.setName("View Results Tree");
        return resultCollector;
    }

    // ---------------------------------------------------------------------
    // Small string / URL helpers ported from har2jmx.py
    // ---------------------------------------------------------------------

    private static boolean isBodyMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private static boolean needsUrlEncoding(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (UNSAFE_CHARS.indexOf(c) >= 0 || c > 127) {
                return true;
            }
        }
        return false;
    }

    /** Percent-decode like Python's urllib.parse.unquote (does NOT turn '+' into space). */
    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        // URLDecoder maps '+' to space, so protect existing '+' first.
        String protectedValue = value.replace("+", "%2B");
        try {
            return URLDecoder.decode(protectedValue, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String removeInvalidXmlChars(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0x9 || c == 0xA || c == 0xD
                    || (c >= 0x20 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String detectDynamicReferences(HarEntry target, ParsedUrl url) {
        Set<String> tokens = dynamicRequestTokens(target, url);
        if (tokens.isEmpty()) {
            return "";
        }
        for (HarEntry entry : entries) {
            if (entry.getOriginalIndex() >= target.getOriginalIndex()) {
                continue;
            }
            for (NameValue header : entry.getResponseHeaders()) {
                for (String token : tokens) {
                    if (header.getValue().contains(token)) {
                        return "Reference detected in " + entry.getUrl();
                    }
                }
            }
            for (String token : tokens) {
                if (entry.getResponseContentText().contains(token)) {
                    return "Reference detected in " + entry.getUrl();
                }
            }
        }
        return "";
    }

    private static Set<String> dynamicRequestTokens(HarEntry entry, ParsedUrl url) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String segment : url.path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            addDynamicTokensFromText(segment, tokens);
        }
        addDynamicTokensFromRawQuery(url.query, tokens);
        for (NameValue param : entry.getQueryString()) {
            addDynamicTokensFromNameValue(param.getName(), param.getValue(), tokens);
        }
        PostData postData = entry.getPostData();
        if (postData != null) {
            addDynamicTokensFromText(postData.getText(), tokens);
            addJsonIdFieldTokens(postData.getText(), tokens);
            for (NameValue param : postData.getParams()) {
                addDynamicTokensFromNameValue(param.getName(), param.getValue(), tokens);
            }
        }
        return tokens;
    }

    private static void addDynamicTokensFromRawQuery(String query, Set<String> tokens) {
        if (query == null || query.isEmpty()) {
            return;
        }
        for (String param : query.split("&")) {
            int equals = param.indexOf('=');
            if (equals >= 0) {
                addDynamicTokensFromNameValue(
                        percentDecode(param.substring(0, equals)),
                        percentDecode(param.substring(equals + 1)),
                        tokens);
            } else {
                addDynamicTokensFromText(percentDecode(param), tokens);
            }
        }
    }

    private static void addDynamicTokensFromNameValue(String name, String value, Set<String> tokens) {
        addDynamicTokensFromText(value, tokens);
        if (isIdLikeName(name) && value != null && NUMERIC_TOKEN_PATTERN.matcher(value).matches()) {
            tokens.add(value);
        }
    }

    private static void addJsonIdFieldTokens(String text, Set<String> tokens) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = JSON_ID_FIELD_PATTERN.matcher(text);
        while (matcher.find()) {
            addDynamicTokensFromNameValue(matcher.group(1), matcher.group(2), tokens);
        }
    }

    private static void addDynamicTokensFromText(String text, Set<String> tokens) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher uuidMatcher = UUID_TOKEN_PATTERN.matcher(text);
        while (uuidMatcher.find()) {
            tokens.add(uuidMatcher.group(1));
        }
        Matcher hexMatcher = HEX_TOKEN_PATTERN.matcher(text);
        while (hexMatcher.find()) {
            tokens.add(hexMatcher.group(1));
        }
        Matcher opaqueMatcher = OPAQUE_TOKEN_PATTERN.matcher(text);
        while (opaqueMatcher.find()) {
            String token = opaqueMatcher.group(1);
            if (looksOpaque(token)) {
                tokens.add(token);
            }
        }
    }

    private static boolean isIdLikeName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("id")
                || lower.contains("uuid")
                || lower.contains("token")
                || lower.contains("nonce")
                || lower.contains("csrf")
                || lower.contains("session")
                || lower.contains("key")
                || lower.contains("code");
    }

    private static boolean looksOpaque(String token) {
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else if ("._~+/=-".indexOf(c) >= 0) {
                return true;
            }
            if (hasLetter && hasDigit) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Lenient URL parsing (HAR request URLs are absolute)
    // ---------------------------------------------------------------------

    private static final Pattern URL_PATTERN =
            Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/:?#]+)(?::(\\d+))?([^?#]*)(?:\\?(.*))?$");

    private static final class ParsedUrl {
        final String scheme;
        final String host;
        final int port;
        final String path;
        final String query;

        ParsedUrl(String scheme, String host, int port, String path, String query) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.path = path == null ? "" : path;
            this.query = query;
        }
    }

    private static ParsedUrl parseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return new ParsedUrl("", "", -1, "", null);
        }
        try {
            URI uri = new URI(url);
            if (uri.getHost() != null) {
                return new ParsedUrl(uri.getScheme(), uri.getHost(), uri.getPort(),
                        uri.getRawPath() == null ? "" : uri.getRawPath(), uri.getRawQuery());
            }
        } catch (Exception ignored) {
            // fall back to the regex parser below
        }
        Matcher matcher = URL_PATTERN.matcher(url);
        if (matcher.matches()) {
            int port = matcher.group(3) == null ? -1 : Integer.parseInt(matcher.group(3));
            return new ParsedUrl(matcher.group(1), matcher.group(2), port,
                    matcher.group(4), matcher.group(5));
        }
        return new ParsedUrl("", "", -1, url, null);
    }
}
