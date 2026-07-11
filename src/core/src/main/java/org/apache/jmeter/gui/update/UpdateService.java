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

package org.apache.jmeter.gui.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Checks GitHub releases and securely stages a BreakTest binary update. */
public final class UpdateService {
    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI DEFAULT_RELEASE_API =
            URI.create("https://api.github.com/repos/Breaking-IT/breaktest/releases/latest");
    private static final String LAST_CHECK_KEY = "last_successful_update_check";
    private static final long MAX_API_BYTES = 1024 * 1024;
    private static final long MAX_CHECKSUM_BYTES = 4096;
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 30_000;
    private static final Pattern CHECKSUM_LINE =
            Pattern.compile("(?i)^([0-9a-f]{128})\\s+\\*?(.+?)\\s*$");
    private static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._-]{0,63}");
    private static final Pattern VERSION_NUMBER = Pattern.compile("\\d+");
    private static final Pattern QUALIFIER_CHUNK = Pattern.compile("\\d+|\\D+");

    private final HttpClient httpClient;
    private final URI releaseApi;
    private final String currentVersion;
    private final Preferences preferences;
    private final Duration checkInterval;
    private final BooleanSupplier guiMode;
    private final ScheduledExecutorService executor;
    private final List<Consumer<ReleaseInfo>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean automaticChecksStarted = new AtomicBoolean();

    private volatile ReleaseInfo availableRelease;

    private UpdateService() {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                URI.create(JMeterUtils.getPropDefault("breaktest.update.api_url", DEFAULT_RELEASE_API.toString())),
                JMeterUtils.getJMeterVersion(),
                Preferences.userNodeForPackage(UpdateService.class),
                Duration.ofHours(Math.max(1, JMeterUtils.getPropDefault("breaktest.update.interval_hours", 6))),
                UpdateService::isGuiMode);
    }

    UpdateService(HttpClient httpClient, URI releaseApi, String currentVersion,
            Preferences preferences, Duration checkInterval, BooleanSupplier guiMode) {
        this.httpClient = httpClient;
        this.releaseApi = releaseApi;
        this.currentVersion = currentVersion;
        this.preferences = preferences;
        this.checkInterval = checkInterval;
        this.guiMode = guiMode;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "breaktest-update-checker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static UpdateService getInstance() {
        return Holder.INSTANCE;
    }

    public ReleaseInfo getAvailableRelease() {
        return availableRelease;
    }

    public void addListener(Consumer<ReleaseInfo> listener) {
        listeners.add(listener);
        ReleaseInfo current = availableRelease;
        if (current != null) {
            listener.accept(current);
        }
    }

    /** Checks immediately at startup and then repeats every configured interval. */
    @SuppressWarnings("FutureReturnValueIgnored")
    public void startAutomaticChecks() {
        if (!guiMode.getAsBoolean() || !JMeterUtils.getPropDefault("breaktest.update.enabled", true)
                || !automaticChecksStarted.compareAndSet(false, true)) {
            return;
        }
        // The startup check is not throttled by the persisted timestamp: every fresh GUI
        // session should learn about an available update right away.
        executor.execute(this::checkQuietly);
        long intervalMillis = Math.max(TimeUnit.SECONDS.toMillis(1), checkInterval.toMillis());
        executor.scheduleWithFixedDelay(this::automaticCheck, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<Optional<ReleaseInfo>> checkNow() {
        if (!guiMode.getAsBoolean()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return checkForUpdate();
            } catch (IOException e) {
                throw new UpdateException(e);
            }
        }, executor);
    }

    private static boolean isGuiMode() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        return guiPackage != null && guiPackage.getMainFrame() != null;
    }

    private void automaticCheck() {
        // Skip when another BreakTest instance sharing these preferences checked recently.
        // Half the interval as threshold: a tick of this instance always lands ~interval after
        // its own last check (never skipped), while ticks of concurrently running instances
        // are still deduplicated.
        long elapsed = System.currentTimeMillis() - preferences.getLong(LAST_CHECK_KEY, 0);
        if (elapsed < checkInterval.toMillis() / 2) {
            return;
        }
        checkQuietly();
    }

    private void checkQuietly() {
        try {
            checkForUpdate();
        } catch (Exception e) {
            log.info("Could not check for a BreakTest update: {}", e.getMessage());
        }
    }

    private Optional<ReleaseInfo> checkForUpdate() throws IOException {
        requireSecureUri(releaseApi, "release API");
        HttpRequest request = HttpRequest.newBuilder(releaseApi)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BreakTest/" + currentVersion)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            ensureSuccess(response, "release metadata");
            requireSecureUri(response.uri(), "release API redirect");
            JsonNode root = JSON.readTree(readLimited(body, MAX_API_BYTES));
            preferences.putLong(LAST_CHECK_KEY, System.currentTimeMillis());
            Optional<ReleaseInfo> result = parseRelease(root, currentVersion);
            setAvailableRelease(result.orElse(null));
            return result;
        }
    }

    static Optional<ReleaseInfo> parseRelease(JsonNode root, String currentVersion) throws IOException {
        if (root.path("draft").asBoolean() || root.path("prerelease").asBoolean()) {
            return Optional.empty();
        }
        String version = root.path("tag_name").asText("").replaceFirst("^(?:rel/)?v", "");
        if (version.isBlank()) {
            return Optional.empty();
        }
        if (!SAFE_VERSION.matcher(version).matches()) {
            throw new IOException("Release contains an invalid version tag");
        }
        if (compareVersions(version, currentVersion) <= 0) {
            return Optional.empty();
        }
        String archiveName = "breaktest-" + version + ".zip";
        String checksumName = archiveName + ".sha512";
        ReleaseInfo.Asset archive = null;
        ReleaseInfo.Asset checksum = null;
        for (JsonNode assetNode : root.path("assets")) {
            // Only the expected binary ZIP and its checksum are parsed and validated; any
            // other asset attached to the release (sources, signatures, externally hosted
            // files) must not break the update check.
            String name = assetNode.path("name").asText("");
            if (archiveName.equals(name)) {
                archive = parseAsset(assetNode, name);
            } else if (checksumName.equals(name)) {
                checksum = parseAsset(assetNode, name);
            }
        }
        if (archive == null || checksum == null) {
            throw new IOException("Release " + version + " does not contain the expected binary ZIP and checksum");
        }
        URI page;
        try {
            page = URI.create(root.path("html_url").asText(
                    "https://github.com/Breaking-IT/breaktest/releases/tag/" + version));
            requireSecureUri(page, "release page");
        } catch (IllegalArgumentException e) {
            throw new IOException("Release contains an invalid page URL", e);
        }
        return Optional.of(new ReleaseInfo(version, page, archive, checksum));
    }

    private static ReleaseInfo.Asset parseAsset(JsonNode asset, String name) throws IOException {
        String url = asset.path("browser_download_url").asText("");
        if (url.isBlank()) {
            throw new IOException("Release asset " + name + " has no download URL");
        }
        try {
            URI downloadUri = URI.create(url);
            requireGitHubAssetUri(downloadUri);
            return new ReleaseInfo.Asset(name, downloadUri, asset.path("size").asLong(-1),
                    asset.path("digest").asText(""));
        } catch (IllegalArgumentException e) {
            throw new IOException("Release contains an invalid asset URL", e);
        }
    }

    static int compareVersions(String left, String right) {
        VersionValue leftValue = versionValue(left);
        VersionValue rightValue = versionValue(right);
        int length = Math.max(leftValue.parts().size(), rightValue.parts().size());
        for (int i = 0; i < length; i++) {
            int leftPart = i < leftValue.parts().size() ? leftValue.parts().get(i) : 0;
            int rightPart = i < rightValue.parts().size() ? rightValue.parts().get(i) : 0;
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        if (leftValue.qualifier().isBlank() != rightValue.qualifier().isBlank()) {
            return leftValue.qualifier().isBlank() ? 1 : -1;
        }
        return compareQualifiers(leftValue.qualifier(), rightValue.qualifier());
    }

    /** Compares digit runs numerically so {@code rc10} sorts after {@code rc2}. */
    private static int compareQualifiers(String left, String right) {
        Matcher leftChunks = QUALIFIER_CHUNK.matcher(left);
        Matcher rightChunks = QUALIFIER_CHUNK.matcher(right);
        while (true) {
            boolean leftHasChunk = leftChunks.find();
            boolean rightHasChunk = rightChunks.find();
            if (!leftHasChunk || !rightHasChunk) {
                return Boolean.compare(leftHasChunk, rightHasChunk);
            }
            String leftChunk = leftChunks.group();
            String rightChunk = rightChunks.group();
            int result;
            if (isDigits(leftChunk) && isDigits(rightChunk)) {
                result = new BigInteger(leftChunk).compareTo(new BigInteger(rightChunk));
            } else {
                result = leftChunk.compareTo(rightChunk);
            }
            if (result != 0) {
                return result;
            }
        }
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static VersionValue versionValue(String version) {
        String normalized = version.replaceFirst("^(?:rel/)?v", "").trim();
        int qualifierStart = normalized.indexOf('-');
        int whitespaceStart = normalized.indexOf(' ');
        if (qualifierStart < 0 || whitespaceStart >= 0 && whitespaceStart < qualifierStart) {
            qualifierStart = whitespaceStart;
        }
        String base = qualifierStart < 0 ? normalized : normalized.substring(0, qualifierStart);
        String qualifier = qualifierStart < 0 ? "" : normalized.substring(qualifierStart + 1);
        List<Integer> result = new ArrayList<>();
        Matcher matcher = VERSION_NUMBER.matcher(base);
        while (matcher.find()) {
            try {
                result.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException e) {
                result.add(Integer.MAX_VALUE);
            }
        }
        return new VersionValue(result, qualifier.toLowerCase(Locale.ROOT));
    }

    private void setAvailableRelease(ReleaseInfo release) {
        availableRelease = release;
        if (release != null) {
            listeners.forEach(listener -> listener.accept(release));
        }
    }

    /** Downloads, verifies, and extracts an update without changing the current installation. */
    public PreparedUpdate prepareUpdate(ReleaseInfo release, Consumer<String> progress) throws IOException {
        Path workspace = Files.createTempDirectory("breaktest-update-");
        try {
            Path archive = workspace.resolve(release.archive().name());
            progress.accept("Downloading BreakTest " + release.version() + "...");
            download(release.archive(), archive, MAX_ARCHIVE_BYTES);

            progress.accept("Verifying the update...");
            String expectedSha512 = downloadChecksum(release.checksum(), release.archive().name());
            verifyDigest(archive, "SHA-512", expectedSha512);
            verifyGitHubDigest(archive, release.archive().digest());

            progress.accept("Preparing the update...");
            Path extracted = workspace.resolve("extracted");
            Path distributionRoot = extractZip(archive, extracted);
            validateDistribution(distributionRoot);
            return new PreparedUpdate(workspace, distributionRoot);
        } catch (Exception e) {
            deleteRecursively(workspace);
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Could not prepare the update", e);
        }
    }

    private String downloadChecksum(ReleaseInfo.Asset checksum, String archiveName) throws IOException {
        Path temporary = Files.createTempFile("breaktest-checksum-", ".sha512");
        try {
            download(checksum, temporary, MAX_CHECKSUM_BYTES);
            verifyGitHubDigest(temporary, checksum.digest());
            for (String line : Files.readAllLines(temporary, StandardCharsets.UTF_8)) {
                Matcher matcher = CHECKSUM_LINE.matcher(line);
                if (matcher.matches() && archiveName.equals(matcher.group(2))) {
                    return matcher.group(1).toLowerCase(Locale.ROOT);
                }
            }
            throw new IOException("The release checksum file is malformed");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void download(ReleaseInfo.Asset asset, Path destination, long maximumBytes) throws IOException {
        requireSecureUri(asset.downloadUri(), "release asset");
        if (asset.size() > maximumBytes) {
            throw new IOException("Release asset is larger than the allowed limit");
        }
        HttpRequest request = HttpRequest.newBuilder(asset.downloadUri())
                .timeout(Duration.ofMinutes(10))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "BreakTest/" + currentVersion)
                .GET()
                .build();
        HttpResponse<InputStream> response = send(request);
        ensureSuccess(response, asset.name());
        requireSecureUri(response.uri(), "release redirect");
        try (InputStream input = response.body(); var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new IOException("Release asset exceeds the allowed download size");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Update request was interrupted", e);
        }
    }

    private static void ensureSuccess(HttpResponse<?> response, String description) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Could not download " + description + " (HTTP " + response.statusCode() + ")");
        }
    }

    private static byte[] readLimited(InputStream input, long maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new IOException("Update response exceeds the allowed size");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void verifyGitHubDigest(Path archive, String digest) throws IOException {
        if (digest == null || digest.isBlank()) {
            return;
        }
        String[] parts = digest.split(":", 2);
        if (parts.length != 2 || !"sha256".equalsIgnoreCase(parts[0])) {
            throw new IOException("GitHub returned an unsupported release digest");
        }
        verifyDigest(archive, "SHA-256", parts[1]);
    }

    private static void verifyDigest(Path file, String algorithm, String expected) throws IOException {
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Required digest is unavailable: " + algorithm, e);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                messageDigest.update(buffer, 0, read);
            }
        }
        String actual = HexFormat.of().formatHex(messageDigest.digest());
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException(algorithm + " verification failed for the downloaded update");
        }
    }

    static Path extractZip(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        int entries = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES || entry.getName().contains("\\")) {
                    throw new IOException("Update archive contains invalid entries");
                }
                Path target = normalizedDestination.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedDestination)) {
                    throw new IOException("Update archive attempts to write outside its staging directory");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var output = Files.newOutputStream(target)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            total += read;
                            if (total > MAX_EXTRACTED_BYTES) {
                                throw new IOException("Update archive expands beyond the allowed size");
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        try (var children = Files.list(normalizedDestination)) {
            List<Path> roots = children.filter(Files::isDirectory).toList();
            if (roots.size() != 1) {
                throw new IOException("Update archive must contain one distribution directory");
            }
            return roots.get(0);
        }
    }

    private static void validateDistribution(Path root) throws IOException {
        if (!Files.isRegularFile(root.resolve("bin/ApacheJMeter.jar"))
                || !Files.isRegularFile(root.resolve("lib/ext/ApacheJMeter_core.jar"))) {
            throw new IOException("Downloaded archive is not a valid BreakTest binary distribution");
        }
    }

    public boolean canInstall() {
        Path home = Path.of(JMeterUtils.getJMeterHome()).toAbsolutePath().normalize();
        return !Files.exists(home.resolve(".git"))
                && Files.isRegularFile(home.resolve("bin/ApacheJMeter.jar"))
                && Files.isRegularFile(home.resolve("lib/ext/ApacheJMeter_core.jar"))
                && Files.isWritable(home);
    }

    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    log.debug("Could not delete update staging path {}", item, e);
                }
            });
        } catch (IOException e) {
            log.debug("Could not clean update staging directory {}", path, e);
        }
    }

    /** A verified, extracted update ready for the external installer. */
    public record PreparedUpdate(Path workspace, Path distributionRoot) implements AutoCloseable {
        @Override
        public void close() {
            deleteRecursively(workspace);
        }
    }

    /** Completion wrapper that keeps checked I/O failures available to GUI callers. */
    public static final class UpdateException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UpdateException(IOException cause) {
            super(cause);
        }
    }

    private static void requireSecureUri(URI uri, String description) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) {
            throw new IOException("The " + description + " must use HTTPS without embedded credentials");
        }
    }

    private static void requireGitHubAssetUri(URI uri) throws IOException {
        requireSecureUri(uri, "release asset");
        if (!"github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IOException("Release assets must be hosted by GitHub");
        }
    }

    private static final class Holder {
        private static final UpdateService INSTANCE = new UpdateService();
    }

    private record VersionValue(List<Integer> parts, String qualifier) {
    }
}
