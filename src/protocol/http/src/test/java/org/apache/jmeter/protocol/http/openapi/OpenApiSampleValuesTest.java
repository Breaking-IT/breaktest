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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class OpenApiSampleValuesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void respectsNumericBoundsAndMultiples() throws Exception {
        var values = new OpenApiSampleValues();
        var integer = JSON.readTree("""
                {"type":"integer","format":"int32","minimum":10,"maximum":40,"multipleOf":2.5}
                """);
        var value = values.create(integer, "integer", "accountId");
        assertTrue(value.asInt() >= 10 && value.asInt() <= 40);
        assertEquals(0, value.asInt() % 5);
        assertEquals(value, values.create(integer, "integer", "accountId"));
        var decimal = JSON.readTree("""
                {"exclusiveMinimum":0.1,"exclusiveMaximum":0.2,"multipleOf":0.01}
                """);
        BigDecimal number = values.create(decimal, "number", "price").decimalValue();
        assertTrue(number.compareTo(new BigDecimal("0.1")) > 0);
        assertTrue(number.compareTo(new BigDecimal("0.2")) < 0);
        assertEquals(0, number.remainder(new BigDecimal("0.01")).signum());
    }

    @Test
    void producesStableFormattedAndLengthConstrainedStrings() throws Exception {
        var values = new OpenApiSampleValues();
        var date = JSON.createObjectNode().put("format", "date");
        LocalDate.parse(values.create(date, "string", "birthDate").asText());
        var uuid = JSON.createObjectNode().put("format", "uuid");
        UUID.fromString(values.create(uuid, "string", "externalId").asText());
        var shortString = JSON.createObjectNode().put("minLength", 8).put("maxLength", 8);
        String sample = values.create(shortString, "string", "name").asText();
        assertEquals(8, sample.length());
        assertEquals(sample, new OpenApiSampleValues().create(shortString, "string", "name").asText());
    }
}
