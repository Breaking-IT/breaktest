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

package org.apache.jmeter.protocol.udp.sampler;

import java.io.IOException;
import java.net.DatagramSocket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

final class UDPSocketManager {

    record SocketKey(long threadScope, String socketId) {
    }

    static final class SocketHandle {
        private final DatagramSocket socket;
        private final String configuration;
        private final ReentrantLock lock = new ReentrantLock();

        SocketHandle(DatagramSocket socket, String configuration) {
            this.socket = socket;
            this.configuration = configuration;
        }

        DatagramSocket socket() {
            return socket;
        }

        ReentrantLock lock() {
            return lock;
        }

        boolean matches(String expectedConfiguration) {
            return configuration.equals(expectedConfiguration);
        }
    }

    private static final Map<SocketKey, SocketHandle> SHARED_SOCKETS = new ConcurrentHashMap<>();
    private static final Set<Long> ACTIVE_THREADS = ConcurrentHashMap.newKeySet();

    private UDPSocketManager() {
    }

    static synchronized SocketHandle getOrCreate(SocketKey key, String configuration, SocketFactory factory)
            throws IOException {
        SocketHandle existing = SHARED_SOCKETS.get(key);
        if (existing != null && !existing.socket().isClosed()) {
            if (!existing.matches(configuration)) {
                throw new IOException("UDP socket ID '" + key.socketId()
                        + "' is already in use with different endpoint or bind settings");
            }
            return existing;
        }
        SocketHandle created = new SocketHandle(factory.create(), configuration);
        SHARED_SOCKETS.put(key, created);
        return created;
    }

    static SocketHandle get(SocketKey key) {
        SocketHandle handle = SHARED_SOCKETS.get(key);
        return handle == null || handle.socket().isClosed() ? null : handle;
    }

    static void remove(SocketKey key, SocketHandle expected) {
        if (SHARED_SOCKETS.remove(key, expected)) {
            expected.socket().close();
        }
    }

    static synchronized void registerThread(long threadScope) {
        if (ACTIVE_THREADS.add(threadScope)) {
            releaseThread(threadScope);
        }
    }

    static synchronized void unregisterThread(long threadScope) {
        if (ACTIVE_THREADS.remove(threadScope)) {
            releaseThread(threadScope);
        }
    }

    static void releaseThread(long threadScope) {
        SHARED_SOCKETS.forEach((key, handle) -> {
            if (key.threadScope() == threadScope && SHARED_SOCKETS.remove(key, handle)) {
                handle.socket().close();
            }
        });
    }

    @FunctionalInterface
    interface SocketFactory {
        DatagramSocket create() throws IOException;
    }
}
