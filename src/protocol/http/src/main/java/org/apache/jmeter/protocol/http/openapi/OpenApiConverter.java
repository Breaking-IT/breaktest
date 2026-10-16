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

package org.apache.jmeter.protocol.http.openapi;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.control.gui.LogicControllerGui;
import org.apache.jmeter.control.gui.TestFragmentControllerGui;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Converts local OpenAPI JSON into editable sample requests without contacting the API. */
public final class OpenApiConverter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private final JsonNode document;
    private final OpenApiSampleValues sampleValues = new OpenApiSampleValues();

    public OpenApiConverter(JsonNode document) {
        this.document = document;
    }

    public static HashTree read(Path file) throws IOException {
        return new OpenApiConverter(JSON.readTree(file.toFile())).convert(file.getFileName().toString());
    }

    public HashTree convert(String fileName) {
        if (document == null || !(document.path("openapi").asText().startsWith("3.")
                || "2.0".equals(document.path("swagger").asText())) || !document.path("paths").isObject()) {
            throw new IllegalArgumentException("Expected an OpenAPI 3.x or Swagger 2.0 JSON document with paths.");
        }
        TestFragmentController fragment = configure(new TestFragmentController(),
                document.path("info").path("title").asText(fileName), TestFragmentControllerGui.class);
        HashTree tree = new ListedHashTree();
        HashTree requests = tree.add(fragment);
        Map<String, HashTree> groups = new LinkedHashMap<>();
        document.path("paths").properties().forEach(path -> {
            if (!path.getKey().startsWith("/")) {
                return;
            }
            JsonNode item = resolve(path.getValue());
            item.properties().forEach(operation -> {
                if (!METHODS.contains(operation.getKey())) {
                    return;
                }
                JsonNode op = operation.getValue();
                String group = op.path("tags").path(0).asText();
                if (group.isBlank()) {
                    String[] parts = path.getKey().split("/");
                    group = parts.length > 1 ? parts[1] : "Endpoints";
                }
                HashTree target = groups.computeIfAbsent(group,
                        name -> requests.add(configure(new GenericController(), name, LogicControllerGui.class)));
                addRequest(target, path.getKey(), operation.getKey(), item, op);
            });
        });
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("The OpenAPI document contains no HTTP operations.");
        }
        return tree;
    }

    private void addRequest(HashTree target, String path, String method, JsonNode item, JsonNode operation) {
        HTTPSamplerProxy sampler = configure(new HTTPSamplerProxy(),
                method.toUpperCase(Locale.ROOT) + " " + path, HttpTestSampleGui.class);
        sampler.setMethod(method.toUpperCase(Locale.ROOT));
        sampler.setUseKeepAlive(true);
        sampler.setFollowRedirects(true);
        sampler.setContentEncoding(StandardCharsets.UTF_8.name());
        sampler.setComment(operation.path("summary").asText(operation.path("description").asText()));
        // Do not set domain, protocol or port: HTTP Request Defaults supplies them at runtime.
        List<Header> headers = new ArrayList<>();
        Map<String, JsonNode> parameters = new LinkedHashMap<>();
        collectParameters(parameters, item.path("parameters"));
        collectParameters(parameters, operation.path("parameters"));
        List<String> query = new ArrayList<>();
        List<String> cookies = new ArrayList<>();
        for (JsonNode parameter : parameters.values()) {
            String name = parameter.path("name").asText();
            JsonNode schema = parameter.has("schema") ? parameter.path("schema") : parameter;
            JsonNode value = sample(schema, example(parameter), new HashSet<>(), 0, name);
            switch (parameter.path("in").asText()) {
            case "path" -> path = path.replace("{" + name + "}", encode(flatten(value, ",")));
            case "query" -> addQuery(query, name, value, parameter);
            case "header" -> {
                headers.removeIf(header -> header.getName().equalsIgnoreCase(name));
                headers.add(new Header(name, flatten(value, ",")));
            }
            case "cookie" -> cookies.add(encode(name) + "=" + encode(flatten(value, ",")));
            case "body" -> setBody(sampler, headers, value, requestContentType(operation));
            case "formData" -> {
                sampler.addArgument(name, flatten(value, ","));
                addDefaultHeader(headers, "Content-Type", "application/x-www-form-urlencoded");
            }
            default -> throw new IllegalArgumentException("Unsupported parameter location: " + parameter.path("in").asText());
            }
        }
        if (!cookies.isEmpty()) {
            headers.add(new Header("Cookie", String.join("; ", cookies)));
        }
        JsonNode body = resolve(operation.path("requestBody"));
        if (body.has("content")) {
            Map.Entry<String, JsonNode> media = chooseMedia(body.path("content"));
            JsonNode value = sample(media.getValue().path("schema"), example(media.getValue()), new HashSet<>(), 0, "body");
            if (media.getKey().equals("application/x-www-form-urlencoded")) {
                value.properties().forEach(field -> sampler.addArgument(field.getKey(), flatten(field.getValue(), ",")));
                addDefaultHeader(headers, "Content-Type", media.getKey());
            } else {
                setBody(sampler, headers, value, media.getKey());
            }
        }
        addDefaultHeader(headers, "User-Agent", "BreakTest");
        addDefaultHeader(headers, "Accept", responseContentTypes(operation));
        // Keep query samples in the path for search/replace, separate from body arguments.
        sampler.setProperty(HTTPSamplerProxy.PATH, path + (query.isEmpty() ? "" : "?" + String.join("&", query)));
        sampler.setNativeHeaders(headers);
        target.add(sampler);
    }

    private static void addDefaultHeader(List<Header> headers, String name, String value) {
        if (headers.stream().noneMatch(header -> header.getName().equalsIgnoreCase(name))) {
            headers.add(new Header(name, value));
        }
    }

    private String requestContentType(JsonNode operation) {
        JsonNode consumes = operation.has("consumes") ? operation.path("consumes") : document.path("consumes");
        for (JsonNode type : consumes) {
            if ("application/json".equals(type.asText())) {
                return type.asText();
            }
        }
        return consumes.path(0).asText("application/json");
    }

    private String responseContentTypes(JsonNode operation) {
        Set<String> types = new LinkedHashSet<>();
        JsonNode produces = operation.has("produces") ? operation.path("produces") : document.path("produces");
        produces.forEach(type -> types.add(type.asText()));
        operation.path("responses").properties().forEach(response -> {
            if (response.getKey().startsWith("2")) {
                resolve(response.getValue()).path("content").fieldNames().forEachRemaining(types::add);
            }
        });
        if (types.isEmpty()) {
            resolve(operation.path("responses").path("default")).path("content").fieldNames().forEachRemaining(types::add);
        }
        return types.isEmpty() ? "*/*" : String.join(", ", types);
    }

    private static Map.Entry<String, JsonNode> chooseMedia(JsonNode content) {
        Iterator<Map.Entry<String, JsonNode>> fields = content.properties().iterator();
        Map.Entry<String, JsonNode> first = null;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (first == null) {
                first = entry;
            }
            if (entry.getKey().equals("application/json") || entry.getKey().endsWith("+json")) {
                return entry;
            }
        }
        if (first == null) {
            throw new IllegalArgumentException("Request body content is empty.");
        }
        if (!first.getKey().equals("application/x-www-form-urlencoded") && !first.getKey().startsWith("text/")) {
            throw new IllegalArgumentException("Unsupported request body media type: " + first.getKey());
        }
        return first;
    }

    private static void setBody(HTTPSamplerProxy sampler, List<Header> headers, JsonNode value, String contentType) {
        sampler.setPostBodyRaw(true);
        sampler.addNonEncodedArgument("", contentType.contains("json") ? value.toPrettyString() : text(value), "");
        addDefaultHeader(headers, "Content-Type", contentType);
    }

    private void collectParameters(Map<String, JsonNode> result, JsonNode parameters) {
        for (JsonNode parameter : parameters) {
            JsonNode resolved = resolve(parameter);
            result.put(resolved.path("in").asText() + ":" + resolved.path("name").asText(), resolved);
        }
    }

    private static void addQuery(List<String> query, String name, JsonNode value, JsonNode parameter) {
        String style = parameter.path("style").asText("form");
        boolean explode = parameter.path("explode").asBoolean("form".equals(style));
        String format = parameter.path("collectionFormat").asText(parameter.has("schema") ? "" : "csv");
        if (!format.isEmpty()) {
            explode = format.equals("multi");
        }
        if (value.isObject() && !explode && !style.equals("deepObject")) {
            query.add(encode(name) + "=" + encode(flatten(value, ",")));
        } else if (value.isObject()) {
            value.properties().forEach(field -> query.add(encode(style.equals("deepObject")
                    ? name + "[" + field.getKey() + "]" : field.getKey()) + "=" + encode(text(field.getValue()))));
        } else if (value.isArray() && explode) {
            value.forEach(entry -> query.add(encode(name) + "=" + encode(text(entry))));
        } else {
            String delimiter = style.equals("spaceDelimited") || format.equals("ssv") ? " "
                    : style.equals("pipeDelimited") || format.equals("pipes") ? "|" : format.equals("tsv") ? "\t" : ",";
            query.add(encode(name) + "=" + encode(flatten(value, delimiter)));
        }
    }

    private JsonNode resolve(JsonNode node) {
        Set<String> seen = new HashSet<>();
        while (node.has("$ref")) {
            String ref = node.path("$ref").asText();
            if (!seen.add(ref)) {
                throw new IllegalArgumentException("Circular OpenAPI reference: " + ref);
            }
            node = reference(ref);
        }
        return node;
    }

    private JsonNode reference(String ref) {
        if (!ref.startsWith("#/")) {
            throw new IllegalArgumentException("Only references within this JSON file are supported: " + ref);
        }
        JsonNode result = document.at(ref.substring(1));
        if (result.isMissingNode()) {
            throw new IllegalArgumentException("Unresolved OpenAPI reference: " + ref);
        }
        return result;
    }

    private JsonNode example(JsonNode node) {
        if (node.has("example")) {
            return node.get("example");
        }
        JsonNode examples = node.path("examples");
        if (examples.isArray() && !examples.isEmpty()) {
            return examples.get(0);
        }
        if (examples.isObject() && !examples.isEmpty()) {
            return resolve(examples.elements().next()).get("value");
        }
        return null;
    }

    private JsonNode sample(JsonNode schema, JsonNode supplied, Set<String> refs, int depth, String field) {
        if (depth > 40) {
            return JSON.nullNode();
        }
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            if (!refs.add(ref)) {
                return supplied == null ? JSON.nullNode() : supplied.deepCopy();
            }
            JsonNode value = sample(reference(ref), supplied, refs, depth + 1, field);
            refs.remove(ref);
            return value;
        }
        JsonNode explicit = supplied == null ? example(schema) : supplied;
        if (explicit == null) {
            explicit = schema.get("default");
        }
        if (explicit == null) {
            explicit = schema.get("const");
        }
        if (explicit == null && !schema.path("enum").isEmpty()) {
            explicit = schema.path("enum").get(0);
        }
        for (String choice : List.of("oneOf", "anyOf")) {
            if (schema.path(choice).isArray() && !schema.path(choice).isEmpty()) {
                return sample(schema.path(choice).get(0), explicit, refs, depth + 1, field);
            }
        }
        JsonNode typeNode = schema.path("type");
        String type = typeNode.isArray() ? typeNode.path(0).asText() : typeNode.asText();
        if (schema.has("properties") || schema.has("allOf") || "object".equals(type)) {
            return objectSample(schema, explicit, refs, depth, field);
        }

        if ("array".equals(type) && explicit != null && explicit.isArray()) {
            var values = JSON.createArrayNode();
            for (JsonNode entry : explicit) {
                values.add(sample(schema.path("items"), entry, refs, depth + 1, field));
            }
            return values;
        }
        if (explicit != null) {
            return explicit.deepCopy();
        }
        return "array".equals(type)
                ? JSON.createArrayNode().add(sample(schema.path("items"), null, refs, depth + 1, field))
                : sampleValues.create(schema, type, field);
    }

    private JsonNode objectSample(JsonNode schema, JsonNode explicit, Set<String> refs, int depth, String field) {
        if (explicit != null && !explicit.isObject()) {
            return explicit.deepCopy();
        }
        ObjectNode value = explicit == null ? JSON.createObjectNode() : (ObjectNode) explicit.deepCopy();
        for (JsonNode part : schema.path("allOf")) {
            JsonNode component = sample(part, explicit, refs, depth + 1, field);
            if (component.isObject()) {
                value.setAll((ObjectNode) component);
            } else if (!schema.has("properties")) {
                // allOf is also used to wrap scalar and enum references, especially by .NET APIs.
                return component;
            }
        }
        schema.path("properties").properties().forEach(property -> {
            JsonNode propertySchema = resolve(property.getValue());
            if (propertySchema.path("readOnly").asBoolean()) {
                value.remove(property.getKey());
            } else {
                value.set(property.getKey(), sample(property.getValue(), value.get(property.getKey()), refs, depth + 1, property.getKey()));
            }
        });
        return value;
    }

    private static String flatten(JsonNode value, String delimiter) {
        if (!value.isContainerNode()) {
            return text(value);
        }
        List<String> parts = new ArrayList<>();
        if (value.isObject()) {
            value.properties().forEach(field -> {
                parts.add(field.getKey());
                parts.add(text(field.getValue()));
            });
        } else {
            value.forEach(entry -> parts.add(text(entry)));
        }
        return String.join(delimiter, parts);
    }

    private static String text(JsonNode value) {
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static <T extends TestElement> T configure(T element, String name, Class<?> gui) {
        element.setName(name);
        element.setEnabled(true);
        element.setProperty(TestElement.TEST_CLASS, element.getClass().getName());
        element.setProperty(TestElement.GUI_CLASS, gui.getName());
        return element;
    }
}
