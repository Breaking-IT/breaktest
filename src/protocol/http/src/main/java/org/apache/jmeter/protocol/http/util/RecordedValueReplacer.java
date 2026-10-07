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

package org.apache.jmeter.protocol.http.util;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;

/** Matches recorded representations while preserving their encoding at replay time. */
public final class RecordedValueReplacer {
    private RecordedValueReplacer() {
    }

    private static Map<String, String> variants(String value, String reference) {
        Map<String, String> variants = new LinkedHashMap<>();
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        variants.put(encoded.replace("+", "%20"),
                "${__strReplace(${__urlencode(" + reference + ")},+,%20)}");
        variants.put(encoded, "${__urlencode(" + reference + ")}");
        variants.put(value, reference);
        variants.remove("");
        return variants;
    }

    private static String percentDecode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private static Map<String, String> decodedVariants(String value, String reference) {
        Map<String, String> variants = new LinkedHashMap<>();
        String decoded = percentDecode(value);
        if (!decoded.equals(value)) {
            // Match the converter's percent decoding, preserving literal plus signs even
            // when a later runtime value contains a plus absent from the recording.
            variants.put(decoded, "${__urldecode(${__strReplace(" + reference + ",+,%2B)})}");
        }
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        variants.put(percentDecode(encoded), reference);
        variants.put(percentDecode(encoded.replace("+", "%20")), reference);
        variants.put(value, reference);
        variants.remove("");
        return orderByLength(variants);
    }

    public static String matchedDecodedLiteral(String text, String value) {
        if (text == null || value.isEmpty()) {
            return null;
        }
        return decodedVariants(value, "").keySet().stream().filter(text::contains).findFirst().orElse(null);
    }

    private static boolean isEscape(String text, int offset) {
        return offset + 2 < text.length() && text.charAt(offset) == '%'
                && Character.digit(text.charAt(offset + 1), 16) >= 0
                && Character.digit(text.charAt(offset + 2), 16) >= 0;
    }

    private static String normalizeEscapes(String text) {
        StringBuilder normalized = null;
        int i = 0;
        while (i < text.length()) {
            if (isEscape(text, i)) {
                for (int j = i + 1; j <= i + 2; j++) {
                    char original = text.charAt(j);
                    char upper = original >= 'a' && original <= 'f' ? (char) (original - ('a' - 'A')) : original;
                    if (upper != original) {
                        if (normalized == null) {
                            normalized = new StringBuilder(text);
                        }
                        normalized.setCharAt(j, upper);
                    }
                }
                i += 3;
            } else {
                i++;
            }
        }
        return normalized == null ? text : normalized.toString();
    }

    private static final class SearchText {
        private final String original;
        private String normalized;

        private SearchText(String original) {
            this.original = original;
        }

        private String normalized() {
            if (normalized == null) {
                normalized = normalizeEscapes(original);
            }
            return normalized;
        }
    }

    /** Literal search with case folding confined to complete percent escapes in the literal. */
    private static final class Literal {
        private final String value;
        private final String prefix;
        private final String middle;
        private final String suffix;

        private Literal(String value, boolean decoded) {
            this.value = value;
            int first = -1;
            int end = 0;
            if (!decoded) {
                int i = 0;
                while (i < value.length()) {
                    if (isEscape(value, i)) {
                        if (first < 0) {
                            first = i;
                        }
                        end = i + 3;
                        i += 3;
                    } else {
                        i++;
                    }
                }
            }
            prefix = first < 0 ? value : value.substring(0, first);
            middle = first < 0 ? "" : normalizeEscapes(value.substring(first, end));
            suffix = first < 0 ? "" : value.substring(end);
        }

        private int find(SearchText text, int from) {
            int last = text.original.length() - value.length();
            // Intersect the positions of three literal searches. Exact boundary portions
            // preserve substring semantics even when a match starts/ends inside an escape.
            while (from <= last) {
                int start = text.original.indexOf(prefix, from);
                if (start < 0 || start > last) {
                    return -1;
                }
                if (middle.isEmpty()) {
                    return start;
                }
                int mid = text.normalized().indexOf(middle, start + prefix.length());
                if (mid < 0 || mid - prefix.length() > last) {
                    return -1;
                }
                if (mid != start + prefix.length()) {
                    from = mid - prefix.length();
                    continue;
                }
                int tailOffset = prefix.length() + middle.length();
                int tail = text.original.indexOf(suffix, start + tailOffset);
                if (tail < 0 || tail - tailOffset > last) {
                    return -1;
                }
                if (tail == start + tailOffset) {
                    return start;
                }
                from = tail - tailOffset;
            }
            return -1;
        }
    }

