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

package org.apache.jmeter.threads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class VariableChangeSubscriptionTest {
    @Test
    void onlyMatchingWritesAdvanceRevisionAndClosingUnsubscribes() {
        JMeterVariables vars = new JMeterVariables();
        JMeterVariables.ChangeSubscription subscription = vars.watchChanges(Set.of("ready", "expected"));
        long version = subscription.getVersion();
        vars.put("unrelated", "value");
        assertEquals(version, subscription.getVersion());
        vars.put("ready", "yes");
        assertTrue(subscription.getVersion() > version);
        version = subscription.getVersion();
        vars.putAll(Map.of("expected", "yes"));
        assertTrue(subscription.getVersion() > version);
        version = subscription.getVersion();
        vars.remove("ready");
        assertTrue(subscription.getVersion() > version);
        version = subscription.getVersion();
        vars.clear();
        assertTrue(subscription.getVersion() > version);
        subscription.close();
        subscription.close();
        version = subscription.getVersion();
        vars.put("ready", "again");
        assertEquals(version, subscription.getVersion());
    }

    @Test
    void unknownDependenciesWatchAllWritesAndCopiesNotify() {
        JMeterVariables vars = new JMeterVariables();
        try (JMeterVariables.ChangeSubscription broad = vars.watchChanges(null);
                JMeterVariables.ChangeSubscription exact = vars.watchChanges(Set.of("ready"))) {
            long version = broad.getVersion();
            vars.putObject("unrelated", 1);
            assertTrue(broad.getVersion() > version);
            assertEquals(0, exact.getVersion());
            JMeterVariables source = new JMeterVariables();
            source.put("ready", "yes");
            vars.putAll(source);
            assertTrue(exact.getVersion() > 0);
        }
    }

    @Test
    void registrationBeforeEvaluationDoesNotLoseConcurrentWrites() throws Exception {
        JMeterVariables vars = new JMeterVariables();
        try (JMeterVariables.ChangeSubscription subscription = vars.watchChanges(Set.of("ready"))) {
            long version = subscription.getVersion();
            Thread writer = new Thread(() -> vars.put("ready", "yes"));
            writer.start();
            writer.join();
            long start = System.nanoTime();
            vars.awaitChange(subscription, version, TimeUnit.SECONDS.toNanos(5));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
        }
    }

    @Test
    void forkViewsSubscribeToParentAndKeepLocalValuesWhenCopied() throws Exception {
        JMeterVariables parent = new JMeterVariables();
        ParallelWorkerVariables worker = new ParallelWorkerVariables(parent);
        try (JMeterVariables.ChangeSubscription subscription = worker.watchChanges(Set.of("ready"))) {
            long version = subscription.getVersion();
            parent.put("ready", "yes");
            assertTrue(subscription.getVersion() > version);
            worker.awaitChange(subscription, version, TimeUnit.SECONDS.toNanos(5));
        }
        worker.put(JMeterThread.LAST_SAMPLE_OK, "false");
        JMeterVariables copy = new JMeterVariables();
        copy.putAll(worker);
        assertEquals("false", copy.get(JMeterThread.LAST_SAMPLE_OK));
        assertEquals("yes", copy.get("ready"));
    }

    @Test
    void lastSubscriptionReleasesRegistryAndCanBeRegisteredAgain() throws Exception {
        JMeterVariables vars = new JMeterVariables();
        var registry = JMeterVariables.class.getDeclaredField("subscriptions");
        registry.setAccessible(true);
        for (int i = 0; i < 3; i++) {
            try (JMeterVariables.ChangeSubscription subscription = vars.watchChanges(Set.of("ready"))) {
                vars.put("ready", "value");
                assertTrue(subscription.getVersion() > 0);
            }
            assertNull(registry.get(vars), "Completed waits must release their registry and owner references");
        }
    }

    @Test
    void oppositeDirectionCopiesDoNotDeadlockOrObservePartialBulkWrites() throws Exception {
        JMeterVariables a = new JMeterVariables();
        JMeterVariables b = new JMeterVariables();
        a.putAll(Map.of("a", "0", "b", "0"));
        b.putAll(a);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var writer = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 10000; i++) {
                    String value = Integer.toString(i);
                    a.putAll(Map.of("a", value, "b", value));
                }
                return null;
            });
            var forward = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 10000; i++) {
                    b.putAll(a);
                    assertEquals(b.get("a"), b.get("b"));
                }
                return null;
            });
            var reverse = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 10000; i++) {
                    a.putAll(b);
                }
                return null;
            });
            start.countDown();
            writer.get(5, TimeUnit.SECONDS);
            forward.get(5, TimeUnit.SECONDS);
            reverse.get(5, TimeUnit.SECONDS);
        }
        assertFalse(a.entrySet().isEmpty());
    }
}
