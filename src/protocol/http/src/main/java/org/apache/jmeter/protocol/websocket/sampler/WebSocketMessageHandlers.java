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

package org.apache.jmeter.protocol.websocket.sampler;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;

/** Bounded queues keep application handlers off the network callback threads. */
final class WebSocketMessageHandlers implements AutoCloseable {
    private final List<Worker> workers = new ArrayList<>();

    WebSocketMessageHandlers(List<WebSocketMatchController> matches, String session, Consumer<SampleResult> failures) {
        for (WebSocketMatchController match : matches) {
            if (!match.children().isEmpty()) {
                TestBeanHelper.prepare(match);
                workers.add(new Worker(match, session, failures));
            }
        }
    }

    void start() {
        if (workers.isEmpty()) {
            return;
        }
        JMeterThread owner = JMeterContextService.getContext().getThread();
        if (owner == null) {
            throw new IllegalStateException("WebSocket Match requires a running virtual user");
        }
        for (Worker worker : workers) {
            worker.owner = owner;
            worker.stop = owner.startBackgroundFlow(worker.getName(), worker,
                    sampler -> worker.sources.getOrDefault(sampler, sampler));
        }
    }

    void accept(SampleResult message) {
        for (Worker worker : workers) {
            worker.offer(message);
        }
    }

    @Override
    public void close() {
        workers.forEach(Worker::close);
    }

    private static final class Worker extends GenericController {
        private static final long serialVersionUID = 1L;
        private static final long MAX_QUEUED_BYTES = 4 * 1024 * 1024;
        private record Invocation(SampleResult message, Map<String, Object> captures) { }
        private final ArrayBlockingQueue<Invocation> queue = new ArrayBlockingQueue<>(64);
        private final AtomicLong queuedBytes = new AtomicLong();
        private final IdentityHashMap<Sampler, Sampler> sources = new IdentityHashMap<>();
        private final GenericController execution;
        private final WebSocketMatchController.MessageMatcher matcher;
        private final Consumer<SampleResult> failures;
        private volatile boolean closed;
        private volatile Runnable stop = () -> { };
        private JMeterThread owner;
        private boolean executing;

        Worker(WebSocketMatchController match, String session, Consumer<SampleResult> failures) {
            setName("WebSocket " + session + " / " + match.getName());
            this.matcher = match.matcher(session);
            this.execution = match.execution(sources);
            this.failures = failures;
        }

        void offer(SampleResult message) {
            if (closed) {
                return;
            }
            Map<String, Object> captures = matcher.match(message);
            if (captures == null) {
                return;
            }
            int size = message.getResponseData().length;
            if (queuedBytes.addAndGet(size) <= MAX_QUEUED_BYTES && queue.offer(new Invocation(message, captures))) {
                return;
            }
            queuedBytes.addAndGet(-size);
            close();
            SampleResult failure = new SampleResult();
            failure.setSampleLabel(getName());
            failure.sampleStart();
            failure.sampleEnd();
            failure.setSuccessful(false);
            failure.setResponseCode("WS_HANDLER_OVERFLOW");
            failure.setResponseMessage("Match handler stopped: incoming message queue exceeded 64 messages or 4 MiB");
            failures.accept(failure);
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            queue.clear();
            stop.run();
        }

        @Override
        public Sampler next() {
            while (!closed && !owner.isBackgroundFlowStopping()) {
                if (executing) {
                    Sampler next = execution.next();
                    if (next != null) {
                        return next;
                    }
                    executing = false;
                }
                try {
                    Invocation invocation = owner.awaitBackgroundEvent(queue);
                    queuedBytes.addAndGet(-invocation.message().getResponseData().length);
                    JMeterThread.setBackgroundLocalVariables(invocation.captures());
                    JMeterContextService.getContext().setPreviousResult(invocation.message());
                    execution.initialize();
                    executing = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }
    }
}
