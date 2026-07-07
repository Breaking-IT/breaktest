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

package org.apache.jmeter.gui.settings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Line-preserving editor for a Java properties file such as
 * {@code user.properties}. Values are read with {@link Properties} semantics,
 * while edits replace or append individual {@code key=value} lines so that
 * comments and unrelated entries keep their exact formatting.
 */
public class PropertiesFileStore {

    private static final String MANAGED_SECTION_MARKER =
            "# Properties below were added via the Settings dialog";

    private final File file;
    private final List<String> lines = new ArrayList<>();
    private final Properties values = new Properties();

    public PropertiesFileStore(File file) throws IOException {
        this.file = file;
        if (file.canRead()) {
            // Properties files are ISO 8859-1 per the java.util.Properties format
            lines.addAll(Files.readAllLines(file.toPath(), StandardCharsets.ISO_8859_1));
            try (InputStream in = Files.newInputStream(file.toPath())) {
                values.load(in);
            }
        }
    }

    public File getFile() {
        return file;
    }

    public boolean exists() {
        return file.exists();
    }

    /**
     * @param key property key to look up
     * @return the value as {@link Properties} would parse it, or {@code null} when absent
     */
    public String getValue(String key) {
        return values.getProperty(key);
    }

    public boolean containsKey(String key) {
        return values.containsKey(key);
    }

    /**
     * @return all keys defined in the file
     */
    public List<String> getKeys() {
        List<String> keys = new ArrayList<>();
        for (Object key : values.keySet()) {
            keys.add((String) key);
        }
        return keys;
    }

    /**
     * Sets {@code key} to {@code value}, replacing the existing definition line
     * (and any continuation lines) in place, or appending a new line at the end
     * of the file.
     *
     * @param key property key, must not be {@code null}
     * @param value property value, must not be {@code null}
     */
    public void setValue(String key, String value) {
        String newLine = escapeKey(key) + "=" + escapeValue(value);
        int index = findDefinition(key);
        if (index >= 0) {
            int end = definitionEnd(index);
            // collapse a possibly multi-line definition into the new single line
            lines.subList(index, end + 1).clear();
            lines.add(index, newLine);
        } else {
            appendManagedLine(newLine);
        }
        values.setProperty(key, value);
    }

    /**
     * Removes the definition of {@code key} from the file, if present.
     *
     * @param key property key to remove
     */
    public void removeValue(String key) {
        int index = findDefinition(key);
        if (index >= 0) {
            int end = definitionEnd(index);
            lines.subList(index, end + 1).clear();
        }
        values.remove(key);
    }

    /**
     * Writes the (possibly modified) lines back to the file, creating it when missing.
     *
     * @throws IOException when the file cannot be written
     */
    public void save() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory " + parent);
        }
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.ISO_8859_1)) {
            for (String line : lines) {
                writer.write(line);
                writer.write(System.lineSeparator());
            }
        }
    }

    private void appendManagedLine(String line) {
        if (!lines.contains(MANAGED_SECTION_MARKER)) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
                lines.add("");
            }
            lines.add(MANAGED_SECTION_MARKER);
        }
        lines.add(line);
    }

    /**
     * @return the index of the line where {@code key} is defined, or -1
     */
    private int findDefinition(String key) {
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0 && isContinuation(lines.get(i - 1))) {
                continue;
            }
            String parsedKey = parseKey(lines.get(i));
            if (key.equals(parsedKey)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return the index of the last line of the definition starting at {@code start},
     *         following backslash line continuations
     */
    private int definitionEnd(int start) {
        int end = start;
        while (end < lines.size() - 1 && isContinuation(lines.get(end))) {
            end++;
        }
        return end;
    }

    private static boolean isContinuation(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /**
     * Parses the key of a {@code key=value} line, un-escaping backslash escapes,
     * or returns {@code null} for blank and comment lines.
     */
    private static String parseKey(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\f')) {
            i++;
        }
        if (i >= line.length() || line.charAt(i) == '#' || line.charAt(i) == '!') {
            return null;
        }
        StringBuilder key = new StringBuilder();
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                key.append(unescapeChar(next));
                i += 2;
                continue;
            }
            if (c == '=' || c == ':' || c == ' ' || c == '\t' || c == '\f') {
                break;
            }
            key.append(c);
            i++;
        }
        return key.length() == 0 ? null : key.toString();
    }

    private static char unescapeChar(char c) {
        return switch (c) {
            case 't' -> '\t';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 'f' -> '\f';
            default -> c;
        };
    }

    private static String escapeKey(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ' ' || c == '=' || c == ':' || c == '\\' || c == '#' || c == '!') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String escapeValue(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (i == 0 && c == ' ') {
                        sb.append("\\ ");
                    } else if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
