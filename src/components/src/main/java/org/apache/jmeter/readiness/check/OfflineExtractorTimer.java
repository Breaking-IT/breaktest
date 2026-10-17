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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.readiness.evidence.ExtractorCapture;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;

/**
 * Measures extractors again against the responses captured during the readiness validation, without sending any
 * request. Each run uses a scratch variables context and a copy of the response, so neither the plan's variables nor
 * the captured response change. As during validation, the response is decoded before timing starts.
 */
public final class OfflineExtractorTimer {

    private OfflineExtractorTimer() {
    }

    /**
     * @param segment main readiness validation run
     * @param model in-scope plan; only its supported extractors are measured
     * @param runs runs per captured response
     * @param cancelled tells when to stop early
     * @return extractor source id to processing times in nanoseconds
     */
    public static Map<String, List<Long>> measure(RunSegment segment, PlanModel model, int runs,
            BooleanSupplier cancelled) {
        Map<String, List<Long>> result = new LinkedHashMap<>();
        if (runs <= 0) {
            return result;
        }
        JMeterContext context = JMeterContextService.getContext();
        JMeterVariables savedVariables = context.getVariables();
        SampleResult savedPrevious = context.getPreviousResult();
        Sampler savedSampler = context.getCurrentSampler();
        try {
            for (ExtractorCapture capture : segment.captures().values()) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                if (model.nodesWithId(capture.extractorId()).isEmpty()
                        || ExtractorSupport.describe(capture.extractor()).isEmpty()
                        || !(capture.extractor() instanceof PostProcessor postProcessor)) {
                    continue;
                }
                List<Long> times = new ArrayList<>();
                TestElement extractor = capture.extractor();
                for (int i = 0; i < runs && !cancelled.getAsBoolean(); i++) {
                    SampleResult response = new SampleResult(capture.response());
                    response.getResponseDataAsString();
                    context.setVariables(new JMeterVariables());
                    context.setPreviousResult(response);
                    TestBeanHelper.prepare(extractor);
                    long started = System.nanoTime();
                    try {
                        postProcessor.process();
                    } catch (RuntimeException e) {
                        break; // an extractor failing offline is not timed further
                    }
                    times.add(System.nanoTime() - started);
                }
                result.put(capture.extractorId(), times);
            }
        } finally {
            context.setVariables(savedVariables);
            context.setPreviousResult(savedPrevious);
            context.setCurrentSampler(savedSampler);
        }
        return result;
    }
}
