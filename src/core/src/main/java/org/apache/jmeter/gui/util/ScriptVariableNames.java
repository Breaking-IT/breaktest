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

package org.apache.jmeter.gui.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative lexical discovery of literal vars.put names; never compiles or evaluates scripts. */
final class ScriptVariableNames {
    private ScriptVariableNames() {
    }

    static Set<String> find(String script, String language) {
        List<Token> tokens = tokenize(script, language.toLowerCase(Locale.ROOT));
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i + 5 < tokens.size(); i++) {
            if ((i == 0 || !tokens.get(i - 1).is("."))
                    && tokens.get(i).is("vars") && tokens.get(i + 1).is(".")
                    && tokens.get(i + 2).is("put") && tokens.get(i + 3).is("(")
                    && tokens.get(i + 4).literal() && tokens.get(i + 5).is(",")) {
                String name = tokens.get(i + 4).text();
                if (!name.isBlank() && name.chars().noneMatch(Character::isISOControl)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private record Token(String text, boolean literal) {
        boolean is(String value) {
            return !literal && text.equals(value);
        }
    }

    private static List<Token> tokenize(String script, String language) {
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < script.length();) {
            char c = script.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (script.startsWith("//", i) || c == '#') {
                while (i < script.length() && script.charAt(i) != '\n' && script.charAt(i) != '\r') {
                    i++;
                }
            } else if (script.startsWith("/*", i)) {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? script.length() : end + 2;
            } else if (script.startsWith("$/", i)) {
                int end = script.indexOf("/$", i + 2);
                tokens.add(new Token("", true));
                i = end < 0 ? script.length() : end + 2;
            } else if (c == '\'' || c == '"' || c == '`' || c == '/' && startsRegex(tokens, language)) {
                boolean triple = (c == '\'' || c == '"') && script.startsWith(String.valueOf(c).repeat(3), i);
                String delimiter = String.valueOf(c).repeat(triple ? 3 : 1);
                int start = i + delimiter.length();
                i = start;
                boolean constant = c != '`' && c != '/';
                StringBuilder value = new StringBuilder();
                while (i < script.length() && !script.startsWith(delimiter, i)) {
                    char next = script.charAt(i++);
                    if (next == '\\' && i < script.length()) {
                        char escaped = script.charAt(i++);
                        // Other escape sequences are deliberately not guessed across languages.
                        if (escaped != '\\' && escaped != '\'' && escaped != '"' && escaped != '$') {
                            constant = false;
                        }
                        value.append(escaped);
                    } else {
                        if (c == '"' && interpolated(script, i - 1, language)) {
                            constant = false;
                        }
                        value.append(next);
                    }
                }
                constant &= i < script.length();
                i = Math.min(script.length(), i + delimiter.length());
                // Even unsupported or incomplete strings remain a single token, hiding their contents.
                tokens.add(new Token(constant ? value.toString() : "", true));
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i++;
                while (i < script.length() && Character.isJavaIdentifierPart(script.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(script.substring(start, i), false));
            } else {
                tokens.add(new Token(String.valueOf(c), false));
                i++;
            }
        }
        return tokens;
    }

    private static boolean interpolated(String script, int offset, String language) {
        if (offset + 1 >= script.length()) {
            return false;
        }
        char current = script.charAt(offset);
        char next = script.charAt(offset + 1);
        if (language.isEmpty() || language.equals("groovy") || language.equals("kotlin")) {
            return current == '$' && (next == '{' || Character.isJavaIdentifierStart(next));
        }
        return language.equals("ruby") && current == '#' && next == '{';
    }

    private static boolean startsRegex(List<Token> tokens, String language) {
        if (!language.isEmpty() && !language.equals("groovy") && !language.equals("javascript")) {
            return false;
        }
        if (tokens.isEmpty()) {
            return true;
        }
        Token previous = tokens.get(tokens.size() - 1);
        return previous.is("=") || previous.is("(") || previous.is(",") || previous.is(":")
                || previous.is("~") || previous.is("return");
    }
}
