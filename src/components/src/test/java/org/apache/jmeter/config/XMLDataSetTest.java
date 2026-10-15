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
import java.io.IOException;
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

class XMLDataSetTest extends JMeterTestCase {
    @TempDir
    Path directory;
    private JMeterVariables variables;

    @BeforeEach
    void setupVariables() {
        variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
    }

    private XMLDataSet dataset(String xml) throws IOException {
        Path file = directory.resolve("data.xml");
        Files.writeString(file, xml);
        XMLDataSet data = new XMLDataSet();
        data.setFilename(file.toString());
        data.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        return data;
    }

    @Test
    void loadsMultipleVariablesPerRecordAndRecycles() throws Exception {
        XMLDataSet data = dataset("""
                <users><user id="1"><name>Zoë &amp; Alice</name><age>30</age><empty/>
                <roles><role>reader</role><role>writer</role></roles></user>
                <user id="2"><name>Bob</name><age>40</age></user></users>
                """);
        data.setVariablePrefix("user.");
        data.iterationStart(null);
        assertEquals("Zoë & Alice", variables.get("user.name"));
        assertEquals("30", variables.get("user.age"));
        assertEquals("1", variables.get("user.@id"));
        assertEquals("", variables.get("user.empty"));
        assertEquals("<roles><role>reader</role><role>writer</role></roles>", variables.get("user.roles"));
        data.iterationStart(null);
        assertEquals("Bob", variables.get("user.name"));
        assertEquals("40", variables.get("user.age"));
        assertEquals("", variables.get("user.roles"));
        data.iterationStart(null);
        assertEquals("Zoë & Alice", variables.get("user.name"));
    }

    @Test
    void singleRootRecordPreservesRepeatedFieldsAndAttributes() throws Exception {
        XMLDataSet data = dataset("""
                <user><name>Alice</name><tag>one</tag><tag>two</tag><price currency="EUR">12</price></user>
                """);
        data.setRecordXPath("/*");
        data.iterationStart(null);
        assertEquals("Alice", variables.get("name"));
        assertEquals("<tag>one</tag><tag>two</tag>", variables.get("tag"));
        assertEquals("<price currency=\"EUR\">12</price>", variables.get("price"));
    }

    @Test
    void previewDoesNotConsumeRowsAndEofClearsEveryVariable() throws Exception {
        XMLDataSet data = dataset("<rows><row><a>1</a></row><row><a>2</a><b>3</b></row></rows>");
        data.setRecycle(false);
        assertTrue(data.readFirstSample(1).contains("${b} = "));
        data.iterationStart(null);
        assertEquals("1", variables.get("a"));
        data.readFirstSample(10);
        data.iterationStart(null);
        assertEquals("2", variables.get("a"));
        assertEquals("3", variables.get("b"));
        data.iterationStart(null);
        assertEquals("<EOF>", variables.get("a"));
        assertEquals("<EOF>", variables.get("b"));
    }

    @Test
    void clonesShareTheDefaultCursorAndStopAtEof() throws Exception {
        XMLDataSet data = dataset("<rows><row><name>Alice</name></row></rows>");
        data.setRecycle(false);
        data.setStopThread(true);
        data.iterationStart(null);
        XMLDataSet copy = (XMLDataSet) data.clone();
        assertThrows(JMeterStopThreadException.class, () -> data.iterationStart(null));
        assertThrows(JMeterStopThreadException.class, () -> copy.iterationStart(null));
        assertEquals("Alice", variables.get("name"));
    }

    @Test
    void xpathSupportsNestedAndNamespacedRecords() throws Exception {
        XMLDataSet data = dataset("""
                <root xmlns:u="urn:users"><wrapper><u:user><u:name>Alice</u:name></u:user></wrapper></root>
                """);
        data.setRecordXPath("//*[local-name()='user']");
        data.iterationStart(null);
        assertEquals("Alice", variables.get("u:name"));
    }

