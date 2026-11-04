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

package org.apache.jmeter.protocol.java.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

class JSR223SamplerTest {

    @Test
    void interruptRunningScript() throws Exception {
        String latchKey = JSR223SamplerTest.class.getName() + ".latch";
        CountDownLatch started = new CountDownLatch(1);
        System.getProperties().put(latchKey, started);
        JSR223Sampler sampler = new JSR223Sampler();
        sampler.setScriptLanguage("groovy");
        sampler.setScript("System.getProperties().get('" + latchKey + "').countDown(); Thread.sleep(30000)");
        AtomicReference<SampleResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(sampler.sample(null)));
        try {
            assertFalse(sampler.interrupt());
            worker.start();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertTrue(sampler.interrupt());
            worker.join(10000);
            assertFalse(worker.isAlive());
            assertFalse(result.get().isSuccessful());
            assertEquals("499", result.get().getResponseCode());
            assertFalse(sampler.interrupt());
        } finally {
            worker.interrupt();
            worker.join(10000);
            System.getProperties().remove(latchKey);
        }
    }

    @Test
    void subsequentInterruptIsPreserved() throws Exception {
        String latchKey = JSR223SamplerTest.class.getName() + ".ignoredLatch";
        String consumedKey = JSR223SamplerTest.class.getName() + ".consumedLatch";
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch consumed = new CountDownLatch(1);
        System.getProperties().put(latchKey, started);
        System.getProperties().put(consumedKey, consumed);
        JSR223Sampler sampler = new JSR223Sampler();
        sampler.setScriptLanguage("groovy");
        sampler.setScript("System.getProperties().get('" + latchKey + "').countDown();"
                + " while (!Thread.interrupted()) { Thread.onSpinWait() };"
                + " System.getProperties().get('" + consumedKey + "').countDown();"
                + " while (!Thread.currentThread().isInterrupted()) { Thread.onSpinWait() }; 'OK'");
        AtomicReference<SampleResult> result = new AtomicReference<>();
        AtomicBoolean interruptedAfterSample = new AtomicBoolean(true);
        Thread worker = new Thread(() -> {
            result.set(sampler.sample(null));
            interruptedAfterSample.set(Thread.currentThread().isInterrupted());
        });
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertTrue(sampler.interrupt());
            assertTrue(consumed.await(10, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(10000);
            assertFalse(worker.isAlive());
            assertTrue(result.get().isSuccessful());
            assertTrue(interruptedAfterSample.get());
        } finally {
            worker.interrupt();
            worker.join(10000);
            System.getProperties().remove(latchKey);
            System.getProperties().remove(consumedKey);
        }
    }

    @Test
    void scriptFailureAfterInterruptIsReported() throws Exception {
        String latchKey = JSR223SamplerTest.class.getName() + ".failureLatch";
        CountDownLatch started = new CountDownLatch(1);
        System.getProperties().put(latchKey, started);
        JSR223Sampler sampler = new JSR223Sampler();
        sampler.setScriptLanguage("groovy");
        sampler.setScript("System.getProperties().get('" + latchKey + "').countDown();"
                + " try { Thread.sleep(30000) } catch (InterruptedException ignored) {};"
                + " throw new IllegalStateException('script failed')");
        AtomicReference<SampleResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(sampler.sample(null)));
        try {
            worker.start();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertTrue(sampler.interrupt());
            worker.join(10000);
            assertFalse(worker.isAlive());
            assertEquals("500", result.get().getResponseCode());
            assertTrue(result.get().getResponseMessage().contains("script failed"));
        } finally {
            worker.interrupt();
            worker.join(10000);
            System.getProperties().remove(latchKey);
        }
    }

    @Test
    void sampleWithEndTimeSet() {
        JSR223Sampler sampler = new JSR223Sampler();
        sampler.setName("jsr223Test");
        sampler.setScript("SampleResult.setEndTime(42); 'OK'");
        sampler.setScriptLanguage("groovy");
        SampleResult sampleResult = sampler.sample(null);
        assertEquals(42, sampleResult.getEndTime());
    }

    @Test
    void sampleWithoutEndTimeSet() {
        JSR223Sampler sampler = new JSR223Sampler();
        sampler.setName("jsr223Test");
        sampler.setScript("'OK'");
        sampler.setScriptLanguage("groovy");
        SampleResult sampleResult = sampler.sample(null);
        assertEquals(System.currentTimeMillis(), sampleResult.getEndTime(), 1000);
    }
}
