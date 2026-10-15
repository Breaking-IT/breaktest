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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.services.FileServer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Loads selected XML elements as variable records, advancing once per thread iteration. */
@GUIMenuSortOrder(8)
@TestElementMetadata(labelResource = "displayName")
public class XMLDataSet extends StructuredDataSet {
    private static final long serialVersionUID = 1L;

    @Override
    protected List<Map<String, String>> loadDataRecords() throws IOException {
        return parseRecords(readXmlContent());
    }

    @Override
    protected String recordSource() throws IOException {
        return isUseXmlFromArchive() ? "archive:" + archiveEntry()
                : resolveXmlFile().toRealPath().toString();
    }

    @Override
    protected String recordSelection() {
        return getRecordXPath();
    }

    private static Document parseDocument(byte[] content) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return builder.parse(new ByteArrayInputStream(content));
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException e) {
            throw new IOException("Invalid XML data: " + e.getMessage(), e);
        }
    }

    static String fileEncoding(byte[] content) throws IOException {
        if (content.length == 0) {
            return "UTF-8";
        }
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try {
            // Read only the declaration so malformed documents can still be opened for repair.
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(content));
            try {
                String encoding = reader.getCharacterEncodingScheme();
                if (encoding == null) {
                    encoding = reader.getEncoding();
                }
                return encoding == null ? "UTF-8" : encoding;
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("Unable to determine XML encoding: " + e.getMessage(), e);
        }
    }

    List<Map<String, String>> parseRecords(byte[] content) throws IOException {
        Document document = parseDocument(content);
        if (getRecordXPath().isBlank()) {
            throw new IOException("Record XPath must not be empty");
        }
        try {
            var xpathFactory = XPathFactory.newDefaultInstance();
            xpathFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            NodeList selected = (NodeList) xpathFactory.newXPath().evaluate(
                    getRecordXPath(), document, XPathConstants.NODESET);
            if (selected.getLength() == 0) {
                throw new IOException("Record XPath did not select any elements: " + getRecordXPath());
            }
            TransformerFactory factory = TransformerFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer serializer = factory.newTransformer();
            serializer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            Set<String> lists = new LinkedHashSet<>();
            for (int i = 0; i < selected.getLength(); i++) {
                if (!(selected.item(i) instanceof Element element)) {
                    throw new IOException("Record XPath must select elements only");
                }
                XmlDataVariables.collectLists(element, "", lists, 0);
            }
            List<Map<String, String>> loaded = new ArrayList<>();
            for (int i = 0; i < selected.getLength(); i++) {
                Map<String, String> record = new LinkedHashMap<>();
                XmlDataVariables.flatten((Element) selected.item(i), "", "", lists, record, serializer, 0);
                loaded.add(record);
            }
            return loaded;
        } catch (XPathExpressionException | javax.xml.xpath.XPathFactoryConfigurationException
                | TransformerException | IllegalArgumentException e) {
            throw new IOException("Unable to read XML records: " + e.getMessage(), e);
        }
    }

    public String getRecordXPath() {
        return getPropertyAsString("recordXPath", "/*/*");
    }

    public void setRecordXPath(String xpath) {
        setProperty("recordXPath", xpath, "/*/*");
    }

    public List<String> readFirstSample(int sampleCount) throws IOException {
        return readFirstSample(sampleCount, readXmlContent());
    }

    List<String> readFirstSample(int sampleCount, byte[] content) throws IOException {
        List<Map<String, String>> samples = parseRecords(content);
        Set<String> names = new LinkedHashSet<>();
        samples.forEach(record -> names.addAll(record.keySet()));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(sampleCount, samples.size()); i++) {
            Map<String, String> record = samples.get(i);
            for (String name : names) {
                lines.add("${" + getVariablePrefix() + name + "} = " + record.getOrDefault(name, StructuredDataVariables.missingValue(name)));
            }
            lines.add("------------");
        }
        return lines;
    }

    Path resolveXmlFile() {
        if (getFilename().isBlank()) {
            throw new IllegalArgumentException("Filename must not be empty");
        }
        return FileServer.getFileServer().resolveFile(getFilename().trim()).toPath();
    }

    String archiveEntry() {
        return ArchiveFiles.entryName(getFilename());
    }

    byte[] readXmlContent() throws IOException {
        return isUseXmlFromArchive() ? ArchiveFiles.read(archiveEntry()) : Files.readAllBytes(resolveXmlFile());
    }

    void storeArchivedXml(byte[] content) throws IOException {
        parseRecords(content);
        if (ArchiveFiles.currentPlan() == null) {
            throw new IOException("No test plan is available for the XML archive");
        }
        String entry = isUseXmlFromArchive() ? archiveEntry() : ArchiveFiles.importEntryName(getFilename());
        ArchiveFiles.put(ArchiveFiles.currentPlan(), entry, content, isUseXmlFromArchive());
        setFilename(entry.substring("files/".length()));
        setUseXmlFromArchive(true);
    }

    public boolean isUseXmlFromArchive() {
        return getPropertyAsBoolean("useXmlFromArchive");
    }

    public void setUseXmlFromArchive(boolean useArchive) {
        setProperty("useXmlFromArchive", useArchive, false);
    }

}
