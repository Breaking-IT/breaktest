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

import java.awt.Component;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.swing.JOptionPane;

import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stores replay results selected by the View Results Tree in recording bundles. */
final class ReplayRecordingStore {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayRecordingStore.class);

    private ReplayRecordingStore() {
    }

    static void chooseAndStore(Component parent, Map<JMeterTreeNode, SampleResult> replayedSamples) {
        if (replayedSamples.isEmpty()) {
            JMeterUtils.reportErrorToUser(
                    JMeterUtils.getResString("view_results_store_replay_no_results")); // $NON-NLS-1$
            return;
        }
        StorageChoice[] choices = {
                new StorageChoice("har_import_recording_storage_all", RecordingStorageMode.ALL), // $NON-NLS-1$
                new StorageChoice(
                        "har_import_recording_storage_without_static_bodies", // $NON-NLS-1$
                        RecordingStorageMode.OMIT_STATIC_BODIES),
                new StorageChoice("har_import_recording_storage_without_statics", RecordingStorageMode.OMIT_STATICS), // $NON-NLS-1$
                new StorageChoice("har_import_recording_storage_none", RecordingStorageMode.NONE)}; // $NON-NLS-1$
        Object selected = JOptionPane.showInputDialog(
                parent,
                JMeterUtils.getResString("view_results_store_replay_mode_prompt"), // $NON-NLS-1$
                JMeterUtils.getResString("view_results_store_replay_mode_title"), // $NON-NLS-1$
                JOptionPane.QUESTION_MESSAGE,
                null,
                choices,
                choices[0]);
        if (selected instanceof StorageChoice choice) {
            store(replayedSamples, choice.mode());
        }
    }

    static void store(Map<JMeterTreeNode, SampleResult> replayedSamples, RecordingStorageMode storageMode) {
        if (replayedSamples.isEmpty()) {
            JMeterUtils.reportErrorToUser(
                    JMeterUtils.getResString("view_results_store_replay_no_results")); // $NON-NLS-1$
            return;
        }

        Map<JMeterTreeNode, Map<JMeterTreeNode, SampleResult>> samplesByThreadGroup = new LinkedHashMap<>();
        for (Map.Entry<JMeterTreeNode, SampleResult> replayedSample : replayedSamples.entrySet()) {
            JMeterTreeNode threadGroupNode = replayedSample.getKey().getPathToThreadGroup().stream()
                    .filter(node -> node.getTestElement() instanceof AbstractThreadGroup
                            || node.getTestElement() instanceof TestFragmentController)
                    .findFirst()
                    .orElse(null);
            if (threadGroupNode == null) {
                JMeterUtils.reportErrorToUser(
                        JMeterUtils.getResString("view_results_store_replay_no_thread_group")); // $NON-NLS-1$
                return;
            }
            samplesByThreadGroup.computeIfAbsent(threadGroupNode, ignored -> new LinkedHashMap<>())
                    .put(replayedSample.getKey(), replayedSample.getValue());
        }

        try {
            Map<JMeterTreeNode, RecordedExchangeStore.Archive> updatedRecordings = new LinkedHashMap<>();
            Map<JMeterTreeNode, Map<JMeterTreeNode, String>> exchangeIdsByThreadGroup = new LinkedHashMap<>();
            for (Map.Entry<JMeterTreeNode, Map<JMeterTreeNode, SampleResult>> threadGroupSamples
                    : samplesByThreadGroup.entrySet()) {
                TestElement recordingSource = threadGroupSamples.getKey().getTestElement();
                String manifestEntryName = recordingSource.getPropertyAsString(
                        RecordedExchangeStore.MANIFEST_PROPERTY);
                String checksum = recordingSource.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY);
                Map<String, byte[]> entries = manifestEntryName.isEmpty()
                        ? Map.of()
                        : JmxArchiveEntryStore.findBundle(manifestEntryName, checksum)
                                .orElseThrow(() -> new IOException(
                                        JMeterUtils.getResString(
                                                "view_results_store_replay_missing_recording"))); // $NON-NLS-1$
                Map<JMeterTreeNode, String> exchangeIds = new LinkedHashMap<>();
                Map<String, SampleResult> resultsByExchangeId = new LinkedHashMap<>();
                for (Map.Entry<JMeterTreeNode, SampleResult> samplerResult : threadGroupSamples.getValue().entrySet()) {
                    TestElement sampler = samplerResult.getKey().getTestElement();
                    String exchangeId = sampler.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY);
                    boolean storeSample = storageMode != RecordingStorageMode.NONE
                            && (storageMode != RecordingStorageMode.OMIT_STATICS
                                    || !RecordedExchangeStore.isStaticResource(samplerResult.getValue()));
                    if (exchangeId.isEmpty() && storeSample) {
                        exchangeId = UUID.randomUUID().toString();
                    }
                    exchangeIds.put(samplerResult.getKey(), storeSample ? exchangeId : null);
                    if (!exchangeId.isEmpty()) {
                        resultsByExchangeId.put(exchangeId, samplerResult.getValue());
                    }
                }
                if (!resultsByExchangeId.isEmpty()) {
                    RecordedExchangeStore.Archive recording = RecordedExchangeStore.storeReplays(
                            manifestEntryName, entries, resultsByExchangeId, storageMode);
                    updatedRecordings.put(threadGroupSamples.getKey(), Objects.requireNonNull(recording));
                }
                exchangeIdsByThreadGroup.put(threadGroupSamples.getKey(), exchangeIds);
            }

            int storedCount = applyRecordings(samplesByThreadGroup, updatedRecordings, exchangeIdsByThreadGroup);
            LOG.info("Stored {} replayed request/response recording(s)", storedCount); // $NON-NLS-1$
        } catch (IOException | RuntimeException ex) {
            LOG.warn("Unable to store replayed request/response", ex); // $NON-NLS-1$
            JMeterUtils.reportErrorToUser(
                    JMeterUtils.getResString("view_results_store_replay_failed") + "\n\n" + ex.getMessage()); // $NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static int applyRecordings(
            Map<JMeterTreeNode, Map<JMeterTreeNode, SampleResult>> samplesByThreadGroup,
            Map<JMeterTreeNode, RecordedExchangeStore.Archive> updatedRecordings,
            Map<JMeterTreeNode, Map<JMeterTreeNode, String>> exchangeIdsByThreadGroup) {
        int storedCount = 0;
        for (Map.Entry<JMeterTreeNode, Map<JMeterTreeNode, String>> threadGroupUpdates
                : exchangeIdsByThreadGroup.entrySet()) {
            JMeterTreeNode threadGroupNode = threadGroupUpdates.getKey();
            TestElement recordingSource = threadGroupNode.getTestElement();
            RecordedExchangeStore.Archive recording = updatedRecordings.get(threadGroupNode);
            if (recording != null) {
                JmxArchiveEntryStore.registerBundle(
                        recording.manifestEntryName(), recording.checksum(), recording.entries());
                recordingSource.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, recording.manifestEntryName());
                recordingSource.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, recording.checksum());
            }
            for (Map.Entry<JMeterTreeNode, String> samplerExchangeId : threadGroupUpdates.getValue().entrySet()) {
                TestElement sampler = samplerExchangeId.getKey().getTestElement();
                if (samplerExchangeId.getValue() == null) {
                    sampler.removeProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY);
                } else {
                    sampler.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, samplerExchangeId.getValue());
                    storedCount++;
                }
                sampler.removeProperty(RecordedHarExchangeResolver.HAR_ENTRY_INDEX);
                sampler.removeProperty(RecordedHarExchangeResolver.HAR_STARTED_DATE_TIME);
                sampler.removeProperty(RecordedHarExchangeResolver.HAR_REQUEST_METHOD);
                sampler.removeProperty(RecordedHarExchangeResolver.HAR_REQUEST_URL);
            }
            if (!hasRecordingReferences(threadGroupNode)) {
                recordingSource.removeProperty(RecordedExchangeStore.MANIFEST_PROPERTY);
                recordingSource.removeProperty(RecordedExchangeStore.CHECKSUM_PROPERTY);
                recordingSource.removeProperty(RecordedHarExchangeResolver.HAR_FILENAME);
                recordingSource.removeProperty(RecordedHarExchangeResolver.HAR_MD5);
            }
        }

        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage != null) {
            for (Map.Entry<JMeterTreeNode, Map<JMeterTreeNode, SampleResult>> threadGroupSamples
                    : samplesByThreadGroup.entrySet()) {
                threadGroupSamples.getValue().keySet().forEach(guiPackage.getTreeModel()::nodeChanged);
                guiPackage.getTreeModel().nodeChanged(threadGroupSamples.getKey());
            }
            guiPackage.setDirty(true);
            guiPackage.addUndoHistory(
                    JMeterUtils.getResString("view_results_store_replay_recording")); // $NON-NLS-1$
        }
        return storedCount;
    }

    private static boolean hasRecordingReferences(JMeterTreeNode node) {
        TestElement element = node.getTestElement();
        if (!element.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY).isEmpty()
                || !element.getPropertyAsString(RecordedHarExchangeResolver.HAR_ENTRY_INDEX).isEmpty()
                || !element.getPropertyAsString(RecordedHarExchangeResolver.HAR_STARTED_DATE_TIME).isEmpty()
                || !element.getPropertyAsString(RecordedHarExchangeResolver.HAR_REQUEST_METHOD).isEmpty()
                || !element.getPropertyAsString(RecordedHarExchangeResolver.HAR_REQUEST_URL).isEmpty()) {
            return true;
        }
        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            if (hasRecordingReferences((JMeterTreeNode) children.nextElement())) {
                return true;
            }
        }
        return false;
    }

    private record StorageChoice(String resourceKey, RecordingStorageMode mode) {
        @Override
        public String toString() {
            return JMeterUtils.getResString(resourceKey);
        }
    }
}
