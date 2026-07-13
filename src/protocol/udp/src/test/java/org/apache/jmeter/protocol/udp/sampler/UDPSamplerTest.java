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

package org.apache.jmeter.protocol.udp.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.control.ForeachController;
import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

class UDPSamplerTest extends JMeterTestCase {

    private static final int LARGE_DATAGRAM_SIZE = 60_000;

    @BeforeEach
    void useIsolatedSamplerContext() {
        JMeterContext context = JMeterContextService.getContext();
        context.clear();
        context.setVariables(new JMeterVariables());
        new UDPSampler().setThreadContext(context);
    }

    @AfterEach
    void cleanUpSamplerContext() {
        UDPSocketManager.releaseScope(Thread.currentThread());
        JMeterContextService.getContext().clear();
    }

    @Test
    void defaultsToUtf8TextPayloads() {
        assertEquals(UTF8StringUDPTrafficCodec.class.getName(), new UDPSampler().getEncoderClass());
    }

    @Test
    void sendsAndReceivesDatagramUsingHexCodec() throws Exception {
        try (UdpServer server = UdpServer.echo()) {
            UDPSampler sampler = samplerFor(server.port());

            SampleResult result = sampler.sample(null);

            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals("74657374", result.getResponseDataAsString());
            assertEquals(StandardCharsets.US_ASCII.name(), result.getDataEncodingNoDefault());
            assertEquals(4, result.getSentBytes());
            assertEquals(4, result.getBytesAsLong());
        }
    }

    @Test
    void sendsAndReceivesLargeDatagramAsOneCompleteMessage() throws Exception {
        // DatagramSocket only exposes a datagram after IP reassembly. The payload is deliberately
        // above common network MTUs, although whether a loopback route fragments it is OS-dependent.
        String payload = "0123456789abcdef".repeat(LARGE_DATAGRAM_SIZE / 16);
        try (UdpServer server = UdpServer.echo()) {
            UDPSampler sampler = textSamplerFor(server.port(), payload);

            SampleResult result = sampler.sample(null);

            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals(payload, result.getResponseDataAsString());
            assertEquals(LARGE_DATAGRAM_SIZE, result.getSentBytes());
            assertEquals(LARGE_DATAGRAM_SIZE, result.getBytesAsLong());
        }
    }

    @Test
    void rejectsRequestAboveIpv4MaximumBeforeSending() throws Exception {
        try (UdpServer server = UdpServer.withoutResponse()) {
            UDPSampler sampler = textSamplerFor(
                    server.port(), "x".repeat(AbstractUDPSampler.MAX_IPV4_PAYLOAD_SIZE + 1));

            SampleResult result = sampler.sample(null);

            assertFalse(result.isSuccessful());
            assertEquals("413", result.getResponseCode());
            assertTrue(result.getResponseMessage().contains("maximum payload"));
        }
    }

    @Test
    @ResourceLock(AbstractUDPSampler.RECEIVE_BUFFER_SIZE_PROPERTY)
    void detectsResponseAboveConfiguredReceiveLimit() throws Exception {
        Properties properties = JMeterUtils.getJMeterProperties();
        String previousValue = properties.getProperty(AbstractUDPSampler.RECEIVE_BUFFER_SIZE_PROPERTY);
        properties.setProperty(AbstractUDPSampler.RECEIVE_BUFFER_SIZE_PROPERTY, "8");
        try (UdpServer server = UdpServer.echo()) {
            UDPSampler sampler = textSamplerFor(server.port(), "ten-bytes!");

            SampleResult result = sampler.sample(null);

            assertFalse(result.isSuccessful());
            assertEquals("413", result.getResponseCode());
            assertTrue(result.getResponseMessage().contains("receive limit of 8 bytes"));
        } finally {
            restoreProperty(properties, AbstractUDPSampler.RECEIVE_BUFFER_SIZE_PROPERTY, previousValue);
        }
    }

    @Test
    void exposesUtf8ResponseEncoding() throws Exception {
        String payload = "Héllo, 世界";
        try (UdpServer server = UdpServer.echo()) {
            UDPSampler sampler = textSamplerFor(server.port(), payload);

            SampleResult result = sampler.sample(null);

            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals(StandardCharsets.UTF_8.name(), result.getDataEncodingNoDefault());
            assertEquals(payload, result.getResponseDataAsString());
        }
    }

