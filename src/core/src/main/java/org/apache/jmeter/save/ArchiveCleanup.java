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

package org.apache.jmeter.save;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.recording.RecordingStorageMode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

/** Prepares recording cleanup without changing the plan until the user applies it. */
public final class ArchiveCleanup {
    private ArchiveCleanup() {
    }

    public static Prepared prepare(HashTree tree,
            RecordingStorageMode mode, boolean removeOrphans) throws IOException {
        Map<String, Group> owners = new LinkedHashMap<>();
        collectOwners(tree, "", "", owners);
        Map<String, byte[]> before = new LinkedHashMap<>();
        Map<String, byte[]> after = new LinkedHashMap<>();
        List<Update> updates = new ArrayList<>();
        int unlinkedRecordings = 0;
        int retainedExchanges = 0;
        for (Map.Entry<String, Group> group : owners.entrySet()) {
            String entry = group.getKey();
            String checksum = group.getValue().checksum();
            Set<String> liveIds = group.getValue().liveIds();
            Map<String, byte[]> bundle = JmxArchiveEntryStore.findBundle(entry, checksum)
                    .orElseThrow(() -> new IOException("Recording is unavailable: " + entry));
            before.putAll(bundle);
            boolean canRemoveOrphans = removeOrphans && !liveIds.isEmpty();
            if (removeOrphans && liveIds.isEmpty() && mode != RecordingStorageMode.NONE) {
                unlinkedRecordings++;
            }
            RecordedExchangeStore.Archive cleaned = RecordedExchangeStore.cleanArchive(
                    entry, bundle, mode, canRemoveOrphans ? liveIds : null);
            retainedExchanges += cleaned.exchangeCount();
            if (!cleaned.exchangeIds().isEmpty()) {
                after.putAll(cleaned.entries());
            }
            updates.add(new Update(List.copyOf(group.getValue().owners()), cleaned));
        }
        long beforeBytes = before.values().stream().mapToLong(bytes -> bytes.length).sum();
        long afterBytes = after.values().stream().mapToLong(bytes -> bytes.length).sum();
        Set<String> removed = new LinkedHashSet<>(before.keySet());
        removed.removeAll(after.keySet());
        return new Prepared(List.copyOf(updates), beforeBytes, afterBytes, removed.size(), retainedExchanges, unlinkedRecordings);
    }

    private static void collectOwners(HashTree tree, String inheritedEntry, String inheritedChecksum,
            Map<String, Group> groups) throws IOException {
        for (Object node : tree.list()) {
            String entry = inheritedEntry;
            String checksum = inheritedChecksum;
            if (node instanceof TestElement element) {
                String ownEntry = element.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY);
                if (!ownEntry.isEmpty()) {
                    entry = ownEntry;
                    checksum = element.getPropertyAsString(RecordedExchangeStore.CHECKSUM_PROPERTY);
                }
                String id = element.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY);
                if (!entry.isEmpty() && (!ownEntry.isEmpty() || !id.isEmpty())) {
                    Group group = groups.get(entry);
                    if (group == null) {
                        group = new Group(checksum, new ArrayList<>(), new LinkedHashSet<>());
                        groups.put(entry, group);
                    }
                    if (!checksum.equals(group.checksum())) {
                        throw new IOException("Conflicting recording references: " + entry);
                    }
                    group.owners().add(element);
                    if (!id.isEmpty()) {
                        group.liveIds().add(id);
                    }
                }
            }
            collectOwners(tree.getTree(node), entry, checksum, groups);
        }
    }

    private record Group(String checksum, List<TestElement> owners, Set<String> liveIds) {
    }

    private record Update(List<TestElement> owners, RecordedExchangeStore.Archive archive) {
    }

    public static final class Prepared {
        private final List<Update> updates;
        private final long beforeBytes;
        private final long afterBytes;
        private final int removedEntries;
        private final int retainedExchanges;
        private final int unlinkedRecordings;

        private Prepared(List<Update> updates, long beforeBytes, long afterBytes, int removedEntries, int retainedExchanges, int unlinkedRecordings) {
            this.updates = updates;
            this.beforeBytes = beforeBytes;
            this.afterBytes = afterBytes;
            this.removedEntries = removedEntries;
            this.retainedExchanges = retainedExchanges;
            this.unlinkedRecordings = unlinkedRecordings;
        }

        public long bytesRemoved() {
            return Math.max(0, beforeBytes - afterBytes);
        }

        public int removedEntries() {
            return removedEntries;
        }

        public int retainedExchanges() {
            return retainedExchanges;
        }

        public int unlinkedRecordings() {
            return unlinkedRecordings;
        }

        public void apply() {
            for (Update update : updates) {
                RecordedExchangeStore.Archive archive = update.archive();
                if (!archive.exchangeIds().isEmpty()) {
                    JmxArchiveEntryStore.registerBundle(archive.manifestEntryName(), archive.checksum(), archive.entries());
                }
                for (TestElement owner : update.owners()) {
                    String id = owner.getPropertyAsString(RecordedExchangeStore.EXCHANGE_ID_PROPERTY);
                    if (archive.exchangeIds().isEmpty() || (!id.isEmpty() && !archive.exchangeIds().contains(id))) {
                        owner.removeProperty(RecordedExchangeStore.MANIFEST_PROPERTY);
                        owner.removeProperty(RecordedExchangeStore.CHECKSUM_PROPERTY);
                        owner.removeProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY);
                    } else if (!owner.getPropertyAsString(RecordedExchangeStore.MANIFEST_PROPERTY).isEmpty()) {
                        owner.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, archive.checksum());
                    }
                }
            }
        }
    }
}
