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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.PreCompiler;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Handler lifecycle and scoping when handlers close sessions, Connect has children, or forks fail. */
@Timeout(20)
class WebSocketHandlerScopeTest extends JMeterTestCase {
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Results results = new Results();

    @BeforeEach
    void setUp() {
        JMeterUtils.setLocale(java.util.Locale.ENGLISH);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
    }

    @AfterEach
    void cleanup() {
        WebSocketSessions.cleanup();
        JMeterContextService.getContext().clear();
    }

    /** Finding 1: a Close inside a Match handler for the same session should succeed. */
    @Test
    void closeInsideOwnMatchHandlerSucceeds() throws Exception {
        CountDownLatch closed = results.latchFor("close in handler");
        try (var peer = new WebSocketSamplerTest.Peer(false)) {
            var group = group(1);
            ListedHashTree tree = new ListedHashTree();
            HashTree children = tree.add(group);
            children.add(results);
            WebSocketConnectSampler connect = connect(peer.url(), WebSocketConnectSampler.RECONNECT);
            WebSocketMatchController bye = new WebSocketMatchController();
            bye.setMatchValue("bye");
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setName("close in handler");
            close.setSessionName("chat");
            CountDownLatch handlerStarted = results.latchFor("handler started");
            HashTree handler = children.add(connect).add(bye);
            handler.add(new Probe("handler started", () -> { }));
            handler.add(close);
            handler.add(new Probe("after close in handler", () -> { }));
            AtomicReference<WebSocketSession> session = new AtomicReference<>();
            children.add(new Probe("send bye", () -> {
                session.set(WebSocketSessions.current().get("chat"));
                session.get().send("bye".getBytes(StandardCharsets.UTF_8), false).get(3, TimeUnit.SECONDS);
                assertTrue(handlerStarted.await(5, TimeUnit.SECONDS), "Handler never started");
                boolean reported = closed.await(5, TimeUnit.SECONDS);
                assertTrue(reported, "Handler started, but its Close was never reported; session open="
                        + session.get().isOpen());
            }));
            run(tree, group, false);
        }
        SampleResult close = results.only("close in handler");
        assertTrue(close.isSuccessful(),
                () -> "Close inside handler failed: " + close.getResponseCode() + " " + close.getResponseMessage());
    }

    /** Finding 2: an assertion attached to Connect should not run on samplers inside Match handlers. */
    @Test
    void connectAssertionDoesNotApplyToHandlerSamplers() throws Exception {
        CountDownLatch handled = results.latchFor("handler sample");
        try (var peer = new WebSocketSamplerTest.Peer(false)) {
            var group = group(1);
            ListedHashTree tree = new ListedHashTree();
            HashTree children = tree.add(group);
            children.add(results);
            WebSocketConnectSampler connect = connect(peer.url(), WebSocketConnectSampler.RECONNECT);
            HashTree connectTree = children.add(connect);
            ResponseAssertion upgraded = new ResponseAssertion();
            upgraded.setName("expects 101");
            upgraded.setTestFieldResponseCode();
            upgraded.setToEqualsType();
            upgraded.addTestString("101");
            connectTree.add(upgraded);
            WebSocketMatchController go = new WebSocketMatchController();
            go.setMatchValue("go");
            connectTree.add(go).add(new Probe("handler sample", () -> { }));
            children.add(new Probe("send go", () -> {
                WebSocketSessions.current().get("chat")
                        .send("go".getBytes(StandardCharsets.UTF_8), false).get(3, TimeUnit.SECONDS);
                assertTrue(handled.await(5, TimeUnit.SECONDS), "Handler never ran");
            }));
            run(tree, group, false);
        }
        assertTrue(results.only("connect").isSuccessful(), "Control: Connect itself passes its 101 assertion");
        SampleResult handler = results.only("handler sample");
        assertTrue(handler.isSuccessful(), () -> "Connect's assertion leaked into the handler: "
                + java.util.Arrays.stream(handler.getAssertionResults())
                        .map(a -> a.getName() + ": " + a.getFailureMessage()).toList());
    }

