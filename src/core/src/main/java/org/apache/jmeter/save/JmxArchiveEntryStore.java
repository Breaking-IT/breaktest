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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jorphan.util.StringUtilities;

/**
 * Keeps archive attachments available between importing or loading a test plan
 * and its next save.
 */
public final class JmxArchiveEntryStore {

    public static final String HAR_FILENAME_PROPERTY = "BreakTest.har.filename"; // $NON-NLS-1$
    public static final String HAR_MD5_PROPERTY = "BreakTest.har.md5"; // $NON-NLS-1$

    private static final Map<EntryKey, Map<String, byte[]>> BUNDLES = new ConcurrentHashMap<>();

    private JmxArchiveEntryStore() {
    }

    /**
     * Builds a stable, collision-resistant entry name for an imported HAR.
     *
     * @param originalName original HAR file name
     * @param md5 MD5 of the HAR content
     * @return safe relative ZIP entry name
     */
    public static String createHarEntryName(String originalName, String md5) {
        String normalizedName = StringUtilities.isEmpty(originalName) ? "recording.har" : originalName; // $NON-NLS-1$
        int separator = Math.max(normalizedName.lastIndexOf('/'), normalizedName.lastIndexOf('\\'));
        if (separator >= 0) {
            normalizedName = normalizedName.substring(separator + 1);
        }
        normalizedName = normalizedName.replaceAll("[^A-Za-z0-9._-]", "_"); // $NON-NLS-1$ //$NON-NLS-2$
        if (normalizedName.isEmpty() || ".".equals(normalizedName) || "..".equals(normalizedName)) { // $NON-NLS-1$ //$NON-NLS-2$
            normalizedName = "recording.har"; // $NON-NLS-1$
        }
        String checksum = normalizeChecksum(md5).replaceAll("[^a-z0-9_-]", "_"); // $NON-NLS-1$ //$NON-NLS-2$
        if (checksum.isEmpty()) {
            checksum = Integer.toUnsignedString(normalizedName.hashCode(), 16);
        }
        return "har/" + checksum + '/' + normalizedName; // $NON-NLS-1$
    }

    public static void register(String entryName, String checksum, byte[] content) {
        if (!isSafeEntryName(entryName)) {
            throw new IllegalArgumentException("Invalid JMX archive entry name: " + entryName);
        }
        if (content == null) {
            throw new IllegalArgumentException("JMX archive entry content must not be null");
        }
        registerBundle(entryName, checksum, Map.of(entryName, content));
    }

    /** Registers all ZIP entries belonging to one referenced archive attachment. */
    public static void registerBundle(String entryName, String checksum, Map<String, byte[]> entries) {
        if (!isSafeEntryName(entryName)) {
            throw new IllegalArgumentException("Invalid JMX archive entry name: " + entryName);
        }
        if (entries == null || !entries.containsKey(entryName)) {
            throw new IllegalArgumentException("JMX archive bundle must contain its referenced entry");
        }
        Map<String, byte[]> safeEntries = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!isSafeEntryName(entry.getKey())) {
                throw new IllegalArgumentException("Invalid JMX archive bundle entry name: " + entry.getKey());
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("JMX archive bundle entry content must not be null");
            }
            safeEntries.put(entry.getKey(), entry.getValue());
        }
        BUNDLES.put(new EntryKey(entryName, normalizeChecksum(checksum)),
                Collections.unmodifiableMap(safeEntries));
    }

    public static Optional<byte[]> find(String entryName, String checksum) {
        if (!isSafeEntryName(entryName)) {
            return Optional.empty();
        }
        return findBundle(entryName, checksum).map(entries -> entries.get(entryName));
    }

    public static Optional<Map<String, byte[]>> findBundle(String entryName, String checksum) {
        if (!isSafeEntryName(entryName)) {
            return Optional.empty();
        }
        return Optional.ofNullable(BUNDLES.get(new EntryKey(entryName, normalizeChecksum(checksum))));
    }

    public static Optional<byte[]> findBundleEntry(
            String entryName, String checksum, String bundleEntryName) {
        if (!isSafeEntryName(bundleEntryName)) {
            return Optional.empty();
        }
        return findBundle(entryName, checksum).map(entries -> entries.get(bundleEntryName));
    }

    public static boolean isSafeEntryName(String entryName) {
        if (StringUtilities.isEmpty(entryName)
                || entryName.startsWith("/") // $NON-NLS-1$
                || entryName.startsWith("\\") // $NON-NLS-1$
                || entryName.indexOf('\\') >= 0) {
            return false;
        }
        for (String segment : entryName.split("/", -1)) { // $NON-NLS-1$
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) { // $NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }
        return true;
    }

    private static String normalizeChecksum(String checksum) {
        return checksum == null ? "" : checksum.trim().toLowerCase(Locale.ROOT); // $NON-NLS-1$
    }

    private record EntryKey(String entryName, String checksum) {
    }
}
