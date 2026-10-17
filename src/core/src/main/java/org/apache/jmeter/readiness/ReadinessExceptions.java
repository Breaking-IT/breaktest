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

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

/**
 * Reads and writes accepted readiness exceptions, stored as string properties of the affected element:
 * {@code breaktest.readiness.exception.<checkId>.<findingKey>} with an encoded {@code date}, {@code author} and
 * {@code reason}. The properties do not affect execution and are left out of the readiness fingerprint. Callers
 * that edit the GUI plan are responsible for undo history and the dirty flag.
 */
public final class ReadinessExceptions {
    /** Prefix of every exception property */
    public static final String PREFIX = "breaktest.readiness.exception."; // $NON-NLS-1$
    /** Finding key of an exception that covers every finding of its check on the element (and, where a check
     * supports it, below the element) */
    public static final String ANY_KEY = "*"; // $NON-NLS-1$
    /** Key marking an extractor whose match is optional */
    public static final String OPTIONAL_EXTRACTOR_KEY = "optional"; // $NON-NLS-1$
    /** Key marking a transaction or subtree as background/API activity without think time */
    public static final String BACKGROUND_ACTIVITY_KEY = "background"; // $NON-NLS-1$

    private ReadinessExceptions() {
    }

    /**
     * @param propertyName a property name
     * @return whether the property stores a readiness exception
     */
    public static boolean isExceptionProperty(String propertyName) {
        return propertyName.startsWith(PREFIX);
    }

    /**
     * @param checkId check id
     * @param findingKey finding key
     * @return the property name storing that exception
     */
    public static String propertyName(CheckId checkId, String findingKey) {
        String key = findingKey == null || findingKey.isEmpty() ? ANY_KEY : findingKey;
        return PREFIX + checkId.key() + '.' + encode(key);
    }

    /**
     * @param element test element
     * @return the exceptions stored on it, in property order; unparseable entries are skipped
     */
    public static List<AcceptedException> read(TestElement element) {
        List<AcceptedException> result = new ArrayList<>();
        PropertyIterator it = element.propertyIterator();
        while (it.hasNext()) {
            JMeterProperty property = it.next();
            AcceptedException parsed = parse(property.getName(), property.getStringValue());
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    /**
     * @param element test element
     * @param checkId check id
     * @param findingKey finding key
     * @return the exception stored for exactly that key, or {@code null}
     */
    public static AcceptedException get(TestElement element, CheckId checkId, String findingKey) {
        String name = propertyName(checkId, findingKey);
        JMeterProperty property = element.getPropertyOrNull(name);
        return property == null ? null : parse(name, property.getStringValue());
    }

    /**
     * @param element test element holding the finding
     * @param finding finding
     * @return an exception on the element covering the finding, or {@code null}
     */
    public static AcceptedException find(TestElement element, Finding finding) {
        AcceptedException exact = get(element, finding.checkId(), finding.findingKey());
        return exact != null ? exact : get(element, finding.checkId(), ANY_KEY);
    }

    /**
     * Stores an exception, replacing one with the same check and key.
     *
     * @param element test element
     * @param exception exception
     */
    public static void put(TestElement element, AcceptedException exception) {
        element.setProperty(propertyName(exception.checkId(), exception.findingKey()), format(exception));
    }

    /**
     * @param element test element
     * @param checkId check id
     * @param findingKey finding key
     * @return whether an exception was removed
     */
    public static boolean remove(TestElement element, CheckId checkId, String findingKey) {
        String name = propertyName(checkId, findingKey);
        if (element.getPropertyOrNull(name) == null) {
            return false;
        }
        element.removeProperty(name);
        return true;
    }

    static String format(AcceptedException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("date", exception.date()); // $NON-NLS-1$
        fields.put("author", exception.author()); // $NON-NLS-1$
        fields.put("reason", exception.reason()); // $NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        fields.forEach((key, value) -> {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(key).append('=').append(encode(value));
        });
        return sb.toString();
    }

    static AcceptedException parse(String propertyName, String value) {
        if (!isExceptionProperty(propertyName)) {
            return null;
        }
        String rest = propertyName.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) {
            return null;
        }
        CheckId checkId = CheckId.fromKey(rest.substring(0, dot));
        if (checkId == null) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : (value == null ? "" : value).split(";", -1)) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                fields.put(part.substring(0, eq), decode(part.substring(eq + 1)));
            }
        }
        return new AcceptedException(checkId, decode(rest.substring(dot + 1)), fields.get("reason"),
                fields.get("author"), fields.get("date"));
    }

    private static String encode(String value) {
        // Dots and separators are encoded too, so keys never split a property name or a value
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace(".", "%2E");
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
