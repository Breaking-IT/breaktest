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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.engine.PreCompiler;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Cookie;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

@Timeout(15)
class WebSocketMatchControllerTest extends JMeterTestCase {
    @AfterEach
    void cleanup() {
        WebSocketSessions.cleanup();
        JMeterContextService.getContext().clear();
    }

    @Test
    void textRegexAndBinaryMatchersDoNotCrossMatchFrameTypes() {
        WebSocketMatchController match = new WebSocketMatchController();
        match.setMatchValue("ping");
        var exact = match.matcher();
        assertTrue(exact.match(message("ping", false)));
        assertFalse(exact.match(message("prefix ping", false)));
        assertFalse(exact.match(message("ping", true)));
        match.setMatchMode(WebSocketMatchController.REGEX);
        match.setMatchValue("process ready for page: (.*?) done");
        var regex = match.matcher();
        assertTrue(regex.match(message("notice: process ready for page: 42 done", false)));
        assertFalse(regex.match(message("unrelated", false)));
        match.setMatchMode(WebSocketMatchController.BINARY);
        match.setMatchValue("07 95\n03 80 A1 30 03 C0");
        var binary = match.matcher();
        SampleResult frame = message("", true);
        frame.setResponseData(java.util.HexFormat.of().parseHex("0007950380a13003c0ff"));
        assertTrue(binary.match(frame));
        frame.setDataType(SampleResult.TEXT);
        assertFalse(binary.match(frame));
        match.setMatchValue(" ");
        assertThrows(IllegalArgumentException.class, match::matcher);
    }

    @Test
    void savesOnlyTheSelectedVariableWithTheCompleteTextOrLosslessBinaryMessage() {
        WebSocketMatchController match = new WebSocketMatchController();
        JMeterVariables user = new JMeterVariables();
        JMeterVariables otherUser = new JMeterVariables();
        user.put("existing", "unchanged");
        // JMeter preloads start timestamps when an engine has already run in this JVM.
        Map<String, Object> expected = new HashMap<>();
        user.entrySet().forEach(entry -> expected.put(entry.getKey(), entry.getValue()));
        String text = "prefix {ping: true} suffix" + (char) 30;
        match.setMatchMode(WebSocketMatchController.REGEX);
        match.setMatchValue("(ping)");
        match.setSaveMessageVariable(" ");
        var noSave = match.matcher();
        assertTrue(noSave.match(message(text, false)));
        noSave.saveMessage(message(text, false), user);
        assertEquals(expected.entrySet(), user.entrySet());
        match.setSaveMessageVariable("received.message");
        var save = match.matcher();
        save.saveMessage(message(text, false), user);
        assertEquals(text, user.get("received.message"));
        assertNull(otherUser.get("received.message"));
        expected.put("received.message", text);
        assertEquals(expected.entrySet(), user.entrySet(), "No automatic capture or prefix variables");
        SampleResult binary = message("", true);
        binary.setResponseData(new byte[] {0, (byte) 255, 7, 30});
        save.saveMessage(binary, user);
        assertEquals("00ff071e", user.get("received.message"));
        assertEquals("unchanged", user.get("existing"));
        match.setSaveMessageVariable(JMeterThread.PACKAGE_OBJECT);
        assertThrows(IllegalArgumentException.class, match::matcher);
    }

