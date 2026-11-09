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

package org.apache.jmeter.protocol.http.sampler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.io.CloseMode;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Async clients shared by the virtual users that have the same client settings. A client is
 * started for its first user and closed once it has had no users for a grace period, so a
 * stream of short-lived users (open model) does not rebuild its reactor for every arrival.
 *
 * @param <K> the settings that make two users' clients interchangeable
 */
final class SharedAsyncClients<K> {
    private static final Logger log = LoggerFactory.getLogger(SharedAsyncClients.class);

    /**
     * JMeter property, default 1000: how long a shared client may stay unused before it closes.
     * 0 closes it as soon as its last user finishes.
     */
    static final String IDLE_CLOSE_MS = "httpsampler.hc5.h2.shared_reactor_idle_close_ms";

    private static final ScheduledExecutorService CLOSER =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                    .name("breaktest-shared-client-closer").daemon().factory());

    /** One user's hold on a shared client. */
    static final class Shared<K> {
        private final K key;
        private final CloseableHttpAsyncClient client;
        private int users; // guarded by clients
        private long idleGeneration; // guarded by clients; changes each time users reaches 0

        private Shared(K key, CloseableHttpAsyncClient client) {
            this.key = key;
            this.client = client;
        }

        CloseableHttpAsyncClient client() {
            return client;
        }
    }

    // Guarded by itself.
    private final Map<K, Shared<K>> clients = new HashMap<>();

    Shared<K> acquire(K key, Supplier<CloseableHttpAsyncClient> factory) {
        synchronized (clients) {
            Shared<K> shared = clients.get(key);
            if (shared == null) {
                CloseableHttpAsyncClient client = factory.get();
                client.start();
                log.debug("Created shared HTTP/2 HttpAsyncClient: @{} {}", System.identityHashCode(client), key);
                shared = new Shared<>(key, client);
                clients.put(key, shared);
            }
            shared.users++;
            return shared;
        }
    }

    // The scheduled close needs no handle: a stale task finds a newer idle generation and stops.
    @SuppressWarnings("FutureReturnValueIgnored")
    void release(Shared<K> shared) {
        long graceMillis = JMeterUtils.getPropDefault(IDLE_CLOSE_MS, 1000L);
        long generation;
        synchronized (clients) {
            if (--shared.users > 0) {
                return;
            }
            generation = ++shared.idleGeneration;
        }
        if (graceMillis <= 0) {
            closeIfStillIdle(shared, generation);
            return;
        }
        CLOSER.schedule(() -> closeIfStillIdle(shared, generation), graceMillis, TimeUnit.MILLISECONDS);
    }

    private void closeIfStillIdle(Shared<K> shared, long generation) {
        synchronized (clients) {
            if (shared.users > 0 || shared.idleGeneration != generation) {
                return;
            }
            clients.remove(shared.key, shared);
        }
        // No user pools remain on this client, so a graceful close returns at once.
        shared.client.close(CloseMode.GRACEFUL);
        log.debug("Closed shared HTTP/2 HttpAsyncClient: @{} {}", System.identityHashCode(shared.client), shared.key);
    }

    int count(Predicate<K> matching) {
        synchronized (clients) {
            return (int) clients.keySet().stream().filter(matching).count();
        }
    }
}
