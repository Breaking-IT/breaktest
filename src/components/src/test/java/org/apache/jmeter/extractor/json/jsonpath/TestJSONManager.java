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

package org.apache.jmeter.extractor.json.jsonpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class TestJSONManager {
    @Test
    void parsedDocumentPreservesValueConversionAndCanBeReadRepeatedly() throws Exception {
        JSONManager manager = new JSONManager();
        String json = "{\"values\":[\"token\",123,true,null,{\"key\":\"value\"},[1,2]]}";
        Object document = manager.parse(json);
        List<Object> expected = Arrays.asList("token", "123", "true", null, "{\"key\":\"value\"}", "[1,2]");
        assertEquals(expected, manager.extractFromParsedJson(document, "$.values[*]"));
        assertEquals(List.of(), manager.extractFromParsedJson(document, "$.missing"));
        assertEquals(List.of("value"), manager.extractFromParsedJson(document, "$.values[4].key"));
        assertEquals(manager.extractWithJsonPath(json, "$.values[*]"),
                manager.extractFromParsedJson(document, "$.values[*]"));
        assertThrows(UnsupportedOperationException.class,
                () -> manager.extractFromParsedJson(document, "$.values[*]").clear());
    }
}
