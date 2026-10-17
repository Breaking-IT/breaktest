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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.config.RandomVariableConfig;
import org.apache.jmeter.config.StructuredDataSet;
import org.apache.jmeter.control.ForeachController;
import org.apache.jmeter.modifiers.CounterConfig;
import org.apache.jmeter.modifiers.UserParameters;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.ScriptingTestElement;

/**
 * The variables defined in scope, and by which elements. Sources whose variable names cannot be determined
 * statically (scripts with computed names, script files, CSV files without a readable header, unknown
 * configuration elements) make an otherwise undefined name uncertain instead of undefined.
 */
public final class VariableSources {
    private static final Set<String> BUILT_IN = Set.of("START.MS", "START.YMD", "START.HMS", // $NON-NLS-1$
            "TESTSTART.MS", "JMeterThread.last_sample_ok", "JMeterThread.pack"); // $NON-NLS-1$
    /** Configuration and processing elements known not to define variables */
    private static final Set<String> NON_DEFINING = Set.of(
            "org.apache.jmeter.config.ConfigTestElement", // $NON-NLS-1$
            "org.apache.jmeter.protocol.http.control.HeaderManager", // $NON-NLS-1$
            "org.apache.jmeter.protocol.http.control.CookieManager", // $NON-NLS-1$
            "org.apache.jmeter.protocol.http.control.CacheManager", // $NON-NLS-1$
            "org.apache.jmeter.protocol.http.control.AuthManager", // $NON-NLS-1$
            "org.apache.jmeter.protocol.http.control.DNSCacheManager", // $NON-NLS-1$
            "org.apache.jmeter.protocol.http.modifier.AnchorModifier", // $NON-NLS-1$
            "org.apache.jmeter.protocol.http.modifier.URLRewritingModifier", // $NON-NLS-1$
            "org.apache.jmeter.config.KeystoreConfig", // $NON-NLS-1$
            "org.apache.jmeter.config.LoginConfig", // $NON-NLS-1$
            "org.apache.jmeter.config.ClientCertificateConfig", // $NON-NLS-1$
            "org.apache.jmeter.protocol.jdbc.config.DataSourceElement", // $NON-NLS-1$
            "org.apache.jmeter.modifiers.SampleTimeout", // $NON-NLS-1$
            "org.apache.jmeter.reporters.ResultAction", // $NON-NLS-1$
            "org.apache.jmeter.extractor.DebugPostProcessor"); // $NON-NLS-1$

    /** How a name is defined. */
    public enum Status {
        DEFINED,
        /** Only a function or a script may define it, or some source's names are unknown */
        UNCERTAIN,
        UNDEFINED
    }

    /**
     * @param status how the name is defined
     * @param sources elements that define it (or may define it)
     */
    public record Lookup(Status status, List<PlanNode> sources) {
    }

    private final Map<String, List<PlanNode>> exact = new LinkedHashMap<>();
    private final Map<String, List<PlanNode>> families = new LinkedHashMap<>();
    private final Map<String, List<PlanNode>> prefixes = new LinkedHashMap<>();
    private final Map<String, List<PlanNode>> possible = new LinkedHashMap<>();
    private final List<PlanNode> unknownSources = new ArrayList<>();

    private VariableSources() {
    }

    /**
     * @param model in-scope plan
     * @return its variable sources
     */
    public static VariableSources collect(PlanModel model) {
        VariableSources sources = new VariableSources();
        for (PlanNode node : model.nodes()) {
            sources.addSource(node);
            for (String text : PropertyTexts.of(node.element()).values()) {
                VariableReferences.Result parsed = VariableReferences.parse(text);
                parsed.possiblyDefined().forEach(name -> add(sources.possible, name, node));
            }
        }
        return sources;
    }

