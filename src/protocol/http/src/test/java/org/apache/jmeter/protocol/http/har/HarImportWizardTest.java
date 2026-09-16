/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class HarImportWizardTest {

    @Test
    void skipsUploadReviewForEmptyRecorderInventory() throws Exception {
        HarParser.Recording recording = HarParser.parseRecording("""
                {"log":{"entries":[{
                  "request":{"method":"POST","url":"https://example.test/messages",
                    "postData":{"mimeType":"application/json","text":"{}"}}
                }],"_breaktest":{"uploadCapture":{"version":1,"files":[]}}}}
                """.getBytes(StandardCharsets.UTF_8));
        List<HarEntry.NameValue> selectedUploads = recording.entries().stream()
                .filter(entry -> entry.getPostData() != null)
                .flatMap(entry -> entry.getPostData().getParams().stream())
                .filter(HarEntry.NameValue::isFileUpload).toList();

        assertTrue(recording.uploads().present());
        assertTrue(selectedUploads.isEmpty());
        assertFalse(HarImportWizard.shouldReviewFileUploads(recording.uploads(), selectedUploads));
    }

    @Test
    void reviewsRequestUploadsEvenWithoutCapturedContent() {
        HarEntry.NameValue file = new HarEntry.NameValue("file", "", "example.txt", "text/plain", null);

        assertTrue(HarImportWizard.shouldReviewFileUploads(HarUploadCapture.Result.empty(), List.of(file)));
    }

    @Test
    void reviewsUnassignedCapturedFilesAndCaptureWarnings() {
        HarEntry.NameValue file = new HarEntry.NameValue("file", "", "example.txt", "text/plain", new byte[0]);

        assertTrue(HarImportWizard.shouldReviewFileUploads(
                new HarUploadCapture.Result(true, List.of(file), List.of()), List.of()));
        assertTrue(HarImportWizard.shouldReviewFileUploads(
                new HarUploadCapture.Result(true, List.of(), List.of("Content unavailable")), List.of()));
        assertFalse(HarImportWizard.shouldReviewFileUploads(HarUploadCapture.Result.empty(), List.of()));
    }

    @Test
    void recognizesBreakTestTransactionMetadata() {
        HarEntry plain = new HarEntry();
        HarEntry recordedTransaction = new HarEntry();
        recordedTransaction.setTransactionId("checkout");

        assertFalse(HarConverter.hasExplicitTransactions(List.of(plain)));
        assertTrue(HarConverter.hasExplicitTransactions(List.of(plain, recordedTransaction)));
    }

    @Test
    void formatsRecordingSizeForTheStorageChoices() {
        assertEquals("0 B", HarImportWizard.formatStoredSize(0));
        assertEquals("1.5 KB", HarImportWizard.formatStoredSize(1536));
        assertEquals("2.0 MB", HarImportWizard.formatStoredSize(2L * 1024 * 1024));
    }

    @Test
    void usesLoadTestFriendlyRandomDelayDefaults() {
        HarImportOptions options = new HarImportOptions();

        assertEquals("5000", options.getDelayMin());
        assertEquals("25000", options.getDelayMax());
    }

    @Test
    void enablesPredefinedCorrelationScanByDefault() {
        assertTrue(HarImportWizard.DEFAULT_FIND_PREDEFINED_CORRELATIONS);
    }
}
