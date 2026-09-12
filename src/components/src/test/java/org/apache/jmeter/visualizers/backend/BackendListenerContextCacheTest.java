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

package org.apache.jmeter.visualizers.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.property.FunctionProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackendListenerContextCacheTest {
    public static class CapturingClient extends AbstractBackendListenerClient {
        static final List<BackendListenerContext> seen = new ArrayList<>();

        @Override
        public SampleResult createSampleResult(BackendListenerContext context, SampleResult result) {
            seen.add(context);
            return null;
        }

        @Override
        public void handleSampleResults(List<SampleResult> results, BackendListenerContext context) {
            // Results are deliberately filtered at createSampleResult.
        }
    }

    public static class ReusingClient extends CapturingClient {
        private boolean initialized;

        @Override
        public void setupTest(BackendListenerContext context) {
            initialized = true;
        }

        @Override
        public boolean canReuseSampleContext() {
            assertTrue(initialized, "Capability must be queried after setupTest");
            return true;
        }
    }

    public static class ReusingQueueClient extends QueueClient {
        @Override
        public boolean canReuseSampleContext() {
            return true;
        }
    }

    public static class QueueClient extends AbstractBackendListenerClient {
        static int delivered;

        @Override
        public void handleSampleResults(List<SampleResult> results, BackendListenerContext context) {
            delivered += results.size();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliversEveryQueuedEvent(boolean cached) {
        BackendListener listener = listener(cached, new Arguments());
        listener.setClassname((cached ? ReusingQueueClient.class : QueueClient.class).getName());
        listener.setQueueSize("32");
        QueueClient.delivered = 0;
        listener.testStarted();
        try {
            for (int i = 0; i < 1000; i++) {
                sample(listener);
            }
        } finally {
            listener.testEnded();
        }
        assertEquals(1000, QueueClient.delivered);
    }

    private BackendListener listener(boolean cached, Arguments arguments) {
        BackendListener listener = new BackendListener();
        listener.setName("context-test-" + System.nanoTime());
        listener.setClassname((cached ? ReusingClient.class : CapturingClient.class).getName());
        listener.setArguments(arguments);
        CapturingClient.seen.clear();
        return listener;
    }

    private void sample(BackendListener listener) {
        listener.sampleOccurred(new SampleEvent(new SampleResult(), "group"));
    }

    @Test
    void staticContextIsReusedAndRefreshedOnRestart() {
        Arguments args = new Arguments();
        args.addArgument("key", "first");
        args.addArgument("key", "duplicate");
        BackendListener listener = listener(true, args);
        listener.testStarted();
        BackendListenerContext first;
        try {
            sample(listener);
            sample(listener);
            first = CapturingClient.seen.get(0);
            assertSame(first, CapturingClient.seen.get(1));
            assertEquals("first", first.getParameter("key"));
            var names = first.getParameterNamesIterator();
            names.next();
            assertThrows(UnsupportedOperationException.class, names::remove);
        } finally {
            listener.testEnded();
        }
        args.getArgument(0).setValue("second");
        listener.testStarted();
        try {
            sample(listener);
            BackendListenerContext restarted = CapturingClient.seen.get(2);
            assertNotSame(first, restarted);
            assertEquals("second", restarted.getParameter("key"));
        } finally {
            listener.testEnded();
        }
    }

    @Test
    void switchingToDefaultClientClearsCachedContext() {
        BackendListener listener = listener(true, new Arguments());
        listener.testStarted();
        try {
            sample(listener);
        } finally {
            listener.testEnded();
        }
        listener.setClassname(CapturingClient.class.getName());
        listener.testStarted();
        try {
            sample(listener);
            sample(listener);
            assertNotSame(CapturingClient.seen.get(0), CapturingClient.seen.get(1));
            assertNotSame(CapturingClient.seen.get(1), CapturingClient.seen.get(2));
        } finally {
            listener.testEnded();
        }
    }

    @Test
    void defaultPathEvaluatesVariablesPerSample() throws Exception {
        Arguments args = new Arguments();
        args.addArgument("key", "placeholder");
        FunctionProperty dynamic = new FunctionProperty(Argument.VALUE, new CompoundVariable("${contextValue}"));
        dynamic.setRunningVersion(true);
        args.getArgument(0).setProperty(dynamic);
        BackendListener listener = listener(false, args);
        // An old JMX property must not opt a client into sharing its context.
        listener.setProperty("BackendListener.cacheSampleContext", true);
        JMeterVariables previous = JMeterContextService.getContext().getVariables();
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        listener.testStarted();
        try {
            JMeterContextService.getContext().getVariables().put("contextValue", "first");
            sample(listener);
            JMeterContextService.getContext().getVariables().put("contextValue", "second");
            sample(listener);
            assertNotSame(CapturingClient.seen.get(0), CapturingClient.seen.get(1));
            assertEquals("first", CapturingClient.seen.get(0).getParameter("key"));
            assertEquals("second", CapturingClient.seen.get(1).getParameter("key"));
        } finally {
            listener.testEnded();
            JMeterContextService.getContext().setVariables(previous);
        }
    }
}
