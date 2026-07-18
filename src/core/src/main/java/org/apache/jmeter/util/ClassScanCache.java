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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent cache for {@link org.apache.jorphan.reflect.ClassFinder} scan results.
 * <p>
 * Scanning the jars on the search path (lib/ext plus {@code search_paths}) requires
 * loading every class they contain, which dominates non-GUI startup time. The set of
 * matching classes only changes when the jars themselves change, so the result of each
 * scan is stored in a small per-user file together with a fingerprint of the search
 * path contents. While the fingerprint matches, the scan is skipped entirely.
 * <p>
 * The cache can be disabled with {@code classfinder.cache=false}; its location can be
 * changed with {@code classfinder.cache.file}.
 */
final class ClassScanCache {
    private static final Logger log = LoggerFactory.getLogger(ClassScanCache.class);

    private static final String FORMAT_KEY = "cache.format";
    private static final String FORMAT_VERSION = "1";
    private static final String FINGERPRINT_KEY = "fingerprint";
    private static final String SERVICE_KEY_PREFIX = "service.";

    /** Directory trees larger than this are considered too expensive to fingerprint. */
    private static final int MAX_FINGERPRINT_FILES = 10_000;

    private static final Object LOCK = new Object();
    private static Map<String, List<String>> entries;
    private static String loadedFingerprint;

    private ClassScanCache() {
    }

    /**
     * Same contract as {@link JMeterUtils#findClassesThatExtend(Class)}, but backed by
     * the persistent cache when it is enabled and the search path is unchanged.
     *
     * @param service interface or parent class to search implementations of
     * @return discovered class names
     * @throws IOException when scanning the search path fails
     */
    @SuppressWarnings("deprecation")
    static List<String> findClassesThatExtend(Class<?> service) throws IOException {
        if (!JMeterUtils.getPropDefault("classfinder.cache", true)) { // $NON-NLS-1$
            return JMeterUtils.findClassesThatExtend(service);
        }
        String fingerprint = computeFingerprint();
        if (fingerprint == null) {
            return JMeterUtils.findClassesThatExtend(service);
        }
        synchronized (LOCK) {
            ensureLoaded(fingerprint);
            List<String> cached = entries.get(service.getName());
            if (cached != null) {
                log.debug("Class scan cache hit for {}: {}", service, cached);
                return cached;
            }
        }
        List<String> found = JMeterUtils.findClassesThatExtend(service);
        synchronized (LOCK) {
            ensureLoaded(fingerprint);
            entries.put(service.getName(), Collections.unmodifiableList(new ArrayList<>(found)));
            persist(fingerprint);
        }
        return found;
    }

    private static File getCacheFile() {
        String path = JMeterUtils.getPropDefault("classfinder.cache.file", null); // $NON-NLS-1$
        if (path != null && !path.isEmpty()) {
            return new File(path);
        }
        return new File(new File(System.getProperty("user.home"), ".breaktest"), "class-scan.cache");
    }

    /** Drops the in-memory state so the next lookup re-reads the cache file. For tests. */
    static void reset() {
        synchronized (LOCK) {
            entries = null;
            loadedFingerprint = null;
        }
    }

    /**
     * Builds a digest of everything that can influence a scan result: BreakTest and Java
     * versions plus path, size and modification time of every file on the search path.
     *
     * @return hex digest, or {@code null} when the search path cannot be fingerprinted cheaply
     */
    static String computeFingerprint() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("format:").append(FORMAT_VERSION)
                .append("\nversion:").append(JMeterUtils.getJMeterVersion())
                .append("\njava:").append(System.getProperty("java.version"));
        int[] fileCount = new int[1];
        for (String pathName : JMeterUtils.getSearchPaths()) {
            sb.append("\npath:").append(pathName);
            File pathEntry = new File(pathName);
            if (pathEntry.isDirectory()) {
                if (!appendDirFingerprint(sb, pathEntry.toPath(), fileCount)) {
                    return null;
                }
            } else if (pathEntry.isFile()) {
                appendFileFingerprint(sb, pathEntry);
            }
        }
        return sha256(sb.toString());
    }

    private static boolean appendDirFingerprint(StringBuilder sb, Path dir, int[] fileCount) {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                if (++fileCount[0] > MAX_FINGERPRINT_FILES) {
                    log.info("Search path {} contains more than {} files; class scan caching disabled",
                            dir, MAX_FINGERPRINT_FILES);
                    return false;
                }
                appendFileFingerprint(sb, path.toFile());
            }
        } catch (IOException e) {
            log.debug("Unable to walk {} for class scan fingerprint", dir, e);
            return false;
        }
        return true;
    }

    private static void appendFileFingerprint(StringBuilder sb, File file) {
        sb.append("\nfile:").append(file.getAbsolutePath())
                .append('|').append(file.length())
                .append('|').append(file.lastModified());
    }

    private static String sha256(String input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            log.debug("SHA-256 unavailable; class scan caching disabled", e);
            return null;
        }
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    // Must be called with LOCK held
    private static void ensureLoaded(String fingerprint) {
        if (entries != null && fingerprint.equals(loadedFingerprint)) {
            return;
        }
        entries = new HashMap<>();
        loadedFingerprint = fingerprint;
        File cacheFile = getCacheFile();
        if (!cacheFile.isFile()) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(cacheFile.toPath()))) {
            props.load(in);
        } catch (IOException e) {
            log.debug("Unable to read class scan cache {}", cacheFile, e);
            return;
        }
        if (!FORMAT_VERSION.equals(props.getProperty(FORMAT_KEY))
                || !fingerprint.equals(props.getProperty(FINGERPRINT_KEY))) {
            log.debug("Class scan cache {} is stale, jars will be re-scanned", cacheFile);
            return;
        }
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith(SERVICE_KEY_PREFIX)) {
                continue;
            }
            String serviceName = key.substring(SERVICE_KEY_PREFIX.length());
            String value = props.getProperty(key);
            List<String> classes = value.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(Arrays.asList(value.split(",")));
            entries.put(serviceName, classes);
        }
    }

    // Must be called with LOCK held
    private static void persist(String fingerprint) {
        File cacheFile = getCacheFile().getAbsoluteFile();
        File dir = cacheFile.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log.debug("Unable to create {} to store the class scan cache", dir);
            return;
        }
        Properties props = new Properties();
        props.setProperty(FORMAT_KEY, FORMAT_VERSION);
        props.setProperty(FINGERPRINT_KEY, fingerprint);
        for (Map.Entry<String, List<String>> entry : entries.entrySet()) {
            props.setProperty(SERVICE_KEY_PREFIX + entry.getKey(), String.join(",", entry.getValue()));
        }
        try {
            Path tmp = Files.createTempFile(dir.toPath(), cacheFile.getName(), ".tmp");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                props.store(out, "BreakTest class scan cache; safe to delete");
            }
            try {
                Files.move(tmp, cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.debug("Unable to write class scan cache {}", cacheFile, e);
        }
    }
}
