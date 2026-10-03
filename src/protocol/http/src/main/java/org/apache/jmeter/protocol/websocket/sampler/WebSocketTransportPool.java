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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLParameters;

import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.JsseSSLManager;
import org.apache.jmeter.util.JsseSSLManager.AsyncClientIdentity;
import org.apache.jmeter.util.SSLManager;

/** Shared transport machinery; session state, cookies and headers remain user-owned. */
final class WebSocketTransportPool {
    private static final Object PLAIN = new Object();
    private static final Map<Object, CompletableFuture<Entry>> CLIENTS = new ConcurrentHashMap<>();

    private record TlsKey(AsyncClientIdentity identity, List<String> protocols, List<String> ciphers) {
    }

    private WebSocketTransportPool() {
    }

    static Lease acquire(URI uri) throws GeneralSecurityException {
        // Resolve aliases before entering transport callbacks, and exactly once
        // per connection (keystore rotation can advance on every getAlias call).
        AsyncClientIdentity identity = "wss".equalsIgnoreCase(uri.getScheme())
                ? ((JsseSSLManager) SSLManager.getInstance()).getAsyncClientIdentity() : null;
        String[] protocols = JMeterUtils.getArrayPropDefault("https.socket.protocols", new String[0]);
        String[] ciphers = JMeterUtils.getArrayPropDefault("https.cipherSuites",
                JMeterUtils.getArrayPropDefault("https.socket.ciphers", new String[0]));
        Object key = identity == null ? PLAIN : new TlsKey(identity, List.of(protocols), List.of(ciphers));
        while (true) {
            CompletableFuture<Entry> created = new CompletableFuture<>();
            CompletableFuture<Entry> future = CLIENTS.putIfAbsent(key, created);
            if (future == null) {
                future = created;
                ExecutorService executor = Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("websocket-worker-", 0).factory());
                try {
                    HttpClient.Builder builder = HttpClient.newBuilder().executor(executor)
                            .cookieHandler(new WebSocketHandshakeCookies());
                    if (identity != null) {
                        builder.sslContext(identity.createContext());
                        SSLParameters parameters = new SSLParameters();
                        if (protocols.length > 0) {
                            parameters.setProtocols(protocols);
                        }
                        if (ciphers.length > 0) {
                            parameters.setCipherSuites(ciphers);
                        }
                        builder.sslParameters(parameters);
                    }
                    created.complete(new Entry(builder.build(), executor));
                } catch (GeneralSecurityException | RuntimeException | Error error) {
                    executor.shutdownNow();
                    CLIENTS.remove(key, created);
                    created.completeExceptionally(error);
                    throw error;
                }
            }
            Entry entry;
            try {
                entry = future.join();
            } catch (CompletionException error) {
                if (error.getCause() instanceof GeneralSecurityException security) {
                    throw security;
                }
                throw error;
            }
            synchronized (entry) {
                if (CLIENTS.get(key) != future) {
                    continue; // Its last lease closed before this acquisition.
                }
                if (!((WebSocketHandshakeCookies) entry.client.cookieHandler().orElseThrow()).acceptsHandshakes()) {
                    CLIENTS.remove(key, future);
                    continue;
                }
                entry.references++;
                return new Lease(key, future, entry);
            }
        }
    }

    private static final class Entry {
        private final HttpClient client;
        private final ExecutorService executor;
        private int references;

        private Entry(HttpClient client, ExecutorService executor) {
            this.client = client;
            this.executor = executor;
        }
    }

    static final class Lease implements AutoCloseable {
        private final Object key;
        private final Entry entry;
        private final CompletableFuture<Entry> future;
        private boolean closed;

        private Lease(Object key, CompletableFuture<Entry> future, Entry entry) {
            this.key = key;
            this.future = future;
            this.entry = entry;
        }

        HttpClient client() {
            return entry.client;
        }

        @Override
        public void close() {
            boolean last;
            synchronized (entry) {
                if (closed) {
                    return;
                }
                closed = true;
                last = --entry.references == 0;
                if (last) {
                    CLIENTS.remove(key, future);
                }
            }
            if (last) {
                entry.client.shutdownNow();
                // Let pending transport completion tasks finish before retiring the executor.
                Thread.ofVirtual().name("websocket-client-cleanup").start(() -> {
                    try {
                        boolean terminated;
                        do {
                            terminated = entry.client.awaitTermination(java.time.Duration.ofSeconds(1));
                        } while (!terminated);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        entry.executor.shutdown();
                    }
                });
            }
        }
    }
}
