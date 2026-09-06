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

package org.apache.jmeter.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import org.apache.jmeter.services.FileServer;
import org.apache.jorphan.io.BomInputStream;

/** The file and encoding captured when a CSV editing session is opened. */
final class CsvFileEditor {
    private final Path path;
    private final Charset charset;
    private final String content;
    private final byte[] prefix;

    private CsvFileEditor(Path path, Charset charset, String content, byte[] prefix) {
        this.path = path;
        this.charset = charset;
        this.content = content;
        this.prefix = prefix;
    }

    // The decoded text, normalized text and Swing document coexist while editing.
    static long maxEditableBytes() {
        return Math.min(128L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 12);
    }

    static void checkEditableSize(long size) throws IOException {
        if (size > maxEditableBytes()) {
            throw new IOException("CSV is too large for the built-in editor (limit "
                    + maxEditableBytes() / (1024 * 1024)
                    + " MiB). Export it and use an external editor, then import the updated file.");
        }
    }

    static byte[] readEditableContent(java.io.InputStream input) throws IOException {
        byte[] content = input.readNBytes((int) maxEditableBytes() + 1);
        checkEditableSize(content.length);
        return content;
    }

    static CsvFileEditor open(String filename, String encoding) throws IOException {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename must not be null or empty");
        }
        Path path = FileServer.getFileServer().resolveFile(filename.trim()).toPath();
        checkEditableSize(Files.size(path));
        try (var input = Files.newInputStream(path)) {
            return fromBytes(path, encoding, readEditableContent(input));
        }
    }

    static CsvFileEditor fromBytes(Path path, String encoding, byte[] original) throws IOException {
        checkEditableSize(original.length);
        Charset charset;
        if (encoding != null && !encoding.trim().isEmpty()) {
            charset = Charset.forName(encoding);
        } else {
            try (Reader reader = BomInputStream.reader(new ByteArrayInputStream(original))) {
                charset = Charset.forName(((InputStreamReader) reader).getEncoding());
            }
        }
        String content = charset.newDecoder().decode(ByteBuffer.wrap(original)).toString();
        byte[] encoded = content.getBytes(charset);
        // Some decoders consume the BOM. Retain it when the encoder does not emit it.
        int prefixLength = original.length - encoded.length;
        byte[] prefix = new byte[0];
        if (prefixLength > 0 && prefixLength <= 4
                && Arrays.equals(original, prefixLength, original.length, encoded, 0, encoded.length)) {
            prefix = Arrays.copyOf(original, prefixLength);
        }
        return new CsvFileEditor(path, charset, content, prefix);
    }

    Path getPath() {
        return path;
    }

    String getContent() {
        return content;
    }

    String getEditorText() {
        // Swing's text area treats LF as the line boundary. A raw CR remains an
        // invisible editable character, so typing at End can split a CSV record.
        return normalizeLineEndings(content);
    }

    String toFileText(String editorText) {
        String normalized = normalizeLineEndings(editorText);
        if (normalized.equals(getEditorText())) {
            return content;
        }
        String separator = "\n";
        for (int i = 0; i < content.length(); i++) {
            char character = content.charAt(i);
            if (character == '\r') {
                separator = i + 1 < content.length() && content.charAt(i + 1) == '\n' ? "\r\n" : "\r";
                break;
            }
            if (character == '\n') {
                break;
            }
        }
        return normalized.replace("\n", separator);
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    void save(String updatedContent) throws IOException {
        Files.write(path, encode(updatedContent), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    byte[] encode(String updatedContent) throws IOException {
        // Encode before opening the file so unsupported characters cannot truncate it.
        ByteBuffer encoded = charset.newEncoder().encode(CharBuffer.wrap(updatedContent));
        byte[] bytes = Arrays.copyOf(prefix, prefix.length + encoded.remaining());
        encoded.get(bytes, prefix.length, encoded.remaining());
        return bytes;
    }
}
