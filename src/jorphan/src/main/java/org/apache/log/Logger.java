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

package org.apache.log;

import org.slf4j.LoggerFactory;

/**
 * Compatibility facade for plugins compiled against the historical Avalon
 * LogKit logger used by older JMeter versions.
 */
public class Logger {
    private final org.slf4j.Logger delegate;

    public Logger(String name) {
        this.delegate = LoggerFactory.getLogger(name);
    }

    public Logger(Class<?> clazz) {
        this.delegate = LoggerFactory.getLogger(clazz);
    }

    public Logger getChildLogger(String name) {
        return new Logger(delegate.getName() + "." + name);
    }

    public void debug(String message) {
        delegate.debug(message);
    }

    public void debug(String message, Throwable throwable) {
        delegate.debug(message, throwable);
    }

    public void info(String message) {
        delegate.info(message);
    }

    public void info(String message, Throwable throwable) {
        delegate.info(message, throwable);
    }

    public void warn(String message) {
        delegate.warn(message);
    }

    public void warn(String message, Throwable throwable) {
        delegate.warn(message, throwable);
    }

    public void error(String message) {
        delegate.error(message);
    }

    public void error(String message, Throwable throwable) {
        delegate.error(message, throwable);
    }

    public void fatalError(String message) {
        delegate.error(message);
    }

    public void fatalError(String message, Throwable throwable) {
        delegate.error(message, throwable);
    }

    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    public boolean isInfoEnabled() {
        return delegate.isInfoEnabled();
    }

    public boolean isWarnEnabled() {
        return delegate.isWarnEnabled();
    }

    public boolean isErrorEnabled() {
        return delegate.isErrorEnabled();
    }

    public boolean isFatalErrorEnabled() {
        return delegate.isErrorEnabled();
    }
}
