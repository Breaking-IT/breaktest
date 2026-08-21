/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.protocol.http.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.concurrent.BasicFuture;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.function.Supplier;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.nio.AsyncPushConsumer;
import org.apache.hc.core5.http.nio.AsyncRequestProducer;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.HandlerFactory;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.apache.jmeter.control.ForeachController;
import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

@Execution(ExecutionMode.SAME_THREAD)
class HTTPHC5H2ThreadLifecycleTest {
    private final Set<Object> cacheKeysToClean = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void setupJMeterProperties() throws Exception {
        if (JMeterUtils.getJMeterProperties() == null) {
            Path properties = Files.createTempFile("jmeter", ".properties");
            JMeterUtils.loadJMeterProperties(properties.toString());
            Files.deleteIfExists(properties);
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Object key : cacheKeysToClean) {
            closeCacheEntry(key);
        }
        JMeterContextService.getContext().clear();
    }

    @Test
    void shortLivedOpenModelUsersReturnCacheAndReactorThreadsToBaseline() throws Exception {
        withServer(server -> {
            int cacheBaseline = clientCache().size();
            int reactorThreadBaseline = httpClientReactorThreadCount();

            for (int i = 0; i < 8; i++) {
                runUser(server.port(), i, ExitMode.NORMAL, true, null);
            }

            assertEquals(cacheBaseline, clientCache().size(),
                    "HC5/H2 clients must not accumulate after short-lived JMeter threads finish");
            assertEventually(() -> httpClientReactorThreadCount() <= reactorThreadBaseline, 5_000,
                    "HTTP-client reactor threads must be bounded by active users, not cumulative users");
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BREAKTEST_HC5_LIFECYCLE_BENCHMARK", matches = "true")
    void benchmarkShortLivedParallelUserChurn() throws Exception {
        int waves = benchmarkSetting("BREAKTEST_HC5_BENCHMARK_WAVES", 20);
        int usersPerWave = benchmarkSetting("BREAKTEST_HC5_BENCHMARK_USERS_PER_WAVE", 50);
        withServer(100, server -> {
            int cacheBaseline = clientCache().size();
            int reactorThreadBaseline = httpClientReactorThreadCount();
            int totalThreadBaseline = totalLiveThreadCount();
            long fileDescriptorBaseline = openFileDescriptorCount();
            AtomicInteger maxCacheEntries = new AtomicInteger(cacheBaseline);
            AtomicInteger maxReactorThreads = new AtomicInteger(reactorThreadBaseline);
            AtomicInteger maxTotalThreads = new AtomicInteger(totalThreadBaseline);
            AtomicLong maxFileDescriptors = new AtomicLong(fileDescriptorBaseline);
            long started = System.nanoTime();

            for (int wave = 0; wave < waves; wave++) {
                runParallelUserWave(
                        server.port(), wave * usersPerWave, usersPerWave,
                        maxCacheEntries, maxReactorThreads, maxTotalThreads, maxFileDescriptors);
                assertEquals(cacheBaseline, clientCache().size(),
                        () -> "HC5/H2 cache must return to baseline after churn wave");
            }

            assertEventually(() -> httpClientReactorThreadCount() <= reactorThreadBaseline, 10_000,
                    "HTTP-client threads must return to baseline after churn");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.printf(
                    "HC5/H2 lifecycle benchmark: users=%d, peakActiveUsers=%d, requests=%d, elapsedMs=%d, "
                            + "cache=%d/%d/%d, httpClientThreads=%d/%d/%d, totalThreads=%d/%d/%d, "
                            + "fileDescriptors=%d/%d/%d%n",
                    waves * usersPerWave,
                    usersPerWave,
                    waves * usersPerWave * 2,
                    elapsedMillis,
                    cacheBaseline,
                    maxCacheEntries.get(),
                    clientCache().size(),
                    reactorThreadBaseline,
                    maxReactorThreads.get(),
                    httpClientReactorThreadCount(),
                    totalThreadBaseline,
                    maxTotalThreads.get(),
                    totalLiveThreadCount(),
                    fileDescriptorBaseline,
                    maxFileDescriptors.get(),
                    openFileDescriptorCount());
        });
    }

    private static int benchmarkSetting(String name, int defaultValue) {
        return Integer.parseInt(System.getenv().getOrDefault(name, Integer.toString(defaultValue)));
    }

    @Test
    void cleanupRunsForFailureStopThreadAndTestShutdownPaths() throws Exception {
        withServer(server -> {
            int baseline = clientCache().size();
            int userNumber = 0;
            for (ExitMode exitMode : ExitMode.values()) {
                runUser(server.port(), userNumber++, exitMode, false, null);
                assertEquals(baseline, clientCache().size(),
                        () -> "HC5/H2 cache must return to baseline after " + exitMode);
            }
        });
    }

    @Test
    void parallelBranchesAndClosedModelIterationsReuseTheOwningUserClient() throws Exception {
        withServer(server -> {
            AtomicReference<JMeterThread> owner = new AtomicReference<>();
            AtomicInteger clientsAtThreadFinish = new AtomicInteger(-1);
            CacheSnapshotListener snapshot = new CacheSnapshotListener(owner, clientsAtThreadFinish);

            runUser(server.port(), 0, ExitMode.NORMAL, true, new UserRunObserver(owner, snapshot));

            assertEquals(1, clientsAtThreadFinish.get(),
                    "parallel branches for one VU should share one client for the same route");
            server.verify(2, WireMock.getRequestedFor(WireMock.urlEqualTo("/resource")));

            clientsAtThreadFinish.set(-1);
            runClosedModelUser(server.port(), 1, owner, snapshot);

            assertEquals(1, clientsAtThreadFinish.get(),
                    "ordinary closed-model iterations should reuse the existing client");
            server.verify(4, WireMock.getRequestedFor(WireMock.urlEqualTo("/resource")));
        });
    }

    @Test
    void parallelForEachRuntimeSamplerClonesReturnCacheToBaseline() throws Exception {
        withServer(server -> {
            int baseline = clientCache().size();
            LoopController loop = loopController(1);
            ForeachController foreach = parallelForEachController();
            HTTPSamplerProxy sampler = httpSampler(server.port(), ExitMode.NORMAL);

            HashTree testTree = new ListedHashTree();
            testTree.add(loop);
            testTree.add(loop, foreach);
            testTree.add(foreach, sampler);

            JMeterThread thread = jMeterThread(testTree, 0, ExitMode.NORMAL);
            thread.putVariables(forEachVariables());
            cacheKeysToClean.add(thread);
            runJMeterThread(thread);

            server.verify(2, WireMock.getRequestedFor(WireMock.urlEqualTo("/resource")));
            assertEquals(baseline, clientCache().size(),
                    "runtime HTTP sampler clones must not orphan the owning VU cache entry");
        });
    }

    @Test
    void forkContainingParallelForEachRuntimeClonesReturnsCacheToBaseline() throws Exception {
        withServer(server -> {
            int baseline = clientCache().size();
            LoopController loop = loopController(1);
            ForkController fork = new ForkController();
            fork.setName("fork");
            fork.setEnabled(true);
            ForeachController foreach = parallelForEachController();
            HTTPSamplerProxy sampler = httpSampler(server.port(), ExitMode.NORMAL);

            HashTree testTree = new ListedHashTree();
            testTree.add(loop);
            testTree.add(loop, fork);
            testTree.add(fork, foreach);
            testTree.add(foreach, sampler);

            JMeterThread thread = jMeterThread(testTree, 0, ExitMode.NORMAL);
            thread.putVariables(forEachVariables());
            cacheKeysToClean.add(thread);
            runJMeterThread(thread);

            server.verify(2, WireMock.getRequestedFor(WireMock.urlEqualTo("/resource")));
            assertEquals(baseline, clientCache().size(),
                    "runtime clones nested in detached fork workers must use owning-VU cleanup");
        });
    }

    @Test
    void classicFacadeAndBackingAsyncClientAreClosedExactlyOnceAndRemoved() throws Exception {
        Object owner = new Object();
        cacheKeysToClean.add(owner);
        CountingAsyncClient asyncClient = new CountingAsyncClient();
        CountingClassicClient classicClient = new CountingClassicClient(
                HttpAsyncClients.classic(asyncClient, Timeout.ofSeconds(1)));

        Constructor<?> constructor = httpClientStateClass().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object clientState = constructor.newInstance(classicClient, null);
        Map<Object, Object> clients = new HashMap<>();
        clients.put(new Object(), clientState);
        clientCache().put(owner, clients);

        closeCacheEntry(owner);

        assertFalse(clientCache().containsKey(owner), "the owning VU cache entry should be removed");
        assertEquals(1, classicClient.closeCount.get(), "the classic client should be closed exactly once");
        assertEquals(1, asyncClient.closeCount.get(), "the backing async client should be closed exactly once");
        assertEquals(IOReactorStatus.SHUT_DOWN, asyncClient.getStatus());
    }

    private void runUser(int port, int userNumber, ExitMode exitMode, boolean parallel,
            UserRunObserver observer) throws InterruptedException {
        JMeterThread thread = createUser(port, userNumber, exitMode, parallel, observer);
        cacheKeysToClean.add(thread);
        runJMeterThread(thread);
    }

    private void runParallelUserWave(int port, int firstUserNumber, int userCount,
            AtomicInteger maxCacheEntries, AtomicInteger maxReactorThreads, AtomicInteger maxTotalThreads,
            AtomicLong maxFileDescriptors)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(userCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> carriers = new ArrayList<>(userCount);
        for (int i = 0; i < userCount; i++) {
            int userNumber = firstUserNumber + i;
            JMeterThread user = createUser(port, userNumber, ExitMode.NORMAL, true, null);
            cacheKeysToClean.add(user);
            Thread carrier = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    user.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "benchmark-carrier-" + userNumber);
            carriers.add(carrier);
            carrier.start();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "benchmark users should be ready to start");
        start.countDown();

        boolean usersRunning;
        do {
            maxCacheEntries.accumulateAndGet(clientCache().size(), Math::max);
            maxReactorThreads.accumulateAndGet(httpClientReactorThreadCount(), Math::max);
            maxTotalThreads.accumulateAndGet(totalLiveThreadCount(), Math::max);
            maxFileDescriptors.accumulateAndGet(openFileDescriptorCount(), Math::max);
            usersRunning = carriers.stream().anyMatch(Thread::isAlive);
            if (usersRunning) {
                Thread.sleep(10);
            }
        } while (usersRunning);
        for (Thread carrier : carriers) {
            carrier.join();
        }
    }

    private JMeterThread createUser(int port, int userNumber, ExitMode exitMode, boolean parallel,
            UserRunObserver observer) {
        LoopController loop = loopController(1);
        HashTree testTree = new ListedHashTree();
        testTree.add(loop);
        if (observer != null) {
            testTree.add(loop, observer.listener);
        }
        if (parallel) {
            ParallelController parallelController = new ParallelController();
            parallelController.setName("parallel");
            parallelController.setMaxParallel(2);
            parallelController.setEnabled(true);
            testTree.add(loop, parallelController);
            testTree.add(parallelController, httpSampler(port, exitMode));
            testTree.add(parallelController, httpSampler(port, exitMode));
        } else {
            testTree.add(loop, httpSampler(port, exitMode));
        }

        JMeterThread thread = jMeterThread(testTree, userNumber, exitMode);
        if (observer != null) {
            observer.owner.set(thread);
        }
        return thread;
    }

    private void runClosedModelUser(int port, int userNumber, AtomicReference<JMeterThread> owner,
            CacheSnapshotListener snapshot) throws InterruptedException {
        LoopController loop = loopController(2);
        HashTree testTree = new ListedHashTree();
        testTree.add(loop);
        testTree.add(loop, snapshot);
        testTree.add(loop, httpSampler(port, ExitMode.NORMAL));

        JMeterThread thread = jMeterThread(testTree, userNumber, ExitMode.NORMAL);
        owner.set(thread);
        cacheKeysToClean.add(thread);
        runJMeterThread(thread);
    }

    private static void runJMeterThread(JMeterThread jMeterThread) throws InterruptedException {
        Thread carrier = new Thread(jMeterThread, "test-carrier-" + jMeterThread.getThreadName());
        carrier.start();
        carrier.join(30_000);
        if (carrier.isAlive()) {
            carrier.interrupt();
        }
        assertFalse(carrier.isAlive(), "JMeter user carrier thread should finish");
    }

    private static JMeterThread jMeterThread(HashTree testTree, int userNumber, ExitMode exitMode) {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("user-group");
        threadGroup.setNumThreads(1);

        JMeterThread thread = new JMeterThread(testTree, threadGroup, new ListenerNotifier());
        thread.setThreadName("user-" + userNumber);
        thread.setThreadGroup(threadGroup);
        thread.setOnErrorStopThread(exitMode == ExitMode.STOP_THREAD);
        thread.setOnErrorStopTest(exitMode == ExitMode.TEST_SHUTDOWN);
        thread.setOnErrorStopTestNow(exitMode == ExitMode.TEST_SHUTDOWN_NOW);
        return thread;
    }

    private static LoopController loopController(int loops) {
        LoopController loop = new LoopController();
        loop.setLoops(loops);
        loop.setContinueForever(false);
        loop.setEnabled(true);
        return loop;
    }

    private static ForeachController parallelForEachController() {
        ForeachController foreach = new ForeachController();
        foreach.setName("parallel-foreach");
        foreach.setInputVal("input");
        foreach.setReturnVal("item");
        foreach.setUseSeparator(true);
        foreach.setParallel(true);
        foreach.setMaxParallel(2);
        foreach.setEnabled(true);
        return foreach;
    }

    private static JMeterVariables forEachVariables() {
        JMeterVariables variables = new JMeterVariables();
        variables.putObject("input_1", "one");
        variables.putObject("input_2", "two");
        return variables;
    }

    private static HTTPSamplerProxy httpSampler(int port, ExitMode exitMode) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy(HTTPSamplerFactory.IMPL_HTTP_CLIENT5);
        sampler.setName("request");
        sampler.setProtocol("http");
        sampler.setDomain("localhost");
        sampler.setPort(port);
        sampler.setPath(exitMode == ExitMode.NORMAL ? "/resource" : "/failure");
        sampler.setMethod(HTTPConstants.GET);
        return sampler;
    }

    private static void withServer(ServerTask task) throws Exception {
        withServer(0, task);
    }

    private static void withServer(int responseDelayMillis, ServerTask task) throws Exception {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        try {
            server.stubFor(WireMock.get(WireMock.urlEqualTo("/resource"))
                    .willReturn(WireMock.aResponse()
                            .withFixedDelay(responseDelayMillis)
                            .withStatus(200)
                            .withBody("ok")));
            server.stubFor(WireMock.get(WireMock.urlEqualTo("/failure"))
                    .willReturn(WireMock.aResponse().withStatus(500).withBody("failed")));
            task.run(server);
        } finally {
            server.stop();
        }
    }

    private static int httpClientReactorThreadCount() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("httpclient-"))
                .count();
    }

    private static int totalLiveThreadCount() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .count();
    }

    private static long openFileDescriptorCount() {
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.UnixOperatingSystemMXBean unixOperatingSystem) {
            return unixOperatingSystem.getOpenFileDescriptorCount();
        }
        return -1;
    }

    private static void assertEventually(BooleanSupplier condition, long timeoutMillis, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> clientCache() throws Exception {
        Field cache = HTTPHC5H2Impl.class.getDeclaredField("HTTPCLIENTS_CACHE_PER_JMETER_THREAD");
        cache.setAccessible(true);
        return (Map<Object, Object>) cache.get(null);
    }

    private static Class<?> httpClientStateClass() {
        for (Class<?> nestedClass : HTTPHC5H2Impl.class.getDeclaredClasses()) {
            if (nestedClass.getSimpleName().equals("HttpClientState")) {
                return nestedClass;
            }
        }
        throw new AssertionError("HTTPHC5H2Impl.HttpClientState not found");
    }

    private static void closeCacheEntry(Object cacheKey) throws Exception {
        Method close = HTTPHC5H2Impl.class.getDeclaredMethod("closeThreadLocalConnections", Object.class);
        close.setAccessible(true);
        close.invoke(null, cacheKey);
    }

    private enum ExitMode {
        NORMAL,
        SAMPLER_FAILURE,
        STOP_THREAD,
        TEST_SHUTDOWN,
        TEST_SHUTDOWN_NOW
    }

    private record UserRunObserver(AtomicReference<JMeterThread> owner, CacheSnapshotListener listener) {
    }

    private static final class CacheSnapshotListener extends AbstractTestElement implements ThreadListener {
        private static final long serialVersionUID = 1L;
        private final AtomicReference<JMeterThread> owner;
        private final AtomicInteger clientsAtThreadFinish;

        private CacheSnapshotListener(AtomicReference<JMeterThread> owner, AtomicInteger clientsAtThreadFinish) {
            this.owner = owner;
            this.clientsAtThreadFinish = clientsAtThreadFinish;
        }

        @Override
        public void threadStarted() {
        }

        @Override
        public void threadFinished() {
            try {
                Object clients = clientCache().get(owner.get());
                clientsAtThreadFinish.set(clients instanceof Map<?, ?> map ? map.size() : 0);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    private static final class CountingClassicClient extends CloseableHttpClient {
        private final CloseableHttpClient delegate;
        private final AtomicInteger closeCount = new AtomicInteger();

        private CountingClassicClient(CloseableHttpClient delegate) {
            this.delegate = delegate;
        }

        @Override
        protected CloseableHttpResponse doExecute(HttpHost target, ClassicHttpRequest request, HttpContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            delegate.close();
        }

        @Override
        public void close(CloseMode closeMode) {
            closeCount.incrementAndGet();
            delegate.close(closeMode);
        }
    }

    @SuppressWarnings("deprecation")
    private static final class CountingAsyncClient extends CloseableHttpAsyncClient {
        private final AtomicInteger closeCount = new AtomicInteger();
        private volatile IOReactorStatus status = IOReactorStatus.INACTIVE;

        @Override
        public void start() {
            status = IOReactorStatus.ACTIVE;
        }

        @Override
        public IOReactorStatus getStatus() {
            return status;
        }

        @Override
        public void awaitShutdown(TimeValue waitTime) {
        }

        @Override
        public void initiateShutdown() {
            status = IOReactorStatus.SHUTTING_DOWN;
        }

        @Override
        protected <T> Future<T> doExecute(HttpHost target, AsyncRequestProducer requestProducer,
                AsyncResponseConsumer<T> responseConsumer, HandlerFactory<AsyncPushConsumer> pushHandlerFactory,
                HttpContext context, FutureCallback<T> callback) {
            BasicFuture<T> future = new BasicFuture<>(callback);
            future.failed(new UnsupportedOperationException());
            return future;
        }

        @Override
        public void register(String hostname, String uriPattern, Supplier<AsyncPushConsumer> supplier) {
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            status = IOReactorStatus.SHUT_DOWN;
        }

        @Override
        public void close(CloseMode closeMode) {
            close();
        }
    }

    @FunctionalInterface
    private interface ServerTask {
        void run(WireMockServer server) throws Exception;
    }
}
