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

package org.apache.jmeter.modifiers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class SampleTimeoutTest extends JMeterTestCase {

    /** Spins until interrupted and leaves the interrupt status set, like a script that ignores it. */
    private static class InterruptIgnoringSampler extends AbstractSampler implements Interruptible {
        private static final long serialVersionUID = 1L;

        private volatile Thread samplingThread;

        @Override
        public SampleResult sample(Entry e) {
            samplingThread = Thread.currentThread();
            try {
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                return new SampleResult();
            } finally {
                samplingThread = null;
            }
        }

        @Override
        public boolean interrupt() {
            Thread thread = samplingThread;
            if (thread == null) {
                return false;
            }
            thread.interrupt();
            return true;
        }
    }

    private static boolean runTimedSample(SampleTimeout timeout, AbstractSampler sampler, boolean selfInterrupt)
            throws InterruptedException {
        AtomicBoolean interruptedAfterSample = new AtomicBoolean(true);
        Thread worker = new Thread(() -> {
            timeout.sampleStarting(sampler);
            if (selfInterrupt) {
                Thread.currentThread().interrupt();
            }
            sampler.sample(null);
            timeout.sampleEnded(sampler);
            interruptedAfterSample.set(Thread.currentThread().isInterrupted());
        });
        worker.setDaemon(true);
        worker.start();
        worker.join(20_000);
        assertFalse(worker.isAlive());
        return interruptedAfterSample.get();
    }

    @Test
    void timeoutInterruptIgnoredBySamplerIsCleared() throws Exception {
        SampleTimeout timeout = new SampleTimeout();
        timeout.setTimeout("50");
        assertFalse(runTimedSample(timeout, new InterruptIgnoringSampler(), false));
    }

    @Test
    void independentInterruptIsPreservedWhenTimeoutDidNotFire() throws Exception {
        SampleTimeout timeout = new SampleTimeout();
        timeout.setTimeout("60000");
        assertTrue(runTimedSample(timeout, new InterruptIgnoringSampler(), true));
    }
}
