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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.jmeter.util.JMeterUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class AiEngineDescription {
    private AiEngineDescription() {
    }

    static String describe(String toolId, String displayName) {
        String normalizedToolId = toolId.toLowerCase(Locale.ROOT);
        String prefix = "breaktest." + normalizedToolId;
        String model = JMeterUtils.getProperty(prefix + ".model");
        String modelSource = model != null && !model.isBlank() ? prefix + ".model property" : null;
        String reasoning = null;
        String fastMode = null;
        File home = new File(System.getProperty("user.home"));
        if ("codex".equals(normalizedToolId)) {
            Map<String, String> config = readTopLevelToml(new File(home, ".codex/config.toml"));
            if (modelSource == null && config.get("model") != null) {
                model = config.get("model");
                modelSource = "~/.codex/config.toml";
            }
            reasoning = config.get("model_reasoning_effort");
        } else if ("claude".equals(normalizedToolId)) {
            JsonNode settings = readJsonFile(new File(home, ".claude/settings.json"));
            if (settings != null) {
                if (modelSource == null && settings.hasNonNull("model")) {
                    model = settings.get("model").asText();
                    modelSource = "~/.claude/settings.json";
                }
                if (settings.hasNonNull("fastMode")) {
                    fastMode = settings.get("fastMode").asBoolean() ? "yes" : "no";
                }
            }
        } else if ("opencode".equals(normalizedToolId)) {
            String agent = JMeterUtils.getProperty("breaktest.opencode.agent");
            modelSource = readOpenCodeModel(home, agent, modelSource);
            if (modelSource != null && (model == null || model.isBlank())) {
                model = modelSource.substring(modelSource.indexOf('=') + 1);
                modelSource = modelSource.substring(0, modelSource.indexOf('='));
            }
        }
        return description(displayName, model, modelSource, reasoning, fastMode);
    }

    private static String readOpenCodeModel(File home, String agent, String existingSource) {
        if (existingSource != null) {
            return existingSource;
        }
        if (agent != null && !agent.isBlank()) {
            File agentFile = new File(home, ".config/opencode/agent/" + agent + ".md");
            String agentModel = readYamlFrontMatterValue(agentFile, "model");
            if (agentModel != null) {
                return "~/.config/opencode/agent/" + agent + ".md=" + agentModel;
            }
        }
        for (String name : new String[] {"opencode.json", "opencode.jsonc"}) {
            File configFile = new File(home, ".config/opencode/" + name);
            JsonNode config = readJsonFile(configFile);
            if (config == null) {
                continue;
            }
            JsonNode agentModel = agent == null || agent.isBlank()
                    ? null
                    : config.path("agent").path(agent).path("model");
            JsonNode configured = agentModel != null && agentModel.isTextual() ? agentModel : config.path("model");
            if (configured.isTextual() && !configured.asText().isBlank()) {
                return "~/.config/opencode/" + name + "=" + configured.asText();
            }
        }
        return null;
    }

    private static String description(
            String displayName,
            String model,
            String modelSource,
            String reasoning,
            String fastMode) {
        StringBuilder text = new StringBuilder("AI engine: ").append(displayName);
        text.append(", model=").append(model == null || model.isBlank() ? "(CLI default)" : model);
        if (modelSource != null) {
            text.append(" [").append(modelSource).append(']');
        }
        text.append(", reasoning=").append(reasoning == null || reasoning.isBlank() ? "(CLI default)" : reasoning);
        text.append(", fast mode=").append(fastMode == null ? "n/a" : fastMode);
        return text.toString();
    }

    private static Map<String, String> readTopLevelToml(File file) {
        Map<String, String> values = new HashMap<>();
        if (!file.isFile()) {
            return values;
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    break;
                }
                int separator = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || separator <= 0) {
                    continue;
                }
                values.put(trimmed.substring(0, separator).trim(), unquote(trimmed.substring(separator + 1).trim()));
            }
        } catch (IOException ignored) {
            // best effort: fall back to CLI defaults in the description
        }
        return values;
    }

    private static JsonNode readJsonFile(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            ObjectMapper lenient = new ObjectMapper();
            lenient.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
            lenient.configure(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            return lenient.readTree(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readYamlFrontMatterValue(File file, String key) {
        if (!file.isFile()) {
            return null;
        }
        try {
            List<String> lines = java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
                return null;
            }
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.equals("---")) {
                    break;
                }
                int separator = line.indexOf(':');
                if (separator > 0 && line.substring(0, separator).strip().equals(key)) {
                    String value = unquote(line.substring(separator + 1).strip());
                    return value.isBlank() ? null : value;
                }
            }
        } catch (IOException ignored) {
            // best effort: fall back to CLI defaults in the description
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
