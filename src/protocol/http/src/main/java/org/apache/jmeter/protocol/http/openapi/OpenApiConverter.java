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
    /** OpenAPI 3 says header parameters with these names SHALL be ignored. */
    private static final Set<String> IGNORED_HEADERS = Set.of("accept", "content-type", "authorization");
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String MULTIPART = "multipart/form-data";
    private final JsonNode document;
    private final boolean swagger;
    private final OpenApiSampleValues sampleValues = new OpenApiSampleValues();
    private final List<String> warnings = new ArrayList<>();
    private String basePath = "";

    public OpenApiConverter(JsonNode document) {
        this.document = document;
        this.swagger = document != null && "2.0".equals(document.path("swagger").asText());
    }

    public static OpenApiConverter load(Path file) throws IOException {
        return new OpenApiConverter(JSON.readTree(file.toFile()));
    }

    /** Operations imported without a generated body or file fields, for review after import. */
    public List<String> getWarnings() {
        return List.copyOf(warnings);
    }

    public HashTree convert(String fileName) {
        if (document == null || !(document.path("openapi").asText().startsWith("3.") || swagger)
                || !document.path("paths").isObject()) {
            throw new IllegalArgumentException("Expected an OpenAPI 3.x or Swagger 2.0 JSON document with paths.");
        }
        basePath = basePath();
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

    /** The path part of Swagger's basePath or the first OpenAPI server; host, protocol and port are inherited. */
    private String basePath() {
        String base;
        if (swagger) {
            base = document.path("basePath").asText("");
        } else {
            JsonNode server = document.path("servers").path(0);
            base = server.path("url").asText("");
            for (Map.Entry<String, JsonNode> variable : server.path("variables").properties()) {
                base = base.replace("{" + variable.getKey() + "}", variable.getValue().path("default").asText());
            }
            int scheme = base.indexOf("://");
            if (scheme >= 0) {
                int slash = base.indexOf('/', scheme + 3);
                base = slash < 0 ? "" : base.substring(slash);
            }
        }
        base = base.replaceAll("/+$", "");
        return base.isEmpty() || base.startsWith("/") ? base : "/" + base;
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
        String request = method.toUpperCase(Locale.ROOT) + " " + path;
        List<String> query = new ArrayList<>();
        List<String> cookies = new ArrayList<>();
        List<String> fileFields = new ArrayList<>();
        boolean formData = false;
        for (JsonNode parameter : parameters.values()) {
            String name = parameter.path("name").asText();
            String in = parameter.path("in").asText();
            if (!swagger && "header".equals(in) && IGNORED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            JsonNode value = parameterValue(parameter, name);
            switch (in) {
            case "path" -> path = path.replace("{" + name + "}", encode(flatten(value, ",")));
            case "query" -> addQuery(query, name, value, parameter);
            case "header" -> {
                headers.removeIf(header -> header.getName().equalsIgnoreCase(name));
                headers.add(new Header(name, flatten(value, ",")));
            }
            case "cookie" -> cookies.add(encode(name) + "=" + encode(flatten(value, ",")));
            case "body" -> setBody(sampler, headers, value, requestContentType(operation));
            case "formData" -> {
                formData = true;
                if ("file".equals(parameter.path("type").asText())) {
                    fileFields.add(name);
                } else {
                    sampler.addArgument(name, flatten(value, ","));
                }
            }
            default -> throw new IllegalArgumentException("Unsupported parameter location: " + in);
            }
        }
        if (formData) {
            setForm(sampler, headers, !fileFields.isEmpty() || consumes(operation, MULTIPART) ? MULTIPART : FORM);
        }
        if (!cookies.isEmpty()) {
            headers.add(new Header("Cookie", String.join("; ", cookies)));
        }
        JsonNode body = resolve(operation.path("requestBody"));
        if (body.has("content")) {
            addRequestBody(sampler, headers, body.path("content"), request, fileFields);
        }
        if (!fileFields.isEmpty()) {
            warnings.add(request + ": add files for " + String.join(", ", fileFields));
        }
        addDefaultHeader(headers, "User-Agent", "BreakTest");
        addDefaultHeader(headers, "Accept", responseContentTypes(operation));
        // Keep query samples in the path for search/replace, separate from body arguments.
        sampler.setProperty(HTTPSamplerProxy.PATH,
                basePath + path + (query.isEmpty() ? "" : "?" + String.join("&", query)));
        sampler.setNativeHeaders(headers);
        target.add(sampler);
    }

    private static void addDefaultHeader(List<Header> headers, String name, String value) {
        if (headers.stream().noneMatch(header -> header.getName().equalsIgnoreCase(name))) {
            headers.add(new Header(name, value));
        }
    }

    private JsonNode consumes(JsonNode operation) {
        return operation.has("consumes") ? operation.path("consumes") : document.path("consumes");
    }

    private boolean consumes(JsonNode operation, String type) {
        for (JsonNode consumed : consumes(operation)) {
            if (type.equals(mediaType(consumed.asText()))) {
                return true;
            }
        }
        return false;
    }

    private String requestContentType(JsonNode operation) {
        JsonNode consumes = consumes(operation);
        for (JsonNode type : consumes) {
            if (isJson(type.asText())) {
                return type.asText();
            }
        }
        return consumes.path(0).asText("application/json");
    }

    private void addRequestBody(HTTPSamplerProxy sampler, List<Header> headers, JsonNode content, String request,
            List<String> fileFields) {
        Map.Entry<String, JsonNode> media = chooseMedia(content);
        if (media == null) {
            warnings.add(request + ": request body has no media type");
            return;
        }
        String type = mediaType(media.getKey());
        if (!isJson(type) && !type.startsWith("text/") && !type.equals(FORM) && !type.equals(MULTIPART)) {
            // Keep the request so the rest of the API still imports; the body has to be supplied by hand.
            addDefaultHeader(headers, "Content-Type", media.getKey());
            warnings.add(request + ": add the " + media.getKey() + " request body");
            return;
        }
        JsonNode schema = resolve(media.getValue().path("schema"));
        JsonNode value = sample(schema, example(media.getValue()), new HashSet<>(), 0, "body");
        if (type.equals(FORM) || type.equals(MULTIPART)) {
            value.properties().forEach(field -> {
                if (type.equals(MULTIPART) && isFile(schema.path("properties").path(field.getKey()))) {
                    fileFields.add(field.getKey());
                } else {
                    sampler.addArgument(field.getKey(), flatten(field.getValue(), ","));
                }
            });
            setForm(sampler, headers, type);
        } else {
            setBody(sampler, headers, value, media.getKey());
        }
    }

    private boolean isFile(JsonNode property) {
        JsonNode schema = resolve(property);
        if ("array".equals(schema.path("type").asText())) {
            schema = resolve(schema.path("items"));
        }
        String format = schema.path("format").asText();
        return "file".equals(schema.path("type").asText()) || "binary".equals(format) || "base64".equals(format)
                || schema.has("contentMediaType");
    }

    private static void setForm(HTTPSamplerProxy sampler, List<Header> headers, String type) {
        if (type.equals(MULTIPART)) {
            // JMeter generates the multipart Content-Type with its boundary.
            sampler.setDoMultipart(true);
        } else {
            addDefaultHeader(headers, "Content-Type", type);
        }
    }

    private static String mediaType(String contentType) {
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isJson(String contentType) {
        String type = mediaType(contentType);
        return type.equals("application/json") || type.endsWith("+json");
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
            if (isJson(entry.getKey())) {
                return entry;
            }
        }
        return first;
    }

    private static void setBody(HTTPSamplerProxy sampler, List<Header> headers, JsonNode value, String contentType) {
        sampler.setPostBodyRaw(true);
        sampler.addNonEncodedArgument("", isJson(contentType) ? value.toPrettyString() : text(value), "");
        addDefaultHeader(headers, "Content-Type", contentType);
    }

    private void collectParameters(Map<String, JsonNode> result, JsonNode parameters) {
        for (JsonNode parameter : parameters) {
            JsonNode resolved = resolve(parameter);
            result.put(resolved.path("in").asText() + ":" + resolved.path("name").asText(), resolved);
        }
    }

    private JsonNode parameterValue(JsonNode parameter, String name) {
        Iterator<Map.Entry<String, JsonNode>> content = parameter.path("content").properties().iterator();
        if (!content.hasNext()) {
            JsonNode schema = parameter.has("schema") ? parameter.path("schema") : parameter;
            return sample(schema, example(parameter), new HashSet<>(), 0, name);
        }
        // A content parameter is sent as one value serialized with its media type, e.g. JSON in a query string.
        JsonNode media = content.next().getValue();
        JsonNode supplied = example(parameter);
        JsonNode value = sample(media.path("schema"), supplied == null ? example(media) : supplied,
                new HashSet<>(), 0, name);
        return value.isValueNode() ? value : JSON.getNodeFactory().textNode(value.toString());
    }

    private void addQuery(List<String> query, String name, JsonNode value, JsonNode parameter) {
        String style = parameter.path("style").asText("form");
        boolean explode = parameter.path("explode").asBoolean("form".equals(style));
        String format = parameter.path("collectionFormat").asText(swagger ? "csv" : "");
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
        if (typeNode.isArray()) {
            // OpenAPI 3.1 nullable types such as ["null", "string"]: sample the non-null alternative.
            for (JsonNode alternative : typeNode) {
                if (!"null".equals(alternative.asText())) {
                    type = alternative.asText();
                    break;
                }
            }
        }
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
