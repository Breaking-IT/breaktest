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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import javax.net.ssl.SSLSession;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.EndpointInfo;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.nio.AsyncClientConnectionManager;
import org.apache.hc.client5.http.nio.AsyncConnectionEndpoint;
import org.apache.hc.client5.http.nio.ManagedAsyncClientConnection;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.core5.concurrent.BasicFuture;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.AsyncPushConsumer;
import org.apache.hc.core5.http.nio.HandlerFactory;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

/** Cross-origin eligibility and borrowed leases. Registries belong to one virtual user. */
final class H2ConnectionCoalescing {
    static final String CONNECTING_ROUTE = H2ConnectionCoalescing.class.getName() + ".connectingRoute";
    static final String USED = H2ConnectionCoalescing.class.getName() + ".used";

    @FunctionalInterface
    interface LeaseSource {
        Future<AsyncConnectionEndpoint> lease(String id, HttpRoute route, Object state, Timeout timeout,
                FutureCallback<AsyncConnectionEndpoint> callback);
    }

    private final Object identity;
    private final DnsResolver dns;
    private final Supplier<List<H2ConnectionCoalescing>> peers;
    private final AsyncClientConnectionManager manager;
    private final LeaseSource lease;
    private final ConcurrentMap<ManagedAsyncClientConnection, HttpRoute> connections = new ConcurrentHashMap<>();
    private final Set<HttpRoute> rejected = ConcurrentHashMap.newKeySet();

    H2ConnectionCoalescing(Object identity, DnsResolver dns, Supplier<List<H2ConnectionCoalescing>> peers,
            AsyncClientConnectionManager manager, LeaseSource lease) {
        this.identity = identity;
        this.dns = dns;
        this.peers = peers;
        this.manager = manager;
        this.lease = lease;
    }

    void track(ManagedAsyncClientConnection connection, HttpRoute route) {
        connections.keySet().removeIf(old -> !old.isOpen());
        if (route != null && direct(route)) {
            connections.put(connection, route);
        }
    }

    void reject(HttpRoute route) {
        if (route != null) {
            rejected.add(route);
        }
    }

    private static boolean direct(HttpRoute route) {
        return route.isSecure() && route.getProxyHost() == null && route.getLocalAddress() == null;
    }

    Future<AsyncConnectionEndpoint> tryLease(String id, HttpRoute route, Object state, Timeout timeout,
            FutureCallback<AsyncConnectionEndpoint> callback) {
        if (!direct(route) || rejected.contains(route)) {
            return null;
        }
        // Prefer a dedicated connection once one exists (notably after a 421).
        if (connections.entrySet().stream().anyMatch(e -> e.getKey().isOpen() && e.getValue().equals(route))) {
            return null;
        }
        InetAddress[] addresses;
        try {
            addresses = dns.resolve(route.getTargetHost().getHostName());
        } catch (java.net.UnknownHostException e) {
            return null;
        }
        for (H2ConnectionCoalescing peer : peers.get()) {
            if (peer == this || !identity.equals(peer.identity)) {
                continue;
            }
            for (var entry : peer.connections.entrySet()) {
                var connection = entry.getKey();
                var original = entry.getValue();
                if (!original.equals(route) && connection.isOpen()
                        && HttpVersion.HTTP_2_0.equals(connection.getProtocolVersion())
                        && connection.getRemoteAddress() instanceof InetSocketAddress remote
                        && remote.getPort() == route.getTargetHost().getPort()
                        && Arrays.asList(addresses).contains(remote.getAddress())
                        && certificateMatches(connection.getSSLSession(), original, route)) {
                    return borrow(peer, id, original, route, addresses, state, timeout, callback);
                }
            }
        }
        return null;
    }

    private static boolean certificateMatches(SSLSession session, HttpRoute original, HttpRoute target) {
        var verifier = new DefaultHostnameVerifier(PublicSuffixMatcherLoader.getDefault());
        return session != null && verifier.verify(original.getTargetHost().getHostName(), session)
                && verifier.verify(target.getTargetHost().getHostName(), session);
    }

