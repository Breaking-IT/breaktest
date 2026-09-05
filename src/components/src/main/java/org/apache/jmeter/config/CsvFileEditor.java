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

    static CsvFileEditor open(String filename, String encoding) throws IOException {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename must not be null or empty");
        }
        Path path = FileServer.getFileServer().resolveFile(filename.trim()).toPath();
        Charset charset;
        if (encoding != null && !encoding.trim().isEmpty()) {
            charset = Charset.forName(encoding);
        } else {
            try (Reader reader = BomInputStream.reader(Files.newInputStream(path))) {
                charset = Charset.forName(((InputStreamReader) reader).getEncoding());
            }
        }
        byte[] original = Files.readAllBytes(path);
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

    void save(String updatedContent) throws IOException {
        // Encode before opening the file so unsupported characters cannot truncate it.
        ByteBuffer encoded = charset.newEncoder().encode(CharBuffer.wrap(updatedContent));
        byte[] bytes = Arrays.copyOf(prefix, prefix.length + encoded.remaining());
        encoded.get(bytes, prefix.length, encoded.remaining());
        Files.write(path, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
