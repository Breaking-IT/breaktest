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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AiModelSelectorTest {
    @Test
    @Timeout(10)
    void lateResultsPreserveTypedModelAndSelectionsAreRememberedPerTool() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AiModelSelector> picker = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AiModelSelector selector = new AiModelSelector((tool, directory) -> {
                calls.incrementAndGet();
                started.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new AiModelCatalog.Result(List.of(tool + "/listed"), AiModelCatalog.Status.LOADED);
            });
            picker.set(selector);
            selector.selectTool("pi", new File("."));
            combo(selector).getEditor().setItem("custom/model");
        });
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            release.countDown();
            awaitEdt(() -> combo(picker.get()).getItemCount() == 2);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("custom/model", picker.get().selectedModel());
                picker.get().selectTool("claude", new File("."));
                assertEquals("", picker.get().selectedModel());
            });
            awaitEdt(() -> combo(picker.get()).getItemCount() == 2);
            SwingUtilities.invokeAndWait(() -> {
                picker.get().selectTool("pi", new File("."));
                assertEquals("custom/model", picker.get().selectedModel());
                assertEquals("pi/listed", combo(picker.get()).getItemAt(1));
                assertEquals(2, calls.get(), "Returning to a tool should reuse its list");
            });
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> picker.get().cancelLookup());
        }
    }

    @Test
    @Timeout(10)
    void cancelledLookupCannotReplaceAnotherToolsModels() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<AiModelSelector> picker = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AiModelSelector selector = new AiModelSelector((tool, directory) -> {
                if (tool.equals("pi")) {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        cancelled.countDown();
                    }
                }
                return new AiModelCatalog.Result(List.of(tool + "/listed"), AiModelCatalog.Status.LOADED);
            });
            picker.set(selector);
            selector.selectTool("pi", new File("."));
        });
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> picker.get().selectTool("opencode", new File(".")));
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            awaitEdt(() -> combo(picker.get()).getItemCount() == 2);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("opencode/listed", combo(picker.get()).getItemAt(1));
                assertFalse(picker.get().selectedModel().contains("pi"));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> picker.get().cancelLookup());
        }
    }

    private static JComboBox<?> combo(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JComboBox<?> result) {
                return result;
            }
            if (child instanceof Container container) {
                JComboBox<?> result = combo(container);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static void awaitEdt(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(ready.get(), "Model results did not reach the EDT");
    }
}
