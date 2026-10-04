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

package org.apache.jmeter.recording;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Shared tolerant HAR timestamp parsing, retaining fractional seconds. */
public final class HarTimestamp {
    private HarTimestamp() {
    }

    public static Optional<Instant> parse(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        // Some exporters use basic numeric offsets instead of ISO's colon-separated form.
        String normalized = value.replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2");
        try {
            return Optional.of(OffsetDateTime.parse(normalized).toInstant());
        } catch (DateTimeParseException ignored) {
            // A missing zone is interpreted as UTC, as in the HTTP HAR importer.
        }
        try {
            return Optional.of(Instant.parse(normalized));
        } catch (DateTimeParseException ignored) {
            // Fall through to local timestamps.
        }
        try {
            return Optional.of(LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }
}
