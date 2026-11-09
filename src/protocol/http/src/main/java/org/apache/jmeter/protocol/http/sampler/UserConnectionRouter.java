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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.nio.AsyncClientConnectionManager;
import org.apache.hc.client5.http.nio.AsyncConnectionEndpoint;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

/**
 * Connection manager of a shared async client. Each request carries its user's
 * connection manager as user token, which HttpClient passes as lease state;
 * the router leases from that manager and remembers which manager owns each endpoint, so
 * connect, upgrade and release reach the same pool. Connections stay per user; only the
 * reactor that runs their I/O is shared.
 */
final class UserConnectionRouter implements AsyncClientConnectionManager {
    private final ConcurrentMap<AsyncConnectionEndpoint, AsyncClientConnectionManager> owners =
            new ConcurrentHashMap<>();

    @Override
    public Future<AsyncConnectionEndpoint> lease(String id, HttpRoute route, Object state,
            Timeout requestTimeout, FutureCallback<AsyncConnectionEndpoint> callback) {
        if (!(state instanceof AsyncClientConnectionManager user)) {
            throw new IllegalStateException("Shared HTTP client request without a user connection pool");
        }
        return user.lease(id, route, state, requestTimeout, new FutureCallback<>() {
            @Override
            public void completed(AsyncConnectionEndpoint endpoint) {
                owners.put(endpoint, user);
                if (callback != null) {
                    callback.completed(endpoint);
                }
            }

            @Override
            public void failed(Exception ex) {
                if (callback != null) {
                    callback.failed(ex);
                }
            }

            @Override
            public void cancelled() {
                if (callback != null) {
                    callback.cancelled();
                }
            }
        });
    }

    private AsyncClientConnectionManager owner(AsyncConnectionEndpoint endpoint) {
        AsyncClientConnectionManager owner = owners.get(endpoint);
        if (owner == null) {
            throw new IllegalStateException("Endpoint was not leased through the shared client router");
        }
        return owner;
    }

    @Override
    public void release(AsyncConnectionEndpoint endpoint, Object state, TimeValue keepAlive) {
        AsyncClientConnectionManager owner = owners.remove(endpoint);
        if (owner != null) {
            owner.release(endpoint, state, keepAlive);
        }
    }

    @Override
    public Future<AsyncConnectionEndpoint> connect(AsyncConnectionEndpoint endpoint,
            ConnectionInitiator connectionInitiator, Timeout connectTimeout, Object attachment,
            HttpContext context, FutureCallback<AsyncConnectionEndpoint> callback) {
        return owner(endpoint).connect(endpoint, connectionInitiator, connectTimeout, attachment, context, callback);
    }

    @Override
    public void upgrade(AsyncConnectionEndpoint endpoint, Object attachment, HttpContext context) {
        owner(endpoint).upgrade(endpoint, attachment, context);
    }

    @Override
    public void upgrade(AsyncConnectionEndpoint endpoint, Object attachment, HttpContext context,
            FutureCallback<AsyncConnectionEndpoint> callback) {
        owner(endpoint).upgrade(endpoint, attachment, context, callback);
    }

    @Override
    public void close() {
        // User pools close with their users.
    }

    @Override
    public void close(CloseMode closeMode) {
        // User pools close with their users.
    }
}
