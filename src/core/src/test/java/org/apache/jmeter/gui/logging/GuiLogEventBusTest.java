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

package org.apache.jmeter.gui.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class GuiLogEventBusTest {
    @Test
    void eachListenerReceivesStartupHistoryThenLiveEvents() {
        GuiLogEventBus bus = new GuiLogEventBus();
        List<String> panel = new ArrayList<>();
        List<String> counter = new ArrayList<>();
        bus.postEvent(event("startup"));
        GuiLogEventListener panelListener = event -> panel.add(event.toString());
        bus.registerEventListener(panelListener);
        bus.postEvent(event("constructing GUI"));
        bus.registerEventListener(event -> counter.add(event.toString()));
        bus.registerEventListener(panelListener);
        bus.postEvent(event("ready"));
        assertEquals(List.of("startup", "constructing GUI", "ready"), panel);
        assertEquals(panel, counter);
        bus.unregisterEventListener(panelListener);
        bus.postEvent(event("later"));
        assertEquals(3, panel.size());
        assertEquals(4, counter.size());
    }

    @Test
    void startupHistoryIsBoundedAndKeepsNewestEventsInOrder() {
        GuiLogEventBus bus = new GuiLogEventBus();
        for (int i = 0; i < 1100; i++) {
            bus.postEvent(event(Integer.toString(i)));
        }
        List<String> received = new ArrayList<>();
        bus.registerEventListener(event -> received.add(event.toString()));
        assertEquals(IntStream.range(100, 1100).mapToObj(Integer::toString).toList(), received);
    }

    @Test
    void concurrentSubscriptionDoesNotLoseOrDuplicateEvents() throws Exception {
        GuiLogEventBus bus = new GuiLogEventBus();
        List<String> received = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> publisher = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 1000; i++) {
                    bus.postEvent(event(Integer.toString(i)));
                }
                return null;
            });
            Future<?> subscriber = executor.submit(() -> {
                start.await();
                bus.registerEventListener(event -> received.add(event.toString()));
                return null;
            });
            start.countDown();
            publisher.get(10, TimeUnit.SECONDS);
            subscriber.get(10, TimeUnit.SECONDS);
            assertEquals(IntStream.range(0, 1000).mapToObj(Integer::toString).toList(), received);
        } finally {
            executor.shutdownNow();
        }
    }

    private static LogEventObject event(String message) {
        return new LogEventObject(GuiLogEventBusTest.class, message);
    }
}
