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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
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
final class SseMessageHandlers implements AutoCloseable {
    private final List<Worker> workers = new ArrayList<>();

    SseMessageHandlers(List<SseMatchController> matches, String session, Consumer<SampleResult> failures) {
        for (SseMatchController match : matches) {
            TestBeanHelper.prepare(match);
            if (!match.children().isEmpty() || !match.getSaveMessageVariable().isBlank()) {
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
            throw new IllegalStateException("SSE Match requires a running virtual user");
        }
        for (Worker worker : workers) {
            worker.owner = owner;
            // Close wakes the worker itself; user shutdown is handled by the engine lifecycle.
            owner.startBackgroundFlow(worker.getName(), worker,
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
        /** Wakes an idle worker after close. Never matched or delivered to a handler. */
        private static final SampleResult END_OF_MESSAGES = new SampleResult();
        private final ArrayBlockingQueue<SampleResult> queue = new ArrayBlockingQueue<>(64);
        private final AtomicLong queuedBytes = new AtomicLong();
        private final IdentityHashMap<Sampler, Sampler> sources = new IdentityHashMap<>();
        private final GenericController execution;
        private final SseMatchController.MessageMatcher matcher;
        private final Consumer<SampleResult> failures;
        private volatile boolean closed;
        private JMeterThread owner;
        private boolean executing;

        Worker(SseMatchController match, String session, Consumer<SampleResult> failures) {
            setName("SSE " + session + " / " + match.getName());
            this.matcher = match.matcher();
            this.execution = match.execution(sources);
            this.failures = failures;
        }

        void offer(SampleResult message) {
            if (closed) {
                return;
            }
            if (!matcher.match(message)) {
                return;
            }
            long size = messageSize(message);
            long previousSize = queuedBytes.getAndAdd(size);
            // A single parser-approved event may exceed the backlog budget. It must
            // still be deliverable; only accumulated pending events cause overflow.
            if ((previousSize == 0 || previousSize + size <= MAX_QUEUED_BYTES)
                    && queue.offer(SseSession.copyMessage(message))) {
                return;
            }
            queuedBytes.addAndGet(-size);
            close();
            SampleResult failure = new SampleResult();
            failure.setSampleLabel(getName());
            failure.sampleStart();
            failure.sampleEnd();
            failure.setSuccessful(false);
            failure.setResponseCode("SSE_HANDLER_OVERFLOW");
            failure.setResponseMessage("Match handler stopped: incoming message queue exceeded 64 messages or 4 MiB");
            failures.accept(failure);
        }

        private static long messageSize(SampleResult message) {
            return message.getResponseData().length + 2L * message.getResponseHeaders().length();
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            queue.clear();
            // Do not interrupt a running handler block: it may be the one closing this
            // session (Close or reconnecting Connect). It completes and is reported, then the
            // worker stops instead of waiting for another message.
            queue.offer(END_OF_MESSAGES);
        }

        @Override
        public Sampler next() {
            while (!owner.isBackgroundFlowStopping()) {
                if (executing) {
                    Sampler next = execution.next();
                    if (next != null) {
                        return next;
                    }
                    executing = false;
                }
                if (closed) {
                    return null;
                }
                try {
                    SampleResult message = owner.awaitBackgroundEvent(queue);
                    if (message == END_OF_MESSAGES || closed) {
                        return null;
                    }
                    queuedBytes.addAndGet(-messageSize(message));
                    matcher.saveMessage(message, JMeterContextService.getContext().getVariables());
                    JMeterContextService.getContext().setPreviousResult(message);
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
