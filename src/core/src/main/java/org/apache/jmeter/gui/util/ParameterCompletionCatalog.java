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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.functions.Function;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JSR223TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** An in-memory snapshot of names, never values, available to an editor. */
public final class ParameterCompletionCatalog {
    private static final Logger log = LoggerFactory.getLogger(ParameterCompletionCatalog.class);

    private ParameterCompletionCatalog() {
    }

    // Serialized property names keep core independent of the optional components module.
    private static final String[][] EXTRACTORS = {
        {"RegexExtractor.refname", "RegexExtractor.match_number"},
        {"BoundaryExtractor.refname", "BoundaryExtractor.match_number"},
        {"HtmlExtractor.refname", "HtmlExtractor.match_number"},
        {"XPathExtractor.refname", "XPathExtractor.matchNumber"},
        {"XPathExtractor2.refname", "XPathExtractor2.matchNumber"},
        {"JMESExtractor.referenceName", "JMESExtractor.matchNumber"},
        {"JSONPostProcessor.referenceNames", "JSONPostProcessor.match_numbers"}
    };

    /** Selection offsets are relative to the complete replacement, including the opening ${. */
    record Suggestion(String name, String replacement, int selectionStart, int selectionLength) {
        static Suggestion variable(String name) {
            String text = "${" + name + "}";
            return new Suggestion(name, text, text.length(), 0);
        }

        static Suggestion indexed(String name) {
            String text = "${" + name + "_1}";
            return new Suggestion(name + "_n", text, text.length() - 2, 1);
        }

        static Suggestion group(String name) {
            String text = "${" + name + "_g1}";
            return new Suggestion(name + "_gn", text, text.length() - 2, 1);
        }

        static Suggestion function(String name, boolean arguments) {
            String text = "${" + name + (arguments ? "()" : "") + "}";
            return new Suggestion(name, text, arguments ? text.length() - 2 : text.length(), 0);
        }

        @Override
        public String toString() {
            return "${" + name + (replacement.endsWith("()}") ? "()" : "") + "}";
        }
    }

    static List<Suggestion> variables(JMeterTreeNode selected) {
        if (selected == null) {
            return List.of();
        }
        JMeterTreeNode group = null;
        JMeterTreeNode plan = selected;
        for (JMeterTreeNode node = selected; node != null; node = (JMeterTreeNode) node.getParent()) {
            if (node.getTestElement() instanceof AbstractThreadGroup) {
                group = node;
            }
            if (node.getTestElement() instanceof TestPlan) {
                plan = node;
                break;
            }
        }
        Map<String, Suggestion> names = new TreeMap<>();
        collect(plan, group, names);
        return List.copyOf(names.values());
    }

    private static void collect(JMeterTreeNode node, JMeterTreeNode group, Map<String, Suggestion> names) {
        if (!node.isEnabled() || node.getTestElement() instanceof AbstractThreadGroup && node != group) {
            return;
        }
        TestElement element = node.getTestElement();
        if (element instanceof TestPlan plan) {
            Object variables = plan.getUserDefinedVariablesAsProperty().getObjectValue();
            if (variables instanceof Arguments arguments) {
                addArguments(arguments, names);
            }
        } else if (element instanceof Arguments arguments) {
            addArguments(arguments, names);
        }
        if (element.getClass().getSimpleName().equals("CSVDataSet")) {
            for (String name : element.getPropertyAsString("variableNames").split(",")) {
                add(names, name);
            }
        }
        add(names, element.getPropertyAsString("CounterConfig.name"));
        if (element.getProperty("UserParameters.names") instanceof CollectionProperty parameters) {
            parameters.forEach(property -> add(names, property.getStringValue()));
        }
        if (element instanceof JSR223TestElement && element.getPropertyAsString("filename").isBlank()) {
            // Read persisted TestBean properties: the bean getters can still hold unprepared defaults.
            ScriptVariableNames.find(element.getPropertyAsString("script"), element.getPropertyAsString("scriptLanguage"))
                    .forEach(name -> add(names, name));
        }
        for (String[] extractor : EXTRACTORS) {
            String references = element.getPropertyAsString(extractor[0]);
            String[] refs = extractor[0].startsWith("JSONPostProcessor")
                    ? references.split(";", -1) : new String[] {references};
            String[] matches = element.getPropertyAsString(extractor[1]).split(";", -1);
            for (int i = 0; i < refs.length; i++) {
                String name = refs[i].trim();
                if (!validName(name)) {
                    continue;
                }
                add(names, name);
                String match = i < matches.length ? matches[i].trim() : "";
                boolean all = negative(match);
                boolean xpath = extractor[0].startsWith("XPath");
                boolean json = extractor[0].startsWith("JSONPostProcessor");
                if (all || xpath || extractor[0].startsWith("JMES")
                        || json && !match.isEmpty() && !match.equals("0")) {
                    add(names, name + "_matchNr");
                }
                if (all || xpath) {
                    Suggestion indexed = Suggestion.indexed(name);
                    names.put(indexed.name(), indexed);
                    add(names, name + "_1");
                }
                if (extractor[0].startsWith("Regex")) {
                    if (all) {
                        add(names, name + "_rand");
                        add(names, name + "_1_g");
                        add(names, name + "_1_g0");
                        Suggestion capture = Suggestion.group(name + "_1");
                        names.put(capture.name(), capture);
                    } else {
                        add(names, name + "_g");
                        add(names, name + "_g0");
                        Suggestion capture = Suggestion.group(name);
                        names.put(capture.name(), capture);
                    }
                }
                if (json && all && element.getPropertyAsBoolean("JSONPostProcessor.compute_concat")) {
                    add(names, name + "_ALL");
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect((JMeterTreeNode) node.getChildAt(i), group, names);
        }
    }

    private static boolean negative(String value) {
        try {
            return Integer.parseInt(value) < 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void addArguments(Arguments arguments, Map<String, Suggestion> names) {
        for (int i = 0; i < arguments.getArgumentCount(); i++) {
            if (arguments.getArgument(i).isEnabled()) {
                add(names, arguments.getArgument(i).getName());
            }
        }
    }

    private static void add(Map<String, Suggestion> names, String value) {
        String name = value.trim();
        if (validName(name)) {
            names.putIfAbsent(name, Suggestion.variable(name));
        }
    }

    private static boolean validName(String name) {
        return !name.isEmpty() && !name.contains("${") && !name.contains("}");
    }

    static List<Suggestion> functions() {
        return Functions.SUGGESTIONS;
    }

    private static final class Functions {
        private static final List<Suggestion> SUGGESTIONS = load();

        private static List<Suggestion> load() {
            List<Suggestion> result = new ArrayList<>();
            for (String name : CompoundVariable.getFunctionNames()) {
                if (!name.startsWith("__")) {
                    continue;
                }
                try {
                    Function function = CompoundVariable.getFunctionClass(name).getDeclaredConstructor().newInstance();
                    result.add(Suggestion.function(name, !function.getArgumentDesc().isEmpty()));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    log.debug("Cannot read function metadata for {}", name, e);
                }
            }
            result.sort(java.util.Comparator.comparing(Suggestion::name));
            return List.copyOf(result);
        }
    }
}