    private void addSource(PlanNode node) {
        TestElement element = node.element();
        if (element instanceof TestPlan plan) {
            // Read without getUserDefinedVariables(), which creates the property and so would change the plan
            if (plan.getUserDefinedVariablesAsProperty().getObjectValue() instanceof Arguments variables) {
                variables.getArgumentsAsMap().keySet().forEach(name -> add(exact, name, node));
            }
        } else if (element.getClass() == Arguments.class) {
            ((Arguments) element).getArgumentsAsMap().keySet().forEach(name -> add(exact, name, node));
        } else if (element instanceof AbstractThreadGroup group) {
            group.getProfileVariables().keySet().forEach(name -> add(exact, name, node));
        } else if (element instanceof CSVDataSet csv) {
            addCsv(Beans.prepared(csv), node);
        } else if (element instanceof StructuredDataSet data) {
            if (data.getVariablePrefix().isEmpty()) {
                unknownSources.add(node);
            } else {
                add(prefixes, data.getVariablePrefix(), node);
            }
        } else if (element instanceof UserParameters parameters) {
            for (JMeterProperty name : parameters.getNames()) {
                add(exact, name.getStringValue(), node);
            }
        } else if (element instanceof CounterConfig counter) {
            add(exact, counter.getVarName(), node);
        } else if (element instanceof RandomVariableConfig random) {
            add(exact, Beans.prepared(random).getVariableName(), node);
        } else if (element instanceof ForeachController foreach) {
            add(exact, foreach.getReturnValString(), node);
        } else if (element instanceof ScriptingTestElement script) {
            ScriptingTestElement prepared = Beans.prepared(script);
            VariableReferences.ScriptAccess access = VariableReferences.scriptAccess(prepared.getScript());
            access.writes().forEach(name -> add(exact, name, node));
            if (access.dynamicWrites() || !isEmpty(prepared.getFilename())) {
                unknownSources.add(node);
            }
        } else if (ExtractorSupport.describe(element).isPresent()) {
            ExtractorSupport.describe(element).get().refNames().forEach(name -> add(families, name, node));
        } else if ((element instanceof ConfigElement || element instanceof PreProcessor
                || element instanceof PostProcessor) && !NON_DEFINING.contains(element.getClass().getName())) {
            unknownSources.add(node);
        }
    }

    private void addCsv(CSVDataSet csv, PlanNode node) {
        String names = csv.getVariableNames();
        if (names != null && !names.trim().isEmpty()) {
            for (String name : names.split(",")) {
                add(exact, name.trim(), node);
            }
            return;
        }
        // Without variable names, the first line of the file holds them
        String header = readHeader(csv);
        if (header == null) {
            unknownSources.add(node);
            return;
        }
        String raw = csv.getDelimiter() == null ? "," : csv.getDelimiter();
        String delimiter = "\\t".equals(raw) ? "\t" : raw;
        for (String name : header.split(java.util.regex.Pattern.quote(delimiter.isEmpty() ? "," : delimiter))) {
            add(exact, name.trim().replace("\"", ""), node);
        }
    }

    static boolean isEmpty(String text) {
        return text == null || text.isEmpty();
    }

    private static String readHeader(CSVDataSet csv) {
        String filename = csv.getFilename() == null ? "" : csv.getFilename().trim();
        if (filename.isEmpty() || filename.contains("${")) {
            return null;
        }
        try {
            Path path = csv.isUseCsvFromArchive()
                    ? ArchiveFiles.resolve(csv.getCsvArchiveEntry().isEmpty()
                            ? ArchiveFiles.importEntryName(filename) : csv.getCsvArchiveEntry())
                    : FileServer.getFileServer().resolveFile(filename).toPath();
            File file = path.toFile();
            if (!file.isFile() || !file.canRead()) {
                return null;
            }
            Charset charset = csv.getFileEncoding() == null || csv.getFileEncoding().isEmpty()
                    ? StandardCharsets.UTF_8 : Charset.forName(csv.getFileEncoding());
            try (BufferedReader reader = Files.newBufferedReader(path, charset)) {
                return reader.readLine();
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static void add(Map<String, List<PlanNode>> map, String name, PlanNode node) {
        if (name != null && !name.isEmpty()) {
            map.computeIfAbsent(name, ignored -> new ArrayList<>()).add(node);
        }
    }

    /**
     * @param name a referenced variable name
     * @return whether and where it is defined
     */
    public Lookup lookup(String name) {
        if (BUILT_IN.contains(name) || name.startsWith("__jm__")) { // $NON-NLS-1$
            return new Lookup(Status.DEFINED, List.of());
        }
        List<PlanNode> definers = new ArrayList<>(exact.getOrDefault(name, List.of()));
        families.forEach((defined, nodes) -> {
            if (VariableReferences.isInFamily(defined, name)) {
                definers.addAll(nodes);
            }
        });
        prefixes.forEach((prefix, nodes) -> {
            if (name.startsWith(prefix)) {
                definers.addAll(nodes);
            }
        });
        if (!definers.isEmpty()) {
            return new Lookup(Status.DEFINED, definers);
        }
        List<PlanNode> maybe = new ArrayList<>(possible.getOrDefault(name, List.of()));
        if (!maybe.isEmpty() || !unknownSources.isEmpty()) {
            maybe.addAll(unknownSources);
            return new Lookup(Status.UNCERTAIN, maybe);
        }
        return new Lookup(Status.UNDEFINED, List.of());
    }

    /**
     * @param name a variable name
     * @return extractors defining it or a variable of its family
     */
    public List<PlanNode> extractorsDefining(String name) {
        List<PlanNode> result = new ArrayList<>();
        families.forEach((defined, nodes) -> {
            if (VariableReferences.isInFamily(defined, name)) {
                result.addAll(nodes);
            }
        });
        return result;
    }
}
