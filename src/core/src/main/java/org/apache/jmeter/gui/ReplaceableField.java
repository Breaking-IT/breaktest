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
 * @since 2026.08
 */
public record ReplaceableField(String name, Supplier<String> getter, Consumer<String> setter) {

    public ReplaceableField {
        Objects.requireNonNull(name);
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
