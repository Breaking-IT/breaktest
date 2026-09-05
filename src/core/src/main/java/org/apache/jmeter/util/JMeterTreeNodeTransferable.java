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

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.testelement.TestElement;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Simple implementation of a transferable for {@link JMeterTreeNode} arrays based on serialization.
 * @since 2.9
 */
public class JMeterTreeNodeTransferable implements Transferable {

    public final static DataFlavor JMETER_TREE_NODE_ARRAY_DATA_FLAVOR = new DataFlavor(JMeterTreeNode[].class, JMeterTreeNode[].class.getName());

    public static final DataFlavor TREE_WITH_ATTACHMENTS_FLAVOR = new DataFlavor(
            "application/x-breaktest-tree-with-attachments;class=\"[B\"", "BreakTest tree with attachments");

    private final static DataFlavor[] DATA_FLAVORS = new DataFlavor[]{
        TREE_WITH_ATTACHMENTS_FLAVOR, JMETER_TREE_NODE_ARRAY_DATA_FLAVOR
    };

    private byte[] data = null;

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return DATA_FLAVORS;
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return flavor.match(TREE_WITH_ATTACHMENTS_FLAVOR) || flavor.match(JMETER_TREE_NODE_ARRAY_DATA_FLAVOR);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
        if(!isDataFlavorSupported(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        if (TREE_WITH_ATTACHMENTS_FLAVOR.equals(flavor)) {
            return data == null ? null : data.clone();
        }
        if(data != null) {
            ObjectInput ois = null;
            try {
                ois = new ObjectInputStream(new ByteArrayInputStream(data));
                JMeterTreeNode[] nodes = (JMeterTreeNode[]) ois.readObject();
                return nodes;
            } catch (ClassNotFoundException cnfe) {
                throw new IOException("Failed to read object stream.", cnfe);
            } finally {
                if(ois != null) {
                    try {
                        ois.close();
                    } catch (Exception e) {
                        // NOOP
                    }
                }
            }
        }
        return null;
    }

    /** Reads and registers attachments in the receiving JVM, including for an OS clipboard transfer. */
    @SuppressWarnings("unchecked")
    public static JMeterTreeNode[] readTransferData(Transferable transferable)
            throws IOException, UnsupportedFlavorException {
        if (!transferable.isDataFlavorSupported(TREE_WITH_ATTACHMENTS_FLAVOR)) {
            return (JMeterTreeNode[]) transferable.getTransferData(JMETER_TREE_NODE_ARRAY_DATA_FLAVOR);
        }
        byte[] payload = (byte[]) transferable.getTransferData(TREE_WITH_ATTACHMENTS_FLAVOR);
        if (payload == null) {
            return null;
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            JMeterTreeNode[] nodes = (JMeterTreeNode[]) input.readObject();
            Map<AttachmentKey, Map<String, byte[]>> attachments =
                    (Map<AttachmentKey, Map<String, byte[]>>) input.readObject();
            attachments.forEach((key, entries) ->
                    JmxArchiveEntryStore.registerBundle(key.entryName(), key.checksum(), entries));
            return nodes;
        } catch (ClassNotFoundException | IllegalArgumentException e) {
            throw new IOException("Failed to read clipboard tree attachments.", e);
        }
    }

    private static void collectAttachments(JMeterTreeNode node,
            Map<AttachmentKey, Map<String, byte[]>> attachments) {
        if (node == null) {
            return;
        }
        TestElement element = node.getTestElement();
        collectAttachment(element, RecordedExchangeStore.MANIFEST_PROPERTY,
                RecordedExchangeStore.CHECKSUM_PROPERTY, attachments);
        collectAttachment(element, JmxArchiveEntryStore.HAR_FILENAME_PROPERTY,
                JmxArchiveEntryStore.HAR_MD5_PROPERTY, attachments);
        collectAttachment(element, JmxArchiveEntryStore.CORRELATION_RULES_FILENAME_PROPERTY,
                JmxArchiveEntryStore.CORRELATION_RULES_CHECKSUM_PROPERTY, attachments);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectAttachments((JMeterTreeNode) node.getChildAt(i), attachments);
        }
    }

    private static void collectAttachment(TestElement element, String filenameProperty,
            String checksumProperty, Map<AttachmentKey, Map<String, byte[]>> attachments) {
        String entryName = element.getPropertyAsString(filenameProperty);
        String checksum = element.getPropertyAsString(checksumProperty);
        JmxArchiveEntryStore.findBundle(entryName, checksum).ifPresent(entries ->
                attachments.putIfAbsent(new AttachmentKey(entryName, checksum), entries));
    }

    private record AttachmentKey(String entryName, String checksum) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public void setTransferData(JMeterTreeNode[] nodes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = null;
        try {
            oos = new ObjectOutputStream(bos);
            oos.writeObject(nodes);
            Map<AttachmentKey, Map<String, byte[]>> attachments = new LinkedHashMap<>();
            for (JMeterTreeNode node : nodes) {
                collectAttachments(node, attachments);
            }
            oos.writeObject(attachments);
            data = bos.toByteArray();
        } finally {
            if(oos != null) {
                try {
                    oos.close();
                } catch (Exception e) {
                    // NOOP
                }
            }
        }
    }
}