    /** Finding 3: a failing Fork Controller must not permanently stop handlers of a session that stays open. */
    @Test
    void forkErrorDoesNotKillHandlersOfReusedSession() throws Exception {
        var seen = new LinkedBlockingQueue<String>();
        var iteration = new AtomicInteger();
        try (var peer = new WebSocketSamplerTest.Peer(false)) {
            var group = group(2);
            ListedHashTree tree = new ListedHashTree();
            HashTree children = tree.add(group);
            children.add(results);
            children.add(new Probe("iteration", iteration::incrementAndGet));
            WebSocketConnectSampler connect = connect(peer.url(), WebSocketConnectSampler.REUSE);
            WebSocketMatchController msg = new WebSocketMatchController();
            msg.setMatchMode(WebSocketMatchController.REGEX);
            msg.setMatchValue("^msg ");
            msg.setSaveMessageVariable("received");
            children.add(connect).add(msg).add(new Probe("handler", () ->
                    seen.add(JMeterContextService.getContext().getVariables().get("received"))));
            children.add(new Probe("send and await", () -> {
                String text = "msg " + iteration.get();
                WebSocketSessions.current().get("chat")
                        .send(text.getBytes(StandardCharsets.UTF_8), false).get(3, TimeUnit.SECONDS);
                assertEquals(text, seen.poll(3, TimeUnit.SECONDS),
                        "Handler did not process message in iteration " + iteration.get());
            }));
            ForkController fork = new ForkController();
            fork.setName("failing fork");
            fork.setErrorAction(ForkController.ErrorAction.END_ITERATION_GRACEFUL);
            children.add(fork).add(new Fail(() -> iteration.get() == 1));
            children.add(new Probe("let fork fail", () -> Thread.sleep(500)));
            children.add(new Probe("boundary", () -> { }));
            run(tree, group, true);
        }
        assertEquals(2, iteration.get());
    }

    private void run(ListedHashTree tree, org.apache.jmeter.threads.ThreadGroup group, boolean sameUser)
            throws InterruptedException {
        tree.traverse(new PreCompiler());
        JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier(), sameUser);
        user.setThreadGroup(group);
        user.setThreadName("handler-scope-user");
        Thread runner = Thread.ofVirtual().start(user);
        try {
            runner.join(15000);
            assertFalse(runner.isAlive(), "User did not finish");
        } finally {
            user.stop();
            runner.interrupt();
            runner.join(2000);
        }
        assertNull(failure.get(), () -> failure.get() + " samples=" + results.summary());
    }

    private static org.apache.jmeter.threads.ThreadGroup group(int loops) {
        var group = new org.apache.jmeter.threads.ThreadGroup();
        LoopController loop = new LoopController();
        loop.setLoops(loops);
        group.setSamplerController(loop);
        return group;
    }

    private static WebSocketConnectSampler connect(String url, String existingAction) {
        WebSocketConnectSampler connect = new WebSocketConnectSampler();
        connect.setName("connect");
        connect.setSessionName("chat");
        connect.setUrl(url);
        connect.setCountIncoming(false);
        connect.setExistingSessionAction(existingAction);
        return connect;
    }

    @FunctionalInterface
    interface CheckedAction { void run() throws Exception; }

    private final class Probe extends AbstractSampler implements NoThreadClone {
        private static final long serialVersionUID = 1L;
        private final transient CheckedAction action;

        Probe(String name, CheckedAction action) {
            this.action = action;
            setName(name);
        }

        @Override
        public SampleResult sample(Entry entry) {
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            try {
                action.run();
                result.setSuccessful(true);
                result.setResponseCodeOK();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                result.setSuccessful(false);
            }
            result.sampleEnd();
            return result;
        }
    }

    private static final class Fail extends AbstractSampler implements NoThreadClone {
        private static final long serialVersionUID = 1L;
        private final transient java.util.function.BooleanSupplier shouldFail;

        Fail(java.util.function.BooleanSupplier shouldFail) {
            this.shouldFail = shouldFail;
            setName("fork sample");
        }

        @Override
        public SampleResult sample(Entry entry) {
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            result.sampleEnd();
            result.setSuccessful(!shouldFail.getAsBoolean());
            return result;
        }
    }

    private static final class Results extends AbstractTestElement implements SampleListener, NoThreadClone {
        private static final long serialVersionUID = 1L;
        final List<SampleResult> samples = new CopyOnWriteArrayList<>();
        private final java.util.Map<String, CountDownLatch> latches = new java.util.concurrent.ConcurrentHashMap<>();

        CountDownLatch latchFor(String label) {
            return latches.computeIfAbsent(label, ignored -> new CountDownLatch(1));
        }

        SampleResult only(String label) {
            List<SampleResult> matching = samples.stream().filter(r -> label.equals(r.getSampleLabel())).toList();
            assertEquals(1, matching.size(), () -> "Samples labelled " + label + " in " + summary());
            return matching.get(0);
        }

        String summary() {
            return samples.stream().map(r -> r.getSampleLabel() + "=" + r.isSuccessful() + "/" + r.getResponseCode()).toList()
                    .toString();
        }

        @Override
        public void sampleOccurred(SampleEvent event) {
            samples.add(event.getResult());
            CountDownLatch latch = latches.get(event.getResult().getSampleLabel());
            if (latch != null) {
                latch.countDown();
            }
        }

        @Override public void sampleStarted(SampleEvent event) { }
        @Override public void sampleStopped(SampleEvent event) { }
    }
}