    @Test
    void handlersRunWhileMainFlowWaitsAndSlowHttpDoesNotBlockApplicationPong() throws Exception {
        JMeterUtils.setLocale(java.util.Locale.ENGLISH);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        CountDownLatch httpStarted = new CountDownLatch(1);
        CountDownLatch pong = new CountDownLatch(1);
        CountDownLatch transaction = new CountDownLatch(1);
        CountDownLatch duplicatePing = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> request = new AtomicReference<>();
        Results results = new Results(pong, transaction);
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", exchange -> {
            request.set(exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("Cookie"));
            httpStarted.countDown();
            try {
                assertTrue(pong.await(3, TimeUnit.SECONDS), "Pong must not wait for the HTTP handler");
                byte[] body = "page completed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                exchange.close();
            }
        });
        http.start();
        try (WebSocketSamplerTest.Peer peer = new WebSocketSamplerTest.Peer(false)) {
            var group = new org.apache.jmeter.threads.ThreadGroup();
            LoopController loop = new LoopController();
            loop.setLoops(1);
            group.setSamplerController(loop);
            ListedHashTree tree = new ListedHashTree();
            var children = tree.add(group);
            children.add(results);
            CookieManager cookies = new CookieManager();
            cookies.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.gui.CookiePanel");
            cookies.add(new Cookie("user", "alice", InetAddress.getLoopbackAddress().getHostAddress(), "/", false, 0));
            cookies.testStarted();
            children.add(cookies);
            WebSocketConnectSampler connect = new WebSocketConnectSampler();
            connect.setSessionName("chat");
            connect.setUrl(peer.url());
            connect.setCountIncoming(false);
            connect.setTextFilter(".*"); // Filtering samples must not disable application handlers.
            var handlers = children.add(connect);
            WebSocketMatchController ping = new WebSocketMatchController();
            ping.setName("application ping");
            ping.setMatchValue("ping");
            WebSocketSendWaitSampler reply = new WebSocketSendWaitSampler();
            reply.setAction(WebSocketSendWaitSampler.SEND_ONLY);
            reply.setName("automatic pong");
            reply.setSessionName("chat");
            reply.setPayload("pong");
            handlers.add(ping).add(reply);
            var anotherPing = (WebSocketMatchController) ping.clone();
            handlers.add(anotherPing).add(new Probe(duplicatePing::countDown, failure));
            WebSocketMatchController ready = new WebSocketMatchController();
            ready.setName("page ready");
            ready.setMatchMode(WebSocketMatchController.REGEX);
            ready.setMatchValue("process ready for page: (.*?) done");
            ready.setSaveMessageVariable("receivedMessage");
            TransactionController page = new TransactionController();
            page.setName("page transaction");
            HTTPSamplerProxy get = new HTTPSamplerProxy();
            get.setName("load page");
            get.setDomain(InetAddress.getLoopbackAddress().getHostAddress());
            get.setPort(http.getAddress().getPort());
            get.setPath("/page/${pageDone}");
            get.setResponseTimeout("3000");
            var pageFlow = handlers.add(ready).add(page);
            pageFlow.add(new Probe(() -> {
                var vars = JMeterContextService.getContext().getVariables();
                String received = vars.get("receivedMessage");
                var pageNumber = java.util.regex.Pattern.compile("page: (.*?) done").matcher(received);
                assertTrue(pageNumber.find());
                vars.put("pageDone", pageNumber.group(1));
            }, failure));
            var httpScope = pageFlow.add(get);
            var assertion = new org.apache.jmeter.assertions.ResponseAssertion();
            assertion.setName("page body assertion");
            assertion.setTestFieldResponseData();
            assertion.setToEqualsType();
            assertion.addTestString("page completed");
            httpScope.add(assertion);
            var extractor = new org.apache.jmeter.extractor.RegexExtractor();
            extractor.setRegex("(page completed)");
            extractor.setTemplate("$1$");
            extractor.setRefName("handler_extracted");
            extractor.setMatchNumber(1);
            httpScope.add(extractor);
            pageFlow.add(new Probe(() -> assertEquals("page completed",
                    JMeterContextService.getContext().getVariables().get("handler_extracted")), failure));
            children.add(new Probe(() -> {
                var vars = JMeterContextService.getContext().getVariables();
                vars.put("pageDone", "main flow value");
                WebSocketSession session = WebSocketSessions.current().get("chat");
                session.send("process ready for page: 42 done".getBytes(StandardCharsets.UTF_8), false)
                        .get(3, TimeUnit.SECONDS);
                assertTrue(httpStarted.await(3, TimeUnit.SECONDS));
                session.send("ping".getBytes(StandardCharsets.UTF_8), false).get(3, TimeUnit.SECONDS);
                assertTrue(transaction.await(4, TimeUnit.SECONDS));
                assertTrue(duplicatePing.await(3, TimeUnit.SECONDS), "Equal match settings must still run both blocks");
                assertEquals("42", vars.get("pageDone"));
                assertEquals("process ready for page: 42 done", vars.get("receivedMessage"));
                assertNull(vars.get("ws_message"));
            }, failure));
            WebSocketCloseSampler close = new WebSocketCloseSampler();
            close.setName("close chat");
            close.setSessionName("chat");
            children.add(close);
            tree.traverse(new PreCompiler());
            JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier(), true);
            user.setThreadGroup(group);
            user.setThreadName("handler-user");
            Thread runner = Thread.ofVirtual().start(user);
            try {
                runner.join(10000);
                assertFalse(runner.isAlive(), "Handlers must stop when the user finishes");
            } finally {
                user.stop();
                runner.interrupt();
                runner.join(2000);
            }
            assertNull(failure.get(), () -> results.samples.stream().map(r -> r.getSampleLabel() + ": " + r.getResponseMessage()).toList().toString());
            assertEquals("/page/42 user=alice", request.get());
            assertEquals(1, results.samples.stream().filter(r -> "automatic pong".equals(r.getSampleLabel())).count());
            assertTrue(results.samples.stream().allMatch(SampleResult::isSuccessful), () -> results.samples.toString());
        } finally {
            http.stop(0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void handlersKeepMessageOrderAndFollowUserIterationLifecycle(boolean sameUser) throws Exception {
        JMeterUtils.setLocale(java.util.Locale.ENGLISH);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var seen = new java.util.concurrent.LinkedBlockingQueue<String>();
        var iteration = new java.util.concurrent.atomic.AtomicInteger();
        var previous = new AtomicReference<WebSocketSession>();
        try (var first = new WebSocketSamplerTest.Peer(false); var second = new WebSocketSamplerTest.Peer(false)) {
            var group = new org.apache.jmeter.threads.ThreadGroup();
            LoopController loop = new LoopController();
            loop.setLoops(2);
            group.setSamplerController(loop);
            ListedHashTree tree = new ListedHashTree();
            var children = tree.add(group);
            children.add(new Probe(() -> {
                var vars = JMeterContextService.getContext().getVariables();
                vars.put("targetUrl", iteration.incrementAndGet() == 1 ? first.url() : second.url());
                vars.put("ws_g1", "main");
            }, failure));
            var connect = new WebSocketConnectSampler();
            connect.setSessionName("chat");
            connect.setUrl("${targetUrl}");
            connect.setCountIncoming(false);
            connect.setExistingSessionAction(WebSocketConnectSampler.REUSE);
            var match = new WebSocketMatchController();
            match.setMatchMode(WebSocketMatchController.REGEX);
            match.setMatchValue("msg (.*)");
            match.setSaveMessageVariable("receivedMessage");
            children.add(connect).add(match).add(new Probe(() -> {
                var context = JMeterContextService.getContext();
                assertNotNull(context.getThread());
                assertSame(group, context.getThreadGroup());
                seen.add(context.getVariables().get("receivedMessage"));
            }, failure));
            var storeOnly = new WebSocketMatchController();
            storeOnly.setMatchMode(WebSocketMatchController.REGEX);
            storeOnly.setMatchValue("msg .*");
            storeOnly.setSaveMessageVariable("savedWithoutChildren");
            children.getTree(connect).add(storeOnly);
            children.add(new Probe(() -> {
                WebSocketSession session = WebSocketSessions.current().get("chat");
                if (previous.get() != null) {
                    if (sameUser) {
                        assertSame(previous.get(), session);
                    } else {
                        assertNotSame(previous.get(), session);
                        assertFalse(previous.get().isOpen());
                    }
                }
                previous.set(session);
                String id = Integer.toString(iteration.get());
                session.send(("msg " + id + "a").getBytes(StandardCharsets.UTF_8), false).get(2, TimeUnit.SECONDS);
                session.send(("msg " + id + "b").getBytes(StandardCharsets.UTF_8), false).get(2, TimeUnit.SECONDS);
                assertEquals("msg " + id + "a", seen.poll(3, TimeUnit.SECONDS));
                assertEquals("msg " + id + "b", seen.poll(3, TimeUnit.SECONDS));
                assertEquals("main", JMeterContextService.getContext().getVariables().get("ws_g1"));
                assertEquals("msg " + id + "b", JMeterContextService.getContext().getVariables().get("receivedMessage"));
                var vars = JMeterContextService.getContext().getVariables();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!("msg " + id + "b").equals(vars.get("savedWithoutChildren")) && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals("msg " + id + "b", vars.get("savedWithoutChildren"));
            }, failure));
            tree.traverse(new PreCompiler());
            JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier(), sameUser);
            user.setThreadGroup(group);
            user.setThreadName("handler-lifecycle");
            Thread runner = Thread.ofVirtual().start(user);
            try {
                runner.join(10000);
                assertFalse(runner.isAlive(), "Idle handlers must stop at user shutdown");
            } finally {
                user.stop();
                runner.interrupt();
                runner.join(2000);
            }
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(2, iteration.get());
            assertFalse(previous.get().isOpen());
        }
    }

    @Test
    void queuesAreBoundedAndReportOverflowOnce() {
        var match = new WebSocketMatchController();
        match.addTestElement(new WebSocketSendWaitSampler());
        match.setMatchValue("ping");
        List<SampleResult> failures = new CopyOnWriteArrayList<>();
        try (var handlers = new WebSocketMessageHandlers(List.of(match), "chat", failures::add)) {
            for (int i = 0; i < 100; i++) {
                handlers.accept(message("unmatched", false));
            }
            assertTrue(failures.isEmpty(), "Unmatched messages must not fill a handler queue");
            for (int i = 0; i < 100; i++) {
                handlers.accept(message("ping", false));
            }
            assertEquals(1, failures.size());
            assertEquals("WS_HANDLER_OVERFLOW", failures.get(0).getResponseCode());
            assertFalse(failures.get(0).isSuccessful());
        }
        failures.clear();
        match.setMatchMode(WebSocketMatchController.BINARY);
        match.setMatchValue("00");
        try (var handlers = new WebSocketMessageHandlers(List.of(match), "chat", failures::add)) {
            SampleResult large = message("", true);
            large.setResponseData(new byte[4 * 1024 * 1024 + 1]);
            handlers.accept(large);
            assertEquals(1, failures.size());
        }
    }

    @Test
    void matchOptionsAndDefaultChildPersist() throws Exception {
        var connect = new WebSocketConnectSampler();
        var match = (WebSocketMatchController) connect.createDefaultChildControllers().get(0);
        assertTrue(connect.acceptsChildController(match));
        assertTrue(org.apache.jmeter.gui.util.MenuFactory.canAddTo(
                new org.apache.jmeter.gui.tree.JMeterTreeNode(connect, null), match));
        assertFalse(org.apache.jmeter.gui.util.MenuFactory.canAddTo(
                new org.apache.jmeter.gui.tree.JMeterTreeNode(new LoopController(), null), match));
        assertFalse(connect.acceptsChildController(new LoopController()));
        match.setMatchMode(WebSocketMatchController.REGEX);
        match.setMatchValue("page (.*)");
        match.setSaveMessageVariable("receivedMessage");
        var output = new java.io.ByteArrayOutputStream();
        org.apache.jmeter.save.SaveService.saveElement(match, output);
        var loaded = (WebSocketMatchController) org.apache.jmeter.save.SaveService.loadElement(
                new java.io.ByteArrayInputStream(output.toByteArray()));
        assertEquals("receivedMessage", loaded.getSaveMessageVariable());
        assertEquals("page (.*)", loaded.getMatchValue());
        assertEquals(WebSocketMatchController.REGEX, loaded.getMatchMode());
    }

    private static SampleResult message(String text, boolean binary) {
        SampleResult result = new SampleResult();
        result.setResponseData(text, StandardCharsets.UTF_8.name());
        result.setDataType(binary ? SampleResult.BINARY : SampleResult.TEXT);
        return result;
    }

    @FunctionalInterface
    interface CheckedAction { void run() throws Exception; }

    private static final class Probe extends AbstractSampler implements org.apache.jmeter.engine.util.NoThreadClone {
        private static final long serialVersionUID = 1L;
        private final CheckedAction action;
        private final AtomicReference<Throwable> failure;
        Probe(CheckedAction action, AtomicReference<Throwable> failure) {
            this.action = action;
            this.failure = failure;
            setName("main flow");
        }
        @Override
        public SampleResult sample(Entry entry) {
            SampleResult result = new SampleResult();
            result.setSampleLabel(getName());
            result.sampleStart();
            try {
                action.run();
                result.setSuccessful(true);
            } catch (Throwable error) {
                failure.set(error);
                result.setSuccessful(false);
            }
            result.sampleEnd();
            return result;
        }
    }

    private static final class Results extends AbstractTestElement implements SampleListener, org.apache.jmeter.engine.util.NoThreadClone {
        private static final long serialVersionUID = 1L;
        final List<SampleResult> samples = new CopyOnWriteArrayList<>();
        final CountDownLatch pong;
        final CountDownLatch transaction;
        Results(CountDownLatch pong, CountDownLatch transaction) { this.pong = pong;
            this.transaction = transaction; }
        @Override
        public void sampleOccurred(SampleEvent event) {
            samples.add(event.getResult());
            if ("automatic pong".equals(event.getResult().getSampleLabel())) { pong.countDown(); }
            if ("page transaction".equals(event.getResult().getSampleLabel())) { transaction.countDown(); }
        }
        @Override public void sampleStarted(SampleEvent event) { }
        @Override public void sampleStopped(SampleEvent event) { }
    }
}
