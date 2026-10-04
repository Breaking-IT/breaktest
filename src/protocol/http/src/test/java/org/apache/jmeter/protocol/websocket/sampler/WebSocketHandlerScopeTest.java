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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.PreCompiler;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
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
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void connectTimersAndProcessorsStayLocalWhileOuterAndMatchScopesStillApply() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        CountDownLatch handled = results.latchFor("nested handler");
        try (var peer = new WebSocketSamplerTest.Peer(false)) {
            var group = group(1);
            ListedHashTree tree = new ListedHashTree();
            HashTree children = tree.add(group);
            children.add(results);
            addScopeProbes(children, "outer", calls);
            HashTree connectTree = children.add(connect(peer.url(), WebSocketConnectSampler.RECONNECT));
            addScopeProbes(connectTree, "connect", calls);
            WebSocketMatchController match = new WebSocketMatchController();
            match.setMatchValue("go");
            HashTree handler = connectTree.add(match);
            addScopeProbes(handler, "match", calls);
            handler.add(new Probe("direct handler", () -> { }));
            handler.add(new GenericController()).add(new Probe("nested handler", () -> { }));
            children.add(new Probe("send go", () -> {
                WebSocketSessions.current().get("chat")
                        .send("go".getBytes(StandardCharsets.UTF_8), false).get(3, TimeUnit.SECONDS);
                assertTrue(handled.await(5, TimeUnit.SECONDS), "Handler never finished");
            }));
            run(tree, group, false);
        }
        for (String stage : List.of("pre", "timer", "post")) {
            assertEquals(List.of("connect"), calls.stream()
                    .filter(call -> call.startsWith("connect:" + stage + ":"))
                    .map(call -> call.substring(("connect:" + stage + ":").length())).toList(),
                    "Connect's " + stage + " must execute once, on Connect only");
            assertEquals(List.of("direct handler", "nested handler"), calls.stream()
                    .filter(call -> call.startsWith("match:" + stage + ":"))
                    .map(call -> call.substring(("match:" + stage + ":").length())).toList(),
                    "Match-scoped " + stage + " must still execute for both handler samplers");
            for (String sampler : List.of("connect", "direct handler", "nested handler", "send go")) {
                assertEquals(1L, calls.stream().filter(call -> call.equals("outer:" + stage + ":" + sampler)).count(),
                        "Outer-scoped " + stage + " must still execute for " + sampler);
            }
        }
        assertTrue(results.samples.stream().allMatch(SampleResult::isSuccessful), results::summary);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void immediateForkErrorPreservesBusyHandlerOnlyForSameUser(boolean sameUser) throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch handlerExited = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch allowForkFailure = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        AtomicInteger iteration = new AtomicInteger();
        AtomicReference<WebSocketSession> firstSession = new AtomicReference<>();
        var seen = new LinkedBlockingQueue<String>();
        try (var first = new WebSocketSamplerTest.Peer(false); var second = new WebSocketSamplerTest.Peer(false)) {
            var group = group(2);
            ListedHashTree tree = new ListedHashTree();
            HashTree children = tree.add(group);
            children.add(results);
            children.add(new Probe("iteration", () -> {
                JMeterContextService.getContext().getVariables().put("targetUrl",
                        iteration.incrementAndGet() == 1 ? first.url() : second.url());
                if (iteration.get() == 2) {
                    if (sameUser) {
                        assertEquals(1L, handlerExited.getCount(), "Busy handler must survive the cancelled iteration");
                        assertEquals(0, interrupted.get());
                        assertTrue(firstSession.get().isOpen());
                        releaseHandler.countDown();
                        assertEquals("msg 1", seen.poll(3, TimeUnit.SECONDS), "Busy block must finish in the next iteration");
                    } else {
                        assertEquals(0L, handlerExited.getCount(), "Old handler must exit before new user starts");
                        assertEquals(1, interrupted.get());
                        assertFalse(firstSession.get().isOpen());
                        assertTrue(seen.isEmpty(), "Cancelled handler must not execute its next sampler");
                        assertNull(JMeterContextService.getContext().getVariables().get("received"));
                    }
                }
            }));
            WebSocketMatchController match = new WebSocketMatchController();
            match.setMatchMode(WebSocketMatchController.REGEX);
            match.setMatchValue("^msg ");
            match.setSaveMessageVariable("received");
            HashTree handler = children.add(connect("${targetUrl}", WebSocketConnectSampler.REUSE)).add(match);
            handler.add(new Probe("busy handler", () -> {
                if ("msg 1".equals(JMeterContextService.getContext().getVariables().get("received"))) {
                    handlerStarted.countDown();
                    try {
                        assertTrue(releaseHandler.await(5, TimeUnit.SECONDS), "Main flow never released handler");
                    } catch (InterruptedException expectedOnNewUser) {
                        interrupted.incrementAndGet();
                        assertFalse(sameUser, "Immediate iteration error interrupted retained handler");
                    } finally {
                        handlerExited.countDown();
                    }
                }
            }));
            handler.add(new Probe("after busy handler", () ->
                    seen.add(JMeterContextService.getContext().getVariables().get("received"))));
            children.add(new Probe("send message", () -> {
                WebSocketSession session = WebSocketSessions.current().get("chat");
                if (iteration.get() == 1) {
                    firstSession.set(session);
                } else {
                    if (sameUser) {
                        assertSame(firstSession.get(), session);
                    } else {
                        assertNotSame(firstSession.get(), session);
                    }
                }
                session.send(("msg " + iteration.get()).getBytes(StandardCharsets.UTF_8), false).get(3, TimeUnit.SECONDS);
                if (iteration.get() == 1) {
                    assertTrue(handlerStarted.await(3, TimeUnit.SECONDS), "Handler must be busy before cancellation");
                } else {
                    assertEquals("msg 2", seen.poll(3, TimeUnit.SECONDS), "Handler must process the next user's/iteration's message");
                }
            }));
            ForkController fork = new ForkController();
            fork.setErrorAction(ForkController.ErrorAction.END_ITERATION_IMMEDIATE);
            children.add(fork).add(new Fail(() -> {
                if (iteration.get() != 1) {
                    return false;
                }
                try {
                    assertTrue(allowForkFailure.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    failure.compareAndSet(null, e);
                }
                return true;
            }));
            children.add(new Probe("await cancellation", () -> {
                if (iteration.get() == 1) {
                    allowForkFailure.countDown();
                    // Stay in a registered main-flow sampler until immediate cancellation interrupts it.
                    try {
                        new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                        throw new AssertionError("Immediate fork error did not interrupt the main flow");
                    } catch (InterruptedException expected) {
                        assertFalse(JMeterContextService.getContext().getThread().isIterationRunning());
                    }
                }
            }));
            run(tree, group, sameUser);
        } finally {
            releaseHandler.countDown();
        }
        assertEquals(2, iteration.get());
        assertEquals(sameUser ? 0 : 1, interrupted.get());
        assertEquals(sameUser ? 2 : 1, results.samples.stream()
                .filter(result -> "busy handler".equals(result.getSampleLabel())).count(),
                "Only an immediate new-user stop should suppress the in-flight handler result");
    }

    /** Parallel branch threads inside a handler belong to the background flow, not the failed iteration. */
    @Test
    void immediateForkErrorPreservesBusyParallelBranchInsideHandler() throws Exception {
        CountDownLatch branchStarted = new CountDownLatch(1);
        CountDownLatch releaseBranch = new CountDownLatch(1);
        CountDownLatch allowForkFailure = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        AtomicInteger iteration = new AtomicInteger();
        CountDownLatch busyReported = results.latchFor("busy branch");
        try (var peer = new WebSocketSamplerTest.Peer(false)) {
            var group = group(2);
            ListedHashTree tree = new ListedHashTree();
            HashTree children = tree.add(group);
            children.add(results);
            children.add(new Probe("iteration", () -> {
                if (iteration.incrementAndGet() == 2) {
                    assertEquals(0, interrupted.get(), "Immediate fork error interrupted a handler's parallel branch");
                    releaseBranch.countDown();
                    assertTrue(busyReported.await(3, TimeUnit.SECONDS), "Busy branch result was not reported");
                }
            }));
            WebSocketMatchController match = new WebSocketMatchController();
            match.setMatchValue("go");
            org.apache.jmeter.control.ParallelController parallel = new org.apache.jmeter.control.ParallelController();
            parallel.setName("handler branches");
            parallel.setMaxParallel(2);
            HashTree branches = children.add(connect(peer.url(), WebSocketConnectSampler.REUSE)).add(match).add(parallel);
            branches.add(new Probe("busy branch", () -> {
                branchStarted.countDown();
                try {
                    assertTrue(releaseBranch.await(5, TimeUnit.SECONDS), "Main flow never released branch");
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                }
            }));
            branches.add(new Probe("quick branch", () -> { }));
            children.add(new Probe("send go", () -> {
                if (iteration.get() == 1) {
                    WebSocketSessions.current().get("chat")
                            .send("go".getBytes(StandardCharsets.UTF_8), false).get(3, TimeUnit.SECONDS);
                    assertTrue(branchStarted.await(3, TimeUnit.SECONDS), "Branch must be busy before cancellation");
                }
            }));
            ForkController fork = new ForkController();
            fork.setErrorAction(ForkController.ErrorAction.END_ITERATION_IMMEDIATE);
            children.add(fork).add(new Fail(() -> {
                if (iteration.get() != 1) {
                    return false;
                }
                try {
                    assertTrue(allowForkFailure.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    failure.compareAndSet(null, e);
                }
                return true;
            }));
            children.add(new Probe("await cancellation", () -> {
                if (iteration.get() == 1) {
                    allowForkFailure.countDown();
                    try {
                        new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                        throw new AssertionError("Immediate fork error did not interrupt the main flow");
                    } catch (InterruptedException expected) {
                        // The failed iteration ends here.
                    }
                }
            }));
            run(tree, group, true);
        } finally {
            releaseBranch.countDown();
        }
        assertEquals(2, iteration.get());
        assertTrue(results.only("busy branch").isSuccessful(), results::summary);
    }

    private static void addScopeProbes(HashTree tree, String scope, List<String> calls) {
        tree.add(new ScopePreProcessor(scope, calls));
        tree.add(new ScopeTimer(scope, calls));
        tree.add(new ScopePostProcessor(scope, calls));
    }

    private abstract static class ScopeProbe extends AbstractTestElement implements NoThreadClone {
        private static final long serialVersionUID = 1L;
        private final String scope;
        private final transient List<String> calls;

        ScopeProbe(String scope, List<String> calls) {
            this.scope = scope;
            this.calls = calls;
        }

        void record(String stage) {
            calls.add(scope + ":" + stage + ":" + JMeterContextService.getContext().getCurrentSampler().getName());
        }
    }

    private static final class ScopePreProcessor extends ScopeProbe implements PreProcessor {
        private static final long serialVersionUID = 1L;
        ScopePreProcessor(String scope, List<String> calls) { super(scope, calls); }
        @Override public void process() { record("pre"); }
    }

    private static final class ScopePostProcessor extends ScopeProbe implements PostProcessor {
        private static final long serialVersionUID = 1L;
        ScopePostProcessor(String scope, List<String> calls) { super(scope, calls); }
        @Override public void process() { record("post"); }
    }

    private static final class ScopeTimer extends ScopeProbe implements Timer {
        private static final long serialVersionUID = 1L;
        ScopeTimer(String scope, List<String> calls) { super(scope, calls); }
        @Override
        public long delay() {
            record("timer");
            return 0;
        }
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
