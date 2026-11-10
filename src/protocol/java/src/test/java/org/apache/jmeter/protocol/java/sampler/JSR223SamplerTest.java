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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.modifiers.SampleTimeout;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class JSR223SamplerTest {

    private enum IndependentInterrupt {
        BEFORE_TIMEOUT,
        AFTER_TIMEOUT,
        PREEXISTING,
        WITHOUT_TIMEOUT
    }

    @ParameterizedTest
    @EnumSource(IndependentInterrupt.class)
    void preservesIndependentInterrupt(IndependentInterrupt interruption) throws Exception {
        String key = JSR223SamplerTest.class.getName() + ".independent." + interruption;
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finish = new AtomicBoolean();
        System.getProperties().put(key, started);
        System.getProperties().put(key + ".finish", finish);
        JSR223Sampler sampler = new JSR223Sampler();
        sampler.setScriptLanguage("groovy");
        sampler.setCacheKey("false");
        sampler.setScript((interruption == IndependentInterrupt.PREEXISTING ? "Thread.currentThread().interrupt(); " : "")
                + "System.getProperties().get('" + key + "').countDown(); "
                + "long deadline = System.nanoTime() + 10000000000L; "
                + "while (!System.getProperties().get('" + key + ".finish').get() && System.nanoTime() < deadline) { "
                + "Thread.onSpinWait() }; 'done'");
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            try {
                sampler.sample(null);
                return Thread.currentThread().isInterrupted();
            } finally {
                Thread.interrupted();
            }
        });
        Thread worker = new Thread(task);
        try {
            assertFalse(sampler.interruptForTimeout());
            worker.start();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            switch (interruption) {
                case BEFORE_TIMEOUT -> {
                    assertTrue(sampler.interrupt());
                    assertTrue(sampler.interruptForTimeout());
                }
                case AFTER_TIMEOUT -> {
                    assertTrue(sampler.interruptForTimeout());
                    assertTrue(sampler.interrupt());
                }
                case PREEXISTING -> assertTrue(sampler.interruptForTimeout());
                case WITHOUT_TIMEOUT -> worker.interrupt();
            }
            finish.set(true);
            assertTrue(task.get(10, TimeUnit.SECONDS));
            assertFalse(sampler.interruptForTimeout());
        } finally {
            finish.set(true);
            worker.interrupt();
            worker.join(10000);
            System.getProperties().remove(key);
            System.getProperties().remove(key + ".finish");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeoutInterruptDoesNotLeakToFollowingSampler(boolean restoreInterrupt) throws Exception {
        String latchKey = JSR223SamplerTest.class.getName() + ".timeout." + restoreInterrupt;
        CountDownLatch started = new CountDownLatch(1);
        System.getProperties().put(latchKey, started);
        JSR223Sampler sampler = new JSR223Sampler();
        sampler.setScriptLanguage("groovy");
        sampler.setCacheKey("false");
        String waitForInterrupt = restoreInterrupt
                ? "try { Thread.sleep(30000) } catch (InterruptedException e) { Thread.currentThread().interrupt() }; "
                : "long deadline = System.nanoTime() + 10000000000L; "
                        + "while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) { Thread.onSpinWait() }; ";
        sampler.setScript("System.getProperties().get('" + latchKey + "').countDown(); "
                + waitForInterrupt + "SampleResult.setSuccessful(false); Thread.currentThread().isInterrupted()");
        JSR223Sampler following = new JSR223Sampler();
        following.setScriptLanguage("groovy");
        following.setCacheKey("false");
        following.setScript("Thread.sleep(1); 'following sample'");
        SampleTimeout timeout = new SampleTimeout();
        timeout.setTimeout("10");
        AtomicReference<SampleResult> timedOutResult = new AtomicReference<>();
        FutureTask<SampleResult> task = new FutureTask<>(() -> {
            try {
                timedOutResult.set(sampler.sample(null));
                timeout.sampleEnded(sampler);
                return following.sample(null);
            } finally {
                timeout.sampleEnded(sampler);
                Thread.interrupted();
            }
        });
        Thread worker = new Thread(task);
        try {
            assertFalse(sampler.interrupt());
            worker.start();
            // Start the timeout after script initialization so compilation cannot consume the deadline.
            assertTrue(started.await(10, TimeUnit.SECONDS));
            timeout.sampleStarting(sampler);
            SampleResult result = task.get(10, TimeUnit.SECONDS);
            assertFalse(timedOutResult.get().isSuccessful());
            assertEquals("true", timedOutResult.get().getResponseDataAsString());
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals("following sample", result.getResponseDataAsString());
            assertFalse(sampler.interrupt());
        } finally {
            worker.interrupt();
            worker.join(10000);
            System.getProperties().remove(latchKey);
        }
    }

    @Test
    void interruptRunningScript() throws Exception {
        String latchKey = JSR223SamplerTest.class.getName() + ".latch";
        CountDownLatch started = new CountDownLatch(1);
        System.getProperties().put(latchKey, started);
        JSR223Sampler sampler = new JSR223Sampler();
        sampler.setScriptLanguage("groovy");
        sampler.setCacheKey("false");
        sampler.setScript("System.getProperties().get('" + latchKey + "').countDown(); Thread.sleep(30000)");
        AtomicReference<SampleResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(sampler.sample(null)));
        List<LogEvent> events = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender(
                "jsr223-sampler-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(JSR223Sampler.class);
        Level previousLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            assertFalse(sampler.interrupt());
            worker.start();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertTrue(sampler.interrupt());
            worker.join(10000);
            assertFalse(worker.isAlive());
            assertFalse(result.get().isSuccessful());
            assertFalse(sampler.interrupt());
            // Stopping a test interrupts scripts routinely, so it must not be logged as a script error.
            assertEquals(List.of(Level.DEBUG), events.stream().map(LogEvent::getLevel).toList());
            events.clear();
            sampler.setScript("throw new InterruptedException('script failure')");
            assertFalse(sampler.sample(null).isSuccessful());
            assertEquals(List.of(Level.ERROR), events.stream().map(LogEvent::getLevel).toList());
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
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
