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

package org.apache.jmeter.readiness.evidence;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.readiness.Redactor;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.ValidationCheckpoints;

/**
 * Collects readiness evidence while a readiness validation run executes. Every hook first reads one volatile field,
 * so ordinary test and validation runs pay nothing more. Evidence stays in memory and is never logged.
 */
public final class ReadinessRecorder {
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^{}$\\s]{1,200})}"); // $NON-NLS-1$
    private static final Pattern ENCODED_REFERENCE =
            Pattern.compile("(?i)%24%7B([^%\\s&]{1,200}?)%7D"); // $NON-NLS-1$
    private static final int MAX_FAILURES = 5;

    private static volatile RunSegment active;
    private static final ThreadLocal<Invocation> INVOCATION = new ThreadLocal<>();

    private ReadinessRecorder() {
    }

    private static final class Invocation {
        private final TestElement element;
        private final SampleResult response;
        private Boolean matched;
        private String expression = "";
        private String excerpt = "";

        private Invocation(TestElement element, SampleResult response) {
            this.element = element;
            this.response = response;
        }
    }

    /**
     * @return whether a readiness validation run is collecting evidence
     */
    public static boolean isActive() {
        return active != null;
    }

    /**
     * @return the segment collecting evidence, or {@code null}
     */
    public static RunSegment active() {
        return active;
    }

    static void begin(RunSegment segment) {
        active = segment;
    }

    static RunSegment end(RunSegment segment) {
        if (active == segment) {
            active = null;
        }
        segment.finish();
        return segment;
    }

    /**
     * Runs a post-processor, measuring its processing time when evidence is collected. The response is decoded
     * once before timing starts, so no extractor absorbs a decoding cost shared by all of them.
     *
     * @param postProcessor post-processor to run
     * @param context context of the current thread
     */
    public static void runPostProcessor(PostProcessor postProcessor, JMeterContext context) {
        RunSegment segment = active;
        if (segment == null || !(postProcessor instanceof TestElement element)) {
            postProcessor.process();
            return;
        }
        String id = ValidationCheckpoints.sourceIdOfClone(element);
        SampleResult previous = context.getPreviousResult();
        if (previous != null) {
            previous.getResponseDataAsString(); // cached by the result, untimed
            if (!id.isEmpty() && segment.wantsCapture(id)
                    && previous.getResponseData().length <= RunSegment.MAX_CAPTURED_RESPONSE_BYTES) {
                segment.addCapture(new ExtractorCapture(id, (TestElement) element.clone(),
                        new SampleResult(previous)));
            }
        }
        Invocation invocation = new Invocation(element, previous);
        Invocation outer = INVOCATION.get();
        INVOCATION.set(invocation);
        long started = System.nanoTime();
        try {
            postProcessor.process();
        } finally {
            long nanos = System.nanoTime() - started;
            if (outer == null) {
                INVOCATION.remove();
            } else {
                INVOCATION.set(outer);
            }
            if (!id.isEmpty()) {
                Sampler sampler = context.getCurrentSampler();
                segment.addExtraction(new ExtractorObservation(id,
                        sampler != null ? ValidationCheckpoints.sourceIdOfClone(sampler) : "",
                        threadName(context), iteration(context), invocation.matched, nanos,
                        segment.firstInvocation(id), invocation.expression, invocation.excerpt));
            }
        }
    }

    /**
     * Reports whether a supported extractor matched. Called by the extractor for every invocation, whatever its
     * "fail on no match" setting; the variables it set are never inspected, as they may hold a default or a stale
     * value.
     *
     * @param extractor extractor reporting
     * @param matched whether it matched
     * @param expression its expression, for evidence
     * @param anchor literal text the expected value follows in the response, used to take an excerpt; may be empty
     */
    public static void extractorOutcome(TestElement extractor, boolean matched, String expression, String anchor) {
        if (active == null) {
            return;
        }
        Invocation invocation = INVOCATION.get();
        if (invocation == null || invocation.element != extractor) {
            return;
        }
        // An extractor with several expressions matched only when all of them did
        invocation.matched = invocation.matched == null ? matched : invocation.matched && matched;
        if (expression != null && !expression.isEmpty()) {
            String combined = invocation.expression.isEmpty() ? expression : invocation.expression + " | " + expression;
            invocation.expression = Redactor.bound(combined);
        }
        if (!matched && invocation.excerpt.isEmpty() && invocation.response != null) {
            String text = invocation.response.getResponseDataAsString();
            int index = anchor == null || anchor.isEmpty() ? -1 : text.indexOf(anchor);
            invocation.excerpt = index >= 0
                    ? Redactor.excerpt(text, index, anchor.length())
                    : Redactor.excerpt(text, 0, 0);
        }
    }

    /**
     * Records a completed sample: what ran, what verified it and literal variable references in what it sent.
     *
     * @param sampler the executed sampler (validation clone)
     * @param assertions assertions that ran on it
     * @param postProcessors post-processors that ran on it
     * @param result its result
     * @param context context of the current thread
     */
    public static void sampleCompleted(Sampler sampler, Collection<?> assertions, List<?> postProcessors,
            SampleResult result, JMeterContext context) {
        RunSegment segment = active;
        if (segment == null || sampler == null) {
            return;
        }
        String samplerId = ValidationCheckpoints.sourceIdOfClone(sampler);
        if (samplerId.isEmpty()) {
            return;
        }
        Set<String> verifiers = new LinkedHashSet<>();
        addIds(verifiers, assertions);
        addIds(verifiers, postProcessors);
        List<String> failures = new ArrayList<>();
        if (!result.isSuccessful()) {
            failures.add(Redactor.bound("Response " + result.getResponseCode() + ": "
                    + Redactor.redact(result.getResponseMessage())));
        }
        for (AssertionResult assertion : result.getAssertionResults()) {
            if ((assertion.isFailure() || assertion.isError()) && failures.size() < MAX_FAILURES) {
                failures.add(Redactor.bound(assertion.getName() + ": "
                        + Redactor.redact(String.valueOf(assertion.getFailureMessage()))));
            }
        }
        String thread = threadName(context);
        int iteration = iteration(context);
        var group = context.getThreadGroup();
        String code = String.valueOf(result.getResponseCode());
        segment.addSample(new SampleObservation(samplerId, ValidationCheckpoints.executionIdOf(sampler),
                group == null ? "" : ValidationCheckpoints.sourceIdOfClone(group), thread, iteration,
                result.isSuccessful(), code, failures, verifiers, code.startsWith("3"), result.getBodySizeAsLong()));
        scanUnresolved(segment, samplerId, result, thread, iteration, new LinkedHashSet<>(), 0);
    }

    /**
     * Records that a thread stopped before finishing its iterations.
     *
     * @param context context of the stopping thread
     * @param onError whether the thread group's error policy stopped it
     * @param detail what stopped it
     */
    public static void threadStopping(JMeterContext context, boolean onError, String detail) {
        RunSegment segment = active;
        if (segment == null) {
            return;
        }
        var group = context.getThreadGroup();
        segment.groupStopped(group == null ? "" : ValidationCheckpoints.sourceIdOfClone(group),
                onError ? RunStatus.STOPPED_ON_ERROR : RunStatus.STOPPED_BY_ACTION,
                Redactor.bound(threadName(context) + ": " + detail));
    }

    private static void scanUnresolved(RunSegment segment, String samplerId, SampleResult result, String thread,
            int iteration, Set<String> seen, int depth) {
        scan(segment, samplerId, "URL", result.getUrlAsString(), thread, iteration, seen);
        scan(segment, samplerId, "request headers", result.getRequestHeaders(), thread, iteration, seen);
        scan(segment, samplerId, "request data", result.getSamplerData(), thread, iteration, seen);
        if (depth < 3) {
            for (SampleResult sub : result.getSubResults()) {
                scanUnresolved(segment, samplerId, sub, thread, iteration, seen, depth + 1);
            }
        }
    }

    private static void scan(RunSegment segment, String samplerId, String location, String text, String thread,
            int iteration, Set<String> seen) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (text.contains("${")) {
            Matcher matcher = REFERENCE.matcher(text);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (seen.add(name)) {
                    segment.addUnresolved(new UnresolvedObservation(samplerId, name, location, false, thread,
                            iteration, Redactor.excerpt(text, matcher.start(), matcher.end() - matcher.start())));
                }
            }
        }
        if (text.contains("%7B") || text.contains("%7b")) {
            Matcher matcher = ENCODED_REFERENCE.matcher(text);
            while (matcher.find()) {
                String name = decode(matcher.group(1));
                if (seen.add(name)) {
                    segment.addUnresolved(new UnresolvedObservation(samplerId, name, location, true, thread,
                            iteration, Redactor.excerpt(text, matcher.start(), matcher.end() - matcher.start())));
                }
            }
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static void addIds(Set<String> ids, Collection<?> elements) {
        if (elements == null) {
            return;
        }
        for (Object element : elements) {
            if (element instanceof TestElement testElement) {
                String id = ValidationCheckpoints.sourceIdOfClone(testElement);
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
    }

    private static String threadName(JMeterContext context) {
        JMeterThread thread = context.getThread();
        return thread == null ? Thread.currentThread().getName() : thread.getThreadName();
    }

    private static int iteration(JMeterContext context) {
        return context.getVariables() == null ? 0 : context.getVariables().getIteration();
    }
}
