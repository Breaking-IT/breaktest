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

package org.apache.jmeter.protocol.http.readiness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase.ResponseProcessingMode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBaseSchema;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.Finding;
import org.apache.jmeter.readiness.Severity;
import org.apache.jmeter.readiness.check.Beans;
import org.apache.jmeter.readiness.check.CheckContext;
import org.apache.jmeter.readiness.check.CheckOutcome;
import org.apache.jmeter.readiness.check.PlanNode;
import org.apache.jmeter.readiness.check.PropertyTexts;
import org.apache.jmeter.readiness.check.ReadinessCheck;
import org.apache.jmeter.readiness.check.RuntimeEvidence;
import org.apache.jmeter.readiness.evidence.SampleObservation;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.reporters.ResultSaver;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleSaveConfiguration;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.util.ScriptingTestElement;
import org.apache.jmeter.visualizers.backend.BackendListener;

import com.google.auto.service.AutoService;

/**
 * Response body storage of HTTP requests.
 * <ul>
 *     <li>A script that reads the previous response body (a JSR223 or BeanShell sampler, pre-processor or timer, or
 *     a scripting function such as <code>__groovy</code> in a field or a controller condition) is a Warning when any
 *     HTTP request of its thread group discards successful bodies: which request runs just before the script depends
 *     on the flow (If controllers, loops), and validation always keeps bodies, so only a test run shows the script
 *     reading an empty body. Requests that keep their bodies because assertions or post-processors apply to them do
 *     not count.</li>
 *     <li>Without such scripts, it suggests <code>Store on error only</code> for requests whose successful bodies
 *     nothing needs, to save memory under load. Suggestions never affect readiness. Bodies stay needed when embedded
 *     resources are retrieved from them or a listener saves successful response data.</li>
 * </ul>
 */
@AutoService(ReadinessCheck.class)
public final class ResponseBodyStorageCheck implements ReadinessCheck {
    private static final String MODE = HTTPSamplerBaseSchema.INSTANCE.getResponseProcessingMode().getName();
    private static final String EMBEDDED = HTTPSamplerBaseSchema.INSTANCE.getRetrieveEmbeddedResources().getName();
    private static final Pattern BODY_READ = Pattern.compile(
            "(?:\\bprev|getPreviousResult\\(\\s*\\)|\\bpreviousResult)\\s*\\??\\.\\s*(?:get)?[rR]esponseData"); // $NON-NLS-1$
    private static final Pattern PREVIOUS_RESULT = Pattern.compile(
            "\\bprev\\b|getPreviousResult|\\bpreviousResult\\b"); // $NON-NLS-1$
    /** Scripting functions, whose code can use the previous result */
    private static final Pattern SCRIPT_FUNCTION = Pattern.compile(
            "\\$\\{__(?:groovy|javaScript|jexl2|jexl3|BeanShell)\\("); // $NON-NLS-1$
    private static final int MAX_LISTED = 10;
    /** Backend listener clients shipped with BreakTest, which send metrics and never read response bodies */
    private static final Set<String> BUILT_IN_BACKEND_CLIENTS = Set.of(
            "org.apache.jmeter.visualizers.backend.influxdb.InfluxdbBackendListenerClient", // $NON-NLS-1$
            "org.apache.jmeter.visualizers.backend.influxdb.InfluxDBRawBackendListenerClient", // $NON-NLS-1$
            "org.apache.jmeter.visualizers.backend.graphite.GraphiteBackendListenerClient"); // $NON-NLS-1$

    /** What happens to a request's successful bodies with a discarding mode. */
    enum Use {
        /** Nothing reads them: discarding saves memory */
        DISCARD,
        /** Its own assertions or post-processors read them; the sampler keeps them automatically */
        AUTOMATIC,
        /** They must stay stored: set Store response explicitly */
        KEEP,
        /** Something may read them (scripts using the previous result) */
        UNCERTAIN
    }

    /**
     * Configured mode of a request.
     *
     * @param mode the mode
     * @param source element the mode comes from: the request, an HTTP Request Defaults, or {@code null} for the
     *     built-in default
     */
    record Resolved(ResponseProcessingMode mode, PlanNode source) {
        boolean inherited(PlanNode sampler) {
            return source != sampler;
        }

        boolean discards() {
            return mode == ResponseProcessingMode.STORE_ON_ERROR || mode == ResponseProcessingMode.FETCH_AND_DISCARD;
        }

