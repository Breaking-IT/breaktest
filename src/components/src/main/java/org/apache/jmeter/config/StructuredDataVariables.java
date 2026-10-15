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

import java.util.List;
import java.util.Map;

import net.minidev.json.JSONValue;

/** Names shared by structured previews and execution. */
final class StructuredDataVariables {
    private StructuredDataVariables() {
    }

    static void put(Map<String, String> record, String name, String value) {
        if (record.putIfAbsent(name, value) != null) {
            throw new IllegalArgumentException("Conflicting flattened variable name: " + name);
        }
    }

    static String missingValue(String name) {
        return name.endsWith("_matchNr") ? "0" : "";
    }

    static void flatten(Map<String, String> record, String name, Object value) {
        flatten(record, name, value, 0);
    }

    private static void flatten(Map<String, String> record, String name, Object value, int depth) {
        if (depth > 100) {
            throw new IllegalArgumentException("Structured data exceeds 100 nesting levels");
        }
        put(record, name, value == null ? "" : value instanceof String text ? text : JSONValue.toJSONString(value));
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, member) -> flatten(record, name + "." + key, member, depth + 1));
        } else if (value instanceof List<?> list) {
            put(record, name + "_matchNr", Integer.toString(list.size()));
            for (int i = 0; i < list.size(); i++) {
                flatten(record, name + "_" + (i + 1), list.get(i), depth + 1);
            }
        }
    }
}
