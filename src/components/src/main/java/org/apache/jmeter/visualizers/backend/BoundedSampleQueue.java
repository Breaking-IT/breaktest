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

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.jmeter.samplers.SampleResult;

/**
 * Bounded FIFO queue for many producers and a single consumer.
 *
 * <p>{@link java.util.concurrent.ArrayBlockingQueue} guards every offer and poll with
 * one lock. At several hundred thousand samples per second, producers (virtual
 * threads) briefly block on that lock and each release wakes a single waiter, so
 * throughput is limited by lock hand-offs while CPUs stay idle. Here producers and
 * the consumer only use lock-free operations while the queue has room; the lock is
 * taken solely by producers that find the queue full and by the consumer when such
 * producers are waiting.
 */
final class BoundedSampleQueue {
    private final ConcurrentLinkedQueue<SampleResult> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;
    private volatile Thread waitingConsumer;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    /** Producers waiting for space; the consumer signals only when nonzero. */
    private final AtomicInteger waiting = new AtomicInteger();

    BoundedSampleQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Queue capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * Inserts the sample if the queue has room.
     *
     * @return false if the queue is full
     */
    boolean offer(SampleResult sample) {
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            return false;
        }
        queue.offer(sample);
        LockSupport.unpark(waitingConsumer);
        return true;
    }

    /**
     * Waits only while empty. Register before checking so an offer racing with
     * the check either makes the queue nonempty or supplies an unpark permit.
     * Must only be called by the single consumer; spurious wakeups are allowed.
     */
    void awaitNotEmpty(long nanos) {
        waitingConsumer = Thread.currentThread();
        try {
            if (queue.isEmpty()) {
                LockSupport.parkNanos(this, nanos);
            }
        } finally {
            waitingConsumer = null;
        }
    }

    /** Inserts the sample, waiting for space if necessary. */
    void put(SampleResult sample) throws InterruptedException {
        if (offer(sample)) {
            return;
        }
        lock.lockInterruptibly();
        try {
            // Register before retrying: a consumer that frees space after this point
            // sees the waiter and signals; one that freed space before lets the retry succeed.
            waiting.incrementAndGet();
            try {
                while (!offer(sample)) {
                    // Timed as a safety net; signals are the normal wake-up path.
                    notFull.await(10, TimeUnit.MILLISECONDS);
                }
            } finally {
                waiting.decrementAndGet();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Moves up to {@code maxElements} samples into {@code sink} in insertion order.
     * Must only be called by the single consumer.
     *
     * @return number of samples transferred
     */
    int drainTo(Collection<? super SampleResult> sink, int maxElements) {
        int drained = 0;
        SampleResult sample;
        while (drained < maxElements && (sample = queue.poll()) != null) {
            sink.add(sample);
            drained++;
        }
        if (drained > 0) {
            size.addAndGet(-drained);
            if (waiting.get() > 0) {
                lock.lock();
                try {
                    notFull.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }
        return drained;
    }
}
