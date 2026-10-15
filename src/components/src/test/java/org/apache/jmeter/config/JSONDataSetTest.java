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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jorphan.util.JMeterStopThreadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JSONDataSetTest extends JMeterTestCase {
    @TempDir
    Path directory;

    private JMeterVariables variables;

    @BeforeEach
    void setupVariables() {
        variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
    }

    private JSONDataSet dataset(String json) throws Exception {
        Path file = directory.resolve("data.json");
        Files.writeString(file, json);
        JSONDataSet data = new JSONDataSet();
        data.setFilename(file.toString());
        data.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        return data;
    }

    @Test
    void loadsObjectValuesAndRecycles() throws Exception {
        JSONDataSet data = dataset("\uFEFF" + """
                {"name":"Zoë","count":42,"enabled":true,"empty":null,
                 "nested":{"id":1},"items":["a",2]}
                """);
        data.setVariablePrefix("data.");
        data.iterationStart(null);
        assertEquals("Zoë", variables.get("data.name"));
        assertEquals("42", variables.get("data.count"));
        assertEquals("true", variables.get("data.enabled"));
        assertEquals("", variables.get("data.empty"));
        assertEquals("{\"id\":1}", variables.get("data.nested"));
        assertEquals("[\"a\",2]", variables.get("data.items"));
        variables.put("data.name", "changed");
        data.iterationStart(null);
        assertEquals("Zoë", variables.get("data.name"));
    }

    @Test
    void advancesRecordsClearsMissingKeysAndMarksEof() throws Exception {
        JSONDataSet data = dataset("""
                [{"name":"first","optional":"value"},{"name":"second"}]
                """);
        data.setRecycle(false);
        variables.put("unrelated", "keep");
        data.iterationStart(null);
        assertEquals("first", variables.get("name"));
        data.iterationStart(null);
        assertEquals("second", variables.get("name"));
        assertEquals("", variables.get("optional"));
        data.iterationStart(null);
        assertEquals("<EOF>", variables.get("name"));
        assertEquals("<EOF>", variables.get("optional"));
        assertEquals("keep", variables.get("unrelated"));
    }

    @Test
    void stopsAtEofAndClonesShareTheDefaultCursor() throws Exception {
        JSONDataSet data = dataset("{\"name\":\"first\"}");
        data.setRecycle(false);
        data.setStopThread(true);
        data.iterationStart(null);
        JSONDataSet copy = (JSONDataSet) data.clone();
        assertThrows(JMeterStopThreadException.class, () -> data.iterationStart(null));
        assertThrows(JMeterStopThreadException.class, () -> copy.iterationStart(null));
        assertEquals("first", variables.get("name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "42", "[]", "[{} , 1]", "{broken", "{\"a\":1} trailing"})
    void rejectsInvalidDocuments(String json) throws Exception {
        JSONDataSet data = dataset(json);
        assertThrows(JMeterStopThreadException.class, () -> data.iterationStart(null));
    }

    @Test
    void savedConfigurationReloadsAndRecyclesArray() throws Exception {
        JSONDataSet data = dataset("[{\"name\":\"first\"},{\"name\":\"second\"}]");
        data.setVariablePrefix("user.");
        data.setShareMode("shared-users");
        data.setStopThread(true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(data, output);
        JSONDataSet loaded = (JSONDataSet) SaveService.loadElement(new ByteArrayInputStream(output.toByteArray()));
        TestBeanHelper.prepare(loaded);
        assertEquals(data.getFilename(), loaded.getFilename());
        assertEquals("user.", loaded.getVariablePrefix());
        assertEquals("shared-users", loaded.getShareMode());
        loaded.iterationStart(null);
        assertEquals("first", variables.get("user.name"));
        loaded.iterationStart(null);
        assertEquals("second", variables.get("user.name"));
        loaded.iterationStart(null);
        assertEquals("first", variables.get("user.name"));
    }

    @Test
    void previewUsesAllColumnsAndDoesNotAdvanceExecution() throws Exception {
        JSONDataSet data = dataset("[{\"name\":\"first\"},{\"name\":\"second\",\"extra\":2}]");
        data.setVariablePrefix("row.");
        var preview = data.readFirstSample(1);
        assertEquals(3, preview.size());
        org.junit.jupiter.api.Assertions.assertTrue(preview.contains("${row.name} = first"));
        org.junit.jupiter.api.Assertions.assertTrue(preview.contains("${row.extra} = "));
        data.iterationStart(null);
        assertEquals("first", variables.get("row.name"));
        data.readFirstSample(10);
        data.iterationStart(null);
        assertEquals("second", variables.get("row.name"));
        assertEquals("2", variables.get("row.extra"));
    }

    @Test
    void nestedVariablesRetainRawValuesAndClearShorterArrays() throws Exception {
        JSONDataSet data = dataset("""
                [{"addresses":[{"city":"First","tags":["one","two"]},{"city":"Second"}],
                  "profile":{"enabled":true},"matrix":[[1,2],[3]]},
                 {"addresses":[{"city":"Third"}]},{"addresses":[]}]
                """);
        data.setVariablePrefix("row.");
        assertTrue(data.readFirstSample(1).contains("${row.addresses_1.city} = First"));
        data.iterationStart(null);
        assertEquals("First", variables.get("row.addresses_1.city"));
        assertEquals("Second", variables.get("row.addresses_2.city"));
        assertEquals("two", variables.get("row.addresses_1.tags_2"));
        assertEquals("2", variables.get("row.addresses_matchNr"));
        assertEquals("true", variables.get("row.profile.enabled"));
        assertEquals("2", variables.get("row.matrix_1_2"));
        assertEquals("1", variables.get("row.matrix_2_matchNr"));
        assertTrue(variables.get("row.addresses").startsWith("["));
        data.iterationStart(null);
        assertEquals("Third", variables.get("row.addresses_1.city"));
        assertEquals("", variables.get("row.addresses_2.city"));
        assertEquals("", variables.get("row.addresses_1.tags_2"));
        assertEquals("0", variables.get("row.addresses_1.tags_matchNr"));
        assertEquals("1", variables.get("row.addresses_matchNr"));
        data.iterationStart(null);
        assertEquals("0", variables.get("row.addresses_matchNr"));
        assertEquals("", variables.get("row.addresses_1.city"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":{\"b\":1},\"a.b\":2}", "{\"a\":[1],\"a_1\":2}",
            "{\"a\":[1],\"a_matchNr\":2}"})
    void rejectsFlattenedNameCollisions(String content) throws Exception {
        JSONDataSet data = dataset(content);
        assertThrows(JMeterStopThreadException.class, () -> data.iterationStart(null));
    }

    @Test
    void stopsForMissingFile() {
        JSONDataSet data = new JSONDataSet();
        data.setFilename(directory.resolve("missing.json").toString());
        assertThrows(JMeterStopThreadException.class, () -> data.iterationStart(null));
    }
}
