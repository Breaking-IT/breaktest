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

package org.apache.jmeter.protocol.websocket.sampler;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;

/** Owned by the virtual user, never by the WebSocket callback thread. */
final class WebSocketSessions {
    private static final String KEY = WebSocketSessions.class.getName();
    private final ReentrantLock notificationLock = new ReentrantLock();
    private volatile boolean closed;
    private final Map<String, WebSocketSession> sessions = new HashMap<>();
    private final Map<String, WebSocketTransportPool.Lease> clients = new HashMap<>();

    static WebSocketSessions current() {
        JMeterVariables variables = JMeterContextService.getContext().getVariables();
        JMeterThread owner = JMeterContextService.getContext().getThread();
        synchronized (owner == null ? variables : owner) {
            WebSocketSessions sessions = (WebSocketSessions) variables.getObject(KEY);
            if (sessions == null) {
                sessions = new WebSocketSessions();
                variables.putObject(KEY, sessions);
                if (owner != null) {
                    // The engine clears context variables before threadFinished callbacks.
                    owner.registerThreadCleanup(sessions, sessions::close);
                }
            }
            return sessions;
        }
    }

    synchronized HttpClient client(String sessionName, WebSocketSession session, URI uri) throws GeneralSecurityException {
        if (sessions.get(sessionName) != session) {
            throw new IllegalStateException("WebSocket session was closed or replaced: " + sessionName);
        }
        WebSocketTransportPool.Lease lease = clients.get(sessionName);
        if (lease == null) {
            lease = WebSocketTransportPool.acquire(uri);
            clients.put(sessionName, lease);
        }
        return lease.client();
    }

    synchronized void add(String name, WebSocketSession session) {
        if (sessions.containsKey(name)) {
            throw new IllegalStateException("WebSocket session already exists; close it before reconnecting: " + name);
        }
        sessions.put(name, session);
    }

    synchronized WebSocketSession get(String name) {
        WebSocketSession session = sessions.get(name);
        if (session == null) {
            throw new IllegalStateException("Unknown WebSocket session: " + name);
        }
        return session;
    }

    synchronized void remove(String name, WebSocketSession session) {
        if (!sessions.remove(name, session)) {
            return; // A delayed close must never release a replacement session's lease.
        }
        session.dispose();
        WebSocketTransportPool.Lease lease = clients.remove(name);
        if (lease != null) {
            lease.close();
        }
    }

    static void cleanup() {
        JMeterVariables variables = JMeterContextService.getContext().getVariables();
        if (variables == null) {
            return; // The engine's registered cleanup owns resources after context.clear().
        }
        WebSocketSessions sessions = (WebSocketSessions) variables.getObject(KEY);
        if (sessions != null) {
            sessions.close();
            variables.remove(KEY);
        }
    }

    void notifyListeners(Runnable notification) {
        notificationLock.lock();
        try {
            if (!closed) {
                notification.run();
            }
        } finally {
            notificationLock.unlock();
        }
    }

    private synchronized void close() {
        closed = true;
        sessions.values().forEach(WebSocketSession::dispose);
        sessions.clear();
        clients.values().forEach(WebSocketTransportPool.Lease::close);
        clients.clear();
    }
}
