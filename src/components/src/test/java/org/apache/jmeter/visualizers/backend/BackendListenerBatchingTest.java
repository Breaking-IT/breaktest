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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class BackendListenerBatchingTest {
    private static final ConcurrentHashMap<String, Capture> CAPTURES = new ConcurrentHashMap<>();

    private static final class Capture {
        final List<String> labels = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release;
        final CountDownLatch tornDown = new CountDownLatch(1);
        int teardownCount;

        Capture(boolean holdFirstBatch) {
            release = new CountDownLatch(holdFirstBatch ? 1 : 0);
        }
    }

    public static final class CapturingClient extends AbstractBackendListenerClient {
        private Capture capture;

        @Override
        public void setupTest(BackendListenerContext context) {
            capture = CAPTURES.remove(context.getParameter("capture"));
        }

        @Override
        public void handleSampleResults(List<SampleResult> samples, BackendListenerContext context) {
            capture.entered.countDown();
            try {
                if (!capture.release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for test producer");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            capture.sizes.add(samples.size());
            for (SampleResult sample : samples) {
                capture.labels.add(sample.getSampleLabel());
            }
        }

        @Override
        public void teardownTest(BackendListenerContext context) {
            capture.teardownCount++;
            capture.tornDown.countDown();
        }
    }

    private BackendListener start(Capture capture, int capacity) {
        String id = UUID.randomUUID().toString();
        CAPTURES.put(id, capture);
        Arguments arguments = new Arguments();
        arguments.addArgument("capture", id);
        BackendListener listener = new BackendListener();
        listener.setName("batch-test-" + id);
        listener.setArguments(arguments);
        listener.setQueueSize(Integer.toString(capacity));
        listener.setClassname(CapturingClient.class.getName());
        listener.testStarted();
        return listener;
    }

    private void sample(BackendListener listener, String label) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(label);
        listener.sampleOccurred(new SampleEvent(result, "group"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 1023, 1024, 1025, 2049, 4097})
    void deliversOrderedSamplesBeforeTeardownAtBatchBoundaries(int count) throws Exception {
        Capture capture = new Capture(count > 0);
        BackendListener listener = start(capture, 6000);
        List<String> expected = new ArrayList<>();
        try (var workers = Executors.newSingleThreadExecutor()) {
            try {
                if (count > 0) {
                    sample(listener, "0");
                    expected.add("0");
                    assertTrue(capture.entered.await(5, TimeUnit.SECONDS));
                    for (int i = 1; i < count; i++) {
                        expected.add(Integer.toString(i));
                        sample(listener, Integer.toString(i));
                    }
                }
                Future<?> stopped = workers.submit(() -> listener.testEnded());
                if (count > 0) {
                    assertEquals(1, capture.tornDown.getCount());
                }
                capture.release.countDown();
                stopped.get(5, TimeUnit.SECONDS);
            } finally {
                capture.release.countDown();
            }
        }
        assertEquals(expected, capture.labels, "No marker, loss, duplication, or reordering");
        assertEquals(1, capture.teardownCount);
        assertEquals(0, capture.tornDown.getCount());
        assertTrue(capture.sizes.stream().allMatch(size -> size > 0 && size <= 1024));
        if (count > 1024) {
            assertTrue(capture.sizes.contains(1024), "A prefilled queue must be drained in bounded batches");
        }
    }

    @Test
    void defaultSampleResultIsNotMistakenForShutdownMarker() {
        Capture capture = new Capture(false);
        BackendListener listener = start(capture, 1);
        SampleResult result = new SampleResult();
        try {
            listener.sampleOccurred(new SampleEvent(result, "group"));
        } finally {
            listener.testEnded();
        }
        assertEquals(List.of(result.getSampleLabel()), capture.labels);
        assertEquals(1, capture.teardownCount);
    }

    @Test
    void concurrentProducersWithCapacityOneDeliverEverySampleAndShutDown() throws Exception {
        Capture capture = new Capture(false);
        BackendListener listener = start(capture, 1);
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> produced = new ArrayList<>();
            for (int producer = 0; producer < 4; producer++) {
                int id = producer;
                produced.add(workers.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        sample(listener, id + ":" + i);
                    }
                }));
            }
            for (Future<?> future : produced) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            listener.testEnded();
        }
        assertEquals(2000, capture.labels.size());
        assertEquals(2000, new HashSet<>(capture.labels).size());
        for (int producer = 0; producer < 4; producer++) {
            String prefix = producer + ":";
            List<String> ordered = capture.labels.stream().filter(label -> label.startsWith(prefix)).toList();
            for (int i = 0; i < 500; i++) {
                assertEquals(prefix + i, ordered.get(i));
            }
        }
        assertEquals(1, capture.teardownCount);
    }

    @Test
    void concurrentProducersBlockingOnSmallCapacityDeliverEverySampleInPerProducerOrder() throws Exception {
        Capture capture = new Capture(false);
        // Small capacity: producers repeatedly block on a full queue
        BackendListener listener = start(capture, 64);
        int producers = 8;
        int perProducer = 2000;
        try (var workers = Executors.newFixedThreadPool(producers)) {
            List<Future<?>> produced = new ArrayList<>();
            for (int producer = 0; producer < producers; producer++) {
                int id = producer;
                produced.add(workers.submit(() -> {
                    for (int i = 0; i < perProducer; i++) {
                        sample(listener, id + ":" + i);
                    }
                }));
            }
            for (Future<?> future : produced) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            listener.testEnded();
        }
        assertEquals(producers * perProducer, capture.labels.size(), "Delivered before teardown returned");
        assertEquals(producers * perProducer, new HashSet<>(capture.labels).size());
        for (int producer = 0; producer < producers; producer++) {
            String prefix = producer + ":";
            List<String> ordered = capture.labels.stream().filter(label -> label.startsWith(prefix)).toList();
            for (int i = 0; i < perProducer; i++) {
                assertEquals(prefix + i, ordered.get(i));
            }
        }
        assertTrue(capture.sizes.stream().allMatch(size -> size > 0 && size <= 1024));
        assertEquals(1, capture.teardownCount);
    }
}
