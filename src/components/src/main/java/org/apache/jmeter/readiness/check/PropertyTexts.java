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

package org.apache.jmeter.readiness.check;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.readiness.PlanFingerprint;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;

/**
 * The string values of an element's properties, nested ones included, keyed by a readable property path.
 */
final class PropertyTexts {
    private static final Set<String> SKIPPED = Set.of(TestElement.NAME, TestElement.TEST_CLASS,
            TestElement.ENABLED);

    private PropertyTexts() {
    }

    static Map<String, String> of(TestElement element) {
        Map<String, String> texts = new LinkedHashMap<>();
        collect(element, "", texts, 0);
        return texts;
    }

    private static void collect(TestElement element, String prefix, Map<String, String> texts, int depth) {
        PropertyIterator it = element.propertyIterator();
        while (it.hasNext()) {
            collect(it.next(), prefix, texts, depth);
        }
    }

    private static void collect(JMeterProperty property, String prefix, Map<String, String> texts, int depth) {
        String name = property.getName();
        if (depth == 0 && (SKIPPED.contains(name) || PlanFingerprint.isIgnoredProperty(name))) {
            return;
        }
        String path = prefix.isEmpty() ? name : prefix + "/" + name;
        if (depth > 8) {
            return;
        }
        if (property instanceof TestElementProperty elementProperty
                && elementProperty.getObjectValue() instanceof TestElement nested) {
            collect(nested, path, texts, depth + 1);
        } else if (property instanceof MultiProperty multi) {
            for (JMeterProperty child : multi) {
                collect(child, path, texts, depth + 1);
            }
        } else {
            String value = property.getStringValue();
            if (value != null && !value.isEmpty()) {
                texts.merge(path, value, (a, b) -> a + "\n" + b);
            }
        }
    }
}
