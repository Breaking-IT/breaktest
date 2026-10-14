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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class BoundedSampleQueueTest {

    private static SampleResult sample(String label) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(label);
        return result;
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedSampleQueue(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 5000})
    void concurrentOffersNeverExceedCapacity(int capacity) throws Exception {
        BoundedSampleQueue queue = new BoundedSampleQueue(capacity);
        int accepted = 0;
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                results.add(executor.submit(() -> {
                    int ok = 0;
                    for (int i = 0; i < capacity; i++) {
                        if (queue.offer(sample("x"))) {
                            ok++;
                        }
                    }
                    return ok;
                }));
            }
            for (Future<Integer> result : results) {
                accepted += result.get(5, TimeUnit.SECONDS);
            }
        }
        assertEquals(capacity, accepted, "Exactly the capacity is accepted");
        List<SampleResult> sink = new ArrayList<>();
        int drained;
        do {
            drained = queue.drainTo(sink, 1024);
        } while (drained > 0);
        assertEquals(capacity, sink.size());
        assertTrue(queue.offer(sample("after drain")), "Drained space is reusable");
    }

    @Test
    void drainsInInsertionOrderWithinBound() {
        BoundedSampleQueue queue = new BoundedSampleQueue(100);
        for (int i = 0; i < 50; i++) {
            assertTrue(queue.offer(sample(Integer.toString(i))));
        }
        List<SampleResult> sink = new ArrayList<>();
        assertEquals(20, queue.drainTo(sink, 20));
        assertEquals(30, queue.drainTo(sink, 1024));
        assertEquals(0, queue.drainTo(sink, 1024));
        for (int i = 0; i < 50; i++) {
            assertEquals(Integer.toString(i), sink.get(i).getSampleLabel());
        }
    }

    @Test
    void putWaitsForSpaceAndResumesAfterDrain() throws Exception {
        BoundedSampleQueue queue = new BoundedSampleQueue(1);
        assertTrue(queue.offer(sample("first")));
        assertFalse(queue.offer(sample("rejected")));
        CountDownLatch started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> blocked = executor.submit(() -> {
                started.countDown();
                queue.put(sample("second"));
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertFalse(blocked.isDone(), "put must wait while the queue is full");
            List<SampleResult> sink = new ArrayList<>();
            assertEquals(1, queue.drainTo(sink, 10));
            blocked.get(5, TimeUnit.SECONDS);
            assertEquals(1, queue.drainTo(sink, 10));
            assertEquals(List.of("first", "second"), sink.stream().map(SampleResult::getSampleLabel).toList());
        }
    }

    @Test
    void offerWakesParkedConsumerWithoutWaitingForTimeout() throws Exception {
        BoundedSampleQueue queue = new BoundedSampleQueue(1);
        CountDownLatch started = new CountDownLatch(1);
        Thread consumer = Thread.ofPlatform().unstarted(() -> {
            started.countDown();
            queue.awaitNotEmpty(TimeUnit.SECONDS.toNanos(30));
        });
        try {
            consumer.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (consumer.getState() != Thread.State.TIMED_WAITING && consumer.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(Thread.State.TIMED_WAITING, consumer.getState());
            assertTrue(queue.offer(sample("wake")));
            consumer.join(5000);
            assertFalse(consumer.isAlive(), "An offer must wake the consumer before its park timeout");
        } finally {
            consumer.interrupt();
            consumer.join(5000);
        }
    }

    @Test
    void sampleOfferedBeforeWaitDoesNotNeedAnotherWakeup() throws Exception {
        BoundedSampleQueue queue = new BoundedSampleQueue(1);
        assertTrue(queue.offer(sample("already queued")));
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> waiting = executor.submit(() -> queue.awaitNotEmpty(TimeUnit.SECONDS.toNanos(30)));
            try {
                waiting.get(5, TimeUnit.SECONDS);
            } finally {
                waiting.cancel(true);
            }
        }
    }

    @Test
    void putIsInterruptible() throws Exception {
        BoundedSampleQueue queue = new BoundedSampleQueue(1);
        assertTrue(queue.offer(sample("first")));
        Thread producer = Thread.ofPlatform().unstarted(() -> {
            try {
                queue.put(sample("second"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        Thread.sleep(50);
        producer.interrupt();
        producer.join(5000);
        assertFalse(producer.isAlive());
        List<SampleResult> sink = new ArrayList<>();
        assertEquals(1, queue.drainTo(sink, 10), "Interrupted put adds nothing");
    }
}
