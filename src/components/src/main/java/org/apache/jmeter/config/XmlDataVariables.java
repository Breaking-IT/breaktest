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

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class XmlDataVariables {
    private XmlDataVariables() {
    }

    private static Map<String, List<Element>> fields(Element element) {
        Map<String, List<Element>> fields = new LinkedHashMap<>();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                fields.computeIfAbsent(child.getTagName(), ignored -> new ArrayList<>()).add(child);
            }
        }
        return fields;
    }

    static void collectLists(Element element, String path, Set<String> lists, int depth) {
        checkDepth(depth);
        fields(element).forEach((name, children) -> {
            String childPath = path + name;
            // Recognize repeated siblings across the whole file, including singleton rows.
            // Generic item containers also remain indexed when they contain only one item.
            if (children.size() > 1 || "item".equals(children.get(0).getLocalName())) {
                lists.add(childPath);
            }
            children.forEach(child -> collectLists(child, childPath + ".", lists, depth + 1));
        });
    }

    static void flatten(Element element, String prefix, String path, Set<String> lists,
            Map<String, String> record, Transformer serializer, int depth) throws TransformerException {
        checkDepth(depth);
        var attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                StructuredDataVariables.put(record, prefix + "@" + attribute.getNodeName(), attribute.getNodeValue());
            }
        }
        Map<String, List<Element>> fields = fields(element);
        for (var field : fields.entrySet()) {
            String name = field.getKey();
            List<Element> children = field.getValue();
            String childPath = path + name;
            String variable = prefix + name;
            boolean indexed = lists.contains(childPath);
            StructuredDataVariables.put(record, variable, children.size() == 1
                    ? value(children.get(0), serializer) : combined(children, serializer));
            if (indexed) {
                StructuredDataVariables.put(record, variable + "_matchNr", Integer.toString(children.size()));
            }
            for (int i = 0; i < children.size(); i++) {
                Element child = children.get(i);
                String childVariable = indexed ? variable + "_" + (i + 1) : variable;
                if (indexed) {
                    StructuredDataVariables.put(record, childVariable, value(child, serializer));
                }
                if (child.hasAttributes() || !fields(child).isEmpty()) {
                    flatten(child, childVariable + ".", childPath + ".", lists, record, serializer, depth + 1);
                }
            }
        }
        if (fields.isEmpty()) {
            StructuredDataVariables.put(record, prefix + "#text", element.getTextContent());
        }
    }

    private static String combined(List<Element> children, Transformer serializer) throws TransformerException {
        StringWriter combined = new StringWriter();
        for (Element child : children) {
            serializer.transform(new DOMSource(child), new StreamResult(combined));
        }
        return combined.toString();
    }

    private static String value(Element element, Transformer serializer) throws TransformerException {
        if (!element.hasAttributes() && fields(element).isEmpty()) {
            return element.getTextContent();
        }
        StringWriter output = new StringWriter();
        serializer.transform(new DOMSource(element), new StreamResult(output));
        return output.toString();
    }

    private static void checkDepth(int depth) {
        if (depth > 100) {
            throw new IllegalArgumentException("XML data exceeds 100 nesting levels");
        }
    }
}
