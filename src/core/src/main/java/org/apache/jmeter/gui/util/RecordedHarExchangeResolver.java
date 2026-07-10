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

package org.apache.jmeter.gui.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.tree.TreeNode;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class RecordedHarExchangeResolver {

    public static final String HAR_FILENAME = JmxArchiveEntryStore.HAR_FILENAME_PROPERTY;
    public static final String HAR_MD5 = JmxArchiveEntryStore.HAR_MD5_PROPERTY;
    public static final String HAR_ENTRY_INDEX = "BreakTest.har.entryIndex"; // $NON-NLS-1$
    public static final String HAR_STARTED_DATE_TIME = "BreakTest.har.startedDateTime"; // $NON-NLS-1$
    public static final String HAR_REQUEST_METHOD = "BreakTest.har.requestMethod"; // $NON-NLS-1$
    public static final String HAR_REQUEST_URL = "BreakTest.har.requestUrl"; // $NON-NLS-1$
    public static final String RECORDING_MANIFEST = RecordedExchangeStore.MANIFEST_PROPERTY;
    public static final String RECORDING_CHECKSUM = RecordedExchangeStore.CHECKSUM_PROPERTY;
    public static final String RECORDING_EXCHANGE_ID = RecordedExchangeStore.EXCHANGE_ID_PROPERTY;

    private static final Logger LOG = LoggerFactory.getLogger(RecordedHarExchangeResolver.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Map<String, CachedHar> HAR_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> TEXTUAL_MIME_TYPES = Set.of(
            "application/ecmascript", // $NON-NLS-1$
            "application/graphql", // $NON-NLS-1$
            "application/javascript", // $NON-NLS-1$
            "application/json", // $NON-NLS-1$
            "application/problem+json", // $NON-NLS-1$
            "application/x-www-form-urlencoded", // $NON-NLS-1$
            "application/xhtml+xml", // $NON-NLS-1$
            "application/xml", // $NON-NLS-1$
            "multipart/form-data"); // $NON-NLS-1$

    private RecordedHarExchangeResolver() {
    }

    public static Resolution resolveFor(SampleResult sampleResult) {
        JMeterTreeNode samplerNode = findTestPlanNode(sampleResult);
        if (samplerNode == null) {
            return Resolution.notLinked();
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        Path testPlanFile = guiPackage == null || StringUtilities.isEmpty(guiPackage.getTestPlanFile())
                ? null
                : Path.of(guiPackage.getTestPlanFile());
        return resolveFor(samplerNode, testPlanFile);
    }

    public static Resolution resolveFor(TestElement sampler) {
        if (sampler == null) {
            return Resolution.notLinked();
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return Resolution.notLinked();
        }
        JMeterTreeNode samplerNode = guiPackage.getNodeOf(sampler);
        if (samplerNode == null
                && guiPackage.getCurrentNode() != null
                && guiPackage.getCurrentNode().getTestElement() == sampler) {
            samplerNode = guiPackage.getCurrentNode();
        }
        if (samplerNode == null) {
            if (!hasRecordingMetadata(sampler)) {
                return Resolution.notLinked();
            }
            return Resolution.diagnostic(Status.HAR_SOURCE_NOT_FOUND,
                    "The sampler has BreakTest recording metadata, but its parent recording source metadata "
                            + "could not be found."); // $NON-NLS-1$
        }
        Path testPlanFile = StringUtilities.isEmpty(guiPackage.getTestPlanFile())
                ? null
                : Path.of(guiPackage.getTestPlanFile());
        return resolveFor(samplerNode, testPlanFile);
    }

    public static Resolution checkLinkFor(TestElement sampler) {
        if (sampler == null) {
            return Resolution.notLinked();
        }
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return Resolution.notLinked();
        }
        JMeterTreeNode samplerNode = guiPackage.getNodeOf(sampler);
        if (samplerNode == null
                && guiPackage.getCurrentNode() != null
                && guiPackage.getCurrentNode().getTestElement() == sampler) {
            samplerNode = guiPackage.getCurrentNode();
        }
        if (samplerNode == null) {
            if (!hasRecordingMetadata(sampler)) {
                return Resolution.notLinked();
            }
            return Resolution.diagnostic(Status.HAR_SOURCE_NOT_FOUND,
                    "The sampler has BreakTest recording metadata, but its parent recording source metadata "
                            + "could not be found."); // $NON-NLS-1$
        }
        TestElement recordingSource = findRecordingSource(samplerNode);
        if (recordingSource == null) {
            return Resolution.diagnostic(Status.HAR_SOURCE_NOT_FOUND,
                    "The sampler has BreakTest recording metadata, but no parent Thread Group or Test Fragment contains "
                            + "a recording source."); // $NON-NLS-1$
        }
        Path testPlanFile = StringUtilities.isEmpty(guiPackage.getTestPlanFile())
                ? null
                : Path.of(guiPackage.getTestPlanFile());
        HarFileCheck check = StringUtilities.isNotEmpty(
                recordingSource.getPropertyAsString(RECORDING_MANIFEST))
                        ? checkRecordingStore(recordingSource, testPlanFile)
                        : checkHarFile(recordingSource, testPlanFile);
        if (check.status() == Status.FOUND) {
            return Resolution.diagnostic(Status.FOUND,
                    "Recorded data is available. Select this tab to load it from the recording store."); // $NON-NLS-1$
        }
        return Resolution.diagnostic(check.status(), check.diagnosticText());
    }

    public static Resolution resolveFor(JMeterTreeNode samplerNode, Path testPlanFile) {
        if (samplerNode == null) {
            return Resolution.notLinked();
        }
        TestElement sampler = samplerNode.getTestElement();
        String exchangeId = sampler.getPropertyAsString(RECORDING_EXCHANGE_ID);
        if (StringUtilities.isNotEmpty(exchangeId)) {
            return resolveNativeRecording(samplerNode, testPlanFile, exchangeId);
        }
        return resolveLegacyHar(samplerNode, testPlanFile, sampler);
    }

    private static Resolution resolveLegacyHar(
            JMeterTreeNode samplerNode, Path testPlanFile, TestElement sampler) {
        SamplerHarMetadata samplerMetadata = SamplerHarMetadata.from(sampler);
        if (samplerMetadata.isEmpty()) {
            return Resolution.notLinked();
        }
        TestElement harSource = findRecordingSource(samplerNode);
        if (harSource == null) {
            return Resolution.diagnostic(Status.HAR_SOURCE_NOT_FOUND,
                    "The sampler has BreakTest HAR metadata, but no parent Thread Group or Test Fragment contains "
                            + HAR_FILENAME + "."); // $NON-NLS-1$ //$NON-NLS-2$
        }
        String harFilename = harSource.getPropertyAsString(HAR_FILENAME);
        if (StringUtilities.isEmpty(harFilename)) {
            return Resolution.diagnostic(Status.HAR_SOURCE_NOT_FOUND,
                    "The parent recording source does not contain " + HAR_FILENAME + "."); // $NON-NLS-1$ //$NON-NLS-2$
        }
        String expectedMd5 = harSource.getPropertyAsString(HAR_MD5);
        Path expectedLocation = archiveEntryLocation(testPlanFile, harFilename);
        try {
            Optional<HarSource> source = findHarSource(testPlanFile, harFilename, expectedMd5);
            if (source.isEmpty()) {
                return Resolution.diagnostic(Status.HAR_FILE_NOT_FOUND,
                        "Linked HAR file was not found.\n\nTried archive entry:\n" + expectedLocation); // $NON-NLS-1$
            }
            HarSource harSourceContent = source.orElseThrow();
            CachedHar cachedHar = harSourceContent.cachedHar();
            if (StringUtilities.isNotEmpty(expectedMd5) && !expectedMd5.equalsIgnoreCase(cachedHar.md5())) {
                return Resolution.diagnostic(Status.MD5_MISMATCH,
                        "Linked HAR file MD5 does not match the JMX metadata.\n\n"
                                + "HAR location:\n" + harSourceContent.location() + "\n\n"
                                + "Expected MD5:\n" + expectedMd5 + "\n\n"
                                + "Actual MD5:\n" + cachedHar.md5()); // $NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            JsonNode entries = cachedHar.entries();
            if (entries == null) {
                entries = parseHarEntries(harSourceContent.cacheKey(), cachedHar.bytes());
            }
            JsonNode finalEntries = entries;
            Optional<JsonNode> entry = samplerMetadata.entryIndex()
                    .flatMap(entryIndex -> entryAt(finalEntries, entryIndex));
            if (entry.isEmpty()) {
                entry = findByRequestMetadata(entries, samplerMetadata);
            }
            if (entry.isEmpty()) {
                return Resolution.diagnostic(Status.ENTRY_NOT_FOUND,
                        "The linked HAR was found and matched, but no HAR entry matched this sampler."); // $NON-NLS-1$
            }
            return Resolution.found(toRecordedExchange(entry.orElseThrow()));
        } catch (IOException | RuntimeException ex) {
            LOG.debug("Unable to load linked HAR {}", expectedLocation, ex);
            return Resolution.diagnostic(Status.IO_ERROR,
                    "Unable to read the linked HAR file.\n\nLocation:\n" + expectedLocation + "\n\nError:\n"
                            + ex.getMessage()); // $NON-NLS-1$ //$NON-NLS-2$
        }
    }

    public static Optional<RecordedExchange> findFor(SampleResult sampleResult) {
        return resolveFor(sampleResult).exchange();
    }

    public static Optional<RecordedExchange> findFor(JMeterTreeNode samplerNode, Path testPlanFile) {
        return resolveFor(samplerNode, testPlanFile).exchange();
    }

    public static List<String> searchableTokensFor(JMeterTreeNode samplerNode, Path testPlanFile) {
        return resolveFor(samplerNode, testPlanFile)
                .exchange()
                .map(exchange -> List.of(exchange.request(), exchange.response()))
                .orElseGet(List::of);
    }

    private static Resolution resolveNativeRecording(
            JMeterTreeNode samplerNode, Path testPlanFile, String exchangeId) {
        TestElement recordingSource = findRecordingSource(samplerNode);
        if (recordingSource == null) {
            return Resolution.diagnostic(Status.HAR_SOURCE_NOT_FOUND,
                    "The sampler has recording metadata, but no parent Thread Group or Test Fragment contains a manifest."); // $NON-NLS-1$
        }
        String manifestEntryName = recordingSource.getPropertyAsString(RECORDING_MANIFEST);
        String expectedChecksum = recordingSource.getPropertyAsString(RECORDING_CHECKSUM);
        Path expectedLocation = archiveEntryLocation(testPlanFile, manifestEntryName);
        try {
            Optional<byte[]> manifest = findRecordingEntry(
                    testPlanFile, manifestEntryName, expectedChecksum, manifestEntryName);
            if (manifest.isEmpty()) {
                return Resolution.diagnostic(Status.HAR_FILE_NOT_FOUND,
                        "Linked recording manifest was not found.\n\nTried archive entry:\n" + expectedLocation); // $NON-NLS-1$
            }
            String actualChecksum = RecordedExchangeStore.sha256Hex(manifest.orElseThrow());
            if (StringUtilities.isNotEmpty(expectedChecksum)
                    && !expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                return Resolution.diagnostic(Status.MD5_MISMATCH,
                        "Linked recording manifest checksum does not match the JMX metadata.\n\n"
                                + "Recording location:\n" + expectedLocation + "\n\n"
                                + "Expected checksum:\n" + expectedChecksum + "\n\n"
                                + "Actual checksum:\n" + actualChecksum); // $NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            Optional<JsonNode> entry = RecordedExchangeStore.resolveExchange(
                    manifest.orElseThrow(), exchangeId,
                    entryName -> findRecordingEntry(
                            testPlanFile, manifestEntryName, expectedChecksum, entryName));
            if (entry.isEmpty()) {
                return Resolution.diagnostic(Status.ENTRY_NOT_FOUND,
                        "The recording store was found, but no exchange matched this sampler."); // $NON-NLS-1$
            }
            return Resolution.found(toRecordedExchange(entry.orElseThrow()));
        } catch (IOException | RuntimeException ex) {
            LOG.debug("Unable to load recording store {}", expectedLocation, ex);
            return Resolution.diagnostic(Status.IO_ERROR,
                    "Unable to read the recording store.\n\nLocation:\n" + expectedLocation + "\n\nError:\n"
                            + ex.getMessage()); // $NON-NLS-1$ //$NON-NLS-2$
        }
    }

    public static HarFileCheck checkRecordingStore(TestElement recordingSource, Path testPlanFile) {
        if (recordingSource == null) {
            return HarFileCheck.diagnostic(Status.HAR_SOURCE_NOT_FOUND, null, "", "", // $NON-NLS-1$ //$NON-NLS-2$
                    "Unable to check recording metadata because the source is missing."); // $NON-NLS-1$
        }
        String manifestEntryName = recordingSource.getPropertyAsString(RECORDING_MANIFEST);
        String expectedChecksum = recordingSource.getPropertyAsString(RECORDING_CHECKSUM);
        Path expectedLocation = archiveEntryLocation(testPlanFile, manifestEntryName);
        if (StringUtilities.isEmpty(manifestEntryName)) {
            return HarFileCheck.diagnostic(Status.HAR_SOURCE_NOT_FOUND, expectedLocation,
                    expectedChecksum, "", "The recording source does not contain a manifest."); // $NON-NLS-1$ //$NON-NLS-2$
        }
        try {
            Optional<byte[]> manifest = findRecordingEntry(
                    testPlanFile, manifestEntryName, expectedChecksum, manifestEntryName);
            if (manifest.isEmpty()) {
                Status status = testPlanFile == null ? Status.TEST_PLAN_FILE_UNKNOWN : Status.HAR_FILE_NOT_FOUND;
                return HarFileCheck.diagnostic(status, expectedLocation, expectedChecksum, "", // $NON-NLS-1$
                        "Linked recording manifest was not found."); // $NON-NLS-1$
            }
            String actualChecksum = RecordedExchangeStore.sha256Hex(manifest.orElseThrow());
            if (StringUtilities.isNotEmpty(expectedChecksum)
                    && !expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                return HarFileCheck.diagnostic(Status.MD5_MISMATCH, expectedLocation,
                        expectedChecksum, actualChecksum,
                        "Linked recording manifest checksum does not match the JMX metadata."); // $NON-NLS-1$
            }
            RecordedExchangeStore.referencedEntryNames(
                    manifest.orElseThrow(), entryName -> findRecordingEntry(
                            testPlanFile, manifestEntryName, expectedChecksum, entryName));
            return HarFileCheck.found(expectedLocation, expectedChecksum, actualChecksum);
        } catch (IOException | RuntimeException ex) {
            LOG.debug("Unable to preflight recording store {}", expectedLocation, ex);
            return HarFileCheck.diagnostic(Status.IO_ERROR, expectedLocation, expectedChecksum, "", // $NON-NLS-1$
                    "Unable to read the recording store: " + ex.getMessage()); // $NON-NLS-1$
        }
    }

    public static HarFileCheck checkHarFile(TestElement harSource, Path testPlanFile) {
        if (harSource == null) {
            return HarFileCheck.diagnostic(Status.HAR_SOURCE_NOT_FOUND, null, "", "", // $NON-NLS-1$ //$NON-NLS-2$
                    "Unable to check linked HAR metadata because the JMX or HAR source is missing."); // $NON-NLS-1$
        }
        String harFilename = harSource.getPropertyAsString(HAR_FILENAME);
        return checkHarFile(harFilename, harSource.getPropertyAsString(HAR_MD5), testPlanFile);
    }

    public static HarFileCheck checkHarFile(String harFilename, String expectedMd5, Path testPlanFile) {
        if (StringUtilities.isEmpty(harFilename)) {
            return HarFileCheck.diagnostic(Status.HAR_SOURCE_NOT_FOUND, null, "", "", // $NON-NLS-1$ //$NON-NLS-2$
                    "The HAR source does not contain " + HAR_FILENAME + "."); // $NON-NLS-1$ //$NON-NLS-2$
        }
        Path expectedLocation = archiveEntryLocation(testPlanFile, harFilename);
        try {
            Optional<HarSource> source = findHarSource(testPlanFile, harFilename, expectedMd5);
            if (source.isEmpty()) {
                Status status = testPlanFile == null ? Status.TEST_PLAN_FILE_UNKNOWN : Status.HAR_FILE_NOT_FOUND;
                String diagnostic = testPlanFile == null
                        ? "The test plan has not been saved and the pending HAR content is unavailable." // $NON-NLS-1$
                        : "Linked HAR file was not found."; // $NON-NLS-1$
                return HarFileCheck.diagnostic(status, expectedLocation, expectedMd5, "", diagnostic); // $NON-NLS-1$
            }
            HarSource harSource = source.orElseThrow();
            CachedHar cachedHar = harSource.cachedHar();
            if (StringUtilities.isNotEmpty(expectedMd5) && !expectedMd5.equalsIgnoreCase(cachedHar.md5())) {
                return HarFileCheck.diagnostic(Status.MD5_MISMATCH, harSource.location(), expectedMd5, cachedHar.md5(),
                        "Linked HAR file MD5 does not match the JMX metadata."); // $NON-NLS-1$
            }
            return HarFileCheck.found(harSource.location(), expectedMd5, cachedHar.md5());
        } catch (IOException | RuntimeException ex) {
            LOG.debug("Unable to preflight linked HAR {}", expectedLocation, ex);
            return HarFileCheck.diagnostic(Status.IO_ERROR, expectedLocation, expectedMd5, "", // $NON-NLS-1$
                    "Unable to read the linked HAR file: " + ex.getMessage()); // $NON-NLS-1$
        }
    }

    private static JMeterTreeNode findTestPlanNode(SampleResult sampleResult) {
        return SampleResultNodeResolver.find(sampleResult);
    }

    private static boolean hasRecordingMetadata(TestElement sampler) {
        return StringUtilities.isNotEmpty(sampler.getPropertyAsString(RECORDING_EXCHANGE_ID))
                || !SamplerHarMetadata.from(sampler).isEmpty();
    }

    private static TestElement findRecordingSource(JMeterTreeNode samplerNode) {
        for (TreeNode node = samplerNode; node instanceof JMeterTreeNode;) {
            JMeterTreeNode current = (JMeterTreeNode) node;
            TestElement element = current.getTestElement();
            if (StringUtilities.isNotEmpty(element.getPropertyAsString(RECORDING_MANIFEST))
                    || StringUtilities.isNotEmpty(element.getPropertyAsString(HAR_FILENAME))) {
                return element;
            }
            node = current.getParent();
        }
        return null;
    }

    private static Optional<byte[]> findRecordingEntry(Path testPlanFile, String manifestEntryName,
            String expectedChecksum, String requestedEntryName) throws IOException {
        Optional<byte[]> registered = JmxArchiveEntryStore.findBundleEntry(
                manifestEntryName, expectedChecksum, requestedEntryName);
        if (registered.isPresent()) {
            return registered;
        }
        if (testPlanFile == null || !Files.isRegularFile(testPlanFile)) {
            return Optional.empty();
        }
        return SaveService.readArchiveEntry(testPlanFile.toFile(), requestedEntryName);
    }

    private static Path resolveHarPath(Path testPlanFile, String harFilename) {
        Path baseDirectory = testPlanFile.toAbsolutePath().getParent();
        if (baseDirectory == null) {
            baseDirectory = Path.of(".").toAbsolutePath();
        }
        return baseDirectory.resolve(harFilename).normalize();
    }

    private static Path archiveEntryLocation(Path testPlanFile, String harFilename) {
        if (testPlanFile == null) {
            return Path.of("unsaved.jmx!").resolve(harFilename); // $NON-NLS-1$
        }
        Path archivePath = testPlanFile.toAbsolutePath().normalize();
        return Path.of(archivePath + "!/" + harFilename); // $NON-NLS-1$
    }

    private static Optional<HarSource> findHarSource(
            Path testPlanFile, String harFilename, String expectedMd5) throws IOException {
        Optional<byte[]> registered = JmxArchiveEntryStore.find(harFilename, expectedMd5);
        if (testPlanFile != null) {
            Path archivePath = testPlanFile.toAbsolutePath().normalize();
            if (Files.isRegularFile(archivePath)) {
                String cacheKey = "archive:" + archivePath + "!/" + harFilename; // $NON-NLS-1$ //$NON-NLS-2$
                long lastModified = Files.getLastModifiedTime(archivePath).toMillis();
                long size = Files.size(archivePath);
                CachedHar cached = HAR_CACHE.get(cacheKey);
                if (cached != null && cached.lastModified() == lastModified && cached.size() == size) {
                    return Optional.of(new HarSource(cacheKey,
                            archiveEntryLocation(testPlanFile, harFilename), cached));
                }
                if (registered.isPresent()) {
                    CachedHar registeredHar = cacheHar(
                            cacheKey, lastModified, size, registered.orElseThrow());
                    return Optional.of(new HarSource(cacheKey,
                            archiveEntryLocation(testPlanFile, harFilename), registeredHar));
                }
                Optional<byte[]> embedded = SaveService.readArchiveEntry(archivePath.toFile(), harFilename);
                if (embedded.isPresent()) {
                    CachedHar embeddedHar = cacheHar(cacheKey, lastModified, size, embedded.orElseThrow());
                    return Optional.of(new HarSource(cacheKey,
                            archiveEntryLocation(testPlanFile, harFilename), embeddedHar));
                }
            }
        }

        if (registered.isPresent()) {
            String cacheKey = "registered:" + harFilename + ':' + expectedMd5; // $NON-NLS-1$
            byte[] content = registered.orElseThrow();
            CachedHar registeredHar = HAR_CACHE.get(cacheKey);
            if (registeredHar == null || registeredHar.size() != content.length) {
                registeredHar = cacheHar(cacheKey, -1, content.length, content);
            }
            return Optional.of(new HarSource(cacheKey,
                    archiveEntryLocation(testPlanFile, harFilename), registeredHar));
        }

        if (testPlanFile == null) {
            return Optional.empty();
        }
        Path harPath = resolveHarPath(testPlanFile, harFilename).toAbsolutePath().normalize();
        if (!Files.isRegularFile(harPath)) {
            return Optional.empty();
        }
        String cacheKey = "file:" + harPath; // $NON-NLS-1$
        long lastModified = Files.getLastModifiedTime(harPath).toMillis();
        long size = Files.size(harPath);
        CachedHar cached = HAR_CACHE.get(cacheKey);
        if (cached != null && cached.lastModified() == lastModified && cached.size() == size) {
            return Optional.of(new HarSource(cacheKey, harPath, cached));
        }
        CachedHar fileHar = cacheHar(cacheKey, lastModified, size, Files.readAllBytes(harPath));
        return Optional.of(new HarSource(cacheKey, harPath, fileHar));
    }

    private static CachedHar cacheHar(String cacheKey, long lastModified, long size, byte[] harBytes) {
        CachedHar har = new CachedHar(lastModified, size, md5Hex(harBytes), harBytes, null);
        HAR_CACHE.put(cacheKey, har);
        return har;
    }

    private static JsonNode parseHarEntries(String cacheKey, byte[] harBytes) throws IOException {
        JsonNode root = JSON.readTree(harBytes);
        JsonNode entries = root.path("log").path("entries"); // $NON-NLS-1$ //$NON-NLS-2$
        CachedHar cached = HAR_CACHE.get(cacheKey);
        if (cached != null) {
            HAR_CACHE.put(cacheKey,
                    new CachedHar(cached.lastModified(), cached.size(), cached.md5(), cached.bytes(), entries));
        }
        return entries;
    }

    private static String md5Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data)); // $NON-NLS-1$
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 digest is not available", e); // $NON-NLS-1$
        }
    }

    private static Optional<JsonNode> entryAt(JsonNode entries, int entryIndex) {
        if (!entries.isArray() || entryIndex < 0 || entryIndex >= entries.size()) {
            return Optional.empty();
        }
        return Optional.of(entries.get(entryIndex));
    }

    private static Optional<JsonNode> findByRequestMetadata(JsonNode entries, SamplerHarMetadata samplerMetadata) {
        if (!entries.isArray()
                || StringUtilities.isEmpty(samplerMetadata.requestMethod())
                || StringUtilities.isEmpty(samplerMetadata.requestUrl())) {
            return Optional.empty();
        }
        for (JsonNode entry : entries) {
            JsonNode request = entry.path("request"); // $NON-NLS-1$
            if (!samplerMetadata.requestMethod().equals(request.path("method").asText()) // $NON-NLS-1$
                    || !samplerMetadata.requestUrl().equals(request.path("url").asText())) { // $NON-NLS-1$
                continue;
            }
            if (StringUtilities.isNotEmpty(samplerMetadata.startedDateTime())
                    && !samplerMetadata.startedDateTime().equals(entry.path("startedDateTime").asText())) { // $NON-NLS-1$
                continue;
            }
            return Optional.of(entry);
        }
        return Optional.empty();
    }

    private static RecordedExchange toRecordedExchange(JsonNode entry) {
        return new RecordedExchange(formatRequest(entry.path("request")), formatResponse(entry.path("response"))); // $NON-NLS-1$ //$NON-NLS-2$
    }

    private static String formatRequest(JsonNode request) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder,
                request.path("method").asText(), // $NON-NLS-1$
                request.path("url").asText(), // $NON-NLS-1$
                displayHttpVersion(request.path("httpVersion").asText())); // $NON-NLS-1$
        appendHeaders(builder, request.path("headers"), true); // $NON-NLS-1$
        String body = requestBody(request.path("postData")); // $NON-NLS-1$
        appendBody(builder, body);
        return builder.toString();
    }

    private static String formatResponse(JsonNode response) {
        StringBuilder builder = new StringBuilder();
        String httpVersion = response.path("httpVersion").asText(); // $NON-NLS-1$
        appendLine(builder,
                displayHttpVersion(httpVersion),
                response.path("status").isMissingNode() ? "" : response.path("status").asText(), // $NON-NLS-1$ //$NON-NLS-2$
                isHttp2(httpVersion) ? "" : response.path("statusText").asText()); // $NON-NLS-1$ //$NON-NLS-2$
        appendHeaders(builder, response.path("headers"), false); // $NON-NLS-1$
        appendBody(builder, responseBody(response.path("content"))); // $NON-NLS-1$
        return builder.toString();
    }

    private static String displayHttpVersion(String httpVersion) {
        return "HTTP/2.0".equalsIgnoreCase(httpVersion) ? "HTTP/2" : httpVersion; // $NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isHttp2(String httpVersion) {
        return "HTTP/2".equalsIgnoreCase(httpVersion) // $NON-NLS-1$
                || "HTTP/2.0".equalsIgnoreCase(httpVersion); // $NON-NLS-1$
    }

    private static void appendLine(StringBuilder builder, String... parts) {
        boolean needsSpace = false;
        for (String part : parts) {
            if (StringUtilities.isEmpty(part)) {
                continue;
            }
            if (needsSpace) {
                builder.append(' ');
            }
            builder.append(part);
            needsSpace = true;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
    }

    private static void appendHeaders(StringBuilder builder, JsonNode headers, boolean sort) {
        if (!headers.isArray()) {
            return;
        }
        List<String> headerLines = new ArrayList<>();
        for (JsonNode header : headers) {
            String name = header.path("name").asText(); // $NON-NLS-1$
            if (StringUtilities.isEmpty(name)) {
                continue;
            }
            headerLines.add(name + ": " + header.path("value").asText()); // $NON-NLS-1$
        }
        if (sort) {
            headerLines.sort(Comparator
                    .comparing(RecordedHarExchangeResolver::headerName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(String::compareToIgnoreCase));
        }
        for (String headerLine : headerLines) {
            builder.append(headerLine).append('\n');
        }
    }

    private static String headerName(String headerLine) {
        int separator = headerLine.indexOf(':');
        return separator < 0 ? headerLine : headerLine.substring(0, separator);
    }

    private static void appendBody(StringBuilder builder, String body) {
        if (StringUtilities.isEmpty(body)) {
            return;
        }
        if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '\n') {
            builder.append('\n');
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(body);
    }

    private static String requestBody(JsonNode postData) {
        String text = contentText(postData);
        if (text != null) {
            return text;
        }
        JsonNode params = postData.path("params"); // $NON-NLS-1$
        if (!params.isArray()) {
            return ""; // $NON-NLS-1$
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode param : params) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(param.path("name").asText()).append('=').append(param.path("value").asText()); // $NON-NLS-1$ //$NON-NLS-2$
        }
        return builder.toString();
    }

    private static String responseBody(JsonNode content) {
        String text = contentText(content);
        return text == null ? "" : text; // $NON-NLS-1$
    }

    private static String contentText(JsonNode content) {
        String text = content.path("text").asText(null); // $NON-NLS-1$
        if (text == null) {
            return null;
        }
        String mimeType = content.path("mimeType").asText(); // $NON-NLS-1$
        if (isBinaryMimeType(mimeType)) {
            return ""; // $NON-NLS-1$
        }
        if (!"base64".equalsIgnoreCase(content.path("encoding").asText())) { // $NON-NLS-1$ //$NON-NLS-2$
            return text;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(text);
            Charset charset = charsetFromMimeType(mimeType);
            if (!looksLikeText(decoded, charset)) {
                return ""; // $NON-NLS-1$
            }
            return new String(decoded, charset);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    private static boolean isBinaryMimeType(String mimeType) {
        String mediaType = mediaType(mimeType);
        if (StringUtilities.isEmpty(mediaType)) {
            return false;
        }
        return !mediaType.startsWith("text/") // $NON-NLS-1$
                && !TEXTUAL_MIME_TYPES.contains(mediaType)
                && !mediaType.endsWith("+json") // $NON-NLS-1$
                && !mediaType.endsWith("+xml"); // $NON-NLS-1$
    }

    private static String mediaType(String mimeType) {
        if (StringUtilities.isEmpty(mimeType)) {
            return ""; // $NON-NLS-1$
        }
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT); // $NON-NLS-1$
    }

    private static boolean looksLikeText(byte[] bytes, Charset charset) {
        String text = new String(bytes, charset);
        int checked = 0;
        int controls = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '\uFFFD') {
                return false;
            }
            if (Character.isISOControl(current)
                    && current != '\n'
                    && current != '\r'
                    && current != '\t') {
                controls++;
            }
            checked++;
        }
        return checked == 0 || controls * 10 <= checked;
    }

    private static Charset charsetFromMimeType(String mimeType) {
        if (StringUtilities.isEmpty(mimeType)) {
            return StandardCharsets.UTF_8;
        }
        for (String part : mimeType.split(";")) { // $NON-NLS-1$
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) { // $NON-NLS-1$
                try {
                    return Charset.forName(trimmed.substring("charset=".length()).trim()); // $NON-NLS-1$
                } catch (RuntimeException ignored) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    public enum Status {
        NOT_LINKED,
        FOUND,
        TEST_PLAN_FILE_UNKNOWN,
        HAR_SOURCE_NOT_FOUND,
        HAR_FILE_NOT_FOUND,
        MD5_MISMATCH,
        ENTRY_NOT_FOUND,
        IO_ERROR
    }

    public static final class Resolution {
        private final Status status;
        private final RecordedExchange exchange;
        private final String diagnostic;

        private Resolution(Status status, RecordedExchange exchange, String diagnostic) {
            this.status = status;
            this.exchange = exchange;
            this.diagnostic = diagnostic;
        }

        public static Resolution notLinked() {
            return new Resolution(Status.NOT_LINKED, null, ""); // $NON-NLS-1$
        }

        public static Resolution found(RecordedExchange exchange) {
            return new Resolution(Status.FOUND, exchange, ""); // $NON-NLS-1$
        }

        public static Resolution diagnostic(Status status, String diagnostic) {
            return new Resolution(status, null, diagnostic);
        }

        public Status status() {
            return status;
        }

        public Optional<RecordedExchange> exchange() {
            return Optional.ofNullable(exchange);
        }

        public boolean shouldShowTabs() {
            return status != Status.NOT_LINKED;
        }

        public String requestText() {
            return exchange == null ? diagnostic : exchange.request();
        }

        public String responseText() {
            return exchange == null ? diagnostic : exchange.response();
        }
    }

    public static final class RecordedExchange {
        private final String request;
        private final String response;

        RecordedExchange(String request, String response) {
            this.request = request;
            this.response = response;
        }

        public String request() {
            return request;
        }

        public String response() {
            return response;
        }
    }

    public record HarFileCheck(Status status, Path harPath, String expectedMd5, String actualMd5, String diagnostic) {
        public static HarFileCheck found(Path harPath, String expectedMd5, String actualMd5) {
            return new HarFileCheck(Status.FOUND, harPath, expectedMd5, actualMd5, ""); // $NON-NLS-1$
        }

        public static HarFileCheck diagnostic(Status status, Path harPath, String expectedMd5, String actualMd5,
                String diagnostic) {
            return new HarFileCheck(status, harPath, expectedMd5, actualMd5, diagnostic);
        }

        public String diagnosticText() {
            if (status == Status.HAR_FILE_NOT_FOUND) {
                return diagnostic + "\n\nTried location:\n" + harPath; // $NON-NLS-1$
            }
            if (status == Status.MD5_MISMATCH) {
                return diagnostic + "\n\nRecording location:\n" + harPath
                        + "\n\nExpected checksum:\n" + expectedMd5
                        + "\n\nActual checksum:\n" + actualMd5; // $NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            if (status == Status.IO_ERROR && harPath != null) {
                return diagnostic + "\n\nPath:\n" + harPath; // $NON-NLS-1$
            }
            return diagnostic;
        }
    }

    private record HarSource(String cacheKey, Path location, CachedHar cachedHar) {
    }

    private static final class CachedHar {
        private final long lastModified;
        private final long size;
        private final String md5;
        private final byte[] bytes;
        private final JsonNode entries;

        CachedHar(long lastModified, long size, String md5, byte[] bytes, JsonNode entries) {
            this.lastModified = lastModified;
            this.size = size;
            this.md5 = md5;
            this.bytes = bytes;
            this.entries = entries;
        }

        long lastModified() {
            return lastModified;
        }

        long size() {
            return size;
        }

        String md5() {
            return md5;
        }

        byte[] bytes() {
            return bytes;
        }

        JsonNode entries() {
            return entries;
        }
    }

    private static final class SamplerHarMetadata {
        private final Optional<Integer> entryIndex;
        private final String startedDateTime;
        private final String requestMethod;
        private final String requestUrl;

        SamplerHarMetadata(Optional<Integer> entryIndex, String startedDateTime,
                String requestMethod, String requestUrl) {
            this.entryIndex = entryIndex;
            this.startedDateTime = startedDateTime;
            this.requestMethod = requestMethod;
            this.requestUrl = requestUrl;
        }

        static SamplerHarMetadata from(TestElement sampler) {
            return new SamplerHarMetadata(
                    parseInteger(sampler.getPropertyAsString(HAR_ENTRY_INDEX)),
                    sampler.getPropertyAsString(HAR_STARTED_DATE_TIME),
                    sampler.getPropertyAsString(HAR_REQUEST_METHOD),
                    sampler.getPropertyAsString(HAR_REQUEST_URL));
        }

        boolean isEmpty() {
            return entryIndex.isEmpty()
                    && StringUtilities.isEmpty(startedDateTime)
                    && StringUtilities.isEmpty(requestMethod)
                    && StringUtilities.isEmpty(requestUrl);
        }

        Optional<Integer> entryIndex() {
            return entryIndex;
        }

        String startedDateTime() {
            return startedDateTime;
        }

        String requestMethod() {
            return requestMethod;
        }

        String requestUrl() {
            return requestUrl;
        }

        private static Optional<Integer> parseInteger(String value) {
            if (StringUtilities.isEmpty(value)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }
}