    private Future<AsyncConnectionEndpoint> borrow(H2ConnectionCoalescing peer, String id,
            HttpRoute original, HttpRoute target, InetAddress[] addresses, Object state, Timeout timeout,
            FutureCallback<AsyncConnectionEndpoint> callback) {
        // Lease through the owning manager, retaining its lifetime and multiplexing accounting.
        LeaseFuture result = new LeaseFuture(callback);
        result.track(peer.lease.lease(id, original, state, timeout, new FutureCallback<>() {
            @Override public void completed(AsyncConnectionEndpoint endpoint) {
                EndpointInfo info = endpoint.getInfo();
                // Recheck the leased connection, including its actual address, after pool races.
                ManagedAsyncClientConnection connection = info == null ? null : peer.connections.keySet().stream()
                        .filter(candidate -> candidate.isOpen() && info.getSslSession() != null
                                && info.getSslSession().equals(candidate.getSSLSession())
                                && candidate.getRemoteAddress() instanceof InetSocketAddress remote
                                && remote.getPort() == target.getTargetHost().getPort()
                                && Arrays.asList(addresses).contains(remote.getAddress()))
                        .findFirst().orElse(null);
                if (connection != null && HttpVersion.HTTP_2_0.equals(info.getProtocol())
                        && certificateMatches(info.getSslSession(), original, target)) {
                    if (!result.completed(new BorrowedEndpoint(endpoint, peer.manager, target, connection))) {
                        peer.manager.release(endpoint, state, TimeValue.ZERO_MILLISECONDS);
                    }
                } else {
                    peer.manager.release(endpoint, state, TimeValue.ZERO_MILLISECONDS);
                    // No request has been sent; retry the destination's own route.
                    result.track(lease.lease(id, target, state, timeout, new FutureCallback<>() {
                        @Override public void completed(AsyncConnectionEndpoint own) {
                            if (!result.completed(own)) {
                                manager.release(own, state, TimeValue.ZERO_MILLISECONDS);
                            }
                        }
                        @Override public void failed(Exception ex) { result.failed(ex); }
                        @Override public void cancelled() { result.cancel(); }
                    }));
                }
            }
            @Override public void failed(Exception ex) { result.failed(ex); }
            @Override public void cancelled() { result.cancel(); }
        }));
        return result;
    }

    private static final class LeaseFuture extends BasicFuture<AsyncConnectionEndpoint> {
        private final ConcurrentLinkedQueue<Future<AsyncConnectionEndpoint>> pending = new ConcurrentLinkedQueue<>();
        LeaseFuture(FutureCallback<AsyncConnectionEndpoint> callback) { super(callback); }
        void track(Future<AsyncConnectionEndpoint> future) {
            pending.add(future);
            if (isCancelled()) {
                future.cancel(true);
            }
        }
        @Override public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (cancelled) {
                pending.forEach(future -> future.cancel(interrupt));
            }
            return cancelled;
        }
    }

    static final class BorrowedEndpoint extends AsyncConnectionEndpoint {
        private final AsyncConnectionEndpoint endpoint;
        private final AsyncClientConnectionManager owner;
        private final HttpRoute target;
        private final ManagedAsyncClientConnection connection;

        BorrowedEndpoint(AsyncConnectionEndpoint endpoint, AsyncClientConnectionManager owner, HttpRoute target,
                ManagedAsyncClientConnection connection) {
            this.endpoint = endpoint;
            this.owner = owner;
            this.target = target;
            this.connection = connection;
        }

        void release(Object state, TimeValue keepAlive) { owner.release(endpoint, state, keepAlive); }
        @Override public boolean isConnected() { return endpoint.isConnected(); }
        @Override public EndpointInfo getInfo() { return endpoint.getInfo(); }
        @Override public void setSocketTimeout(Timeout timeout) { endpoint.setSocketTimeout(timeout); }
        @Override public void close(CloseMode mode) { endpoint.close(mode); }
        @Override public void execute(String id, AsyncClientExchangeHandler handler,
                HandlerFactory<AsyncPushConsumer> pushes, HttpContext context) {
            context.setAttribute(USED, target);
            HTTPHC5H2Impl.rememberNetworkEndpoint(target.getTargetHost(), context, connection);
            endpoint.execute(id, handler, pushes, context);
        }
    }
}
