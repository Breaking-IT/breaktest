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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketCloseSampler;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketConnectSampler;
import org.apache.jmeter.protocol.websocket.sampler.WebSocketSendWaitSampler;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
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

    private final List<HarEntry> entries;
    private final HarImportOptions options;
    private final String harName;
    private final String harMd5;

    private int sampleCounter;
    private int sseSessionCounter;
    private final Map<Integer, String> webSocketSessionNames = new HashMap<>();
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
            if (host != null && selectedHostnames.contains(host) && !shouldSkip(entry)) {
                kept.add(entry);
            }
        }
        kept.sort((a, b) -> Double.compare(a.getStartMs(), b.getStartMs()));
        sseSessionCounter = 0;
        webSocketSessionNames.clear();
        for (HarEntry entry : kept) {
            if (entry.isWebSocket()) {
                webSocketSessionNames.put(entry.getOriginalIndex(), "websocket-" + (webSocketSessionNames.size() + 1));
            }
        }

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

        List<Transaction> transactions = groupIntoTransactions(withWebSocketEvents(kept));
        for (int i = 0; i < transactions.size(); i++) {
            populateTransaction(threadGroupHt, transactions.get(i), commonHeadersLower, i == 0);
        }
        return tree;
    }

    private static List<HarEntry> withWebSocketEvents(List<HarEntry> kept) {
        List<HarEntry> timeline = new ArrayList<>(kept);
        int syntheticIndex = -1;
        for (HarEntry connection : kept) {
            for (var message : connection.getWebSocketMessages()) {
                if ("send".equals(message.direction()) && (message.opcode() == 1 || message.opcode() == 2)) {
                    timeline.add(HarEntry.webSocketSend(connection, message, syntheticIndex--));
                }
            }
            if (connection.isWebSocket() && connection.getClientCloseOffset() != null) {
                timeline.add(HarEntry.webSocketClose(connection, syntheticIndex--));
            }
        }
        timeline.sort((a, b) -> Double.compare(a.getStartMs(), b.getStartMs()));
        HarEntry transaction = null;
        for (HarEntry entry : timeline) {
            if (entry.getWebSocketConnection() == null && !entry.getTransactionId().isBlank()) {
                transaction = entry;
            } else if (entry.getWebSocketConnection() != null && transaction != null) {
                entry.setTransactionId(transaction.getTransactionId());
                entry.setTransactionName(transaction.getTransactionName());
            }
        }
        return timeline;
    }

    /** Build a recorder scenario without recreating its samplers or their native capture links. */
    public HashTree layoutRecorded(Map<HarEntry, HashTree> recorded, boolean transactions) {
        return layoutRecorded(recorded, transactions ? 4 : 0);
    }

    /** Recorder grouping values: none, separators, simple controllers, first only, transactions. */
    public HashTree layoutRecorded(Map<HarEntry, HashTree> recorded, int grouping) {
        if (grouping == 3) {
            Map<HarEntry, HashTree> firstOnly = new LinkedHashMap<>();
            Set<String> seen = new HashSet<>();
            recorded.entrySet().stream().sorted(Map.Entry.comparingByKey(
                    java.util.Comparator.comparingDouble(HarEntry::getStartMs))).forEach(entry -> {
                        if (seen.add(entry.getKey().getTransactionId())) {
                            firstOnly.put(entry.getKey(), entry.getValue());
                            Object sampler = entry.getValue().list().iterator().next();
                            if (sampler instanceof HTTPSamplerBase http && !entry.getKey().isWebSocket()) {
                                http.setFollowRedirects(true);
                                http.setImageParser(true);
                            }
                        }
                    });
            recorded = firstOnly;
        }
        recorded = expandRecordedWebSockets(recorded);
        List<HarEntry> ordered = new ArrayList<>(recorded.keySet());
        ordered.sort(java.util.Comparator.comparingDouble(HarEntry::getStartMs));
        HashTree tree = new ListedHashTree();
        if (grouping == 0 || grouping == 3) {
            appendRecordedGroups(tree, ordered, recorded);
            return tree;
        }
        List<Transaction> groups = groupIntoTransactions(ordered);
        for (int i = 0; i < groups.size(); i++) {
            Transaction group = groups.get(i);
            boolean parallel = splitParallelGroups(group.entries, true).stream().anyMatch(wave -> wave.size() > 1);
            HashTree parent = tree;
            if (grouping == 4) {
                parent = tree.add(buildTransactionController(group.name, group.recordedGapMs, i == 0, parallel));
            } else if (grouping == 2 || i > 0) {
                var controller = new org.apache.jmeter.control.GenericController();
                controller.setProperty(TestElement.GUI_CLASS, org.apache.jmeter.control.gui.LogicControllerGui.class.getName());
                controller.setName(grouping == 2 ? group.name : "-------------------");
                HashTree added = tree.add(controller);
                if (grouping == 2) {
                    parent = added;
                }
            }
            appendRecordedGroups(parent, group.entries, recorded);
        }
        return tree;
    }

    private Map<HarEntry, HashTree> expandRecordedWebSockets(Map<HarEntry, HashTree> recorded) {
        Map<HarEntry, HashTree> expanded = new LinkedHashMap<>(recorded);
        List<HarEntry> entries = new ArrayList<>(recorded.keySet());
        int index = 0;
        for (HarEntry entry : entries) {
            entry.setOriginalIndex(index++);
            if (entry.isWebSocket()) {
                webSocketSessionNames.put(entry.getOriginalIndex(), "proxy-websocket-" + java.util.UUID.randomUUID());
                HashTree original = recorded.get(entry);
                TestElement source = (TestElement) original.list().iterator().next();
                HashTree replacement = new ListedHashTree();
                addWebSocketSampler(replacement, entry, source.getName());
                TestElement connect = (TestElement) replacement.list().iterator().next();
                for (String property : List.of(org.apache.jmeter.recording.RecordedExchangeStore.MANIFEST_PROPERTY,
                        org.apache.jmeter.recording.RecordedExchangeStore.CHECKSUM_PROPERTY,
                        org.apache.jmeter.recording.RecordedExchangeStore.EXCHANGE_ID_PROPERTY)) {
                    connect.setProperty(property, source.getPropertyAsString(property));
                }
                // These entries came from the proxy, not from the target's inherited HAR file.
                for (String property : List.of(RecordedHarExchangeResolver.HAR_ENTRY_INDEX,
                        RecordedHarExchangeResolver.HAR_STARTED_DATE_TIME, RecordedHarExchangeResolver.HAR_REQUEST_METHOD,
                        RecordedHarExchangeResolver.HAR_REQUEST_URL)) {
                    connect.removeProperty(property);
                }
                connect.setEnabled(source.isEnabled());
                connect.setComment(source.getComment());
                replacement.getTree(connect).add(original.getTree(source));
                expanded.put(entry, replacement);
            }
        }
        for (HarEntry event : withWebSocketEvents(entries)) {
            if (event.getWebSocketConnection() != null) {
                HashTree tree = new ListedHashTree();
                addSampler(tree, event, Set.of());
                TestElement connect = (TestElement) expanded.get(event.getWebSocketConnection()).list().iterator().next();
                for (Object element : tree.list()) {
                    ((TestElement) element).setEnabled(connect.isEnabled());
                }
                expanded.put(event, tree);
            }
        }
        return expanded;
    }

    private void appendRecordedGroups(HashTree parent, List<HarEntry> entries, Map<HarEntry, HashTree> recorded) {
        for (List<HarEntry> wave : splitParallelGroups(entries, true)) {
            HashTree destination = parent;
            if (wave.size() > 1) {
                destination = parent.add(buildParallelController("Parallel Requests " + ++parallelCounter,
                        allMultiplexedProtocol(wave) ? 100 : 6));
            }
            for (HarEntry entry : wave) {
                destination.add(recorded.get(entry));
            }
        }
    }

    private void populateTransaction(HashTree threadGroupHt, Transaction transaction,
            Set<String> commonHeadersLower, boolean isFirst) {
        List<List<HarEntry>> groups = splitForCorrelations(splitParallelGroups(transaction.entries));
        boolean hasParallelControllers = groups.stream().anyMatch(group -> group.size() > 1);

        TransactionController controller = buildTransactionController(
                transaction.name, transaction.recordedGapMs, isFirst, hasParallelControllers);
        HashTree transactionHt = threadGroupHt.add(controller);

        for (List<HarEntry> group : groups) {
            HashTree samplerParent = transactionHt;
            if (group.size() > 1) {
                parallelCounter++;
                int maxParallel = allMultiplexedProtocol(group) ? 100 : 6;
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
            previousEnd = previousEnd == null ? entry.getEndMs() : Math.max(previousEnd, entry.getEndMs());
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
            previousEnd = previousEnd == null ? entry.getEndMs() : Math.max(previousEnd, entry.getEndMs());
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
    // Parallel-group splitting
    // ---------------------------------------------------------------------

    private static List<List<HarEntry>> splitParallelGroups(List<HarEntry> transactionEntries) {
        return splitParallelGroups(transactionEntries, false);
    }

    private static List<List<HarEntry>> splitParallelGroups(List<HarEntry> transactionEntries, boolean connectedOverlaps) {
        List<List<HarEntry>> groups = new ArrayList<>();
        if (transactionEntries.isEmpty()) {
            return groups;
        }
        List<HarEntry> sorted = new ArrayList<>(transactionEntries);
        sorted.sort((a, b) -> Double.compare(a.getStartMs(), b.getStartMs()));

        List<HarEntry> currentGroup = new ArrayList<>();
        Map<String, Integer> pendingRedirectTargets = new HashMap<>();
        double boundaryMs = connectedOverlaps ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (HarEntry entry : sorted) {
            // HAR conversion retains conservative waves ending at the earliest completion.
            // Redirect targets always start a new group, even if timestamp rounding puts
            // their start just before the redirect response's recorded end.
            boolean followsRedirect = consumeRedirectTarget(pendingRedirectTargets, entry.getUrl());
            HarEntry connection = entry.getWebSocketConnection();
            boolean needsEarlierWebSocketStep = connection != null && currentGroup.stream()
                    .anyMatch(previous -> previous == connection || previous.getWebSocketConnection() == connection);
            // Recorder captures also retain overlap chains: A overlaps B, and B overlaps C.
            // A strict end boundary keeps sequential requests and zero-duration captures separate.
            boolean afterBoundary = connectedOverlaps ? entry.getStartMs() >= boundaryMs : entry.getStartMs() > boundaryMs;
            if (!currentGroup.isEmpty() && (afterBoundary || followsRedirect || needsEarlierWebSocketStep)) {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
                boundaryMs = connectedOverlaps ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            currentGroup.add(entry);
            boundaryMs = connectedOverlaps ? Math.max(boundaryMs, entry.getEndMs()) : Math.min(boundaryMs, entry.getEndMs());
            String redirectTarget = redirectTargetOf(entry);
            if (!redirectTarget.isEmpty()) {
                pendingRedirectTargets.merge(redirectTarget, 1, Integer::sum);
            }
        }
        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }
        return groups;
    }

    /**
     * Splits a wave whenever it holds both the request a correlation extracts from and a request
     * that uses the extracted value. Requests in one Parallel Controller start together, so the
     * consumer has to move into the next controller for the variable to be set in time.
     */
    private List<List<HarEntry>> splitForCorrelations(List<List<HarEntry>> groups) {
        Map<Integer, Set<Integer>> sourcesByConsumer = correlationSourcesByConsumer();
        if (sourcesByConsumer.isEmpty()) {
            return groups;
        }
        List<List<HarEntry>> result = new ArrayList<>();
        for (List<HarEntry> group : groups) {
            if (group.size() < 2) {
                result.add(group);
                continue;
            }
            List<HarEntry> current = new ArrayList<>();
            Set<Integer> extractedInCurrent = new HashSet<>();
            for (HarEntry entry : group) {
                Set<Integer> sources = sourcesByConsumer.getOrDefault(entry.getOriginalIndex(), Set.of());
                if (!current.isEmpty() && !Collections.disjoint(sources, extractedInCurrent)) {
                    result.add(current);
                    current = new ArrayList<>();
                    extractedInCurrent.clear();
                }
                current.add(entry);
                extractedInCurrent.add(entry.getOriginalIndex());
            }
            result.add(current);
        }
        return result;
    }

    private Map<Integer, Set<Integer>> correlationSourcesByConsumer() {
        Map<Integer, Set<Integer>> sourcesByConsumer = new HashMap<>();
        for (HarPredefinedCorrelation correlation : options.getPredefinedCorrelations()) {
            for (HarPredefinedCorrelation.Replacement replacement : correlation.getReplacements()) {
                sourcesByConsumer
                        .computeIfAbsent(replacement.getTargetEntryIndex(), index -> new HashSet<>())
                        .add(correlation.getSourceEntryIndex());
            }
        }
        return sourcesByConsumer;
    }

    private static boolean consumeRedirectTarget(Map<String, Integer> pendingTargets, String url) {
        Integer count = pendingTargets.get(url);
        if (count == null) {
            return false;
        }
        if (count == 1) {
            pendingTargets.remove(url);
        } else {
            pendingTargets.put(url, count - 1);
        }
        return true;
    }

    private static String redirectTargetOf(HarEntry entry) {
        int status = entry.getResponseStatus();
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
            return "";
        }
        String redirect = entry.getResponseRedirectUrl();
        if (redirect.isEmpty()) {
            for (NameValue header : entry.getResponseHeaders()) {
                if ("location".equalsIgnoreCase(header.getName())) {
                    redirect = header.getValue();
                    break;
                }
            }
        }
        if (redirect.isEmpty()) {
            return "";
        }
        try {
            return URI.create(entry.getUrl()).resolve(redirect).toString();
        } catch (IllegalArgumentException e) {
            return redirect;
        }
    }

    private static boolean allMultiplexedProtocol(List<HarEntry> group) {
        if (group.isEmpty()) {
            return false;
        }
        for (HarEntry entry : group) {
            String protocol = entry.getProtocol().toLowerCase(java.util.Locale.ROOT);
            if (!protocol.contains("h2") && !protocol.contains("http/2")
                    && !protocol.contains("h3") && !protocol.contains("http/3")) {
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
        if (fromCache != null && !fromCache.isBlank()) {
            return true;
        }
        if (entry.getServerIpAddress() != null && !entry.getServerIpAddress().isEmpty()) {
            return false;
        }
        return !entry.isWebSocket() && !entry.isServerSentEvents() && !entry.hasPositiveTiming();
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
            if (isCommonHeader(header)) {
                common.put(header.getName(), header.getValue());
            }
        }
        for (int i = 1; i < nonPreflight.size(); i++) {
            Map<String, String> currentLower = new HashMap<>();
            for (NameValue header : nonPreflight.get(i).getRequestHeaders()) {
                if (isCommonHeader(header)) {
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

    private void addWebSocketSend(HashTree parent, HarEntry entry) {
        var message = entry.getOutgoingMessage();
        WebSocketSendWaitSampler sampler = new WebSocketSendWaitSampler();
        sampler.setProperty(TestElement.GUI_CLASS, TestBeanGUI.class.getName());
        sampler.setProperty(TestElement.TEST_CLASS, WebSocketSendWaitSampler.class.getName());
        sampler.setName("WebSocket Send");
        sampler.setSessionName(webSocketSessionNames.get(entry.getWebSocketConnection().getOriginalIndex()));
        sampler.setAction(WebSocketSendWaitSampler.SEND_ONLY);
        sampler.setSendOffset(message.relativeTimeMs().max(java.math.BigDecimal.ZERO).toPlainString());
        sampler.setBinary(message.opcode() == 2);
        sampler.setPayload(message.opcode() == 2 ? java.util.HexFormat.of().formatHex(
                java.util.Base64.getDecoder().decode(message.data())) : message.text());
        parent.add(sampler);
    }

    private void addWebSocketSampler(HashTree parent, HarEntry entry, String name) {
        WebSocketConnectSampler sampler = new WebSocketConnectSampler();
        sampler.setProperty(TestElement.GUI_CLASS, TestBeanGUI.class.getName());
        sampler.setProperty(TestElement.TEST_CLASS, WebSocketConnectSampler.class.getName());
        sampler.setName(name);
        sampler.setSessionName(webSocketSessionNames.get(entry.getOriginalIndex()));
        String url = entry.getUrl().replaceFirst("(?i)^https:", "wss:").replaceFirst("(?i)^http:", "ws:");
        sampler.setUrl(replaceCorrelations(entry, url,
                HarPredefinedCorrelation.RequestLocation.URL_PATH,
                HarPredefinedCorrelation.RequestLocation.QUERY_PARAMETER));
        List<Header> headers = new ArrayList<>();
        for (NameValue header : entry.getRequestHeaders()) {
            String lower = header.getName().toLowerCase(Locale.ROOT);
            if (isExportableHeader(lower) && !Set.of("connection", "upgrade", "expect").contains(lower)
                    && (!lower.startsWith("sec-websocket-") || "sec-websocket-protocol".equals(lower))) {
                headers.add(new Header(header.getName(), replaceCorrelations(entry, header.getValue(),
                        HarPredefinedCorrelation.RequestLocation.REQUEST_HEADER)));
            }
        }
        sampler.setHeaders(headers);
        sampler.setProperty(RecordedHarExchangeResolver.HAR_ENTRY_INDEX, String.valueOf(entry.getOriginalIndex()));
        sampler.setProperty(RecordedHarExchangeResolver.HAR_STARTED_DATE_TIME, entry.getStartedDateTime());
        sampler.setProperty(RecordedHarExchangeResolver.HAR_REQUEST_METHOD, entry.getMethod());
        sampler.setProperty(RecordedHarExchangeResolver.HAR_REQUEST_URL, entry.getUrl());
        parent.add(sampler);
    }

    static boolean isExportableHeader(String name) {
        return !IGNORED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT)) && !name.startsWith(":");
    }

    private static boolean isCommonHeader(NameValue header) {
        return isExportableHeader(header.getName())
                && !("content-type".equalsIgnoreCase(header.getName())
                        && HarParser.isMultipart(header.getValue()));
    }

    // ---------------------------------------------------------------------
    // Sampler and child elements
    // ---------------------------------------------------------------------

    private void addSampler(HashTree parent, HarEntry entry, Set<String> commonHeadersLower) {
        if (entry.isWebSocketClose()) {
            WebSocketCloseSampler sampler = new WebSocketCloseSampler();
            sampler.setProperty(TestElement.GUI_CLASS, TestBeanGUI.class.getName());
            sampler.setProperty(TestElement.TEST_CLASS, WebSocketCloseSampler.class.getName());
            sampler.setName("WebSocket Close " + entry.getClientCloseOffset().toPlainString() + " ms");
            sampler.setSessionName(webSocketSessionNames.get(entry.getWebSocketConnection().getOriginalIndex()));
            sampler.setCloseOffset(entry.getClientCloseOffset().toPlainString());
            parent.add(sampler);
            return;
        }
        if (entry.getOutgoingMessage() != null) {
            addWebSocketSend(parent, entry);
            return;
        }
        String method = entry.getMethod().toUpperCase(Locale.ROOT);
        ParsedUrl url = parseUrl(entry.getUrl());
        String path = url.path;
        String fullPath = path;
        boolean bodyMethod = isBodyMethod(method);
        if (url.query != null && !url.query.isEmpty() && bodyMethod) {
            fullPath = path + "?" + url.query;
        }
        fullPath = replaceCorrelations(entry, fullPath,
                HarPredefinedCorrelation.RequestLocation.URL_PATH,
                HarPredefinedCorrelation.RequestLocation.QUERY_PARAMETER);

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

        if (entry.isWebSocket()) {
            addWebSocketSampler(parent, entry, name);
            return;
        }

        HTTPSamplerProxy sampler = entry.isServerSentEvents()
                ? new org.apache.jmeter.protocol.sse.SseSampler() : new HTTPSamplerProxy();
        if (entry.isServerSentEvents()) {
            sseSessionCounter++;
            sampler.setSseSessionName("sse-" + sseSessionCounter);
            sampler.setResponseTimeout(org.apache.jmeter.protocol.sse.SseSampler.DEFAULT_RESPONSE_TIMEOUT);
        }
        sampler.setProperty(TestElement.GUI_CLASS, entry.isServerSentEvents()
                ? org.apache.jmeter.protocol.sse.SseSamplerGui.class.getName() : HttpTestSampleGui.class.getName());
        sampler.setProperty(TestElement.TEST_CLASS, sampler.getClass().getName());
        sampler.setName(name);
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
        boolean generatedMultipart = false;
        boolean keptUploadBody = false;
        if (!bodyMethod) {
            for (NameValue param : entry.getQueryString()) {
                String decodedName = percentDecode(param.getName());
                String decodedValue = percentDecode(param.getValue());
                decodedValue = replaceCorrelations(entry, decodedValue,
                        HarPredefinedCorrelation.RequestLocation.QUERY_PARAMETER);
                boolean alwaysEncode = needsUrlEncoding(decodedName) || !param.getName().equals(decodedName)
                        || needsUrlEncoding(decodedValue) || !param.getValue().equals(decodedValue);
                addHttpArgument(arguments, decodedName, decodedValue, alwaysEncode, true);
            }
        } else if (entry.getPostData() != null) {
            PostData postData = entry.getPostData();
            boolean hasUploads = postData.getParams().stream().anyMatch(NameValue::isFileUpload);
            generatedMultipart = HarParser.isMultipart(postData.getMimeType()) && hasUploads;
            if (hasUploads && options.getFileUploadMode() == HarImportOptions.FileUploadMode.RECORDED_BODY) {
                if (!hasRecordedUploadBody(entry)) {
                    throw new IllegalArgumentException(
                            "Recorded request body is unavailable or encoded for " + entry.getUrl());
                }
                generatedMultipart = false;
                keptUploadBody = true;
                sampler.setPostBodyRaw(true);
                sampler.setContentEncoding(StandardCharsets.UTF_8.name());
                addHttpArgument(arguments, "", postData.getText(), false, false);
            } else if (hasUploads) {
                List<HTTPFileArg> files = new ArrayList<>();
                for (NameValue param : postData.getParams()) {
                    if (param.isFileUpload()) {
                        String filename = param.getResourceName();
                        if (options.getFileUploadMode() == HarImportOptions.FileUploadMode.ARCHIVE
                                && param.hasFileContent()) {
                            filename = "${__archiveFile(" + filename.replace(",", "\\,") + ")}";
                        } else {
                            filename = HarEntry.localFileName(filename);
                        }
                        files.add(new HTTPFileArg(
                                filename,
                                param.getName(),
                                param.getContentType()));
                        continue;
                    }
                    String value = replaceCorrelations(entry, param.getValue(),
                            HarPredefinedCorrelation.RequestLocation.POST_PARAMETER);
                    addHttpArgument(arguments, param.getName(), value, false, true);
                }
                sampler.setHTTPFiles(files.toArray(HTTPFileArg[]::new));
                sampler.setDoMultipart(generatedMultipart);
                sampler.setDoBrowserCompatibleMultipart(generatedMultipart);
            } else if (!postData.getParams().isEmpty()) {
                for (NameValue param : postData.getParams()) {
                    String decodedValue = percentDecode(param.getValue());
                    decodedValue = replaceCorrelations(entry, decodedValue,
                            HarPredefinedCorrelation.RequestLocation.POST_PARAMETER);
                    boolean alwaysEncode = needsUrlEncoding(param.getName())
                            || needsUrlEncoding(decodedValue) || !param.getValue().equals(decodedValue);
                    addHttpArgument(arguments, param.getName(), decodedValue, alwaysEncode, true);
                }
            } else if (postData.getText() != null) {
                sampler.setPostBodyRaw(true);
                String cleaned = removeInvalidXmlChars(postData.getText());
                cleaned = replaceCorrelations(entry, cleaned,
                        HarPredefinedCorrelation.RequestLocation.REQUEST_BODY);
                addHttpArgument(arguments, "", cleaned, false, false);
            }
        }

        // Per-request headers that aren't in the shared Common Headers manager are
        // stored natively on the HTTP Request (BreakTest feature), not as a child manager.
        List<Header> uniqueHeaders = new ArrayList<>();
        for (NameValue header : entry.getRequestHeaders()) {
            String lower = header.getName().toLowerCase(Locale.ROOT);
            boolean generatedBoundaryHeader = generatedMultipart
                    && "content-type".equalsIgnoreCase(header.getName())
                    && HarParser.isMultipart(header.getValue());
            if (!commonHeadersLower.contains(lower)
                    && isExportableHeader(header.getName())
                    && !generatedBoundaryHeader) {
                String value = replaceCorrelations(entry, header.getValue(),
                        HarPredefinedCorrelation.RequestLocation.REQUEST_HEADER);
                uniqueHeaders.add(new Header(header.getName(), value));
            }
        }
        if (keptUploadBody && !entry.getPostData().getMimeType().isBlank()
                && entry.getRequestHeaders().stream()
                        .noneMatch(header -> "content-type".equalsIgnoreCase(header.getName()))) {
            uniqueHeaders.add(new Header("Content-Type", entry.getPostData().getMimeType()));
        }
        if (entry.isServerSentEvents() && entry.getRequestHeaders().stream()
                .noneMatch(header -> "Accept".equalsIgnoreCase(header.getName()))) {
            uniqueHeaders.add(new Header("Accept", "text/event-stream"));
        }
        if (!uniqueHeaders.isEmpty()) {
            sampler.setNativeHeaders(uniqueHeaders);
        }

        HashTree samplerHt = parent.add(sampler);
        addPredefinedExtractors(samplerHt, entry);

        int status = entry.getResponseStatus();
        if (options.isIgnoreErrors() && status >= 400 && status <= 599) {
            samplerHt.add(buildIgnoreErrorAssertion(status));
        }
    }

    /**
     * Whether the recorded request text is the exact body that was sent, so it can be kept in Body Data.
     * A raw upload matched through base64 content has only an encoded copy of the file in the HAR, and
     * Body Data cannot hold characters that XML serialization would drop.
     */
    static boolean hasRecordedUploadBody(HarEntry entry) {
        PostData postData = entry.getPostData();
        if (postData == null || !postData.isComplete() || postData.getText() == null || postData.getText().isEmpty()
                || postData.getText().contains("${")
                || "base64".equalsIgnoreCase(postData.getEncoding())
                || !removeInvalidXmlChars(postData.getText()).equals(postData.getText())) {
            return false;
        }
        byte[] body = postData.getText().getBytes(StandardCharsets.UTF_8);
        if (postData.getBodySize() >= 0 ? body.length != postData.getBodySize()
                : !postData.hasCapturedUploadContent()) {
            return false;
        }
        boolean multipart = HarParser.isMultipart(postData.getMimeType());
        return postData.getParams().stream()
                .filter(NameValue::isFileUpload)
                .allMatch(param -> param.hasFileContent()
                        // Multipart params carry the recorded part payload as their value.
                        && Arrays.equals(multipart ? param.getValue().getBytes(StandardCharsets.UTF_8) : body,
                                param.getFileContent()));
    }

    private String replaceCorrelations(HarEntry entry, String text,
            HarPredefinedCorrelation.RequestLocation... locations) {
        String replaced = text;
        Set<HarPredefinedCorrelation.RequestLocation> acceptedLocations = Set.of(locations);
        for (HarPredefinedCorrelation correlation : options.getPredefinedCorrelations()) {
            for (HarPredefinedCorrelation.Replacement replacement : correlation.getReplacements()) {
                if (replacement.getTargetEntryIndex() == entry.getOriginalIndex()
                        && acceptedLocations.contains(replacement.getLocation())) {
                    for (String variant : HarPredefinedCorrelation.replacementVariants(correlation, replacement)) {
                        replaced = replaced.replace(variant,
                                HarPredefinedCorrelation.variableReference(correlation, replacement));
                    }
                }
            }
        }
        return replaced;
    }

    private void addPredefinedExtractors(HashTree samplerHt, HarEntry entry) {
        Set<String> addedVariables = new HashSet<>();
        for (HarPredefinedCorrelation correlation : options.getPredefinedCorrelations()) {
            if (correlation.getSourceEntryIndex() != entry.getOriginalIndex()
                    || !addedVariables.add(correlation.getVariableName())) {
                continue;
            }
            samplerHt.add(HarPredefinedCorrelation.buildExtractor(correlation));
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
        threadGroup.setValidationStopOnError(true);
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

    static boolean isBodyMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }

    private static boolean needsUrlEncoding(String value) {
        // Use the same form encoder as HTTPArgument rather than a partial list of unsafe characters.
        return !URLEncoder.encode(value, StandardCharsets.UTF_8).equals(value);
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