    @Test
    void reusesReceiveBufferAcrossSamples() throws Exception {
        UDPSampler sampler = null;
        try (UdpServer server = UdpServer.echo(2)) {
            sampler = textSamplerFor(server.port(), "payload");
            sampler.setCloseChannel(false);
            assertTrue(sampler.sample(null).isSuccessful());
            Object firstBuffer = receiveBufferOf(sampler);

            assertTrue(sampler.sample(null).isSuccessful());

            assertNotNull(firstBuffer);
            assertSame(firstBuffer, receiveBufferOf(sampler));
        } finally {
            if (sampler != null) {
                sampler.closeOwnedSocket();
            }
        }
    }

    @Test
    void doesNotCombineSeparateResponseDatagrams() throws Exception {
        String payload = "first-partother-part";
        try (UdpServer server = UdpServer.splitResponse()) {
            UDPSampler sampler = textSamplerFor(server.port(), payload);

            SampleResult result = sampler.sample(null);

            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertTrue(
                    result.getResponseDataAsString().equals("first-part")
                            || result.getResponseDataAsString().equals("other-part"),
                    result::getResponseDataAsString);
            assertEquals(payload.length() / 2, result.getBytesAsLong());
        }
    }

    @Test
    void reportsResponseTimeout() throws Exception {
        try (UdpServer server = UdpServer.withoutResponse()) {
            UDPSampler sampler = samplerFor(server.port());
            sampler.setTimeout("50");

            SampleResult result = sampler.sample(null);

            assertFalse(result.isSuccessful());
            assertEquals("408", result.getResponseCode());
        }
    }

    @Test
    void receivesLaterDatagramOnNamedSocket() throws Exception {
        try (UdpServer server = UdpServer.echo()) {
            UDPSampler request = samplerFor(server.port());
            request.setThreadName("UDP users-1");
            request.setSocketID(" conversation ");
            request.setWaitResponse(false);
            request.threadStarted();

            UDPReceiverSampler receiver = new UDPReceiverSampler();
            receiver.setName("receive");
            receiver.setThreadName("UDP users-1");
            receiver.setSocketID("conversation");
            receiver.setTimeout("1000");
            receiver.setEncoderClass(HexStringUDPTrafficCodec.class.getName());
            receiver.threadStarted();
            try {
                assertTrue(request.sample(null).isSuccessful());

                SampleResult result = receiver.sample(null);

                assertTrue(result.isSuccessful(), result::getResponseMessage);
                assertEquals("74657374", result.getResponseDataAsString());
            } finally {
                receiver.threadFinished();
                request.threadFinished();
            }
        }
    }

    @Test
    void receiverTimeoutCanBeSuccessfulOrFailing() throws Exception {
        try (UdpServer server = UdpServer.withoutResponse()) {
            UDPSampler request = textSamplerFor(server.port(), "request");
            request.setSocketID("conversation");
            request.setWaitResponse(false);
            UDPReceiverSampler receiver = receiverFor("conversation");
            receiver.setTimeout("30");
            request.threadStarted();
            receiver.threadStarted();
            try {
                assertTrue(request.sample(null).isSuccessful());

                receiver.setFailOnTimeout(false);
                SampleResult successfulTimeout = receiver.sample(null);
                receiver.setFailOnTimeout(true);
                SampleResult failingTimeout = receiver.sample(null);

                assertTrue(successfulTimeout.isSuccessful());
                assertEquals("204", successfulTimeout.getResponseCode());
                assertEquals(0, successfulTimeout.getResponseData().length);
                assertFalse(failingTimeout.isSuccessful());
                assertEquals("408", failingTimeout.getResponseCode());
                assertEquals(0, failingTimeout.getResponseData().length);
            } finally {
                receiver.threadFinished();
                request.threadFinished();
            }
        }
    }

