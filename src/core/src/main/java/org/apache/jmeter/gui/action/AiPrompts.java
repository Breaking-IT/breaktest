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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the AI Auto Scripting prompt templates that ship as resources next to this class.
 *
 * <p>Templates use {@code {{TOKEN}}} placeholders rather than positional format specifiers so a
 * template can repeat or reorder a value without the caller having to renumber arguments. Unknown
 * placeholders are left untouched, and replacement values are never rescanned, so a value that
 * happens to contain {@code {{...}}} cannot inject a placeholder.</p>
 */
final class AiPrompts {
    static final String LIVE_GUI_REPAIR = "ai-prompt-live-gui-repair.txt";
    static final String SPECIFIC_REQUEST = "ai-prompt-specific-request.txt";
    static final String FILE_BACKED_REPAIR = "ai-prompt-file-backed-repair.txt";
    static final String USER_INSTRUCTIONS = "ai-prompt-user-instructions.txt";
    static final String RUN_OPTIONS = "ai-prompt-run-options.txt";

    private static final String FRAGMENTS = "ai-prompt-fragments.properties";
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private AiPrompts() {
    }

    /**
     * Renders the named template resource, substituting every {@code {{TOKEN}}} that has an entry
     * in {@code tokens}.
     */
    static String render(String template, Map<String, String> tokens) {
        Matcher matcher = TOKEN.matcher(read(template));
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = tokens.get(matcher.group(1));
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** Returns a short reusable prompt fragment, or an empty string when the key is missing. */
    static String fragment(String key) {
        Properties fragments = new Properties();
        try (InputStream input = AiPrompts.class.getResourceAsStream(FRAGMENTS)) {
            if (input == null) {
                return "";
            }
            fragments.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read AI prompt fragments " + FRAGMENTS, ex);
        }
        return fragments.getProperty(key, "");
    }

    private static String read(String resource) {
        try (InputStream input = AiPrompts.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing AI prompt resource " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read AI prompt resource " + resource, ex);
        }
    }
}
