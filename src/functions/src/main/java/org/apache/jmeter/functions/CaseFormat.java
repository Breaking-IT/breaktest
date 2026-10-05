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

package org.apache.jmeter.functions;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.google.auto.service.AutoService;

/** Implements {@code __caseFormat}. */
@AutoService(Function.class)
public class CaseFormat extends AbstractNativeFunction {
    public CaseFormat() {
        super("__caseFormat", 1, 3, 2, "native_function_text", "native_function_case", "function_name_paropt");
    }

    @Override
    protected String evaluate() {
        String text = argument(0);
        String mode = parameterCount() > 1 ? argument(1).trim().toUpperCase(Locale.ROOT) : "LOWER_CAMEL_CASE";
        String separated = text.replaceAll("([\\p{Lu}]+)([\\p{Lu}][\\p{Ll}])", "$1 $2")
                .replaceAll("([\\p{Ll}\\p{Nd}])(\\p{Lu})", "$1 $2");
        String[] words = separated.strip().split("[\\s_\\-]+", -1);
        List<String> lower = Arrays.stream(words).filter(word -> !word.isEmpty())
                .map(word -> word.toLowerCase(Locale.ROOT)).toList();
        return switch (mode) {
        case "SNAKE_CASE", "LOWER_UNDERSCORE" -> String.join("_", lower);
        case "KEBAB_CASE", "LISP_CASE", "SPINAL_CASE", "LOWER_HYPHEN" -> String.join("-", lower);
        case "TRAIN_CASE" -> String.join("-", lower).toUpperCase(Locale.ROOT);
        case "UPPER_UNDERSCORE" -> String.join("_", lower).toUpperCase(Locale.ROOT);
        case "LOWER_CAMEL_CASE", "UPPER_CAMEL_CASE" -> {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < lower.size(); i++) {
                String word = lower.get(i);
                int firstEnd = word.offsetByCodePoints(0, 1);
                result.append(i == 0 && mode.equals("LOWER_CAMEL_CASE") ? word
                        : word.substring(0, firstEnd).toUpperCase(Locale.ROOT) + word.substring(firstEnd));
            }
            yield result.toString();
        }
        default -> text;
        };
    }
}