    @Test
    void receiverInterruptClosesActiveNamedSocket() throws Exception {
        try (UdpServer server = UdpServer.withoutResponse()) {
            HashTree identityTree = new ListedHashTree();
            identityTree.add(singleIterationLoop());
            JMeterThread virtualUser = new JMeterThread(identityTree, null, new ListenerNotifier());
            JMeterContext mainContext = contextFor(virtualUser);
            new UDPSampler().setThreadContext(mainContext);

            UDPSampler request = textSamplerFor(server.port(), "request");
            request.setSocketID("conversation");
            request.setWaitResponse(false);
            UDPReceiverSampler receiver = receiverFor("conversation");
            receiver.setTimeout("10000");
            request.threadStarted();
            receiver.threadStarted();
            AtomicReference<SampleResult> result = new AtomicReference<>();
            Thread worker = null;
            try {
                assertTrue(request.sample(null).isSuccessful());
                worker = Thread.ofVirtual().name("udp-receiver-worker").start(() -> {
                    JMeterContext workerContext = contextFor(virtualUser);
                    receiver.setThreadContext(workerContext);
                    result.set(receiver.sample(null));
                });

                assertTrue(interruptEventually(receiver, Duration.ofSeconds(2)));
                worker.join(Duration.ofSeconds(2));

                assertFalse(worker.isAlive());
                assertNotNull(result.get());
                assertFalse(result.get().isSuccessful());
                assertEquals(0, UDPSocketManager.openSocketCount());
            } finally {
                if (worker != null && worker.isAlive()) {
                    receiver.interrupt();
                    worker.join(Duration.ofSeconds(2));
                }
                receiver.threadFinished();
                request.threadFinished();
            }
        }
    }

    @Test
    void reusesNamedSocketWithoutRepeatingEndpoint() throws Exception {
        try (UdpServer server = UdpServer.echo(2)) {
            UDPSampler firstRequest = textSamplerFor(server.port(), "first");
            firstRequest.setSocketID("conversation");
            UDPSampler nextRequest = textSamplerFor(server.port(), "second");
            nextRequest.setSocketID("conversation");
            nextRequest.setHostName("");
            nextRequest.setPort("");
            firstRequest.threadStarted();
            nextRequest.threadStarted();
            try {
                SampleResult firstResult = firstRequest.sample(null);
                SampleResult nextResult = nextRequest.sample(null);

                assertTrue(firstResult.isSuccessful(), firstResult::getResponseMessage);
                assertEquals("first", firstResult.getResponseDataAsString());
                assertTrue(nextResult.isSuccessful(), nextResult::getResponseMessage);
                assertEquals("second", nextResult.getResponseDataAsString());
            } finally {
                nextRequest.threadFinished();
                firstRequest.threadFinished();
            }
        }
    }

    @Test
    void requiresEndpointWhenNamedSocketDoesNotExist() {
        UDPSampler sampler = new UDPSampler();
        sampler.setName("UDP request");
        sampler.setSocketID("missing");
        sampler.setRequestData("payload");

        SampleResult result = sampler.sample(null);

        assertFalse(result.isSuccessful());
        assertTrue(result.getResponseMessage().contains("No open UDP socket found for Socket ID 'missing'"));
        assertTrue(result.getResponseMessage().contains("required to create it"));
    }

    @Test
    void rejectsBindSettingsWhenReusingNamedSocketWithoutEndpoint() throws Exception {
        try (UdpServer server = UdpServer.echo()) {
            UDPSampler firstRequest = textSamplerFor(server.port(), "first");
            firstRequest.setSocketID("conversation");
            UDPSampler nextRequest = textSamplerFor(server.port(), "second");
            nextRequest.setSocketID("conversation");
            nextRequest.setHostName("");
            nextRequest.setPort("");
            nextRequest.setBindPort("12345");
            firstRequest.threadStarted();
            nextRequest.threadStarted();
            try {
                assertTrue(firstRequest.sample(null).isSuccessful());

                SampleResult nextResult = nextRequest.sample(null);

                assertFalse(nextResult.isSuccessful());
                assertTrue(nextResult.getResponseMessage().contains("local bind settings cannot be changed"));
            } finally {
                nextRequest.threadFinished();
                firstRequest.threadFinished();
            }
        }
    }