    @Test
    void scalarRecordExposesText() throws Exception {
        XMLDataSet data = dataset("<values><value>one</value><value><![CDATA[two & three]]></value></values>");
        data.iterationStart(null);
        assertEquals("one", variables.get("#text"));
        data.iterationStart(null);
        assertEquals("two & three", variables.get("#text"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/missing", "//*[", "//@id", "count(//row)"})
    void rejectsInvalidOrNonElementRecordSelection(String xpath) throws Exception {
        XMLDataSet data = dataset("<rows><row id=\"1\"/></rows>");
        data.setRecordXPath(xpath);
        assertThrows(JMeterStopThreadException.class, () -> data.iterationStart(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<broken>", "<rows/>",
            "<!DOCTYPE rows [<!ENTITY value 'expanded'>]><rows><row>&value;</row></rows>"})
    void rejectsMalformedEmptyAndDoctypeDocuments(String xml) throws Exception {
        XMLDataSet data = dataset(xml);
        assertThrows(JMeterStopThreadException.class, () -> data.iterationStart(null));
    }

    @Test
    void externalEntitiesAreRejectedInPreviewAndExecution() throws Exception {
        Path secret = directory.resolve("secret.txt");
        Files.writeString(secret, "must-not-be-loaded");
        XMLDataSet data = dataset("<!DOCTYPE rows [<!ENTITY value SYSTEM '" + secret.toUri()
                + "'>]><rows><row>&value;</row></rows>");
        assertThrows(IOException.class, () -> data.readFirstSample(10));
        assertThrows(JMeterStopThreadException.class, () -> data.iterationStart(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-8", "UTF-16", "ISO-8859-1"})
    void respectsXmlEncodingForExecutionAndEditing(String charset) throws Exception {
        XMLDataSet data = dataset("<unused/>");
        String xml = "<?xml version=\"1.0\" encoding=\"" + charset
                + "\"?><rows><row><name>Zoë</name></row></rows>";
        byte[] bytes = xml.getBytes(java.nio.charset.Charset.forName(charset));
        Path path = Path.of(data.getFilename());
        Files.write(path, bytes);
        data.iterationStart(null);
        assertEquals("Zoë", variables.get("name"));
        CsvFileEditor editor = CsvFileEditor.fromBytes(path, XMLDataSet.fileEncoding(bytes), bytes);
        byte[] edited = editor.encode(editor.toFileText(editor.getEditorText().replace("Zoë", "René")));
        assertEquals("René", data.parseRecords(edited).get(0).get("name"));
    }

    @Test
    void configurationSurvivesSaveAndReload() throws Exception {
        XMLDataSet data = dataset("<user><name>Alice</name></user>");
        data.setRecordXPath("/*");
        data.setVariablePrefix("user.");
        data.setShareMode("shareMode.group");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveElement(data, output);
        XMLDataSet loaded = (XMLDataSet) SaveService.loadElement(new ByteArrayInputStream(output.toByteArray()));
        TestBeanHelper.prepare(loaded);
        assertEquals("/*", loaded.getRecordXPath());
        assertEquals("shareMode.group", loaded.getShareMode());
        loaded.iterationStart(null);
        assertEquals("Alice", variables.get("user.name"));
    }

    @Test
    void nestedXmlVariablesUseStableIndexesAcrossRows() throws Exception {
        XMLDataSet data = dataset("""
                <users><user><addresses><item id="s"><city>First</city></item>
                <item><city>Second</city></item></addresses><roles><role>A</role><role>B</role></roles></user>
                <user><addresses><item><city>Third</city></item></addresses><roles><role>C</role></roles></user>
                <user><addresses/></user></users>
                """);
        assertTrue(data.readFirstSample(1).contains("${addresses.item_1.city} = First"));
        data.iterationStart(null);
        assertEquals("First", variables.get("addresses.item_1.city"));
        assertEquals("s", variables.get("addresses.item_1.@id"));
        assertEquals("Second", variables.get("addresses.item_2.city"));
        assertEquals("2", variables.get("addresses.item_matchNr"));
        assertEquals("B", variables.get("roles.role_2"));
        assertTrue(variables.get("addresses").startsWith("<addresses>"));
        data.iterationStart(null);
        assertEquals("Third", variables.get("addresses.item_1.city"));
        assertEquals("", variables.get("addresses.item_1.@id"));
        assertEquals("", variables.get("addresses.item_2.city"));
        assertEquals("1", variables.get("addresses.item_matchNr"));
        assertEquals("1", variables.get("roles.role_matchNr"));
        assertEquals("C", variables.get("roles.role_1"));
        assertEquals("", variables.get("roles.role_2"));
        data.iterationStart(null);
        assertEquals("0", variables.get("addresses.item_matchNr"));
        assertEquals("", variables.get("addresses.item_1.city"));
    }

    @Test
    void differentRecordXpathsUseIndependentPositions() throws Exception {
        XMLDataSet data = dataset("<root><a><id>first</id></a><b><id>second</id></b></root>");
        data.setRecordXPath("/root/a");
        data.setRecycle(false);
        XMLDataSet other = (XMLDataSet) data.clone();
        other.setRecordXPath("/root/b");
        data.iterationStart(null);
        assertEquals("first", variables.get("id"));
        other.iterationStart(null);
        assertEquals("second", variables.get("id"));
    }

    @Test
    void missingFileStopsThread() {
        XMLDataSet data = new XMLDataSet();
        data.setFilename(directory.resolve("missing.xml").toString());
        assertThrows(JMeterStopThreadException.class, () -> data.iterationStart(null));
    }
}
