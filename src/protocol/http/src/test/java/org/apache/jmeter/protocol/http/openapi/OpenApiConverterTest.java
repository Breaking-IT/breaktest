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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiConverterTest extends JMeterTestCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SPEC = """
            {
              "openapi": "3.0.4", "info": {"title": "Accounts"},
              "servers": [{"url":"https://ignore.example:8443/base"}],
              "paths": {
                "/accounts/{id}": {
                  "parameters": [
                    {"name":"id","in":"path","schema":{"type":"string","example":"a/b"}},
                    {"name":"limit","in":"query","schema":{"type":"integer","default":10}}
                  ],
                  "post": {
                    "tags":["Players","Other"], "summary":"Update account",
                    "parameters": [
                      {"name":"limit","in":"query","schema":{"type":"integer","default":20}},
                      {"$ref":"#/components/parameters/Actor"},
                      {"name":"session","in":"cookie","schema":{"type":"string","example":"abc"}},
                      {"name":"filter","in":"query","schema":{"type":"array","items":{"type":"string","enum":["active"]}}}
                    ],
                    "requestBody":{"content":{"application/json":{"schema":{"allOf":[
                      {"$ref":"#/components/schemas/Account"}
                    ]},"example":{"name":"Ada"}}}},
                    "responses":{}
                  },
                  "get":{"tags":["Players"],"responses":{}}
                },
                "/health":{"get":{"responses":{}}}
              },
              "components": {
                "parameters":{"Actor":{"name":"X-Actor","in":"header","schema":{"type":"string","default":"operator"}}},
                "schemas":{"Account":{"type":"object","required":["name"],"properties":{
                  "name":{"type":"string"},
                  "optional":{"type":"boolean"},
                  "status":{"allOf":[{"type":"string","enum":["active","closed"]}]},
                  "nested":{"type":"object","properties":{"count":{"type":"integer"}}},
                  "items":{"type":"array","items":{"$ref":"#/components/schemas/Item"}},
                  "id":{"type":"integer","readOnly":true},
                  "parent":{"$ref":"#/components/schemas/Account"}
                }}, "Item":{"type":"object","properties":{"label":{"type":"string"}}}}
              }
            }
            """;

    @Test
    void importsAllOperationsWithOptionalDataAndInheritedConnectionSettings() throws Exception {
        HashTree tree = convert(SPEC);
        TestFragmentController fragment = assertInstanceOf(TestFragmentController.class, tree.getArray()[0]);
        assertEquals("Accounts", fragment.getName());
        HashTree groups = tree.getTree(fragment);
        assertEquals(2, groups.size());
        GenericController players = assertInstanceOf(GenericController.class, groups.getArray()[0]);
        assertEquals("Players", players.getName());
        List<HTTPSamplerProxy> samplers = samplers(tree);
        assertEquals(3, samplers.size());
        HTTPSamplerProxy post = samplers.get(0);
        assertEquals("POST /accounts/{id}", post.getName());
        assertEquals("POST", post.getMethod());
        assertEquals("/accounts/a%2Fb?limit=20&filter=active", post.getPath());
        assertTrue(post.getPostBodyRaw());
        JsonNode body = JSON.readTree(post.getArguments().getArgument(0).getValue());
        assertEquals("Ada", body.path("name").asText());
        assertTrue(body.path("optional").asBoolean());
        assertEquals("active", body.path("status").asText());
        assertTrue(body.path("nested").path("count").asInt() >= 10_000_000);
        assertTrue(body.path("items").path(0).path("label").asText().startsWith("sample_label_"));
        assertTrue(body.path("parent").isNull());
        assertFalse(body.has("id"));
        List<Header> headers = post.getNativeHeaderList();
        assertEquals(0, groups.getTree(players).getTree(post).size());
        assertEquals("operator", headers.get(0).getValue());
        assertEquals("session=abc", headers.get(1).getValue());
        assertEquals("application/json", headers.get(2).getValue());
        for (HTTPSamplerProxy sampler : samplers) {
            assertEquals("", sampler.getPropertyAsString(HTTPSamplerProxy.DOMAIN));
            assertEquals("", sampler.getPropertyAsString(HTTPSamplerProxy.PROTOCOL));
            assertEquals("", sampler.getPropertyAsString(HTTPSamplerProxy.PORT));
        }
    }

    @Test
    void fragmentCanBeInsertedAndSavedWithoutLosingRequests(@TempDir Path directory) throws Exception {
        HashTree tree = convert(SPEC);
        JMeterTreeModel model = new JMeterTreeModel();
        model.addSubTree(tree, (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0), false);
        assertEquals("Accounts", model.getNodeOf((TestElement) tree.getArray()[0]).getName());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SaveService.saveTree(tree, output);
        Path saved = directory.resolve("imported.jmx");
        Files.write(saved, output.toByteArray());
        HashTree reloaded = SaveService.loadTree(saved.toFile());
        assertEquals(3, samplers(reloaded).size());
        for (HTTPSamplerProxy sampler : samplers(reloaded)) {
            assertEquals("BreakTest", headerValue(sampler, "User-Agent"));
            assertFalse(sampler.getNativeHeaderList().isEmpty());
        }
        assertEquals("operator", headerValue(samplers(reloaded).get(0), "X-Actor"));
        assertEquals("application/json", headerValue(samplers(reloaded).get(0), "Content-Type"));
        assertEquals("/accounts/a%2Fb?limit=10", samplers(reloaded).get(1).getPath());
        assertEquals(0, samplers(reloaded).get(1).getArguments().getArgumentCount());
        assertEquals(samplers(tree).get(0).getPath(), samplers(reloaded).get(0).getPath());
        assertEquals(samplers(tree).get(0).getArguments().getArgument(0).getValue(),
                samplers(reloaded).get(0).getArguments().getArgument(0).getValue());
    }

    @Test
    void supportsSwaggerBodyAndOptionalQueryParameters() throws Exception {
        HashTree tree = convert("""
                {"swagger":"2.0","info":{"title":"Legacy"},"host":"ignore.example","basePath":"/ignored",
                 "paths":{"/items":{"post":{"parameters":[
                   {"in":"query","name":"labels","type":"array","items":{"type":"string","default":"a"}},
                   {"in":"body","name":"body","schema":{"$ref":"#/definitions/Item"}}
                 ]}}},"definitions":{"Item":{"type":"object","properties":{"optional":{"type":"integer"}}}}}
                """);
        HTTPSamplerProxy sampler = samplers(tree).get(0);
        assertEquals("/items?labels=a", sampler.getPath());
        assertTrue(JSON.readTree(sampler.getArguments().getArgument(0).getValue()).path("optional").asInt() >= 10_000_000);
    }

    @Test
    void reportsInvalidFilesAndReferencesWithoutFetchingExternalResources() {
        for (String invalid : List.of("{}", SPEC.replace("#/components/schemas/Account", "https://example.com/schema"),
                SPEC.replace("#/components/parameters/Actor", "#/missing"))) {
            assertThrows(IllegalArgumentException.class, () -> convert(invalid));
        }
    }

    @Test
    void supportsOpenApi31ExamplesEnumsAndUnionTypes() throws Exception {
        HashTree tree = convert("""
                {"openapi":"3.1.0","paths":{"/items":{"post":{"requestBody":{"content":{"application/json":{
                  "schema":{"type":"object","properties":{
                    "name":{"type":["string","null"],"examples":["sample"]},
                    "kind":{"enum":["first","second"]},
                    "choice":{"oneOf":[{"type":"integer"},{"type":"string"}]}
                  }}
                }}}}}}}
                """);
        JsonNode body = JSON.readTree(samplers(tree).get(0).getArguments().getArgument(0).getValue());
        assertEquals("sample", body.path("name").asText());
        assertEquals("first", body.path("kind").asText());
        assertTrue(body.path("choice").asInt() >= 10_000_000);
    }

    @Test
    void serializesObjectAndArrayQueriesAndNamedExamples() throws Exception {
        HashTree tree = convert("""
                {"openapi":"3.0.3","paths":{"/search":{"get":{"parameters":[
                  {"in":"query","name":"filter","style":"deepObject","schema":{"type":"object",
                   "properties":{"name":{"type":"string","example":"a b"}}}},
                  {"in":"query","name":"tags","explode":false,"schema":{"type":"array"},"example":["a","b"]},
                  {"in":"query","name":"sort","explode":false,"schema":{"type":"object"},"example":{"name":"asc"}},
                  {"in":"header","name":"X-Example","schema":{"type":"string"},"examples":{"sample":{"value":"chosen"}}}
                ]}}}}
                """);
        HTTPSamplerProxy request = samplers(tree).get(0);
        assertEquals("/search?filter%5Bname%5D=a%20b&tags=a%2Cb&sort=name%2Casc", request.getPath());
        assertEquals(0, request.getArguments().getArgumentCount());
    }

    @Test
    void fillsOptionalFieldsInArrayExamples() throws Exception {
        HashTree tree = convert("""
                {"openapi":"3.1.0","paths":{"/items":{"post":{"requestBody":{"content":{"application/json":{
                  "example":[{"name":"given"}],"schema":{"type":"array","items":{"type":"object","properties":{
                    "name":{"type":"string"},"optional":{"type":"integer"}
                  }}}
                }}}}}}}
                """);
        JsonNode body = JSON.readTree(samplers(tree).get(0).getArguments().getArgument(0).getValue());
        assertEquals("given", body.path(0).path("name").asText());
        assertTrue(body.path(0).path("optional").asInt() >= 10_000_000);
    }

    @Test
    void addsHeadersFromResponseAndBodyMediaTypesAndPreservesExplicitHeaders() throws Exception {
        HashTree tree = convert("""
                {"openapi":"3.0.3","paths":{"/items":{"post":{
                  "parameters":[{"name":"user-agent","in":"header","example":"Custom client","schema":{"type":"string"}}],
                  "requestBody":{"content":{"application/json":{"schema":{"type":"object"}}}},
                  "responses":{"200":{"$ref":"#/components/responses/Items"},
                               "400":{"content":{"application/problem+json":{}}}}
                }},"/health":{"get":{}}},
                "components":{"responses":{"Items":{"content":{"application/json":{},"text/plain":{}}}}}}
                """);
        List<HTTPSamplerProxy> requests = samplers(tree);
        assertEquals(3, requests.get(0).getNativeHeaderList().size());
        assertEquals("Custom client", headerValue(requests.get(0), "User-Agent"));
        assertEquals("application/json, text/plain", headerValue(requests.get(0), "Accept"));
        assertEquals("application/json", headerValue(requests.get(0), "Content-Type"));
        assertEquals("BreakTest", headerValue(requests.get(1), "User-Agent"));
        assertEquals("*/*", headerValue(requests.get(1), "Accept"));
        assertEquals(2, requests.get(1).getNativeHeaderList().size());
    }

    @Test
    void honorsSwaggerMediaTypesAndExplicitAccept() throws Exception {
        HashTree tree = convert("""
                {"swagger":"2.0","consumes":["text/plain"],"produces":["application/xml"],
                 "paths":{"/items":{"post":{"parameters":[
                   {"in":"body","name":"body","schema":{"type":"string","example":"hello"}},
                   {"in":"header","name":"accept","type":"string","default":"text/plain"}
                 ]}},"/health":{"get":{}}}}
                """);
        assertEquals("hello", samplers(tree).get(0).getArguments().getArgument(0).getValue());
        assertEquals("text/plain", headerValue(samplers(tree).get(0), "Content-Type"));
        assertEquals("text/plain", headerValue(samplers(tree).get(0), "Accept"));
        assertEquals("application/xml", headerValue(samplers(tree).get(1), "Accept"));
    }

    @Test
    void reusesDistinctiveFieldValuesAcrossQueryAndBody() throws Exception {
        HashTree tree = convert("""
                {"openapi":"3.0.3","paths":{
                  "/accounts":{"get":{"parameters":[
                    {"name":"accountId","in":"query","schema":{"type":"integer","format":"int32"}},
                    {"name":"userId","in":"query","schema":{"type":"integer","format":"int32"}}
                  ]}},
                  "/update":{"post":{"requestBody":{"content":{"application/json":{"schema":{
                    "type":"object","properties":{
                      "accountId":{"type":"integer","format":"int32"},
                      "userId":{"type":"integer","format":"int32"},
                      "name":{"type":"string"},"otherName":{"type":"string"},
                      "nested":{"type":"object","properties":{"name":{"type":"string"}}}
                    }
                  }}}}}}
                }}
                """);
        List<HTTPSamplerProxy> requests = samplers(tree);
        JsonNode body = JSON.readTree(requests.get(1).getArguments().getArgument(0).getValue());
        long accountId = body.path("accountId").asLong();
        long userId = body.path("userId").asLong();
        assertTrue(accountId >= 10_000_000 && accountId <= Integer.MAX_VALUE);
        assertTrue(userId >= 10_000_000 && userId <= Integer.MAX_VALUE);
        assertTrue(Math.abs(accountId - userId) > 1000);
        assertEquals("/accounts?accountId=" + accountId + "&userId=" + userId, requests.get(0).getPath());
        assertEquals(body.path("name"), body.path("nested").path("name"));
        assertFalse(body.path("name").equals(body.path("otherName")));
    }

    private static String headerValue(HTTPSamplerProxy sampler, String name) {
        return sampler.getNativeHeaderList().stream()
                .filter(header -> header.getName().equalsIgnoreCase(name))
                .findFirst().orElseThrow().getValue();
    }

    private static HashTree convert(String spec) throws Exception {
        return new OpenApiConverter(JSON.readTree(spec)).convert("api.json");
    }

    static List<HTTPSamplerProxy> samplers(HashTree tree) {
        List<HTTPSamplerProxy> result = new ArrayList<>();
        for (Object item : tree.list()) {
            if (item instanceof HTTPSamplerProxy sampler) {
                assertEquals(0, tree.getTree(item).size(), "Imported samplers must not have Header Manager children");
                result.add(sampler);
            }
            result.addAll(samplers(tree.getTree(item)));
        }
        return result;
    }
}
