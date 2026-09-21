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

package org.apache.jmeter.gui.action;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.ai.gui.AiCliProcess;
import org.apache.jmeter.util.JMeterUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Read-only model discovery. No inference requests or credential handling. */
final class AiModelCatalog {
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    record Result(List<String> models, String status) {
        Result {
            models = List.copyOf(models);
        }
    }

    private AiModelCatalog() {
    }

    static Result load(String tool, File workingDirectory) {
        Set<String> models = new LinkedHashSet<>();
        if (!"pi".equals(tool)) {
            add(models, JMeterUtils.getProperty("breaktest." + tool + ".model"));
        }
        File home = new File(System.getProperty("user.home"));
        addConfiguredModels(models, tool, home);
        try {
            if ("codex".equals(tool)) {
                String configuredHome = System.getenv("CODEX_HOME");
                Path cache = (configuredHome == null || configuredHome.isBlank()
                        ? home.toPath().resolve(".codex") : Path.of(configuredHome)).resolve("models_cache.json");
                if (Files.isRegularFile(cache) && Files.size(cache) <= MAX_OUTPUT_BYTES) {
                    models.addAll(parseCodexCache(Files.readString(cache)));
                }
                return new Result(new ArrayList<>(models), models.isEmpty()
                        ? "No cached models found. Enter a model ID or use Agent default."
                        : "Models from local Codex settings/cache. You can also enter a model ID.");
            }
            List<String> arguments = switch (tool) {
                case "pi" -> List.of("--offline", "--list-models");
                case "opencode" -> List.of("models");
                case "cursor" -> List.of("--list-models");
                default -> List.of();
            };
            if (arguments.isEmpty()) {
                return new Result(new ArrayList<>(models), "Configured models only. Enter another model ID or use Agent default.");
            }
            String executable = JMeterUtils.getPropDefault("breaktest." + tool + ".command",
                    "cursor".equals(tool) ? "cursor-agent" : tool);
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.addAll(arguments);
            models.addAll(parseOutput(tool, query(command, workingDirectory, 8)));
            return new Result(new ArrayList<>(models), "Model list loaded. Availability depends on your account; you can also enter an ID.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Result(new ArrayList<>(models), "Model lookup cancelled. Enter a model ID or use Agent default.");
        } catch (Exception ex) {
            // CLI errors can contain account details. Keep raw output out of the popup.
            return new Result(new ArrayList<>(models), "Could not load model list. Retry Refresh, enter an ID, or use Agent default.");
        }
    }

    private static void addConfiguredModels(Set<String> models, String tool, File home) {
        JsonNode settings = null;
        switch (tool) {
            case "pi" -> {
                settings = AiEngineDescription.readJsonFile(new File(AiEngineDescription.piHome(home), "settings.json"));
                if (settings == null) {
                    settings = new ObjectMapper().createObjectNode();
                }
                String provider = JMeterUtils.getPropDefault("breaktest.pi.provider", settings.path("defaultProvider").asText());
                String model = JMeterUtils.getPropDefault("breaktest.pi.model", settings.path("defaultModel").asText());
                if (!model.isBlank()) {
                    add(models, provider.isBlank() || model.startsWith(provider + "/") ? model : provider + "/" + model);
                }
                for (JsonNode enabled : settings.path("enabledModels")) {
                    String id = enabled.asText();
                    if (!id.contains("*") && !id.contains("?")) {
                        add(models, id);
                    }
                }
            }
            case "claude" -> settings = AiEngineDescription.readJsonFile(new File(home, ".claude/settings.json"));
            case "copilot" -> settings = AiEngineDescription.readJsonFile(new File(AiEngineDescription.copilotHome(home), "config.json"));
            case "gemini" -> {
                settings = AiEngineDescription.readJsonFile(new File(AiEngineDescription.geminiHome(home), "settings.json"));
                if (settings != null) {
                    add(models, settings.path("model").path("name").asText());
                }
            }
            case "codex" -> {
                String configuredHome = System.getenv("CODEX_HOME");
                File directory = configuredHome == null || configuredHome.isBlank() ? new File(home, ".codex") : new File(configuredHome);
                add(models, AiEngineDescription.readTopLevelToml(new File(directory, "config.toml")).get("model"));
            }
            default -> { }
        }
        if (settings != null && settings.path("model").isTextual()) {
            add(models, settings.path("model").asText());
        }
    }

    static List<String> parseCodexCache(String text) throws IOException {
        Set<String> models = new LinkedHashSet<>();
        for (JsonNode model : new ObjectMapper().readTree(text).path("models")) {
            if ("list".equals(model.path("visibility").asText("list"))) {
                add(models, model.path("slug").asText());
            }
        }
        return List.copyOf(models);
    }

    static List<String> parseOutput(String tool, String output) {
        Set<String> models = new LinkedHashSet<>();
        for (String raw : output.split("\\R")) {
            String line = raw.replaceAll("\\x1b\\[[0-9;]*[A-Za-z]", "").trim();
            String[] cells = line.split("\\s+");
            if ("pi".equals(tool) && cells.length >= 6 && cells[2].matches("[0-9].*")
                    && cells[0].matches("[A-Za-z0-9_.-]+")) {
                add(models, cells[0] + "/" + cells[1]);
            } else if ("opencode".equals(tool) && line.matches("[A-Za-z0-9_.-]+/[^\\s]+")) {
                add(models, line);
            } else if ("cursor".equals(tool) && line.matches("[A-Za-z0-9_.:/-]+ - .+")) {
                add(models, line.substring(0, line.indexOf(" - ")));
            }
        }
        return List.copyOf(models);
    }

    private static void add(Set<String> models, String model) {
        if (model != null && !model.isBlank()) {
            models.add(model.trim());
        }
    }

    static String query(List<String> command, File directory, int timeoutSeconds) throws Exception {
        // Reuse Windows .cmd/.bat resolution without submitting an agent prompt.
        List<String> withEmptyPrompt = new ArrayList<>(command);
        withEmptyPrompt.add("");
        List<String> resolved = AiCliProcess.prepare(withEmptyPrompt, AiCliProcess.PromptStyle.POSITIONAL).command();
        Process process = new ProcessBuilder(resolved).directory(directory)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        FutureTask<byte[]> output = new FutureTask<>(() -> process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1));
        Thread reader = new Thread(output, "BreakTest model list reader");
        reader.setDaemon(true);
        reader.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Model list command failed or timed out");
            }
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (bytes.length > MAX_OUTPUT_BYTES) {
                throw new IOException("Model list output too large");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.getInputStream().close();
            output.cancel(true);
        }
    }
}
