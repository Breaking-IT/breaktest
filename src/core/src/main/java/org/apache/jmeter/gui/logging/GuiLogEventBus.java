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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * GUI Log Event Bus.
 * @since 3.2
 */
public class GuiLogEventBus {

    // Logging starts before application properties or Swing are initialized.
    private static final int HISTORY_LIMIT = 1000;
    private static final GuiLogEventBus INSTANCE = new GuiLogEventBus();

    private final Deque<LogEventObject> history = new ArrayDeque<>();

    /**
     * Returns the process-wide bus, available before GuiPackage is created.
     * @return shared GUI log event bus
     */
    public static GuiLogEventBus getInstance() {
        return INSTANCE;
    }

    /**
     * Registered GUI log event listeners array.
     */
    private final List<GuiLogEventListener> listeners = new ArrayList<>();

    /**
     * Default constructor.
     */
    public GuiLogEventBus() {
        super();
    }

    /**
     * Register a GUI log event listener and replay the most recent 1000 events.
     * Replay and live delivery share a lock so events cannot be missed or reordered
     * when a logging thread posts during GUI initialization.
     * @param listener a GUI log event listener ({@link GuiLogEventListener})
     */
    public synchronized void registerEventListener(GuiLogEventListener listener) {
        if (listeners.contains(listener)) {
            return;
        }
        listeners.add(listener);
        for (LogEventObject event : new ArrayList<>(history)) {
            listener.processLogEvent(event);
        }
    }

    /**
     * Unregister a GUI log event listener ({@link GuiLogEventListener}).
     * @param listener a GUI log event listener ({@link GuiLogEventListener})
     */
    public synchronized void unregisterEventListener(GuiLogEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Post a log event object.
     * @param logEventObject log event object
     */
    public synchronized void postEvent(LogEventObject logEventObject) {
        if (history.size() == HISTORY_LIMIT) {
            history.removeFirst();
        }
        history.addLast(logEventObject);
        for (GuiLogEventListener listener : new ArrayList<>(listeners)) {
            listener.processLogEvent(logEventObject);
        }
    }
}
