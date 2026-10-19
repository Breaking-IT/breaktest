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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.WhileController;
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

import com.google.auto.service.AutoService;

/**
 * Response body storage: suggests <code>Store on error only</code> for HTTP requests whose successful response bodies
 * nothing needs, to save memory under load. Only a suggestion; it never affects readiness.
 * <p>
 * Assertions and post-processors applying to a request are not a reason to keep <code>Store response</code>: the
 * sampler keeps the body automatically while they apply, and validation always keeps bodies. Bodies must stay
 * stored when embedded resources are retrieved from them, when something outside the request's own package reads them
 * (the next sampler's pre-processors and timers, a following script sampler, or controller conditions, through
 * <code>prev</code> or the previous result), or when a listener saves successful response data.
 */
@AutoService(ReadinessCheck.class)
public final class ResponseBodyStorageCheck implements ReadinessCheck {
    private static final String MODE = HTTPSamplerBaseSchema.INSTANCE.getResponseProcessingMode().getName();
    private static final String EMBEDDED = HTTPSamplerBaseSchema.INSTANCE.getRetrieveEmbeddedResources().getName();
    private static final Pattern BODY_READ = Pattern.compile(
            "(?:\\bprev|getPreviousResult\\(\\s*\\))\\s*\\??\\.\\s*(?:get)?[rR]esponseData"); // $NON-NLS-1$
    private static final Pattern PREVIOUS_RESULT = Pattern.compile(
            "\\bprev\\s*\\??\\.|getPreviousResult"); // $NON-NLS-1$

    /** What happens to a request's successful bodies with a discarding mode. */
    enum Use {
        /** Nothing reads them: discarding saves memory */
        DISCARD,
        /** Its own assertions or post-processors read them; the sampler keeps them automatically */
        AUTOMATIC,
        /** They must stay stored: set Store response explicitly */
        KEEP,
        /** Something may read them */
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
    }

    /**
     * @param use what happens to the bodies
     * @param reasons why
     */
    record Assessment(Use use, List<String> reasons) {
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
        List<Finding> findings = new ArrayList<>();
        List<PlanNode> inherited = samplers.stream().filter(s -> modes.get(s).mode()
                == ResponseProcessingMode.STORE_COMPRESSED && modes.get(s).inherited(s)).toList();
        int discard = 0;
        int automatic = 0;
        List<String> keep = new ArrayList<>();
        List<String> review = new ArrayList<>();
        List<PlanNode> discarding = new ArrayList<>();
        for (PlanNode sampler : inherited) {
            Assessment assessment = uses.get(sampler);
            switch (assessment.use()) {
                case DISCARD -> {
                    discard++;
                    discarding.add(sampler);
                }
                case AUTOMATIC -> automatic++;
                case KEEP -> keep.add(sampler.name() + " (" + String.join("; ", assessment.reasons()) + ")");
                case UNCERTAIN -> review.add(sampler.name() + " (" + String.join("; ", assessment.reasons()) + ")");
            }
        }
        boolean most = inherited.size() * 2 > samplers.size();
        if (most && discard > 0) {
            PlanNode anchor = anchor(inherited, modes);
            List<String> details = new ArrayList<>();
            details.add(automatic + (automatic == 1 ? " request keeps its body" : " requests keep their bodies")
                    + " automatically while assertions or extractors apply to them.");
            if (!keep.isEmpty()) {
                details.add("Set explicitly to Store response: " + String.join(", ", keep));
            }
            if (!review.isEmpty()) {
                details.add("Possibly reads the body, review before changing: " + String.join(", ", review));
            }
            sizeEstimate(evidence, discarding, details);
            details.add("Store on error only keeps error bodies for triage; Fetch and discard saves the same in steady"
                    + " state but drops them too.");
            findings.add(CheckContext.finding(id(), anchor, "defaults", Severity.SUGGESTION,
                    "Set Processing mode to Store on error only to save memory: " + discard + " of "
                            + samplers.size() + " requests would discard successful bodies", details));
        }
        for (PlanNode sampler : samplers) {
            Resolved resolved = modes.get(sampler);
            Assessment assessment = uses.get(sampler);
            boolean store = resolved.mode() == ResponseProcessingMode.STORE_COMPRESSED;
            boolean discardsBodies = resolved.mode() == ResponseProcessingMode.STORE_ON_ERROR
                    || resolved.mode() == ResponseProcessingMode.FETCH_AND_DISCARD;
            if (store && assessment.use() == Use.DISCARD && (!resolved.inherited(sampler) || !most)) {
                List<String> details = new ArrayList<>();
                sizeEstimate(evidence, List.of(sampler), details);
                findings.add(CheckContext.finding(id(), sampler, "store", Severity.SUGGESTION,
                        "'" + sampler.name() + "' stores successful bodies nothing reads: Store on error only would"
                                + " save memory", details));
            } else if (discardsBodies && assessment.use() == Use.KEEP) {
                // Validation always stores bodies, so a run would not show the problem
                findings.add(CheckContext.finding(id(), sampler, "keep", Severity.SUGGESTION,
                        "'" + sampler.name() + "' discards successful bodies that are needed: set Processing mode"
                                + " to Store response", assessment.reasons()));
            }
        }
        long kept = samplers.stream().filter(s -> uses.get(s).use() == Use.KEEP).count();
        return CheckOutcome.of(findings, true, samplers.size() + " HTTP requests · "
                + samplers.stream().filter(s -> uses.get(s).use() == Use.DISCARD).count()
                + " with bodies nothing reads · " + kept + " need stored bodies");
    }

