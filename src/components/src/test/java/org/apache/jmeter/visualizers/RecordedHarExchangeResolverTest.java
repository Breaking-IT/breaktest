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

package org.apache.jmeter.visualizers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RecordedHarExchangeResolverTest extends JMeterTestCase {

    @TempDir
    private Path tempDir;

    @Test
    public void usesEntryIndexWhenHarHashMatches() throws Exception {
        String har = harWithTwoEntries();
        Files.writeString(tempDir.resolve("recording.har"), har, StandardCharsets.UTF_8);

        JMeterTreeNode samplerNode = samplerNode("1", "POST", "https://example.invalid/wrong",
                "2026-06-24T10:00:00.000Z", md5(har));

        Optional<RecordedHarExchangeResolver.RecordedExchange> exchange =
                RecordedHarExchangeResolver.findFor(samplerNode, tempDir.resolve("plan.jmx"));

        assertTrue(exchange.isPresent());
        assertEquals("""
                GET https://example.invalid/api/items HTTP/1.1
                Accept: application/json
                """, exchange.orElseThrow().request());
        assertEquals("HTTP/1.1 200 OK\n"
                + "Content-Type: application/json\n"
                + "\n"
                + "{\"source\":\"entry-index\"}", exchange.orElseThrow().response());
        assertEquals("{\"source\":\"entry-index\"}", exchange.orElseThrow().responseBody());
    }

    @Test
    public void usesEntryIndexWhenSamplerOnlyStoresEntryIndex() throws Exception {
        String har = harWithTwoEntries();
        Files.writeString(tempDir.resolve("recording.har"), har, StandardCharsets.UTF_8);

        JMeterTreeNode samplerNode = samplerNodeWithOnlyEntryIndex("1", md5(har));

        Optional<RecordedHarExchangeResolver.RecordedExchange> exchange =
                RecordedHarExchangeResolver.findFor(samplerNode, tempDir.resolve("plan.jmx"));

        assertTrue(exchange.isPresent());
        assertTrue(exchange.orElseThrow().request().contains("https://example.invalid/api/items"));
        assertTrue(exchange.orElseThrow().response().contains("{\"source\":\"entry-index\"}"));
    }

    @Test
    public void findsExchangeForValidationSamplePath() throws Exception {
        String har = harWithTwoEntries();
        Files.writeString(tempDir.resolve("recording.har"), har, StandardCharsets.UTF_8);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_FILENAME, "recording.har");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_MD5, md5(har));

        DebugSampler sampler = new DebugSampler();
        sampler.setName("sampler");
        sampler.setProperty(RecordedHarExchangeResolver.HAR_ENTRY_INDEX, "1");

        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        JMeterTreeListener treeListener = new JMeterTreeListener(treeModel);
        GuiPackage.initInstance(treeListener, treeModel);
        setTestPlanFile(tempDir.resolve("plan.jmx"));
        JMeterTreeNode threadGroupNode = new JMeterTreeNode(threadGroup, treeModel);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        ((JMeterTreeNode) treeModel.getRoot()).add(threadGroupNode);
        threadGroupNode.add(samplerNode);

        SampleResult sampleResult = new SampleResult();
        sampleResult.setSourceTestElementPath(List.of(
                new SampleResult.TestElementPathEntry(ThreadGroup.class.getName(), "Thread Group", 0),
                new SampleResult.TestElementPathEntry(DebugSampler.class.getName(), "sampler", 0)));

        Optional<RecordedHarExchangeResolver.RecordedExchange> exchange =
                RecordedHarExchangeResolver.findFor(sampleResult);

        assertTrue(exchange.isPresent());
        assertTrue(exchange.orElseThrow().response().contains("{\"source\":\"entry-index\"}"));
    }

    @Test
    public void reportsDiagnosticWhenHarHashDoesNotMatch() throws Exception {
        String har = harWithTwoEntries();
        Files.writeString(tempDir.resolve("recording.har"), har, StandardCharsets.UTF_8);

        JMeterTreeNode samplerNode = samplerNode("0", "GET", "https://example.invalid/api/items",
                "2026-06-24T10:00:01.000Z", "00000000000000000000000000000000");

        RecordedHarExchangeResolver.Resolution resolution =
                RecordedHarExchangeResolver.resolveFor(samplerNode, tempDir.resolve("plan.jmx"));

        assertEquals(RecordedHarExchangeResolver.Status.MD5_MISMATCH, resolution.status());
        assertTrue(resolution.shouldShowTabs());
        assertTrue(resolution.requestText().contains("Expected MD5"));
        assertTrue(resolution.requestText().contains("Actual MD5"));
    }

    @Test
    public void fallsBackToRequestMetadataWhenEntryIndexIsMissing() throws Exception {
        String har = harWithTwoEntries();
        Files.writeString(tempDir.resolve("recording.har"), har, StandardCharsets.UTF_8);

        JMeterTreeNode samplerNode = samplerNode("", "GET", "https://example.invalid/api/items",
                "2026-06-24T10:00:01.000Z", md5(har));

        Optional<RecordedHarExchangeResolver.RecordedExchange> exchange =
                RecordedHarExchangeResolver.findFor(samplerNode, tempDir.resolve("plan.jmx"));

        assertTrue(exchange.isPresent());
        assertTrue(exchange.orElseThrow().response().contains("{\"source\":\"entry-index\"}"));
    }

    @Test
    public void omitsBinaryResponseBodies() throws Exception {
        String har = harWithBinaryEntry();
        Files.writeString(tempDir.resolve("recording.har"), har, StandardCharsets.UTF_8);

        JMeterTreeNode samplerNode = samplerNode("0", "GET", "https://example.invalid/image.png",
                "2026-06-24T10:00:00.000Z", md5(har));

        RecordedHarExchangeResolver.RecordedExchange exchange =
                RecordedHarExchangeResolver.findFor(samplerNode, tempDir.resolve("plan.jmx")).orElseThrow();

        assertTrue(exchange.response().contains("Content-Type: image/png"));
        assertFalse(exchange.response().contains("PNG"));
        assertFalse(exchange.response().contains("IHDR"));
        assertEquals("", exchange.responseBody());
    }

    @Test
    public void formatsHttp2ResponseStatusLineWithoutMinorVersionOrReasonPhrase() throws Exception {
        String har = harWithHttp2StatusText();
        Files.writeString(tempDir.resolve("recording.har"), har, StandardCharsets.UTF_8);

        JMeterTreeNode samplerNode = samplerNode("0", "GET", "https://example.invalid/http2",
                "2026-06-24T10:00:00.000Z", md5(har));

        RecordedHarExchangeResolver.RecordedExchange exchange =
                RecordedHarExchangeResolver.findFor(samplerNode, tempDir.resolve("plan.jmx")).orElseThrow();

        assertEquals("""
                GET https://example.invalid/http2 HTTP/2
                Accept: text/plain
                User-Agent: Test
                """, exchange.request());
        assertEquals("HTTP/2 200\nContent-Type: text/plain\n\nok", exchange.response());
    }

    @Test
    public void exposesRecordedRequestAndResponseAsSearchableTokens() throws Exception {
        String har = harWithTwoEntries();
        Files.writeString(tempDir.resolve("recording.har"), har, StandardCharsets.UTF_8);

        JMeterTreeNode samplerNode = samplerNodeWithOnlyEntryIndex("1", md5(har));

        List<String> tokens = RecordedHarExchangeResolver.searchableTokensFor(samplerNode, tempDir.resolve("plan.jmx"));

        assertEquals(2, tokens.size());
        assertTrue(tokens.get(0).contains("https://example.invalid/api/items"));
        assertTrue(tokens.get(1).contains("{\"source\":\"entry-index\"}"));
    }

    @Test
    public void returnsEmptyWhenLinkedHarFileIsMissing() {
        JMeterTreeNode samplerNode = samplerNode("0", "GET", "https://example.invalid/api/items",
                "2026-06-24T10:00:01.000Z", "00000000000000000000000000000000");

        Optional<RecordedHarExchangeResolver.RecordedExchange> exchange =
                RecordedHarExchangeResolver.findFor(samplerNode, tempDir.resolve("plan.jmx"));

        assertTrue(exchange.isEmpty());
    }

    @Test
    public void resolvesRecordedExchangeFromEmbeddedHar() throws Exception {
        String har = harWithTwoEntries();
        String entryName = "har/embedded/recording.har";
        Path testPlan = tempDir.resolve("embedded.jmx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(testPlan))) {
            zip.putNextEntry(new ZipEntry(SaveService.TEST_PLAN_ZIP_ENTRY));
            zip.write("unused".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(har.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        JMeterTreeNode samplerNode = samplerNode("1", "POST", "https://example.invalid/wrong",
                "2026-06-24T10:00:00.000Z", md5(har), entryName);

        RecordedHarExchangeResolver.Resolution resolution =
                RecordedHarExchangeResolver.resolveFor(samplerNode, testPlan);
        RecordedHarExchangeResolver.HarFileCheck check =
                RecordedHarExchangeResolver.checkHarFile(entryName, md5(har), testPlan);

        assertEquals(RecordedHarExchangeResolver.Status.FOUND, resolution.status());
        assertTrue(resolution.responseText().contains("{\"source\":\"entry-index\"}"));
        assertEquals(RecordedHarExchangeResolver.Status.FOUND, check.status());
        assertTrue(check.harPath().toString().contains("!/" + entryName));
    }

    @Test
    public void resolvesPendingEmbeddedHarBeforeTheTestPlanIsFirstSaved() throws Exception {
        String har = harWithTwoEntries();
        String entryName = "har/pending/recording.har";
        JmxArchiveEntryStore.register(entryName, md5(har), har.getBytes(StandardCharsets.UTF_8));
        JMeterTreeNode samplerNode = samplerNode("1", "POST", "https://example.invalid/wrong",
                "2026-06-24T10:00:00.000Z", md5(har), entryName);

        RecordedHarExchangeResolver.Resolution resolution =
                RecordedHarExchangeResolver.resolveFor(samplerNode, null);

        assertEquals(RecordedHarExchangeResolver.Status.FOUND, resolution.status());
        assertTrue(resolution.responseText().contains("{\"source\":\"entry-index\"}"));
    }

    @Test
    public void resolvesNativeRecordingWithExternalizedBodies() throws Exception {
        RecordedExchangeStore.Archive archive = RecordedExchangeStore.fromHar(
                harWithTwoEntries().getBytes(StandardCharsets.UTF_8), "recording.har");
        JmxArchiveEntryStore.registerBundle(
                archive.manifestEntryName(), archive.checksum(), archive.entries());
        JMeterTreeNode samplerNode = nativeSamplerNode(archive, 1);

        RecordedHarExchangeResolver.Resolution resolution =
                RecordedHarExchangeResolver.resolveFor(samplerNode, null);
        RecordedHarExchangeResolver.HarFileCheck check =
                RecordedHarExchangeResolver.checkRecordingStore(
                        ((JMeterTreeNode) samplerNode.getParent()).getTestElement(), null);

        assertEquals(RecordedHarExchangeResolver.Status.FOUND, resolution.status());
        assertTrue(resolution.requestText().contains("https://example.invalid/api/items"));
        assertTrue(resolution.responseText().contains("{\"source\":\"entry-index\"}"));
        assertEquals(RecordedHarExchangeResolver.Status.FOUND, check.status());
    }

    private JMeterTreeNode samplerNode(String entryIndex, String method, String url, String startedDateTime,
            String harMd5) {
        return samplerNode(entryIndex, method, url, startedDateTime, harMd5, "recording.har");
    }

    private JMeterTreeNode samplerNode(String entryIndex, String method, String url, String startedDateTime,
            String harMd5, String harFilename) {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_FILENAME, harFilename);
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_MD5, harMd5);

        DebugSampler sampler = new DebugSampler();
        sampler.setName("sampler");
        sampler.setProperty(RecordedHarExchangeResolver.HAR_ENTRY_INDEX, entryIndex);
        sampler.setProperty(RecordedHarExchangeResolver.HAR_REQUEST_METHOD, method);
        sampler.setProperty(RecordedHarExchangeResolver.HAR_REQUEST_URL, url);
        sampler.setProperty(RecordedHarExchangeResolver.HAR_STARTED_DATE_TIME, startedDateTime);

        JMeterTreeNode threadGroupNode = new JMeterTreeNode(threadGroup, null);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, null);
        threadGroupNode.add(samplerNode);
        return samplerNode;
    }

    private JMeterTreeNode samplerNodeWithOnlyEntryIndex(String entryIndex, String harMd5) {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_FILENAME, "recording.har");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_MD5, harMd5);

        DebugSampler sampler = new DebugSampler();
        sampler.setName("sampler");
        sampler.setProperty(RecordedHarExchangeResolver.HAR_ENTRY_INDEX, entryIndex);

        JMeterTreeNode threadGroupNode = new JMeterTreeNode(threadGroup, null);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, null);
        threadGroupNode.add(samplerNode);
        return samplerNode;
    }

    private static JMeterTreeNode nativeSamplerNode(RecordedExchangeStore.Archive archive, int exchangeIndex) {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, archive.manifestEntryName());
        threadGroup.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, archive.checksum());

        DebugSampler sampler = new DebugSampler();
        sampler.setName("sampler");
        sampler.setProperty(
                RecordedExchangeStore.EXCHANGE_ID_PROPERTY, archive.exchangeIds().get(exchangeIndex));

        JMeterTreeNode threadGroupNode = new JMeterTreeNode(threadGroup, null);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, null);
        threadGroupNode.add(samplerNode);
        return samplerNode;
    }

    private static String harWithTwoEntries() {
        return """
                {
                  "log": {
                    "entries": [
                      {
                        "startedDateTime": "2026-06-24T10:00:00.000Z",
                        "request": {
                          "method": "POST",
                          "url": "https://example.invalid/wrong",
                          "httpVersion": "HTTP/1.1",
                          "headers": [],
                          "postData": {"text": "wrong=true"}
                        },
                        "response": {
                          "status": 201,
                          "statusText": "Created",
                          "httpVersion": "HTTP/1.1",
                          "headers": [],
                          "content": {"text": "wrong"}
                        }
                      },
                      {
                        "startedDateTime": "2026-06-24T10:00:01.000Z",
                        "request": {
                          "method": "GET",
                          "url": "https://example.invalid/api/items",
                          "httpVersion": "HTTP/1.1",
                          "headers": [{"name": "Accept", "value": "application/json"}]
                        },
                        "response": {
                          "status": 200,
                          "statusText": "OK",
                          "httpVersion": "HTTP/1.1",
                          "headers": [{"name": "Content-Type", "value": "application/json"}],
                          "content": {"mimeType": "application/json", "text": "{\\"source\\":\\"entry-index\\"}"}
                        }
                      }
                    ]
                  }
                }
                """;
    }

    private static String harWithHttp2StatusText() {
        return """
                {
                  "log": {
                    "entries": [
                      {
                        "startedDateTime": "2026-06-24T10:00:00.000Z",
                        "request": {
                          "method": "GET",
                          "url": "https://example.invalid/http2",
                          "httpVersion": "HTTP/2.0",
                          "headers": [
                            {"name": "User-Agent", "value": "Test"},
                            {"name": "Accept", "value": "text/plain"}
                          ]
                        },
                        "response": {
                          "status": 200,
                          "statusText": "OK",
                          "httpVersion": "HTTP/2.0",
                          "headers": [{"name": "Content-Type", "value": "text/plain"}],
                          "content": {"mimeType": "text/plain", "text": "ok"}
                        }
                      }
                    ]
                  }
                }
                """;
    }

    private static String harWithBinaryEntry() {
        return """
                {
                  "log": {
                    "entries": [
                      {
                        "startedDateTime": "2026-06-24T10:00:00.000Z",
                        "request": {
                          "method": "GET",
                          "url": "https://example.invalid/image.png",
                          "httpVersion": "HTTP/1.1",
                          "headers": []
                        },
                        "response": {
                          "status": 200,
                          "statusText": "OK",
                          "httpVersion": "HTTP/1.1",
                          "headers": [{"name": "Content-Type", "value": "image/png"}],
                          "content": {
                            "mimeType": "image/png",
                            "encoding": "base64",
                            "text": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
                          }
                        }
                      }
                    ]
                  }
                }
                """;
    }

    private static String md5(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void setTestPlanFile(Path testPlanFile) throws Exception {
        Field testPlanFileField = GuiPackage.class.getDeclaredField("testPlanFile");
        testPlanFileField.setAccessible(true);
        testPlanFileField.set(GuiPackage.getInstance(), testPlanFile.toString());
    }
}