    public static String matchedLiteral(String text, String value) {
        // Encoding can only make a raw representation longer. Avoid even constructing
        // encoded variants for large captures checked against short request fields.
        if (text == null || value.isEmpty() || value.length() > text.length()) {
            return null;
        }
        SearchText search = new SearchText(text);
        for (String variant : orderedVariants(value, "").keySet()) {
            if (variant.length() > text.length()) {
                continue;
            }
            int start = new Literal(variant, false).find(search, 0);
            if (start >= 0) {
                return text.substring(start, start + variant.length());
            }
        }
        return null;
    }

    private static Map<String, String> orderedVariants(String value, String reference) {
        return orderByLength(variants(value, reference));
    }

    private static Map<String, String> orderByLength(Map<String, String> variants) {
        List<String> literals = new ArrayList<>(variants.keySet());
        literals.sort(Comparator.comparingInt(String::length).reversed());
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String literal : literals) {
            ordered.put(literal, variants.get(literal));
        }
        return ordered;
    }

    public static String replace(String text, String value, String reference, boolean decoded) {
        if (text == null || value.isEmpty() || !decoded && value.length() > text.length()) {
            return text;
        }
        Map<String, String> variants = decoded ? decodedVariants(value, reference) : orderedVariants(value, reference);
        List<Literal> literals = new ArrayList<>();
        List<String> references = new ArrayList<>();
        variants.forEach((literal, replacement) -> {
            if (literal.length() <= text.length()) {
                literals.add(new Literal(literal, decoded));
                references.add(replacement);
            }
        });
        if (literals.isEmpty()) {
            return text;
        }
        SearchText search = new SearchText(text);
        int[] next = new int[literals.size()];
        for (int i = 0; i < next.length; i++) {
            next[i] = literals.get(i).find(search, 0);
        }
        StringBuilder result = new StringBuilder();
        int offset = 0;
        int variable = text.indexOf("${");
        // Single pass: never match inside a generated or pre-existing variable/function reference.
        while (offset < text.length()) {
            int selected = -1;
            int start = text.length();
            for (int i = 0; i < next.length; i++) {
                if (next[i] >= 0 && next[i] < offset) {
                    next[i] = literals.get(i).find(search, offset);
                }
                if (next[i] >= 0 && next[i] < start) {
                    selected = i;
                    start = next[i];
                }
            }
            if (variable >= 0 && variable < offset) {
                variable = text.indexOf("${", offset);
            }
            if (variable >= 0 && variable <= start) {
                int end = variable + 2;
                int depth = 1;
                while (end < text.length() && depth > 0) {
                    if (text.startsWith("${", end)) {
                        depth++;
                        end += 2;
                    } else if (text.charAt(end++) == '}') {
                        depth--;
                    }
                }
                result.append(text, offset, end);
                offset = end;
            } else if (selected >= 0) {
                result.append(text, offset, start).append(references.get(selected));
                offset = start + literals.get(selected).value.length();
            } else {
                break;
            }
        }
        result.append(text, offset, text.length());
        return result.toString();
    }

    public static int replaceSampler(HTTPSamplerBase sampler, String value, String reference) {
        int changed = 0;
        String path = replace(sampler.getPath(), value, reference, false);
        if (!path.equals(sampler.getPath())) {
            sampler.setPath(path);
            changed++;
        }
        for (var property : sampler.getArguments()) {
            if (property.getObjectValue() instanceof HTTPArgument argument) {
                String before = argument.getValue();
                replaceArgument(argument, value, reference,
                        sampler.getPostBodyRaw() || sampler.getSendParameterValuesAsPostBody());
                if (!before.equals(argument.getValue())) {
                    changed++;
                }
            }
        }
        for (var header : sampler.getNativeHeaderList()) {
            String replaced = replace(header.getValue(), value, reference, false);
            if (!replaced.equals(header.getValue())) {
                header.setValue(replaced);
                changed++;
            }
        }
        return changed;
    }

    public static void replaceArgument(HTTPArgument argument, String value, String reference, boolean raw) {
        if (!raw && !argument.isAlwaysEncoded()) {
            String literal = matchedLiteral(argument.getValue(), value);
            if (literal != null && literal.equals(argument.getValue()) && !literal.equals(value)
                    && !literal.contains("%20")) {
                argument.setValue(reference);
                argument.setAlwaysEncoded(true);
                return;
            }
        }
        argument.setValue(replace(argument.getValue(), value, reference, !raw && argument.isAlwaysEncoded()));
    }
}
