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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

/** One editable table row, removed by identity rather than its name or value. */
public record RemovableRow(SearchArea area, int number, List<String> tokens, BooleanSupplier removal) {
    public RemovableRow {
        tokens = List.copyOf(tokens);
    }

    /** Includes HTTP Request Defaults, whose persisted element is a generic config. */
    public static List<RemovableRow> forElement(TestElement element) {
        if (element instanceof Replaceable replaceable) {
            return replaceable.getRemovableRows();
        }
        if (!(element instanceof ConfigTestElement)) {
            return List.of();
        }
        List<RemovableRow> rows = new ArrayList<>();
        Object args = element.getProperty("HTTPsampler.Arguments").getObjectValue();
        if (args instanceof Arguments arguments && !element.getPropertyAsBoolean("HTTPSampler.postBodyRaw")
                && !isUnnamedBody(arguments)) {
            int number = 0;
            for (JMeterProperty property : arguments) {
                Argument argument = (Argument) property.getObjectValue();
                rows.add(inCollection(SearchArea.PARAMETERS, ++number,
                        List.of(argument.getName(), argument.getValue(), argument.getDescription()),
                        () -> element.getProperty("HTTPsampler.Arguments").getObjectValue() instanceof Arguments current
                                ? current.getArguments() : null, property));
            }
        }
        if (element.getProperty("HTTPSampler.headers") instanceof CollectionProperty headers) {
            int number = 0;
            for (JMeterProperty property : headers) {
                TestElement header = (TestElement) property.getObjectValue();
                rows.add(inCollection(SearchArea.HEADERS, ++number,
                        List.of(header.getPropertyAsString("Header.name"), header.getPropertyAsString("Header.value")),
                        () -> element.getProperty("HTTPSampler.headers") instanceof CollectionProperty current ? current : null,
                        property));
            }
        }
        return rows;
    }

    private static boolean isUnnamedBody(Arguments arguments) {
        boolean hasArguments = false;
        for (JMeterProperty property : arguments.getEnabledArguments()) {
            hasArguments = true;
            if (!((Argument) property.getObjectValue()).getName().isEmpty()) {
                return false;
            }
        }
        return hasArguments;
    }

    public boolean remove() {
        return removal.getAsBoolean();
    }

    /** Re-read the collection so stale previews never modify a detached collection. */
    public static RemovableRow inCollection(SearchArea area, int number, List<String> tokens,
            Supplier<CollectionProperty> collection, JMeterProperty row) {
        JMeterProperty snapshot = row.clone();
        return new RemovableRow(area, number, tokens, () -> {
            CollectionProperty current = collection.get();
            if (current == null || !row.equals(snapshot)) {
                return false;
            }
            PropertyIterator iterator = current.iterator();
            while (iterator.hasNext()) {
                if (iterator.next() == row) {
                    iterator.remove();
                    return true;
                }
            }
            return false;
        });
    }
}