    private static PlanNode anchor(List<PlanNode> inherited, Map<PlanNode, Resolved> modes) {
        for (PlanNode sampler : inherited) {
            if (modes.get(sampler).source() != null) {
                return modes.get(sampler).source();
            }
        }
        for (PlanNode config : inherited.get(0).applicable(ConfigTestElement.class)) {
            if (isHttpDefaults(config.element())) {
                return config;
            }
        }
        PlanNode group = inherited.get(0).threadGroup();
        return group != null ? group : inherited.get(0).ancestors().get(inherited.get(0).ancestors().size() - 1);
    }

    private static boolean isHttpDefaults(TestElement element) {
        return element.getClass() == ConfigTestElement.class
                && (element.getPropertyAsString(TestElement.GUI_CLASS).endsWith("HttpDefaultsGui") // $NON-NLS-1$
                        || element.getPropertyOrNull(MODE) != null
                        || element.getPropertyOrNull("HTTPSampler.domain") != null); // $NON-NLS-1$
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
     * What happens to the successful bodies of a request if they were discarded.
     */
    static Assessment assess(PlanNode sampler) {
        List<String> keep = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        if (embeddedResources(sampler)) {
            keep.add("retrieves embedded resources from its body");
        }
        for (PlanNode reader : readersAfter(sampler)) {
            switch (reads(reader.element())) {
                case KEEP -> keep.add("read by " + describe(reader));
                case UNCERTAIN -> uncertain.add("previous result used by " + describe(reader));
                default -> {
                }
            }
        }
        for (PlanNode listener : sampler.applicable(SampleListener.class)) {
            TestElement element = listener.element();
            if (element instanceof PostProcessor || element instanceof Assertion || element instanceof Timer
                    || element instanceof PreProcessor || element instanceof ConfigElement) {
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
            } else {
                uncertain.add("listener '" + listener.name() + "' (" + element.getClass().getSimpleName() + ")");
            }
        }
        if (!keep.isEmpty()) {
            return new Assessment(Use.KEEP, keep);
        }
        if (!uncertain.isEmpty()) {
            return new Assessment(Use.UNCERTAIN, uncertain);
        }
        boolean ownConsumers = !sampler.applicable(Assertion.class).isEmpty()
                || !sampler.applicable(PostProcessor.class).isEmpty();
        return new Assessment(ownConsumers ? Use.AUTOMATIC : Use.DISCARD, List.of());
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

    /**
     * Elements that see this request's result as the previous result: the next sampler's pre-processors, timers and
     * own script, controllers evaluated before it, and While controllers re-evaluated after the request.
     */
    private static List<PlanNode> readersAfter(PlanNode sampler) {
        List<PlanNode> readers = new ArrayList<>();
        PlanNode group = sampler.threadGroup();
        List<PlanNode> flow = group == null ? List.of() : group.subtree();
        int end = sampler.subtree().get(sampler.subtree().size() - 1).order();
        PlanNode next = null;
        for (PlanNode node : flow) {
            if (node.order() > end && node.isSampler()) {
                next = node;
                break;
            }
        }
        for (PlanNode node : flow) {
            if (node.order() > end && (next == null || node.order() <= next.order()) && node.isController()) {
                readers.add(node);
            }
        }
        for (PlanNode ancestor : sampler.ancestors()) {
            if (ancestor.element() instanceof WhileController) {
                readers.add(ancestor);
            }
            if (ancestor.element() instanceof AbstractThreadGroup) {
                break;
            }
        }
        if (next != null) {
            if (isScriptSampler(next.element())) {
                readers.add(next);
            }
            readers.addAll(next.applicable(PreProcessor.class));
            readers.addAll(next.applicable(Timer.class));
        }
        return readers;
    }

    private static boolean isScriptSampler(TestElement sampler) {
        String name = sampler.getClass().getSimpleName();
        return sampler instanceof ScriptingTestElement || name.contains("JSR223") || name.contains("BeanShell"); // $NON-NLS-1$
    }

    private static Use reads(TestElement element) {
        StringBuilder text = new StringBuilder();
        PropertyTexts.of(element).values().forEach(value -> text.append(value).append('\n'));
        if (element instanceof ScriptingTestElement script) {
            ScriptingTestElement prepared = Beans.prepared(script);
            if (prepared.getScript() != null) {
                text.append(prepared.getScript());
            }
            if (prepared.getFilename() != null && !prepared.getFilename().isEmpty()) {
                return Use.UNCERTAIN;
            }
        }
        if (BODY_READ.matcher(text).find()) {
            return Use.KEEP;
        }
        return PREVIOUS_RESULT.matcher(text).find() ? Use.UNCERTAIN : Use.DISCARD;
    }

    private static String describe(PlanNode node) {
        String type = node.element() instanceof Controller ? "condition of" : "";
        return (type.isEmpty() ? "" : type + " ") + "'" + node.name() + "'";
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