        String describe(PlanNode sampler) {
            String name = mode == ResponseProcessingMode.FETCH_AND_DISCARD ? "Fetch and discard"
                    : mode == ResponseProcessingMode.STORE_ON_ERROR ? "Store on error only" : "Store response";
            return inherited(sampler) && source != null ? name + " from '" + source.name() + "'" : name;
        }
    }

    /**
     * @param use what happens to the bodies
     * @param reasons why
     * @param ownConsumers whether assertions or post-processors apply to the request, so it keeps its bodies anyway
     * @param listenersToReview listeners that may need successful bodies (custom listeners, backend listeners with a
     *     custom client); they are listed for review and do not stop the suggestion
     */
    record Assessment(Use use, List<String> reasons, boolean ownConsumers, List<String> listenersToReview) {
    }

    /** Scripts of a thread group that use the previous result. */
    private static final class Readers {
        private final Set<PlanNode> body = new LinkedHashSet<>();
        private final Set<PlanNode> possible = new LinkedHashSet<>();
    }

    @Override
    public CheckId id() {
        return CheckId.RESPONSE_BODY_STORAGE;
    }

    @Override
    public CheckOutcome run(CheckContext context) {
        List<PlanNode> samplers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PlanNode node : context.model().nodes()) {
            if (node.element() instanceof HTTPSamplerBase && seen.add(node.sourceId())) {
                samplers.add(node);
            }
        }
        if (samplers.isEmpty()) {
            return CheckOutcome.of(List.of(), false, "No HTTP requests in scope");
        }
        RuntimeEvidence evidence = context.evidence() != null && context.evidence().isCurrent()
                ? context.evidence() : null;
        Map<PlanNode, Resolved> modes = new LinkedHashMap<>();
        Map<PlanNode, Assessment> uses = new LinkedHashMap<>();
        for (PlanNode sampler : samplers) {
            modes.put(sampler, resolve(sampler));
            uses.put(sampler, assess(sampler));
        }
        Map<PlanNode, Readers> readers = readers(context);
        List<Finding> findings = new ArrayList<>();
        warnReaders(samplers, modes, uses, readers, findings);
        suggest(samplers, modes, uses, readers, evidence, findings);
        long reading = readers.values().stream().mapToLong(r -> r.body.size()).sum();
        return CheckOutcome.of(findings, true, samplers.size() + " HTTP requests · " + reading
                + " script(s) reading the previous response body");
    }

    /**
     * A Warning on each script reading the previous response body, when requests of its thread group discard
     * successful bodies.
     */
    private void warnReaders(List<PlanNode> samplers, Map<PlanNode, Resolved> modes, Map<PlanNode, Assessment> uses,
            Map<PlanNode, Readers> readers, List<Finding> findings) {
        Set<String> reported = new HashSet<>();
        readers.forEach((group, groupReaders) -> {
            List<String> discarding = new ArrayList<>();
            for (PlanNode sampler : samplers) {
                if (sampler.threadGroup() == group && modes.get(sampler).discards()
                        && !uses.get(sampler).ownConsumers()) {
                    discarding.add("'" + sampler.name() + "' (" + modes.get(sampler).describe(sampler) + ")");
                }
            }
            if (discarding.isEmpty()) {
                return;
            }
            String listed = list(discarding);
            for (PlanNode reader : groupReaders.body) {
                if (reported.add(reader.sourceId())) {
                    findings.add(CheckContext.finding(id(), reader, "reads-previous-body", Severity.WARNING,
                            "'" + reader.name() + "' reads the previous response body, but " + discarding.size()
                                    + (discarding.size() == 1 ? " request" : " requests") + " of '" + group.name()
                                    + "' discard successful bodies",
                            List.of("Requests that can run before it: " + listed,
                                    "Which request runs just before the script depends on the flow (If controllers,"
                                            + " loops), so every request of the thread group counts. Set them, or the"
                                            + " HTTP Request Defaults, to Store response.",
                                    "Validation always keeps response bodies, so it does not show this; in a test"
                                            + " run the script reads an empty body.")));
                }
            }
            for (PlanNode reader : groupReaders.possible) {
                if (reported.add(reader.sourceId())) {
                    findings.add(CheckContext.finding(id(), reader, "may-read-previous-body", Severity.SUGGESTION,
                            "'" + reader.name() + "' uses the previous result while requests of '" + group.name()
                                    + "' discard successful bodies: review whether it reads the body",
                            List.of("Requests that can run before it: " + listed)));
                }
            }
        });
    }

    /**
     * Suggests Store on error only for requests whose successful bodies nothing needs, outside thread groups with
     * scripts that read the previous response body.
     */
    private void suggest(List<PlanNode> samplers, Map<PlanNode, Resolved> modes, Map<PlanNode, Assessment> uses,
            Map<PlanNode, Readers> readers, RuntimeEvidence evidence, List<Finding> findings) {
        List<PlanNode> inherited = samplers.stream().filter(s -> modes.get(s).mode()
                == ResponseProcessingMode.STORE_COMPRESSED && modes.get(s).inherited(s)).toList();
        int automatic = 0;
        List<String> keep = new ArrayList<>();
        Set<String> listeners = new LinkedHashSet<>();
        List<PlanNode> discarding = new ArrayList<>();
        Set<PlanNode> readingGroups = new LinkedHashSet<>();
        Set<String> possibleReaders = new LinkedHashSet<>();
        for (PlanNode sampler : inherited) {
            Readers groupReaders = readers.get(sampler.threadGroup());
            if (groupReaders != null && !groupReaders.body.isEmpty()) {
                readingGroups.add(sampler.threadGroup());
                continue;
            }
            Assessment assessment = uses.get(sampler);
            if (groupReaders != null) {
                groupReaders.possible.forEach(reader -> possibleReaders.add("'" + reader.name() + "'"));
            }
            switch (assessment.use()) {
                case DISCARD, UNCERTAIN -> {
                    discarding.add(sampler);
                    listeners.addAll(assessment.listenersToReview());
                }
                case AUTOMATIC -> automatic++;
                case KEEP -> keep.add(sampler.name() + " (" + String.join("; ", assessment.reasons()) + ")");
            }
        }
        boolean most = inherited.size() * 2 > samplers.size();
        if (most && !discarding.isEmpty()) {
            List<String> details = new ArrayList<>();
            details.add(automatic + (automatic == 1 ? " request keeps its body" : " requests keep their bodies")
                    + " automatically while assertions or extractors apply to them.");
            for (PlanNode group : readingGroups) {
                details.add("Keep Store response for '" + group.name() + "': scripts there read the previous"
                        + " response body (" + list(readers.get(group).body.stream().map(PlanNode::name)
                                .map(n -> "'" + n + "'").toList()) + ")");
            }
            if (!keep.isEmpty()) {
                details.add("Set explicitly to Store response: " + list(keep));
            }
            if (!listeners.isEmpty()) {
                details.add(listenersLine(listeners));
            }
            if (!possibleReaders.isEmpty()) {
                details.add("Check first that these scripts using the previous result do not read its body: "
                        + list(new ArrayList<>(possibleReaders)));
            }
            sizeEstimate(evidence, discarding, details);
            details.add("Store on error only keeps error bodies for triage; Fetch and discard saves the same in steady"
                    + " state but drops them too.");
            findings.add(CheckContext.finding(id(), anchor(discarding, modes), "defaults", Severity.SUGGESTION,
                    "Set Processing mode to Store on error only to save memory: " + discarding.size() + " of "
                            + samplers.size() + " requests would discard successful bodies", details));
        }
        for (PlanNode sampler : samplers) {
            Resolved resolved = modes.get(sampler);
            Assessment assessment = uses.get(sampler);
            Readers groupReaders = readers.get(sampler.threadGroup());
            boolean groupReads = groupReaders != null && !groupReaders.body.isEmpty();
            if (resolved.mode() == ResponseProcessingMode.STORE_COMPRESSED && assessment.use() == Use.DISCARD
                    && !groupReads && (!resolved.inherited(sampler) || !most)) {
                List<String> details = new ArrayList<>();
                if (!assessment.listenersToReview().isEmpty()) {
                    details.add(listenersLine(assessment.listenersToReview()));
                }
                sizeEstimate(evidence, List.of(sampler), details);
                findings.add(CheckContext.finding(id(), sampler, "store", Severity.SUGGESTION,
                        "'" + sampler.name() + "' stores successful bodies nothing reads: Store on error only would"
                                + " save memory", details));
            } else if (resolved.discards() && assessment.use() == Use.KEEP) {
                findings.add(CheckContext.finding(id(), sampler, "keep", Severity.SUGGESTION,
                        "'" + sampler.name() + "' discards successful bodies (" + resolved.describe(sampler)
                                + ") that are needed: set Processing mode to Store response", assessment.reasons()));
            }
        }
    }

    private static String listenersLine(Collection<String> listeners) {
        return "Review before changing, these listeners may need successful response bodies: "
                + list(new ArrayList<>(listeners));
    }

    private static String list(List<String> items) {
        if (items.size() <= MAX_LISTED) {
            return String.join(", ", items);
        }
        return String.join(", ", items.subList(0, MAX_LISTED)) + " and " + (items.size() - MAX_LISTED) + " more";
    }

    /**
     * @return per thread group, the scripts that read the previous response body or may use the previous result.
     *     Elements outside thread groups (test plan level pre-processors and timers) count for every thread group.
     */
    private static Map<PlanNode, Readers> readers(CheckContext context) {
        Map<PlanNode, Readers> result = new LinkedHashMap<>();
        List<PlanNode> planLevel = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PlanNode node : context.model().nodes()) {
            if (!seen.add(node.sourceId() + "@" + node.threadGroup()) || !mayUsePreviousResult(node.element())) {
                continue;
            }
            Use use = reads(node.element());
            if (use == Use.DISCARD) {
                continue;
            }
            if (node.threadGroup() == null) {
                planLevel.add(node);
            } else {
                add(result.computeIfAbsent(node.threadGroup(), ignored -> new Readers()), node, use);
            }
        }
        if (!planLevel.isEmpty()) {
            for (PlanNode group : context.model().threadGroups()) {
                Readers groupReaders = result.computeIfAbsent(group, ignored -> new Readers());
                planLevel.forEach(node -> add(groupReaders, node, reads(node.element())));
            }
        }
        return result;
    }

    private static void add(Readers readers, PlanNode node, Use use) {
        (use == Use.KEEP ? readers.body : readers.possible).add(node);
    }

    /**
     * Elements whose code runs before a sample, when the result of the sample before is the previous result.
     * Post-processors and assertions read their own sample, which keeps its body for them.
     */
    private static boolean mayUsePreviousResult(TestElement element) {
        return !(element instanceof PostProcessor) && !(element instanceof Assertion)
                && !(element instanceof AbstractThreadGroup) && !isListenerOnly(element);
    }

    private static boolean isListenerOnly(TestElement element) {
        return element instanceof SampleListener && !(element instanceof PreProcessor) && !(element instanceof Timer)
                && !(element instanceof ConfigElement) && !(element instanceof org.apache.jmeter.samplers.Sampler)
                && !(element instanceof org.apache.jmeter.control.Controller);
    }

    /**
     * The request's own mode, else that of the nearest HTTP Request Defaults setting one, else Store response.
     */
    static Resolved resolve(PlanNode sampler) {
        ResponseProcessingMode own = parse(sampler.element().getPropertyAsString(MODE));
        if (own != null) {
            return new Resolved(own, sampler);
        }
        for (PlanNode config : sampler.applicable(ConfigElement.class)) {
            ResponseProcessingMode inherited = parse(config.element().getPropertyAsString(MODE));
            if (inherited != null) {
                return new Resolved(inherited, config);
            }
        }
        return new Resolved(ResponseProcessingMode.STORE_COMPRESSED, null);
    }

    static ResponseProcessingMode parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ResponseProcessingMode mode : ResponseProcessingMode.values()) {
            if (mode.getResourceKey().equals(value) || mode.name().equals(value)) {
                return mode;
            }
        }
        return null;
    }

    private static boolean embeddedResources(PlanNode sampler) {
        JMeterProperty own = sampler.element().getPropertyOrNull(EMBEDDED);
        if (own != null) {
            return own.getBooleanValue();
        }
        for (PlanNode config : sampler.applicable(ConfigElement.class)) {
            JMeterProperty inherited = config.element().getPropertyOrNull(EMBEDDED);
            if (inherited != null) {
                return inherited.getBooleanValue();
            }
        }
        return false;
    }

    /**
     * What a request itself needs: embedded resource retrieval and listeners saving successful responses need its
     * bodies; custom listeners may, and are listed for review; scripts reading the previous result are handled per
     * thread group. Backend listeners with a built-in client only send metrics.
     */
    static Assessment assess(PlanNode sampler) {
        List<String> keep = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        if (embeddedResources(sampler)) {
            keep.add("retrieves embedded resources from its body");
        }
        for (PlanNode listener : sampler.applicable(SampleListener.class)) {
            TestElement element = listener.element();
            if (!isListenerOnly(element)) {
                continue;
            }
            if (element instanceof ResultSaver) {
                if (!element.getPropertyAsBoolean(ResultSaver.ERRORS_ONLY)) {
                    keep.add("'" + listener.name() + "' saves successful responses");
                }
            } else if (element instanceof ResultCollector collector) {
                if (savesResponseData(collector)) {
                    keep.add("'" + listener.name() + "' saves response data");
                }
            } else if (element instanceof BackendListener backend) {
                String client = backend.getClassname() == null ? "" : backend.getClassname().trim();
                if (!BUILT_IN_BACKEND_CLIENTS.contains(client)) {
                    uncertain.add("'" + listener.name() + "' (backend listener with client " + client + ")");
                }
            } else {
                uncertain.add("'" + listener.name() + "' (" + element.getClass().getSimpleName() + ")");
            }
        }
        boolean ownConsumers = !sampler.applicable(Assertion.class).isEmpty()
                || !sampler.applicable(PostProcessor.class).isEmpty();
        if (!keep.isEmpty()) {
            return new Assessment(Use.KEEP, keep, ownConsumers, uncertain);
        }
        return new Assessment(ownConsumers ? Use.AUTOMATIC : Use.DISCARD, List.of(), ownConsumers, uncertain);
    }

    private static boolean savesResponseData(ResultCollector collector) {
        if (collector.getFilename() == null || collector.getFilename().isBlank()) {
            return false; // nothing is written
        }
        // Read without getSaveConfig(), which creates a configuration when there is none
        Object config = collector.getProperty("saveConfig").getObjectValue(); // $NON-NLS-1$
        SampleSaveConfiguration saveConfig = config instanceof SampleSaveConfiguration c ? c
                : SampleSaveConfiguration.staticConfig();
        return saveConfig.saveResponseData();
    }

    private static boolean isScriptElement(TestElement element) {
        String name = element.getClass().getSimpleName();
        return element instanceof ScriptingTestElement || name.contains("JSR223") || name.contains("BeanShell"); // $NON-NLS-1$
    }

    /**
     * Whether an element's code reads the previous response body ({@link Use#KEEP}), may use the previous result
     * ({@link Use#UNCERTAIN}) or neither ({@link Use#DISCARD}). Scripts are read whole; other elements only inside
     * scripting functions, so a path such as <code>/prev.html</code> is not mistaken for code.
     */
    static Use reads(TestElement element) {
        StringBuilder code = new StringBuilder();
        if (isScriptElement(element)) {
            PropertyTexts.of(element).values().forEach(value -> code.append(value).append('\n'));
            if (element instanceof ScriptingTestElement script) {
                ScriptingTestElement prepared = Beans.prepared(script);
                if (prepared.getScript() != null) {
                    code.append(prepared.getScript());
                }
                if (prepared.getFilename() != null && !prepared.getFilename().isEmpty()) {
                    return Use.UNCERTAIN;
                }
            }
        } else {
            for (String value : PropertyTexts.of(element).values()) {
                Matcher function = SCRIPT_FUNCTION.matcher(value);
                if (function.find()) {
                    code.append(value.substring(function.start())).append('\n');
                }
            }
        }
        if (BODY_READ.matcher(code).find()) {
            return Use.KEEP;
        }
        return PREVIOUS_RESULT.matcher(code).find() ? Use.UNCERTAIN : Use.DISCARD;
    }

    private static PlanNode anchor(List<PlanNode> requests, Map<PlanNode, Resolved> modes) {
        for (PlanNode sampler : requests) {
            if (modes.get(sampler).source() != null) {
                return modes.get(sampler).source();
            }
        }
        for (PlanNode config : requests.get(0).applicable(ConfigTestElement.class)) {
            if (isHttpDefaults(config.element())) {
                return config;
            }
        }
        PlanNode group = requests.get(0).threadGroup();
        return group != null ? group : requests.get(0).ancestors().get(requests.get(0).ancestors().size() - 1);
    }

    private static boolean isHttpDefaults(TestElement element) {
        return element.getClass() == ConfigTestElement.class
                && (element.getPropertyAsString(TestElement.GUI_CLASS).endsWith("HttpDefaultsGui") // $NON-NLS-1$
                        || element.getPropertyOrNull(MODE) != null
                        || element.getPropertyOrNull("HTTPSampler.domain") != null); // $NON-NLS-1$
    }

    private static void sizeEstimate(RuntimeEvidence evidence, List<PlanNode> samplers, List<String> details) {
        if (evidence == null || samplers.isEmpty()) {
            return;
        }
        long bytes = 0;
        Set<String> iterations = new HashSet<>();
        for (PlanNode sampler : samplers) {
            for (SampleObservation sample : evidence.samplesOf(sampler.sourceId())) {
                iterations.add(sample.thread() + "#" + sample.iteration());
                if (sample.successful()) {
                    bytes += sample.bodyBytes();
                }
            }
        }
        if (iterations.isEmpty() || bytes == 0) {
            return;
        }
        details.add(String.format(Locale.ROOT, "During validation these requests received about %.1f KB of"
                + " successful bodies per iteration and thread, which would no longer be kept",
                bytes / 1024.0 / iterations.size()));
    }
}
