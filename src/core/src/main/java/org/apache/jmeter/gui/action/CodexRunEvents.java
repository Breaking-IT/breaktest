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

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class CodexRunEvents {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CodexRunEvents() {
    }

    static String display(String line, AiRunOutput output) {
        final JsonNode event;
        try {
            event = JSON.readTree(line);
        } catch (IOException ex) {
            return null;
        }
        String type = event.path("type").asText();
        if ("turn.completed".equals(type)) {
            JsonNode usage = event.path("usage");
            for (String field : List.of("input_tokens", "output_tokens", "total_tokens")) {
                if (usage.path(field).isIntegralNumber()) {
                    output.captureTokenLine(field + ": " + usage.path(field).asLong());
                }
            }
            if (usage.path("cached_input_tokens").isIntegralNumber()) {
                output.captureCachedInputTokens(usage.path("cached_input_tokens").asLong());
            }
            return null;
        }
        if ("turn.failed".equals(type) || "error".equals(type)) {
            String message = event.path("error").path("message").asText(
                    event.path("message").asText("Codex reported an unspecified error."));
            output.captureFinalResponse("Status: failed - " + message);
            return "Codex error: " + message;
        }
        JsonNode item = event.path("item");
        if (!"item.completed".equals(type) || !"agent_message".equals(item.path("type").asText())) {
            return null;
        }
        String text = item.path("text").asText();
        output.startFinalResponseBlock();
        for (String responseLine : text.split("\\R")) {
            output.captureFinalResponse(responseLine);
        }
        return text.isBlank() ? null : text;
    }

}
