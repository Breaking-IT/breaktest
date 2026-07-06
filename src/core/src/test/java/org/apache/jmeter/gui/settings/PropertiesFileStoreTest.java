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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PropertiesFileStoreTest {

    @TempDir
    Path tempDir;

    private File write(String content) throws IOException {
        Path file = tempDir.resolve("test.properties");
        Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
        return file.toFile();
    }

    private Properties reload(File file) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(file.toPath())) {
            props.load(in);
        }
        return props;
    }

    @Test
    void readsExistingValues() throws IOException {
        PropertiesFileStore store = new PropertiesFileStore(write("# comment\nfoo=bar\n#commented.out=1\n"));
        assertEquals("bar", store.getValue("foo"));
        assertNull(store.getValue("commented.out"), "commented-out keys are not values");
        assertTrue(store.containsKey("foo"));
    }

    @Test
    void replacesValueInPlaceKeepingComments() throws IOException {
        File file = write("# header comment\nfoo=bar\n# trailing comment\nother=1\n");
        PropertiesFileStore store = new PropertiesFileStore(file);
        store.setValue("foo", "baz");
        store.save();
        String content = Files.readString(file.toPath(), StandardCharsets.ISO_8859_1);
        assertTrue(content.contains("# header comment"), "comments preserved");
        assertTrue(content.contains("# trailing comment"), "comments preserved");
        assertTrue(content.contains("foo=baz"), "value replaced");
        assertEquals("baz", reload(file).getProperty("foo"));
        assertEquals("1", reload(file).getProperty("other"));
    }

    @Test
    void appendsNewKeyAtEnd() throws IOException {
        File file = write("existing=1\n");
        PropertiesFileStore store = new PropertiesFileStore(file);
        store.setValue("added.key", "value with spaces");
        store.save();
        assertEquals("value with spaces", reload(file).getProperty("added.key"));
        assertEquals("1", reload(file).getProperty("existing"));
    }

    @Test
    void removesKeyAndItsContinuationLines() throws IOException {
        File file = write("multi=a,\\\n    b,\\\n    c\nkeep=1\n");
        PropertiesFileStore store = new PropertiesFileStore(file);
        assertEquals("a,b,c", store.getValue("multi"));
        store.removeValue("multi");
        store.save();
        Properties props = reload(file);
        assertNull(props.getProperty("multi"));
        assertEquals("1", props.getProperty("keep"));
        assertFalse(Files.readString(file.toPath(), StandardCharsets.ISO_8859_1).contains("b,"),
                "continuation lines removed");
    }

    @Test
    void replacingMultiLineDefinitionCollapsesIt() throws IOException {
        File file = write("multi=a,\\\n    b\nafter=x\n");
        PropertiesFileStore store = new PropertiesFileStore(file);
        store.setValue("multi", "single");
        store.save();
        Properties props = reload(file);
        assertEquals("single", props.getProperty("multi"));
        assertEquals("x", props.getProperty("after"));
    }

    @Test
    void escapesBackslashesInValues() throws IOException {
        File file = write("");
        PropertiesFileStore store = new PropertiesFileStore(file);
        store.setValue("regex", ".*\\.(png|css)");
        store.setValue("path", "C:\\temp\\dir");
        store.save();
        Properties props = reload(file);
        assertEquals(".*\\.(png|css)", props.getProperty("regex"));
        assertEquals("C:\\temp\\dir", props.getProperty("path"));
    }

    @Test
    void createsMissingFileOnSave() throws IOException {
        File file = tempDir.resolve("missing.properties").toFile();
        PropertiesFileStore store = new PropertiesFileStore(file);
        assertFalse(store.exists());
        store.setValue("a", "b");
        store.save();
        assertEquals("b", reload(file).getProperty("a"));
    }
}