    @Test
    void isolatesNamedSocketsBetweenVirtualUsers() throws Exception {
        try (UdpServer server = UdpServer.echo()) {
            UDPSampler firstUser = samplerFor(server.port());
            firstUser.setSocketID("conversation");
            firstUser.setWaitResponse(false);
            UDPSampler secondUser = samplerFor(server.port());
            secondUser.setSocketID("conversation");
            secondUser.setWaitResponse(false);
            CountDownLatch usersReady = new CountDownLatch(2);
            CountDownLatch releaseUsers = new CountDownLatch(1);
            AtomicReference<UDPSocketManager.SocketKey> firstKey = new AtomicReference<>();
            AtomicReference<UDPSocketManager.SocketKey> secondKey = new AtomicReference<>();
            AtomicReference<UDPSocketManager.SocketHandle> firstHandle = new AtomicReference<>();
            AtomicReference<UDPSocketManager.SocketHandle> secondHandle = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread firstThread = startNamedSocketUser(
                    firstUser, "UDP users-1", firstKey, firstHandle, usersReady, releaseUsers, failure);
            Thread secondThread = startNamedSocketUser(
                    secondUser, "UDP users-2", secondKey, secondHandle, usersReady, releaseUsers, failure);

            assertTrue(usersReady.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertNotEquals(firstKey.get(), secondKey.get());
            assertNotSame(firstHandle.get(), secondHandle.get());
            releaseUsers.countDown();
            firstThread.join(Duration.ofSeconds(2));
            secondThread.join(Duration.ofSeconds(2));
            assertFalse(firstThread.isAlive());
            assertFalse(secondThread.isAlive());
            assertNull(failure.get());
        }
    }

    @Test
    void reusesAndClosesNamedSocketAcrossParallelForEachWorkers() throws Exception {
        try (UdpServer server = UdpServer.echo(5)) {
            UDPSampler openSocket = namedTextSamplerFor(server.port(), "open", "conversation");
            UDPSampler reuseWithoutEndpoint = namedTextSamplerFor(server.port(), "reuse", "conversation");
            reuseWithoutEndpoint.setHostName("");
            reuseWithoutEndpoint.setPort("");
            UDPSampler validateRepeatedEndpoint =
                    namedTextSamplerFor(server.port(), "validate", "conversation");

            ForeachController foreach = parallelForEachController();
            ResultRecordingListener listener = new ResultRecordingListener();
            HashTree testTree = new ListedHashTree();
            LoopController loop = singleIterationLoop();
            testTree.add(loop);
            testTree.add(loop, openSocket);
            testTree.add(openSocket, listener);
            testTree.add(loop, foreach);
            testTree.add(foreach, reuseWithoutEndpoint);
            testTree.add(reuseWithoutEndpoint, listener);
            testTree.add(foreach, validateRepeatedEndpoint);
            testTree.add(validateRepeatedEndpoint, listener);

            JMeterVariables variables = new JMeterVariables();
            variables.putObject("input_1", "one");
            variables.putObject("input_2", "two");
            runTestPlan(testTree, variables, "udp-parallel-foreach");

            assertTrue(server.awaitRequests(Duration.ofSeconds(2)),
                    "The main flow and both parallel branches should send through the named socket");
            assertSuccessfulSamples(listener.results(), 5);
            assertEquals(0, UDPSocketManager.openSocketCount(),
                    "Virtual-user shutdown must close sockets used by parallel workers");
        }
    }

    @Test
    void reusesAndClosesNamedSocketAcrossForkWorker() throws Exception {
        try (UdpServer server = UdpServer.echo(3)) {
            UDPSampler openSocket = namedTextSamplerFor(server.port(), "open", "conversation");
            UDPSampler reuseWithoutEndpoint = namedTextSamplerFor(server.port(), "reuse", "conversation");
            reuseWithoutEndpoint.setHostName("");
            reuseWithoutEndpoint.setPort("");
            UDPSampler validateRepeatedEndpoint =
                    namedTextSamplerFor(server.port(), "validate", "conversation");

            ForkController fork = new ForkController();
            fork.setName("UDP fork");
            fork.setEnabled(true);
            ResultRecordingListener listener = new ResultRecordingListener();
            HashTree testTree = new ListedHashTree();
            LoopController loop = singleIterationLoop();
            testTree.add(loop);
            testTree.add(loop, openSocket);
            testTree.add(openSocket, listener);
            testTree.add(loop, fork);
            testTree.add(fork, reuseWithoutEndpoint);
            testTree.add(reuseWithoutEndpoint, listener);
            testTree.add(fork, validateRepeatedEndpoint);
            testTree.add(validateRepeatedEndpoint, listener);

            runTestPlan(testTree, new JMeterVariables(), "udp-fork");

            assertTrue(server.awaitRequests(Duration.ofSeconds(2)),
                    "The main flow and Fork worker should send through the named socket");
            assertSuccessfulSamples(listener.results(), 3);
            assertEquals(0, UDPSocketManager.openSocketCount(),
                    "Virtual-user shutdown must close sockets used by Fork workers");
        }
    }

    @Test
    void interruptClosesActiveReceiveFromAnotherThread() throws Exception {
        try (UdpServer server = UdpServer.withoutResponse()) {
            UDPSampler sampler = textSamplerFor(server.port(), "request");
            sampler.setTimeout("10000");
            AtomicReference<SampleResult> result = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().name("udp-sample-worker").start(() -> result.set(sampler.sample(null)));
            assertTrue(server.awaitRequest(Duration.ofSeconds(2)));

            assertTrue(sampler.interrupt());
            worker.join(Duration.ofSeconds(2));

            assertFalse(worker.isAlive());
            assertFalse(result.get().isSuccessful());
        }
    }

    private static UDPSampler samplerFor(int port) {
        UDPSampler sampler = new UDPSampler();
        sampler.setName("UDP request");
        sampler.setHostName("127.0.0.1");
        sampler.setPort(Integer.toString(port));
        sampler.setTimeout("1000");
        sampler.setEncoderClass(HexStringUDPTrafficCodec.class.getName());
        sampler.setRequestData("74657374");
        sampler.setWaitResponse(true);
        sampler.setCloseChannel(true);
        return sampler;
    }

    private static UDPSampler textSamplerFor(int port, String payload) {
        UDPSampler sampler = samplerFor(port);
        sampler.setTimeout("3000");
        sampler.setEncoderClass(UTF8StringUDPTrafficCodec.class.getName());
        sampler.setRequestData(payload);
        return sampler;
    }

    private static UDPSampler namedTextSamplerFor(int port, String payload, String socketId) {
        UDPSampler sampler = textSamplerFor(port, payload);
        sampler.setName(payload);
        sampler.setSocketID(socketId);
        return sampler;
    }

    private static ForeachController parallelForEachController() {
        ForeachController controller = new ForeachController();
        controller.setName("parallel UDP requests");
        controller.setInputVal("input");
        controller.setReturnVal("item");
        controller.setUseSeparator(true);
        controller.setParallel(true);
        controller.setMaxParallel(2);
        controller.setEnabled(true);
        return controller;
    }

    private static LoopController singleIterationLoop() {
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        return loop;
    }

    private static void runTestPlan(HashTree testTree, JMeterVariables variables, String threadName)
            throws InterruptedException {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("UDP users");
        threadGroup.setNumThreads(1);

        JMeterThread virtualUser = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        virtualUser.setThreadName(threadName);
        virtualUser.setThreadGroup(threadGroup);
        virtualUser.putVariables(variables);
        Thread runner = new Thread(virtualUser, threadName);
        runner.start();
        runner.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(runner.isAlive(), "UDP test plan should complete");
    }

    private static void assertSuccessfulSamples(List<SampleResult> results, int expectedCount) {
        assertEquals(expectedCount, results.size(), () -> "Unexpected UDP samples: " + results.stream()
                .map(result -> result.getSampleLabel() + '=' + result.getResponseMessage())
                .toList());
        assertTrue(results.stream().allMatch(SampleResult::isSuccessful), () -> "Failed UDP samples: "
                + results.stream()
                        .filter(result -> !result.isSuccessful())
                        .map(result -> result.getSampleLabel() + '=' + result.getResponseMessage())
                        .toList());
    }

    private static UDPReceiverSampler receiverFor(String socketId) {
        UDPReceiverSampler receiver = new UDPReceiverSampler();
        receiver.setName("UDP receiver");
        receiver.setSocketID(socketId);
        receiver.setEncoderClass(UTF8StringUDPTrafficCodec.class.getName());
        return receiver;
    }

    private static JMeterContext contextFor(JMeterThread virtualUser) {
        JMeterContext context = JMeterContextService.getContext();
        context.clear();
        context.setThread(virtualUser);
        context.setVariables(new JMeterVariables());
        return context;
    }

    private static boolean interruptEventually(UDPReceiverSampler receiver, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (receiver.interrupt()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static Object receiveBufferOf(UDPSampler sampler) throws Exception {
        Field field = AbstractUDPSampler.class.getDeclaredField("receiveBuffer");
        field.setAccessible(true);
        return field.get(sampler);
    }

    private static void restoreProperty(Properties properties, String name, String value) {
        if (value == null) {
            properties.remove(name);
        } else {
            properties.setProperty(name, value);
        }
    }

    private static Thread startNamedSocketUser(
            UDPSampler sampler,
            String threadName,
            AtomicReference<UDPSocketManager.SocketKey> key,
            AtomicReference<UDPSocketManager.SocketHandle> handle,
            CountDownLatch ready,
            CountDownLatch release,
            AtomicReference<Throwable> failure) {
        return Thread.ofVirtual().name(threadName).start(() -> {
            sampler.setThreadName(threadName);
            sampler.threadStarted();
            try {
                SampleResult result = sampler.sample(null);
                if (!result.isSuccessful()) {
                    throw new AssertionError(result.getResponseMessage());
                }
                key.set(sampler.getSocketKey());
                handle.set(UDPSocketManager.get(key.get()));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                ready.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, ex);
                }
                sampler.threadFinished();
            }
        });
    }

    private static final class ResultRecordingListener extends AbstractTestElement implements SampleListener {
        private static final long serialVersionUID = 1L;

        private final List<SampleResult> results = new CopyOnWriteArrayList<>();

        List<SampleResult> results() {
            return results;
        }

        @Override
        public void sampleOccurred(SampleEvent event) {
            results.add(event.getResult());
        }

        @Override
        public void sampleStarted(SampleEvent event) {
        }

        @Override
        public void sampleStopped(SampleEvent event) {
        }
    }

    private static final class UdpServer implements AutoCloseable {
        private final DatagramSocket socket;
        private final ResponseMode responseMode;
        private final int requestCount;
        private final CountDownLatch requestsReceived;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        private UdpServer(ResponseMode responseMode, int requestCount) throws Exception {
            this.responseMode = responseMode;
            this.requestCount = requestCount;
            requestsReceived = new CountDownLatch(requestCount);
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            thread = Thread.ofVirtual().name("udp-test-server").start(this::serve);
        }

        static UdpServer echo() throws Exception {
            return echo(1);
        }

        static UdpServer echo(int requestCount) throws Exception {
            return new UdpServer(ResponseMode.ECHO, requestCount);
        }

        static UdpServer withoutResponse() throws Exception {
            return new UdpServer(ResponseMode.NONE, 1);
        }

        static UdpServer splitResponse() throws Exception {
            return new UdpServer(ResponseMode.SPLIT, 1);
        }

        int port() {
            return socket.getLocalPort();
        }

        boolean awaitRequest(Duration timeout) throws InterruptedException {
            return requestsReceived.getCount() < requestCount
                    || requestsReceived.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        boolean awaitRequests(Duration timeout) throws InterruptedException {
            return requestsReceived.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void serve() {
            try {
                for (int requestNumber = 0; requestNumber < requestCount; requestNumber++) {
                    byte[] data = new byte[65_507];
                    DatagramPacket request = new DatagramPacket(data, data.length);
                    socket.receive(request);
                    requestsReceived.countDown();
                    byte[] received = Arrays.copyOfRange(
                            request.getData(), request.getOffset(), request.getOffset() + request.getLength());
                    if (responseMode == ResponseMode.ECHO) {
                        send(received, request);
                    } else if (responseMode == ResponseMode.SPLIT) {
                        int midpoint = received.length / 2;
                        send(Arrays.copyOfRange(received, 0, midpoint), request);
                        send(Arrays.copyOfRange(received, midpoint, received.length), request);
                    }
                }
            } catch (Exception ex) {
                if (!socket.isClosed()) {
                    failure.set(ex);
                }
            }
        }

        private void send(byte[] data, DatagramPacket request) throws Exception {
            socket.send(new DatagramPacket(data, data.length, request.getSocketAddress()));
        }

        @Override
        public void close() throws Exception {
            socket.close();
            thread.join(Duration.ofSeconds(2));
            Throwable throwable = failure.get();
            if (throwable != null) {
                throw new AssertionError("UDP test server failed", throwable);
            }
        }

        private enum ResponseMode {
            ECHO,
            NONE,
            SPLIT
        }
    }
}
