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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds variable references in JMeter expressions and scripts. Recognizes <code>${name}</code>, function calls
 * <code>${__func(...)}</code> (whose arguments are parsed too), escaped <code>\${</code>, and dynamic references whose name is
 * only known when the test runs (<code>${__V(...)}</code>, <code>${a_${i}}</code>, <code>vars.get(expr)</code>).
 */
public final class VariableReferences {
    /** Functions reading a variable whose name they compute */
    private static final Set<String> DYNAMIC_FUNCTIONS = Set.of("__V", "__eval", "__evalVar"); // $NON-NLS-1$
    /** Functions that can store their result in a variable named by an argument */
    private static final Set<String> STORING_FUNCTIONS = Set.of("__Random", "__RandomString", "__RandomDate", // $NON-NLS-1$
            "__counter", "__time", "__timeShift", "__intSum", "__longSum", "__StringFromFile", "__split", // $NON-NLS-1$
            "__regexFunction", "__javaScript", "__groovy", "__jexl2", "__jexl3", "__machineName", // $NON-NLS-1$
            "__machineIP", "__dateTimeConvert", "__digest", "__changeCase", "__property", // $NON-NLS-1$
            "__RandomFromMultipleVars", "__escapeOroRegexpChars", "__strLen", "__substring"); // $NON-NLS-1$
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][\\w.\\-]*"); // $NON-NLS-1$
    private static final Pattern VARS_ACCESS = Pattern.compile(
            "\\bvars\\.(get|getObject|put|putObject|remove)\\s*\\(\\s*([\"'])((?:[^\"'\\\\]|\\\\.)*)\\2"); // $NON-NLS-1$
    private static final Pattern VARS_DYNAMIC = Pattern.compile(
            "\\bvars\\.(get|getObject|put|putObject|entrySet|getIterator)\\s*\\(\\s*(?![\"'])"); // $NON-NLS-1$

    /** A reference found in a text. */
    public record Reference(String name, int start, int end) {
    }

    /**
     * What a text references.
     *
     * @param references plain variable references, with positions in the text
     * @param functions names of the functions called
     * @param dynamic whether the text reads variables whose names are computed at run time
     * @param possiblyDefined names functions in the text may store results in
     */
    public record Result(List<Reference> references, Set<String> functions, boolean dynamic,
            Set<String> possiblyDefined) {
        public Set<String> names() {
            Set<String> names = new LinkedHashSet<>();
            references.forEach(r -> names.add(r.name()));
            return names;
        }
    }

    /**
     * Variable access of a script.
     *
     * @param reads names read with {@code vars.get("...")}
     * @param writes names written with {@code vars.put("...")}
     * @param dynamicReads whether the script reads variables with computed names
     * @param dynamicWrites whether the script writes variables with computed names
     */
    public record ScriptAccess(Set<String> reads, Set<String> writes, boolean dynamicReads, boolean dynamicWrites) {
    }

    private VariableReferences() {
    }

    /**
     * @param text a JMeter expression
     * @return what it references
     */
    public static Result parse(String text) {
        List<Reference> references = new ArrayList<>();
        Set<String> functions = new LinkedHashSet<>();
        Set<String> possiblyDefined = new LinkedHashSet<>();
        boolean[] dynamic = {false};
        if (text != null && text.contains("${")) {
            parse(text, 0, text.length(), references, functions, possiblyDefined, dynamic);
        }
        return new Result(references, functions, dynamic[0], possiblyDefined);
    }

    private static void parse(String text, int from, int to, List<Reference> references, Set<String> functions,
            Set<String> possiblyDefined, boolean[] dynamic) {
        int i = from;
        while (i < to - 1) {
            int start = text.indexOf("${", i);
            if (start < 0 || start >= to - 1) {
                return;
            }
            if (start > 0 && text.charAt(start - 1) == '\\') {
                i = start + 2; // escaped: a literal "${"
                continue;
            }
            int end = matchingBrace(text, start + 2, to);
            if (end < 0) {
                return; // unterminated: JMeter sends it literally, nothing is referenced
            }
            String content = text.substring(start + 2, end);
            if (content.startsWith("__")) {
                parseFunction(text, start + 2, end, references, functions, possiblyDefined, dynamic);
            } else if (content.contains("${")) {
                dynamic[0] = true;
                parse(text, start + 2, end, references, functions, possiblyDefined, dynamic);
            } else if (!content.isEmpty()) {
                references.add(new Reference(content, start, end + 1));
            }
            i = end + 1;
        }
    }

    private static void parseFunction(String text, int from, int to, List<Reference> references,
            Set<String> functions, Set<String> possiblyDefined, boolean[] dynamic) {
        int paren = text.indexOf('(', from);
        String name = paren < 0 || paren > to ? text.substring(from, to) : text.substring(from, paren);
        functions.add(name);
        if (paren < 0 || paren > to) {
            return;
        }
        int close = text.lastIndexOf(')', to);
        int argsEnd = close > paren ? close : to;
        List<int[]> args = splitArguments(text, paren + 1, argsEnd);
        if (DYNAMIC_FUNCTIONS.contains(name)) {
            dynamic[0] = true;
        }
        for (int[] arg : args) {
            parse(text, arg[0], arg[1], references, functions, possiblyDefined, dynamic);
        }
        if ("__evalVar".equals(name) && args.size() == 1) {
            String var = text.substring(args.get(0)[0], args.get(0)[1]).trim();
            if (IDENTIFIER.matcher(var).matches()) {
                references.add(new Reference(var, from - 2, to + 1));
            }
        }
        if (STORING_FUNCTIONS.contains(name)) {
            for (int[] arg : args) {
                String value = text.substring(arg[0], arg[1]).trim();
                if (IDENTIFIER.matcher(value).matches()) {
                    possiblyDefined.add(value);
                }
            }
        }
        if (name.startsWith("__groovy") || name.startsWith("__javaScript") || name.startsWith("__jexl")
                || name.startsWith("__BeanShell")) {
            ScriptAccess script = scriptAccess(text.substring(paren + 1, argsEnd));
            script.reads().forEach(read -> references.add(new Reference(read, from - 2, to + 1)));
            possiblyDefined.addAll(script.writes());
            dynamic[0] |= script.dynamicReads();
        }
    }

    private static List<int[]> splitArguments(String text, int from, int to) {
        List<int[]> args = new ArrayList<>();
        int depth = 0;
        int argStart = from;
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$' && i + 1 < to && text.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(new int[]{argStart, i});
                argStart = i + 1;
            }
            i++;
        }
        args.add(new int[]{argStart, to});
        return args;
    }

    private static int matchingBrace(String text, int from, int to) {
        int depth = 0;
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$' && i + 1 < to && text.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
            i++;
        }
        return -1;
    }

    /**
     * @param script Groovy, JavaScript or BeanShell code
     * @return the variables it reads and writes through {@code vars}
     */
    public static ScriptAccess scriptAccess(String script) {
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        if (script == null || script.isEmpty()) {
            return new ScriptAccess(reads, writes, false, false);
        }
        Matcher literal = VARS_ACCESS.matcher(script);
        while (literal.find()) {
            String method = literal.group(1);
            if (method.startsWith("get")) {
                reads.add(literal.group(3));
            } else if (method.startsWith("put")) {
                writes.add(literal.group(3));
            }
        }
        boolean dynamicReads = false;
        boolean dynamicWrites = false;
        Matcher computed = VARS_DYNAMIC.matcher(script);
        while (computed.find()) {
            if (computed.group(1).startsWith("put")) {
                dynamicWrites = true;
            } else {
                dynamicReads = true;
            }
        }
        // ${name} inside a script is replaced before the script runs
        Result expressions = parse(script);
        reads.addAll(expressions.names());
        return new ScriptAccess(reads, writes, dynamicReads || expressions.dynamic(), dynamicWrites);
    }

    /**
     * @param definedName name a source defines (for instance an extractor reference name)
     * @param referencedName a referenced variable name
     * @return whether the reference reads the name or a variable of its generated family
     *     ({@code name_1}, {@code name_matchNr}, {@code name_g1}, {@code name_1_g2}, {@code name_ALL})
     */
    public static boolean isInFamily(String definedName, String referencedName) {
        if (referencedName.equals(definedName)) {
            return true;
        }
        if (!referencedName.startsWith(definedName + "_")) {
            return false;
        }
        String suffix = referencedName.substring(definedName.length() + 1);
        return suffix.matches("\\d+|matchNr|g\\d*|\\d+_g\\d+|ALL"); // $NON-NLS-1$
    }
}
