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
import java.util.regex.Pattern;

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

    private static Pattern literalPattern(String literal) {
        // Percent escapes are case insensitive; the rest of a token is not.
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < literal.length()) {
            if (literal.charAt(i) == '%' && i + 2 < literal.length()
                    && Character.digit(literal.charAt(i + 1), 16) >= 0
                    && Character.digit(literal.charAt(i + 2), 16) >= 0) {
                regex.append("(?i:").append(Pattern.quote(literal.substring(i, i + 3))).append(')');
                i += 3;
            } else {
                regex.append(Pattern.quote(literal.substring(i, i + 1)));
                i++;
            }
        }
        return Pattern.compile(regex.toString());
    }

    public static String matchedLiteral(String text, String value) {
        if (text == null || value.isEmpty()) {
            return null;
        }
        for (String variant : orderedVariants(value, "").keySet()) {
            var matcher = literalPattern(variant).matcher(text);
            if (matcher.find()) {
                return matcher.group();
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
        if (text == null || value.isEmpty()) {
            return text;
        }
        Map<String, String> variants = decoded ? decodedVariants(value, reference) : orderedVariants(value, reference);
        StringBuilder regex = new StringBuilder("(\\$\\{)");
        List<String> references = new ArrayList<>();
        variants.forEach((literal, replacement) -> {
            regex.append("|(").append(decoded ? Pattern.quote(literal) : literalPattern(literal).pattern()).append(')');
            references.add(replacement);
        });
        var matcher = Pattern.compile(regex.toString()).matcher(text);
        StringBuilder result = new StringBuilder();
        int offset = 0;
        // Single pass: never match inside a generated or pre-existing variable/function reference.
        while (matcher.find(offset)) {
            result.append(text, offset, matcher.start());
            if (matcher.group(1) != null) {
                int end = matcher.end();
                int depth = 1;
                while (end < text.length() && depth > 0) {
                    if (text.startsWith("${", end)) {
                        depth++;
                        end += 2;
                    } else if (text.charAt(end++) == '}') {
                        depth--;
                    }
                }
                result.append(text, matcher.start(), end);
                offset = end;
            } else {
                for (int i = 0; i < references.size(); i++) {
                    if (matcher.group(i + 2) != null) {
                        result.append(references.get(i));
                        break;
                    }
                }
                offset = matcher.end();
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
