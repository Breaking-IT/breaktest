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

import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.EndpointInfo;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.nio.AsyncConnectionEndpoint;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.util.Timeout;

/**
 * Per-route lease bookkeeping for {@code HTTPHC5H2Impl}'s per-user connection manager: whether
 * the route speaks HTTP/1.1 or HTTP/2, the connection limit that follows from it, and a lease
 * turn so that only one lease opens the route's first HTTP/2 connection.
 */
final class RouteLeaseState {
    enum RouteProtocol {
        UNKNOWN,
        HTTP_1,
        HTTP_2
    }

    private final PoolingAsyncClientConnectionManager pool;
    private final HttpRoute route;
    private RouteProtocol protocol = RouteProtocol.UNKNOWN;
    private boolean leaseTurnInProgress;

    RouteLeaseState(PoolingAsyncClientConnectionManager pool, HttpRoute route) {
        this.pool = pool;
        this.route = route;
    }

    synchronized void awaitLeaseTurn(Timeout timeout) throws InterruptedException, TimeoutException {
        if (protocol == RouteProtocol.HTTP_1) {
            applyMaxPerRoute();
            return;
        }
        long deadline = 0;
        if (timeout != null && timeout.isEnabled()) {
            long timeoutMillis = timeout.toMilliseconds();
            if (timeoutMillis == 0) {
                if (leaseTurnInProgress) {
                    throw new TimeoutException("Timed out waiting for HTTP/2 route lease turn");
                }
            } else {
                deadline = System.currentTimeMillis() + timeoutMillis;
            }
        }
        while (leaseTurnInProgress) {
            if (deadline > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new TimeoutException("Timed out waiting for HTTP/2 route lease turn");
                }
                wait(remaining);
            } else {
                wait();
            }
        }
        leaseTurnInProgress = true;
        applyMaxPerRoute();
    }

    synchronized void leaseCompleted(AsyncConnectionEndpoint endpoint) {
        RouteProtocol endpointProtocol = protocol(endpoint);
        if (endpointProtocol != RouteProtocol.UNKNOWN) {
            protocol = endpointProtocol;
            applyMaxPerRoute();
            releaseLeaseTurn();
        } else if (endpoint.isConnected()) {
            releaseLeaseTurn();
        }
    }

    synchronized void connectCompleted(AsyncConnectionEndpoint endpoint) {
        RouteProtocol endpointProtocol = protocol(endpoint);
        if (endpointProtocol != RouteProtocol.UNKNOWN) {
            protocol = endpointProtocol;
            applyMaxPerRoute();
        }
        releaseLeaseTurn();
    }

    // Called with this monitor held, so the pool limit always matches the latest protocol and is
    // raised before a waiting lease proceeds. The pool does not re-check queued leases when the
    // limit changes, so a lease queued against a stale limit of 1 would wait for a release.
    private void applyMaxPerRoute() {
        pool.setMaxPerRoute(route, protocol == RouteProtocol.HTTP_1 ? pool.getDefaultMaxPerRoute() : 1);
    }

    synchronized RouteProtocol protocol() {
        return protocol;
    }

    synchronized void releaseLeaseTurn() {
        leaseTurnInProgress = false;
        notifyAll();
    }

    private static RouteProtocol protocol(AsyncConnectionEndpoint endpoint) {
        EndpointInfo endpointInfo = endpoint.getInfo();
        ProtocolVersion protocol = endpointInfo == null ? null : endpointInfo.getProtocol();
        if (HttpVersion.HTTP_2_0.equals(protocol)) {
            return RouteProtocol.HTTP_2;
        }
        if (protocol != null) {
            return RouteProtocol.HTTP_1;
        }
        return RouteProtocol.UNKNOWN;
    }
}
