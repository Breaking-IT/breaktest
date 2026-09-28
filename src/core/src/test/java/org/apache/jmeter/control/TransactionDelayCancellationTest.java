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

package org.apache.jmeter.control;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jorphan.util.JMeterStopThreadException;
import org.junit.jupiter.api.Test;

class TransactionDelayCancellationTest extends JMeterTestCase {
    @Test
    void delayWithoutUserThreadCanBeCancelledByInterrupt() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            assertNull(JMeterContextService.getContext().getThread());
            TransactionController controller = new TransactionController();
            controller.setDelayMode(TransactionController.DELAY_FIXED);
            controller.setFixedDelay("60000");
            controller.initialize();
            assertThrows(JMeterStopThreadException.class, controller::next);
            assertTrue(Thread.currentThread().isInterrupted());
            return null;
        });
        Thread worker = new Thread(task);
        worker.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (worker.getState() != Thread.State.TIMED_WAITING && !task.isDone()
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(worker.getState() == Thread.State.TIMED_WAITING);
            worker.interrupt();
            task.get(2, TimeUnit.SECONDS);
        } finally {
            worker.interrupt();
            worker.join(2000);
        }
    }
}
