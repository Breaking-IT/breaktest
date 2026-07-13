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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class UDPSocketManagerTest {

    @Test
    void slowSocketCreationDoesNotBlockAnotherVirtualUser() throws Exception {
        Object firstUser = new Object();
        Object secondUser = new Object();
        UDPSocketManager.SocketKey firstKey = new UDPSocketManager.SocketKey(firstUser, "conversation");
        UDPSocketManager.SocketKey secondKey = new UDPSocketManager.SocketKey(secondUser, "conversation");
        CountDownLatch firstFactoryStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstFactory = new CountDownLatch(1);
        CountDownLatch secondFactoryCompleted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread first = Thread.ofVirtual().start(() -> {
            try {
                UDPSocketManager.getOrCreate(firstKey, "first", () -> {
                    firstFactoryStarted.countDown();
                    try {
                        if (!releaseFirstFactory.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release the first socket factory");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    return new DatagramSocket();
                });
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });

        assertTrue(firstFactoryStarted.await(2, TimeUnit.SECONDS));
        Thread second = Thread.ofVirtual().start(() -> {
            try {
                UDPSocketManager.getOrCreate(secondKey, "second", DatagramSocket::new);
                secondFactoryCompleted.countDown();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
        boolean completedIndependently = secondFactoryCompleted.await(1, TimeUnit.SECONDS);
        releaseFirstFactory.countDown();
        first.join(Duration.ofSeconds(2));
        second.join(Duration.ofSeconds(2));
        try {
            assertTrue(completedIndependently,
                    "DNS or socket creation for one virtual user must not block another user");
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertNull(failure.get());
        } finally {
            UDPSocketManager.releaseScope(firstUser);
            UDPSocketManager.releaseScope(secondUser);
        }
    }
}
