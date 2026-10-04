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

package org.apache.jmeter.protocol.sse;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.jmeter.threads.JMeterContextService;

/** Connections belong to the virtual user, including its event-handler flows. */
public final class SseSessions implements AutoCloseable {
    private static final String KEY = SseSessions.class.getName();
    private final Map<String, SseSession> sessions = new HashMap<>();
    private final IdentityHashMap<Object, Runnable> transportCleanup = new IdentityHashMap<>();
    private final ReentrantLock notifications = new ReentrantLock();
    private volatile boolean closed;

    public static SseSessions current() {
        var context = JMeterContextService.getContext();
        var variables = context.getVariables();
        var owner = context.getThread();
        synchronized (owner == null ? variables : owner) {
            SseSessions registry = (SseSessions) variables.getObject(KEY);
            if (registry == null) {
                registry = new SseSessions();
                variables.putObject(KEY, registry);
                if (owner != null) {
                    owner.registerUserCleanup(registry, registry::close);
                }
            }
            return registry;
        }
    }

    /** Reserves a replacement atomically, or keeps an already receiving stream. */
    public synchronized boolean connect(String name, SseSession replacement, String action) {
        if (!SseSampler.RECONNECT.equals(action) && !SseSampler.REUSE.equals(action)
                && !SseSampler.FAIL.equals(action)) {
            throw new IllegalArgumentException("Unknown existing SSE session action: " + action);
        }
        if (closed) {
            throw new IllegalStateException("SSE user sessions are closed");
        }
        SseSession existing = sessions.get(name);
        if (existing != null) {
            if (SseSampler.FAIL.equals(action)) {
                throw new IllegalStateException("SSE session already exists: " + name);
            }
            if (SseSampler.REUSE.equals(action) && existing.isOpen()) {
                return false;
            }
        }
        replace(name, replacement);
        return true;
    }

    public synchronized void replace(String name, SseSession session) {
        if (closed) {
            throw new IllegalStateException("SSE user sessions are closed");
        }
        SseSession previous = sessions.put(name, session);
        if (previous != null) {
            previous.close();
        }
    }

    public synchronized void close(String name) {
        SseSession session = sessions.remove(name);
        if (session != null) {
            session.close();
        }
    }

    public synchronized void registerTransportCleanup(Object key, Runnable cleanup) {
        if (closed) {
            throw new IllegalStateException("SSE user sessions are closed");
        }
        transportCleanup.putIfAbsent(key, cleanup);
    }

    void notifyListeners(Runnable notification) {
        if (closed) {
            return;
        }
        // Listener code must never hold the lifecycle monitor: close must stay responsive.
        try {
            notifications.lockInterruptibly();
            try {
                if (!closed) {
                    notification.run();
                }
            } finally {
                notifications.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        sessions.values().forEach(SseSession::close);
        sessions.clear();
        transportCleanup.values().forEach(Runnable::run);
        transportCleanup.clear();
    }
}
