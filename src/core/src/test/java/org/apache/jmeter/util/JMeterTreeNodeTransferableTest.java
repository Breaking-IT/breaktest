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

package org.apache.jmeter.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

class JMeterTreeNodeTransferableTest extends JMeterTestCase {
    @Test
    void pasteRestoresRecordingBundleWithoutSourceProcessStore() throws Exception {
        String manifest = "recordings/clipboard-test/manifest.json";
        String request = "recordings/clipboard-test/request.bin";
        String response = "recordings/clipboard-test/response.bin";
        byte[] requestBody = new byte[]{0, 1, -1};
        byte[] responseBody = "recorded response".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> entries = Map.of(
                manifest, "{}".getBytes(StandardCharsets.UTF_8),
                request, requestBody, response, responseBody);
        JmxArchiveEntryStore.registerBundle(manifest, "checksum", entries);
        ThreadGroup group = new ThreadGroup();
        group.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, manifest);
        group.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, "checksum");
        JMeterTreeNode root = new JMeterTreeNode(new ThreadGroup(), null);
        root.add(new JMeterTreeNode(group, null));
        JMeterTreeNodeTransferable source = new JMeterTreeNodeTransferable();
        source.setTransferData(new JMeterTreeNode[]{root});
        byte[] payload = (byte[]) source.getTransferData(JMeterTreeNodeTransferable.TREE_WITH_ATTACHMENTS_FLAVOR);

        // Simulate a different JVM: only the bytes cross the clipboard boundary.
        Field bundlesField = JmxArchiveEntryStore.class.getDeclaredField("BUNDLES");
        bundlesField.setAccessible(true);
        Map<?, ?> bundles = (Map<?, ?>) bundlesField.get(null);
        bundles.values().removeIf(bundle -> ((Map<?, ?>) bundle).containsKey(manifest));
        assertTrue(JmxArchiveEntryStore.findBundle(manifest, "checksum").isEmpty());
        Clipboard clipboard = new Clipboard("receiver");
        clipboard.setContents(transferable(JMeterTreeNodeTransferable.TREE_WITH_ATTACHMENTS_FLAVOR, payload), null);
        JMeterTreeNode[] pasted = JMeterTreeNodeTransferable.readTransferData(clipboard.getContents(null));

        assertEquals(manifest, ((JMeterTreeNode) pasted[0].getChildAt(0)).getTestElement()
                .getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY));
        Map<String, byte[]> restored = JmxArchiveEntryStore.findBundle(manifest, "checksum").orElseThrow();
        assertEquals(entries.keySet(), restored.keySet());
        assertArrayEquals(requestBody, restored.get(request));
        assertArrayEquals(responseBody, restored.get(response));
        assertNotSame(responseBody, restored.get(response));
        ListedHashTree savedTree = new ListedHashTree();
        savedTree.add(((JMeterTreeNode) pasted[0].getChildAt(0)).getTestElement());
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        SaveService.saveTree(savedTree, saved);
        Map<String, byte[]> savedEntries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(saved.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                savedEntries.put(entry.getName(), zip.readAllBytes());
            }
        }
        entries.forEach((name, bytes) -> assertArrayEquals(bytes, savedEntries.get(name), name));
        JMeterTreeNode[] secondPaste = JMeterTreeNodeTransferable.readTransferData(clipboard.getContents(null));
        assertNotSame(pasted[0], secondPaste[0]);
    }

    @Test
    void supportsLegacyClipboardAndTreesWithoutAttachments() throws Exception {
        JMeterTreeNode[] nodes = {new JMeterTreeNode(new ThreadGroup(), null)};
        JMeterTreeNodeTransferable source = new JMeterTreeNodeTransferable();
        source.setTransferData(nodes);
        assertEquals(1, JMeterTreeNodeTransferable.readTransferData(source).length);
        JMeterTreeNode[] legacy = (JMeterTreeNode[]) source.getTransferData(
                JMeterTreeNodeTransferable.JMETER_TREE_NODE_ARRAY_DATA_FLAVOR);
        assertNotSame(nodes[0], legacy[0]);
        assertEquals(1, JMeterTreeNodeTransferable.readTransferData(transferable(
                JMeterTreeNodeTransferable.JMETER_TREE_NODE_ARRAY_DATA_FLAVOR, legacy)).length);
    }

    private static Transferable transferable(DataFlavor flavor, Object value) {
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{flavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor requested) {
                return flavor.equals(requested);
            }

            @Override
            public Object getTransferData(DataFlavor requested) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(requested)) {
                    throw new UnsupportedFlavorException(requested);
                }
                return value;
            }
        };
    }
}
