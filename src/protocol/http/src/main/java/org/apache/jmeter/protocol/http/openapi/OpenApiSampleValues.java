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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Stable field-specific placeholders; explicit examples are handled by the converter. */
final class OpenApiSampleValues {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private final Map<BigDecimal, String> numericOwners = new HashMap<>();

    JsonNode create(JsonNode schema, String type, String field) {
        UUID identity = UUID.nameUUIDFromBytes(field.getBytes(StandardCharsets.UTF_8));
        long seed = identity.getMostSignificantBits() & Long.MAX_VALUE;
        return switch (type) {
        case "integer", "number" -> number(schema, type, field, seed);
        case "boolean" -> NODES.booleanNode(true);
        case "null" -> NODES.nullNode();
        default -> NODES.textNode(string(schema, field, identity, seed));
        };
    }

    private JsonNode number(JsonNode schema, String type, String field, long seed) {
        boolean integer = "integer".equals(type);
        BigDecimal low = new BigDecimal("-9007199254740991");
        BigDecimal high = new BigDecimal("9007199254740991");
        if ("int32".equals(schema.path("format").asText())) {
            low = BigDecimal.valueOf(Integer.MIN_VALUE);
            high = BigDecimal.valueOf(Integer.MAX_VALUE);
        } else if ("int64".equals(schema.path("format").asText())) {
            low = BigDecimal.valueOf(Long.MIN_VALUE);
            high = BigDecimal.valueOf(Long.MAX_VALUE);
        }
        if (schema.has("minimum")) {
            low = low.max(schema.get("minimum").decimalValue());
        }
        if (schema.has("maximum")) {
            high = high.min(schema.get("maximum").decimalValue());
        }
        JsonNode exclusiveLow = schema.path("exclusiveMinimum");
        JsonNode exclusiveHigh = schema.path("exclusiveMaximum");
        if (exclusiveLow.isNumber()) {
            low = low.max(exclusiveLow.decimalValue());
        }
        if (exclusiveHigh.isNumber()) {
            high = high.min(exclusiveHigh.decimalValue());
        }
        BigDecimal step = schema.has("multipleOf") ? schema.get("multipleOf").decimalValue()
                : integer ? BigDecimal.ONE : new BigDecimal("0.001");
        if (step.signum() <= 0) {
            throw new IllegalArgumentException("Invalid multipleOf for " + field);
        }
        if (integer) {
            // Integer multiples of a decimal step are multiples of its reduced numerator.
            BigDecimal stripped = step.stripTrailingZeros();
            BigInteger denominator = BigInteger.TEN.pow(Math.max(0, stripped.scale()));
            BigInteger numerator = stripped.unscaledValue().multiply(BigInteger.TEN.pow(Math.max(0, -stripped.scale())));
            step = new BigDecimal(numerator.divide(numerator.gcd(denominator)));
        }
        BigDecimal first = low.divide(step, 0, RoundingMode.CEILING);
        BigDecimal last = high.divide(step, 0, RoundingMode.FLOOR);
        if ((exclusiveLow.isNumber() || exclusiveLow.asBoolean()) && first.multiply(step).compareTo(low) == 0) {
            first = first.add(BigDecimal.ONE);
        }
        if ((exclusiveHigh.isNumber() || exclusiveHigh.asBoolean()) && last.multiply(step).compareTo(high) == 0) {
            last = last.subtract(BigDecimal.ONE);
        }
        if (first.compareTo(last) > 0) {
            throw new IllegalArgumentException("No sample value satisfies the numeric bounds for " + field);
        }
        BigDecimal candidate = BigDecimal.valueOf(10_000_000 + seed % 90_000_000).divide(step, 0, RoundingMode.FLOOR);
        BigDecimal count = last.subtract(first).add(BigDecimal.ONE);
        if (candidate.compareTo(first) < 0 || candidate.compareTo(last) > 0) {
            candidate = first.add(BigDecimal.valueOf(seed).remainder(count));
        }
        BigDecimal value = candidate.multiply(step).stripTrailingZeros();
        // Resolve accidental collisions when the schema has enough distinct values available.
        for (int attempt = 0; attempt < 100 && numericOwners.containsKey(value)
                && !field.equals(numericOwners.get(value)); attempt++) {
            candidate = first.add(candidate.subtract(first).add(BigDecimal.valueOf(104729)).remainder(count));
            value = candidate.multiply(step).stripTrailingZeros();
        }
        numericOwners.putIfAbsent(value, field);
        // Whole numbers are written as integers: a stripped BigDecimal such as 1.234567E+7 would print in E-notation.
        return integer || value.scale() <= 0 ? NODES.numberNode(value.toBigIntegerExact()) : NODES.numberNode(value);
    }

    private static String string(JsonNode schema, String field, UUID identity, long seed) {
        String token = identity.toString().replace("-", "");
        String name = field.replaceAll("[^A-Za-z0-9_]", "_");
        String value = switch (schema.path("format").asText()) {
        case "date" -> LocalDate.of(2000, 1, 1).plusDays(seed % 36525).toString();
        case "date-time" -> LocalDate.of(2000, 1, 1).plusDays(seed % 36525) + "T12:34:56Z";
        case "uuid" -> identity.toString();
        case "email" -> "sample_" + token.substring(0, 12) + "@example.com";
        case "uri", "url" -> "https://example.com/sample/" + token.substring(0, 12);
        default -> "sample_" + name + "_" + token.substring(0, 12);
        };
        int min = schema.path("minLength").asInt(0);
        int max = schema.path("maxLength").asInt(Integer.MAX_VALUE);
        if (min < 0 || max < min || min > 10000) {
            throw new IllegalArgumentException("Unsupported string length bounds for " + field);
        }
        if (!schema.path("format").asText().isEmpty() && (value.length() > max || value.length() < min)
                && java.util.Set.of("date", "date-time", "uuid", "email", "uri", "url")
                        .contains(schema.path("format").asText())) {
            throw new IllegalArgumentException("Provide an example matching the format and length bounds for " + field);
        }
        if (value.length() > max) {
            // Keep the distinguishing token even for short, constrained strings.
            value = token.substring(0, Math.min(max, token.length()));
        }
        while (value.length() < min) {
            value += token.substring(0, Math.min(token.length(), min - value.length()));
        }
        return value;
    }
}
