/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipInputStream;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.har.HarEntry.NameValue;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class HarUploadCaptureTest extends JMeterTestCase {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void suppliedRecordingStructureSupportsRepeatedAndMultipleUploads() throws Exception {
        try (var input = getClass().getResourceAsStream("upload-capture.har")) {
            verifyRecording(input.readAllBytes());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BREAKTEST_UPLOAD_HAR", matches = ".+")
    void originalBrowserRecording() throws Exception {
        verifyRecording(Files.readAllBytes(Path.of(System.getenv("BREAKTEST_UPLOAD_HAR"))));
    }

    private static void verifyRecording(byte[] har) throws Exception {
        HarParser.Recording recording = HarParser.parseRecording(har);
        assertEquals(3, recording.uploads().resources().size());
        assertTrue(recording.uploads().warnings().isEmpty(), recording.uploads().warnings().toString());
        List<NameValue> uploads = recording.entries().stream()
                .filter(entry -> entry.getPostData() != null)
                .flatMap(entry -> entry.getPostData().getParams().stream())
                .filter(NameValue::isFileUpload).toList();
        assertEquals(4, uploads.size());
        assertTrue(uploads.stream().allMatch(NameValue::hasFileContent));
        assertTrue(uploads.stream().allMatch(upload -> upload.getName().equals("userfiles[]")));
        assertEquals(uploads.get(0).getResourceName(), uploads.get(1).getResourceName());
        for (var file : JSON.readTree(har).path("log").path("_breaktest").path("uploadCapture").path("files")) {
            NameValue resource = recording.uploads().resources().stream()
                    .filter(value -> value.getFileName().equals(file.path("fileName").asText())).findFirst().orElseThrow();
            assertArrayEquals(Base64.getDecoder().decode(file.path("content").asText()), resource.getFileContent());
        }
        assertSavedResources(recording);
        HashTree tree = new HarConverter(recording.entries(), new HarImportOptions(), "recording.har", "digest")
                .convert(Set.copyOf(HarConverter.sortedHostnames(recording.entries())));
        List<HTTPSamplerProxy> samplers = new ArrayList<>();
        collect(tree, samplers);
        List<HTTPSamplerProxy> requests = samplers.stream().filter(s -> s.getHTTPFiles().length > 0).toList();
        assertEquals(2, requests.size());
        assertEquals(4, requests.stream().mapToInt(s -> s.getHTTPFiles().length).sum());
        for (HTTPSamplerProxy sampler : requests) {
            assertTrue(sampler.getDoMultipart());
            assertFalse(sampler.getPostBodyRaw());
            for (var file : sampler.getHTTPFiles()) {
                assertTrue(file.getPath().startsWith("${__archiveFile("));
                assertFalse(file.getPath().contains("/"));
                assertEquals("userfiles[]", file.getParamName());
                assertFalse(file.getMimeType().isBlank());
            }
            if (sampler.getHeaderManager() != null) {
                for (int i = 0; i < sampler.getHeaderManager().size(); i++) {
                    assertFalse(sampler.getHeaderManager().get(i).getValue().contains("WebKitFormBoundary"));
                }
            }
        }
    }

    private static void collect(HashTree tree, List<HTTPSamplerProxy> samplers) {
        for (Object item : tree.list()) {
            if (item instanceof HTTPSamplerProxy sampler) {
                samplers.add(sampler);
            }
            collect(tree.getTree(item), samplers);
        }
    }

    private static void assertSavedResources(HarParser.Recording recording) throws Exception {
        TestPlan plan = new TestPlan();
        HarImportAction.storeArchiveUploads(List.of(), Set.of(), plan, recording.uploads().resources());
        assertEquals(recording.uploads().resources().size(), ArchiveFiles.references(plan).size());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveTree(new ListedHashTree(plan), output);
        int found = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                for (NameValue resource : recording.uploads().resources()) {
                    if (name.equals("files/" + resource.getResourceName())) {
                        assertArrayEquals(resource.getFileContent(), zip.readAllBytes());
                        found++;
                    }
                }
            }
        }
        assertEquals(recording.uploads().resources().size(), found);
    }

    @Test
    void preservesBinaryEmptyFilesAndMimeTypesWithSafePaths() throws Exception {
        ObjectNode root = recording();
        byte[] binary = {0, -1, -128, 13, 10};
        capture(root, "../../unsafe?.bin", binary);
        capture(root, "empty.txt", new byte[0]);
        request(root, "../../unsafe?.bin", "empty.txt");
        HarParser.Recording parsed = parse(root);
        assertEquals(2, parsed.uploads().resources().size());
        List<NameValue> params = parsed.entries().get(0).getPostData().getParams();
        assertArrayEquals(binary, params.get(0).getFileContent());
        assertArrayEquals(new byte[0], params.get(1).getFileContent());
        assertEquals("application/octet-stream", params.get(0).getContentType());
        assertFalse(params.get(0).getResourceName().contains(".."));
        assertFalse(params.get(0).getResourceName().contains("?"));
        assertSavedResources(parsed);
        HarImportAction.storeUploadFiles(parsed.entries(), Set.of("example.com"), directory);
        assertArrayEquals(binary, Files.readAllBytes(directory.resolve(
                HarEntry.localFileName(params.get(0).getResourceName()))));
    }

    @Test
    void duplicatesDeduplicateButSameNameDifferentBytesRemainAmbiguous() throws Exception {
        ObjectNode root = recording();
        capture(root, "file.bin", new byte[]{1});
        capture(root, "file.bin", new byte[]{1});
        request(root, "file.bin");
        assertEquals(1, parse(root).uploads().resources().size());
        assertTrue(parse(root).entries().get(0).getPostData().getParams().get(0).hasFileContent());
        capture(root, "file.bin", new byte[]{2});
        HarParser.Recording parsed = parse(root);
        assertEquals(2, parsed.uploads().resources().size());
        assertFalse(parsed.entries().get(0).getPostData().getParams().get(0).hasFileContent());
        assertEquals(List.of("file.bin", "file-2.bin"), parsed.uploads().resources().stream()
                .map(NameValue::getResourceName).toList());
        assertTrue(parsed.uploads().warnings().stream().anyMatch(w -> w.contains("ambiguous")));
        assertSavedResources(parsed);
    }

    @Test
    void doesNotAssociateFilesSelectedAfterRequestOrWithDifferentField() throws Exception {
        ObjectNode root = recording();
        capture(root, "file.bin", new byte[]{1}).put("capturedDateTime", "2026-09-11T12:00:01Z");
        capture(root, "file.bin", new byte[]{2}).put("fieldName", "other");
        request(root, "file.bin");
        HarParser.Recording parsed = parse(root);
        assertFalse(parsed.entries().get(0).getPostData().getParams().get(0).hasFileContent());
        assertEquals(2, parsed.uploads().resources().size());
        assertSavedResources(parsed);
    }

    @Test
    void warnsForUnavailableMalformedMissingAndOversizedContent() throws Exception {
        ObjectNode root = recording();
        capture(root, "unavailable", new byte[]{1}).put("status", "unavailable");
        capture(root, "missing", new byte[]{1}).remove("content");
        capture(root, "malformed", new byte[]{1}).put("content", "%%%!");
        capture(root, "wrong-size", new byte[]{1}).put("size", 2);
        capture(root, "too-large", new byte[]{1}).put("size", HarUploadCapture.MAX_FILE_BYTES + 1);
        request(root, "unavailable");
        HarParser.Recording parsed = parse(root);
        assertTrue(parsed.uploads().resources().isEmpty());
        assertEquals(6, parsed.uploads().warnings().size());
        assertFalse(parsed.entries().get(0).getPostData().getParams().get(0).hasFileContent());
    }

    @Test
    void unavailableCandidatePreventsGuessingAmongSameNameFiles() throws Exception {
        ObjectNode root = recording();
        capture(root, "file.bin", new byte[]{1});
        capture(root, "file.bin", new byte[]{2}).put("status", "unavailable");
        request(root, "file.bin");
        assertFalse(parse(root).entries().get(0).getPostData().getParams().get(0).hasFileContent());
    }

    @Test
    void unsupportedVersionWarnsAndOrdinaryHarIsUnchanged() throws Exception {
        ObjectNode root = recording();
        capture(root, "file.bin", new byte[]{1});
        request(root, "file.bin");
        ((ObjectNode) root.path("log").path("_breaktest").path("uploadCapture")).put("version", 2);
        assertTrue(parse(root).uploads().warnings().get(0).contains("Unsupported"));
        ((ObjectNode) root.path("log")).remove("_breaktest");
        HarParser.Recording parsed = parse(root);
        assertFalse(parsed.uploads().present());
        assertTrue(parsed.uploads().warnings().isEmpty());
        assertEquals("file.bin", parsed.entries().get(0).getPostData().getParams().get(0).getResourceName());
    }

    @Test
    void recordLimitPreservesDecodedResourcesWithoutGuessing() throws Exception {
        ObjectNode root = recording();
        for (int i = 0; i <= HarUploadCapture.MAX_RECORDS; i++) {
            capture(root, "file.bin", new byte[]{1});
        }
        request(root, "file.bin");
        HarParser.Recording parsed = parse(root);
        assertEquals(1, parsed.uploads().resources().size());
        assertTrue(parsed.uploads().warnings().get(0).contains("1000-record limit"));
        assertFalse(parsed.entries().get(0).getPostData().getParams().get(0).hasFileContent());
    }

    @Test
    void pageEvidenceDoesNotMapAnUnrelatedCapture() throws Exception {
        ObjectNode root = recording();
        capture(root, "file.bin", new byte[]{1}).put("pageUrl", "https://example.com/other-page");
        request(root, "file.bin");
        ((ObjectNode) root.path("log").path("entries").get(0).path("request"))
                .putArray("headers").addObject().put("name", "Referer").put("value", "https://example.com/upload");
        HarParser.Recording parsed = parse(root);
        assertFalse(parsed.entries().get(0).getPostData().getParams().get(0).hasFileContent());
        assertEquals(1, parsed.uploads().resources().size());
    }

    private static ObjectNode recording() {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode log = root.putObject("log");
        log.putArray("entries");
        log.putObject("_breaktest").putObject("uploadCapture").put("version", 1).putArray("files");
        return root;
    }

    @Test
    void referenceOnlyUsesBasenameForCapturedUpload() throws Exception {
        ObjectNode root = recording();
        capture(root, "invoice.pdf", new byte[]{1, 2});
        request(root, "invoice.pdf");
        HarImportOptions options = new HarImportOptions();
        options.setFileUploadMode(HarImportOptions.FileUploadMode.REFERENCE_ONLY);
        HashTree tree = new HarConverter(parse(root).entries(), options, "recording.har", "digest")
                .convert(Set.of("example.com"));
        List<HTTPSamplerProxy> samplers = new ArrayList<>();
        collect(tree, samplers);
        assertEquals("invoice.pdf", samplers.get(0).getHTTPFiles()[0].getPath());
        assertTrue(samplers.get(0).getDoMultipart());
        assertFalse(samplers.get(0).getPostBodyRaw());
    }

    @Test
    void workingDirectoryChoiceSavesUnassignedCaptureUsingBasename() throws Exception {
        ObjectNode root = recording();
        capture(root, "invoice.pdf", new byte[]{1, 2});
        HarParser.Recording parsed = parse(root);
        HarImportAction.storeUploadFiles(parsed.entries(), Set.of(), directory, parsed.uploads().resources());
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(directory.resolve("invoice.pdf")));
    }

    private static ObjectNode capture(ObjectNode root, String filename, byte[] content) {
        return ((ArrayNode) root.path("log").path("_breaktest").path("uploadCapture").path("files"))
                .addObject().put("fileName", filename).put("fieldName", "userfiles[]")
                .put("mimeType", "application/octet-stream").put("size", content.length)
                .put("status", "complete").put("encoding", "base64")
                .put("capturedDateTime", "2026-09-11T11:59:00Z")
                .put("content", Base64.getEncoder().encodeToString(content));
    }

    private static void request(ObjectNode root, String... filenames) {
        ObjectNode entry = ((ArrayNode) root.path("log").path("entries")).addObject();
        entry.put("startedDateTime", "2026-09-11T12:00:00Z");
        entry.put("time", 10);
        entry.putObject("response").put("status", 200);
        ArrayNode params = entry.putObject("request").put("method", "POST")
                .put("url", "https://example.com/upload").putObject("postData")
                .put("mimeType", "multipart/form-data").putArray("params");
        for (String filename : filenames) {
            params.addObject().put("name", "userfiles[]").put("fileName", filename);
        }
    }

    private static HarParser.Recording parse(ObjectNode root) throws Exception {
        return HarParser.parseRecording(JSON.writeValueAsBytes(root));
    }
}
