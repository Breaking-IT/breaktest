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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class UDPSocketManager {

    static final class SocketKey {
        private final ScopeKey scope;
        private final String socketId;

        SocketKey(Object virtualUser, String socketId) {
            this.scope = new ScopeKey(virtualUser);
            this.socketId = socketId;
        }

        String socketId() {
            return socketId;
        }

        ScopeKey scope() {
            return scope;
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof SocketKey other
                    && scope.equals(other.scope)
                    && socketId.equals(other.socketId);
        }

        @Override
        public int hashCode() {
            return 31 * scope.hashCode() + socketId.hashCode();
        }
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

    private static final Map<ScopeKey, SocketRegistry> REGISTRIES = new ConcurrentHashMap<>();

    private UDPSocketManager() {
    }

    static SocketHandle getOrCreate(SocketKey key, String configuration, SocketFactory factory)
            throws IOException {
        SocketRegistry registry = REGISTRIES.computeIfAbsent(key.scope(), ignored -> new SocketRegistry());
        return registry.getOrCreate(key.socketId(), configuration, factory);
    }

    static SocketHandle get(SocketKey key) {
        SocketRegistry registry = REGISTRIES.get(key.scope());
        return registry == null ? null : registry.get(key.socketId());
    }

    static void remove(SocketKey key, SocketHandle expected) {
        SocketRegistry registry = REGISTRIES.get(key.scope());
        if (registry != null) {
            registry.remove(key.socketId(), expected);
        }
    }

    static void registerScope(Object virtualUser) {
        REGISTRIES.computeIfAbsent(new ScopeKey(virtualUser), ignored -> new SocketRegistry());
    }

    static void releaseScope(Object virtualUser) {
        SocketRegistry registry = REGISTRIES.remove(new ScopeKey(virtualUser));
        if (registry != null) {
            registry.close();
        }
    }

    static int openSocketCount() {
        return REGISTRIES.values().stream().mapToInt(SocketRegistry::openSocketCount).sum();
    }

    private static final class SocketRegistry {
        private final Map<String, SocketHandle> sockets = new ConcurrentHashMap<>();
        private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
        private boolean closed;

        SocketHandle getOrCreate(String socketId, String configuration, SocketFactory factory)
                throws IOException {
            lifecycleLock.readLock().lock();
            try {
                if (closed) {
                    throw new IOException("UDP socket registry is closed");
                }
                AtomicReference<IOException> failure = new AtomicReference<>();
                SocketHandle handle = sockets.compute(socketId, (ignored, existing) -> {
                    if (existing != null && !existing.socket().isClosed()) {
                        if (!existing.matches(configuration)) {
                            failure.set(new IOException("UDP socket ID '" + socketId
                                    + "' is already in use with different endpoint or bind settings"));
                        }
                        return existing;
                    }
                    try {
                        return new SocketHandle(factory.create(), configuration);
                    } catch (IOException ex) {
                        failure.set(ex);
                        return null;
                    }
                });
                if (failure.get() != null) {
                    throw failure.get();
                }
                return handle;
            } finally {
                lifecycleLock.readLock().unlock();
            }
        }

        SocketHandle get(String socketId) {
            lifecycleLock.readLock().lock();
            try {
                SocketHandle handle = sockets.get(socketId);
                return handle == null || handle.socket().isClosed() ? null : handle;
            } finally {
                lifecycleLock.readLock().unlock();
            }
        }

        void remove(String socketId, SocketHandle expected) {
            lifecycleLock.readLock().lock();
            try {
                if (sockets.remove(socketId, expected)) {
                    expected.socket().close();
                }
            } finally {
                lifecycleLock.readLock().unlock();
            }
        }

        int openSocketCount() {
            return (int) sockets.values().stream().filter(handle -> !handle.socket().isClosed()).count();
        }

        void close() {
            lifecycleLock.writeLock().lock();
            try {
                closed = true;
                sockets.values().forEach(handle -> handle.socket().close());
                sockets.clear();
            } finally {
                lifecycleLock.writeLock().unlock();
            }
        }
    }

    private static final class ScopeKey {
        private final Object virtualUser;

        ScopeKey(Object virtualUser) {
            this.virtualUser = virtualUser;
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof ScopeKey other && virtualUser == other.virtualUser;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(virtualUser);
        }
    }

    @FunctionalInterface
    interface SocketFactory {
        DatagramSocket create() throws IOException;
    }
}
