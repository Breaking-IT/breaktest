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

package org.apache.jmeter.readiness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jorphan.collections.HashTree;

/**
 * Content fingerprint of the part of a test plan a readiness run covers. Tree and undo events are not reliable for
 * staleness (editor field changes fire none), so evidence is current only while the recomputed fingerprint matches.
 * <p>
 * The fingerprint covers, in tree order, the class and properties of every element of the given tree (which is
 * expected to hold enabled elements only, as after {@link org.apache.jmeter.JMeter#convertSubTree}), plus the
 * validation settings and the scope. Readiness exception properties, comments, GUI classes, validation bookkeeping
 * and listener configuration are left out, as they do not change what the script sends.
 */
public final class PlanFingerprint {

    private PlanFingerprint() {
    }

    /**
     * @param tree in-scope tree of enabled elements
     * @param settings validation settings, or {@code null} for a configuration-only fingerprint
     * @param scope identifiers of the scope (thread group ids); order does not matter
     * @return hex digest
     */
    public static String compute(HashTree tree, ValidationSettings settings, List<String> scope) {
        MessageDigest digest = sha256();
        List<String> sortedScope = new ArrayList<>(scope);
        sortedScope.sort(Comparator.naturalOrder());
        update(digest, "scope=" + String.join(",", sortedScope));
        update(digest, "settings=" + (settings == null ? "" : settings.toString()));
        appendTree(digest, tree, 0);
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * @param propertyName property name
     * @return whether the property is left out of the fingerprint
     */
    public static boolean isIgnoredProperty(String propertyName) {
        return ReadinessExceptions.isExceptionProperty(propertyName)
                || propertyName.startsWith("__breaktest_validation") // $NON-NLS-1$
                || TestElement.COMMENTS.equals(propertyName)
                || TestElement.GUI_CLASS.equals(propertyName);
    }

    private static void appendTree(MessageDigest digest, HashTree tree, int depth) {
        for (Object key : tree.list()) {
            update(digest, "<" + depth + ":" + key.getClass().getName());
            if (key instanceof TestElement element && !(element instanceof ResultCollector)) {
                appendElement(digest, element);
            }
            appendTree(digest, tree.getTree(key), depth + 1);
            update(digest, ">");
        }
    }

    private static void appendElement(MessageDigest digest, TestElement element) {
        List<JMeterProperty> properties = new ArrayList<>();
        PropertyIterator it = element.propertyIterator();
        while (it.hasNext()) {
            JMeterProperty property = it.next();
            if (!isIgnoredProperty(property.getName())) {
                properties.add(property);
            }
        }
        // Editors may write properties back in another order without changing them
        properties.sort(Comparator.comparing(JMeterProperty::getName));
        for (JMeterProperty property : properties) {
            appendProperty(digest, property);
        }
    }

    private static void appendProperty(MessageDigest digest, JMeterProperty property) {
        update(digest, "{" + property.getName() + "=");
        if (property instanceof TestElementProperty elementProperty) {
            Object value = elementProperty.getObjectValue();
            update(digest, value == null ? "null" : value.getClass().getName());
            if (value instanceof TestElement nested) {
                appendElement(digest, nested);
            }
        } else if (property instanceof MultiProperty multi) {
            // Collection order is meaningful (arguments, headers)
            for (JMeterProperty child : multi) {
                appendProperty(digest, child);
            }
        } else {
            update(digest, String.valueOf(property.getStringValue()));
        }
        update(digest, "}");
    }

    private static void update(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // Length-prefixed, so different splits of the same characters never collide
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256"); // $NON-NLS-1$
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
