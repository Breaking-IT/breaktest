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

package org.apache.jmeter.protocol.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.PreCompiler;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class SseReviewTest extends JMeterTestCase {
    @Test
    void allowsOneLargeEventButBoundsTheBacklog() {
        List<SampleResult> failures = new ArrayList<>();
        try (var handlers = new SseMessageHandlers(List.of(match()), "large", failures::add)) {
            handlers.accept(message("x".repeat(4 * 1024 * 1024 + 1)));
            assertTrue(failures.isEmpty(), "One parser-approved message must fit regardless of its byte size");
            handlers.accept(message("another"));
            assertEquals(1, failures.size());
            assertEquals("SSE_HANDLER_OVERFLOW", failures.get(0).getResponseCode());
        }
    }

    @Test
    void boundsNumberOfQueuedMessages() {
        List<SampleResult> failures = new ArrayList<>();
        try (var handlers = new SseMessageHandlers(List.of(match()), "queue", failures::add)) {
            for (int i = 0; i < 64; i++) {
                handlers.accept(message("x"));
            }
            assertTrue(failures.isEmpty());
            handlers.accept(message("x"));
            assertEquals(1, failures.size());
        }
    }

    @Test
    void handlerMutationsDoNotReachOtherHandlersOrThePublishedEvent() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch mutated = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(1);
        SseMatchController first = match();
        var firstAction = new SseSamplerTest.Probe(() -> {
            SampleResult result = JMeterContextService.getContext().getPreviousResult();
            result.getResponseData()[0] = 'z';
            result.setSuccessful(false);
            result.setResponseHeaders("changed");
            mutated.countDown();
        }, failure);
        SseMatchController second = match();
        var secondAction = new SseSamplerTest.Probe(() -> {
            assertTrue(mutated.await(3, TimeUnit.SECONDS));
            SampleResult result = JMeterContextService.getContext().getPreviousResult();
            assertEquals("original", result.getResponseDataAsString());
            assertTrue(result.isSuccessful());
            assertEquals("event: message", result.getResponseHeaders());
            checked.countDown();
        }, failure);
        SampleResult incoming = message("original");
        SampleResult published = SseSession.copyMessage(incoming);
        var group = new org.apache.jmeter.threads.ThreadGroup();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        group.setSamplerController(loop);
        ListedHashTree tree = new ListedHashTree();
        tree.add(group).add(first).add(firstAction);
        tree.add(group).add(second).add(secondAction);
        tree.add(group).add(new SseSamplerTest.Probe(() -> {
            try (var handlers = new SseMessageHandlers(List.of(first, second), "copies", ignored -> { })) {
                handlers.start();
                handlers.accept(incoming);
                assertTrue(checked.await(5, TimeUnit.SECONDS));
            }
        }, failure));
        tree.traverse(new PreCompiler());
        JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier(), true);
        user.setThreadGroup(group);
        Thread runner = Thread.ofVirtual().start(user);
        try {
            runner.join(8000);
            assertFalse(runner.isAlive());
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals("original", published.getResponseDataAsString());
            assertTrue(published.isSuccessful());
            assertEquals("event: message", published.getResponseHeaders());
        } finally {
            user.stop();
            runner.interrupt();
            runner.join(1000);
        }
    }

    @Test
    void closeDoesNotWaitForABlockedListener() throws Exception {
        SseSessions registry = new SseSessions();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var notification = executor.submit(() -> registry.notifyListeners(() -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                executor.submit(() -> { registry.close(); }).get(1, TimeUnit.SECONDS);
                registry.notifyListeners(() -> { throw new AssertionError("Closed registry must not notify"); });
            } finally {
                release.countDown();
            }
            notification.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void sampleVariablesAreCapturedWhenEachEventArrives() throws Exception {
        var properties = org.apache.jmeter.util.JMeterUtils.getJMeterProperties();
        Object previous = properties.setProperty("sample_variables", "ssePhase");
        org.apache.jmeter.samplers.SampleEvent.initSampleVariables();
        var variables = new org.apache.jmeter.threads.JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
        List<String> phases = new ArrayList<>();
        var listener = new SseSamplerTest.Results() {
            @Override public void sampleOccurred(org.apache.jmeter.samplers.SampleEvent event) {
                phases.add(event.getVarValue(0));
            }
        };
        var pack = new org.apache.jmeter.threads.SamplePackage(List.of(), List.of(listener), List.of(),
                List.of(), List.of(), List.of(), List.of());
        variables.putObject(JMeterThread.PACKAGE_OBJECT, pack);
        variables.put("ssePhase", "opening");
        var sampler = new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy();
        try (var session = new SseSession(sampler, List.of())) {
            variables.put("ssePhase", "received");
            var response = new org.apache.jmeter.protocol.http.sampler.HTTPSampleResult();
            response.sampleStart();
            response.setResponseCode("200");
            response.setContentType("text/event-stream");
            session.read(response, new java.io.ByteArrayInputStream(
                    "data: hello\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
            assertEquals(List.of("received"), phases);
        } finally {
            SseSessions.current().close();
            JMeterContextService.getContext().clear();
            if (previous == null) {
                properties.remove("sample_variables");
            } else {
                properties.put("sample_variables", previous);
            }
            org.apache.jmeter.samplers.SampleEvent.initSampleVariables();
        }
    }

    @Test
    void recordedPayloadPreviewIsBounded() {
        String preview = SsePanel.preview("x".repeat(SsePanel.MAX_PREVIEW_CHARACTERS + 1));
        assertTrue(preview.startsWith("x".repeat(SsePanel.MAX_PREVIEW_CHARACTERS)));
        assertTrue(preview.contains("truncated"));
        assertTrue(preview.length() < SsePanel.MAX_PREVIEW_CHARACTERS + 200);
    }

    private static SseMatchController match() {
        SseMatchController match = new SseMatchController();
        match.setMatchMode(SseMatchController.REGEX);
        match.setMatchValue("^");
        match.setSaveMessageVariable("data");
        return match;
    }

    private static SampleResult message(String data) {
        SampleResult result = new SampleResult();
        result.setResponseData(data, "UTF-8");
        result.setDataType(SampleResult.TEXT);
        result.setResponseMessage("message");
        result.setResponseHeaders("event: message");
        result.setSuccessful(true);
        return result;
    }
}
