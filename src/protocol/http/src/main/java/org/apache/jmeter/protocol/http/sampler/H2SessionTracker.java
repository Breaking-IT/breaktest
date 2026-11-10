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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.function.BiFunction;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.ConnectionInitiator;
import org.apache.hc.core5.reactor.IOSession;

/**
 * Tracks one HTTP/2 client's connects in progress and the I/O sessions they open.
 * <p>
 * HttpCore's graceful reactor shutdown only reaches established sessions, and a leased endpoint
 * that is still connecting has no connection to discard yet. Either kind of session would keep
 * the reactor alive past the graceful wait, after which the final close shuts the selector under
 * the running reactor. {@link #shutdown()} closes them before that wait.
 */
final class H2SessionTracker {
    private final ConcurrentMap<Object, Future<?>> pendingConnects = new ConcurrentHashMap<>();
    private final Set<IOSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean shutDown;

    /**
     * Runs {@code connect} with a session-tracking initiator and registers the resulting connect,
     * or runs it untracked when {@code tracker} is {@code null}.
     */
    static <T> Future<T> connect(H2SessionTracker tracker, ConnectionInitiator initiator,
            BiFunction<ConnectionInitiator, FutureCallback<T>, Future<T>> connect, FutureCallback<T> callback) {
        if (tracker == null) {
            return connect.apply(initiator, callback);
        }
        Object request = new Object();
        Future<T> future = connect.apply(tracker.trackSessions(initiator), new FutureCallback<>() {
            @Override
            public void completed(T result) {
                tracker.pendingConnects.remove(request);
                callback.completed(result);
            }

            @Override
            public void failed(Exception ex) {
                tracker.pendingConnects.remove(request);
                callback.failed(ex);
            }

            @Override
            public void cancelled() {
                tracker.pendingConnects.remove(request);
                callback.cancelled();
            }
        });
        tracker.pendingConnects.put(request, future);
        if (future.isDone()) {
            // The connect ended before it was registered
            tracker.pendingConnects.remove(request);
        } else if (tracker.shutDown) {
            // Checked after registering, so either this or shutdown() cancels it
            future.cancel(true);
        }
        return future;
    }

    /** Cancels connects in progress and closes their sessions; later ones are closed as they start. */
    void shutdown() {
        shutDown = true;
        for (Future<?> pending : pendingConnects.values()) {
            // Cancels the session request too, which closes a channel that is still connecting
            pending.cancel(true);
        }
        pendingConnects.clear();
        // Closing a session during its TLS handshake does not complete the connect, hence both
        for (IOSession session : sessions) {
            session.close(CloseMode.IMMEDIATE);
        }
        sessions.clear();
    }

    private ConnectionInitiator trackSessions(ConnectionInitiator initiator) {
        return (remoteEndpoint, remoteAddress, localAddress, timeout, attachment, callback) ->
                initiator.connect(remoteEndpoint, remoteAddress, localAddress, timeout, attachment,
                        new FutureCallback<>() {
                            @Override
                            public void completed(IOSession session) {
                                sessions.removeIf(tracked -> !tracked.isOpen());
                                sessions.add(session);
                                // Checked after adding, so either this or shutdown() closes it
                                if (shutDown) {
                                    session.close(CloseMode.IMMEDIATE);
                                }
                                if (callback != null) {
                                    callback.completed(session);
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
}
