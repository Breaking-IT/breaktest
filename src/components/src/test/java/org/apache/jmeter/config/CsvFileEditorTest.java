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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvFileEditorTest {
    @TempDir
    Path directory;

    @Test
    void appendingAtLineEndDoesNotCreateAdditionalCsvRecords() throws Exception {
        for (String separator : new String[] {"\r\n", "\r", "\n"}) {
            String original = "Alice,id-one" + separator + "Bob,id-two" + separator;
            CsvFileEditor file = CsvFileEditor.fromBytes(Path.of("files/accounts.csv"), "UTF-8",
                    original.getBytes(StandardCharsets.UTF_8));
            javax.swing.JTextArea area = new javax.swing.JTextArea(file.getEditorText());
            area.insert("aaaa", area.getLineEndOffset(0) - 1);
            area.insert("bbbb", area.getLineEndOffset(1) - 1);
            byte[] saved = file.encode(file.toFileText(area.getText()));
            String expected = "Alice,id-oneaaaa" + separator + "Bob,id-twobbbb" + separator;
            assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), saved);
            try (var reader = new java.io.BufferedReader(new java.io.StringReader(new String(saved, StandardCharsets.UTF_8)))) {
                assertEquals(java.util.List.of("Alice,id-oneaaaa", "Bob,id-twobbbb"), reader.lines().toList());
            }
        }
    }

    @Test
    void unchangedEditorTextPreservesMixedOriginalLineEndings() throws Exception {
        String original = "Alice,1\r\nBob,2\nCarol,3\r";
        CsvFileEditor file = CsvFileEditor.fromBytes(Path.of("mixed.csv"), "UTF-8",
                original.getBytes(StandardCharsets.UTF_8));
        assertEquals(original, file.toFileText(file.getEditorText()));
    }

    @Test
    void openingDoesNotWriteAndSavingPreservesRawCsv() throws Exception {
        Path path = directory.resolve("data.csv");
        String original = "name,value\r\nAlice,\"two,three\"\r\n";
        Files.writeString(path, original);
        CsvFileEditor editor = CsvFileEditor.open(path.toString(), "UTF-8");
        assertEquals(original, editor.getContent());
        assertEquals(original, Files.readString(path));
        String updated = original.replace("Alice", "Bob");
        editor.save(updated);
        assertEquals(updated, Files.readString(path));
    }

    @Test
    void retainsDetectedEncodingAndBom() throws Exception {
        for (String encoding : new String[] {"UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"}) {
            Charset charset = Charset.forName(encoding);
            Path path = directory.resolve(encoding + ".csv");
            Files.write(path, "\uFEFFname\r\nAndré\r\n".getBytes(charset));
            CsvFileEditor editor = CsvFileEditor.open(path.toString(), "");
            editor.save(editor.getContent().replace("André", "Renée"));
            assertArrayEquals("\uFEFFname\r\nRenée\r\n".getBytes(charset), Files.readAllBytes(path), encoding);
        }
    }

    @Test
    void encodingFailureLeavesFileIntact() throws Exception {
        Path path = directory.resolve("ascii.csv");
        Files.writeString(path, "name\nAlice\n", StandardCharsets.US_ASCII);
        CsvFileEditor editor = CsvFileEditor.open(path.toString(), "US-ASCII");
        assertThrows(CharacterCodingException.class, () -> editor.save("name\nRenée\n"));
        assertEquals("name\nAlice\n", Files.readString(path));
    }
}
