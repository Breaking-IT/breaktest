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

package org.apache.jorphan.logging;

import org.apache.log.Logger;

/**
 * Compatibility entry point for plugins compiled against older JMeter releases.
 */
public final class LoggingManager {
    private LoggingManager() {
    }

    public static Logger getLoggerForClass() {
        return new Logger(callingClass());
    }

    public static Logger getLoggerFor(String category) {
        return new Logger(category);
    }

    private static Class<?> callingClass() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (!className.equals(Thread.class.getName())
                    && !className.equals(LoggingManager.class.getName())) {
                try {
                    return Class.forName(className);
                } catch (ClassNotFoundException ignored) {
                    return LoggingManager.class;
                }
            }
        }
        return LoggingManager.class;
    }
}
