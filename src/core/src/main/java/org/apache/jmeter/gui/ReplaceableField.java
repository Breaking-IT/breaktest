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

package org.apache.jmeter.gui;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A user-editable text field that can participate in Search and Replace.
 *
 * @param name human-readable field name
 * @param getter current field value supplier
 * @param setter updated field value consumer
 * @param area searchable area containing this field
 * @param rowField row column, or ALL for fields outside row name/value columns
 * @since 2026.08
 */
public record ReplaceableField(String name, Supplier<String> getter, Consumer<String> setter, SearchArea area, RowField rowField) {

    public ReplaceableField(String name, Supplier<String> getter, Consumer<String> setter, SearchArea area) {
        this(name, getter, setter, area, RowField.ALL);
    }

    public ReplaceableField(String name, Supplier<String> getter, Consumer<String> setter) {
        this(name, getter, setter, SearchArea.OTHER);
    }

    public ReplaceableField {
        Objects.requireNonNull(name);
        Objects.requireNonNull(area);
        Objects.requireNonNull(getter);
        Objects.requireNonNull(setter);
    }

    public String value() {
        return Objects.toString(getter.get(), "");
    }

    public void setValue(String value) {
        setter.accept(value);
    }
}
