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

package org.apache.jmeter.config;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.engine.util.NoConfigMerge;
import org.apache.jmeter.save.ArchivedDataFile;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jorphan.util.JMeterStopThreadException;

/** Shared record allocation for structured file data sets. */
public abstract class StructuredDataSet extends ConfigTestElement
        implements TestBean, LoopIterationListener, NoConfigMerge, TestStateListener, ArchivedDataFile {
    private static final long serialVersionUID = 1L;
    private static final ConcurrentMap<SourceKey, Source> CURSORS = new ConcurrentHashMap<>();
    private transient Cursor cursor;

    protected abstract List<Map<String, String>> loadDataRecords() throws IOException;

    protected abstract String recordSource() throws IOException;

    /** Name of the boolean property that selects the archived copy of the file. */
    protected abstract String archiveProperty();

    @Override
    public String archivedDataFilename() {
        return getPropertyAsBoolean(archiveProperty()) ? getFilename() : null;
    }

    protected String recordSelection() {
        return "";
    }

    private record SourceKey(Class<?> format, String source, String selection, String mode, Object scope) {
    }

    private record IdentityScope(Object value) {
        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityScope scope && value == scope.value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }

    private SourceKey sourceKey() throws IOException {
        JMeterContext context = getThreadContext();
        String mode = getShareMode();
        Object scope = switch (mode) {
            case "shareMode.all" -> "all";
            case "shareMode.group" -> context.getThreadGroup() == null ? context : context.getThreadGroup();
            case "shareMode.thread" -> context.getThread() == null ? context : context.getThread();
            default -> mode;
        };
        return new SourceKey(getClass(), recordSource(), recordSelection(), mode,
                scope instanceof String ? scope : new IdentityScope(scope));
    }

    @Override
    public Object clone() {
        StructuredDataSet copy = (StructuredDataSet) super.clone();
        copy.cursor = null;
        return copy;
    }

    @Override
    public void iterationStart(LoopIterationEvent event) {
        if (cursor == null) {
            try {
                // Load outside the map's lock; the source loads once and remembers a failure.
                cursor = CURSORS.computeIfAbsent(sourceKey(), key -> new Source()).cursor(this);
            } catch (IOException | IllegalArgumentException e) {
                throw new JMeterStopThreadException("Unable to load " + getClass().getSimpleName() + ": " + getFilename(), e);
            }
        }
        Map<String, String> record = cursor.next(isRecycle());
        if (record == null && isStopThread()) {
            throw new JMeterStopThreadException("End of data file: " + getFilename());
        }
        JMeterVariables variables = getThreadContext().getVariables();
        String prefix = getVariablePrefix();
        for (String name : cursor.names) {
            if (record == null) {
                variables.put(prefix + name, "<EOF>");
            } else if (!record.containsKey(name)) {
                variables.put(prefix + name, StructuredDataVariables.missingValue(name));
            }
        }
        if (record != null) {
            record.forEach((name, value) -> variables.put(prefix + name, value));
        }
    }

    private static final class Source {
        private Cursor cursor;
        private Exception failure;

        private synchronized Cursor cursor(StructuredDataSet owner) throws IOException {
            if (cursor == null && failure == null) {
                try {
                    cursor = new Cursor(owner.loadDataRecords());
                } catch (IOException | RuntimeException e) {
                    failure = e;
                }
            }
            if (failure instanceof IOException e) {
                throw e;
            }
            if (failure instanceof RuntimeException e) {
                throw e;
            }
            return cursor;
        }
    }

    private static final class Cursor {
        private final List<Map<String, String>> records;
        private final Set<String> names = new LinkedHashSet<>();
        private int nextRecord;

        private Cursor(List<Map<String, String>> records) {
            this.records = records;
            records.forEach(record -> names.addAll(record.keySet()));
        }

        private synchronized Map<String, String> next(boolean recycle) {
            if (nextRecord == records.size() && recycle) {
                nextRecord = 0;
            }
            return nextRecord == records.size() ? null : records.get(nextRecord++);
        }
    }

    @Override
    public void testStarted() {
        CURSORS.clear();
        cursor = null;
    }

    @Override
    public void testStarted(String host) {
        testStarted();
    }

    @Override
    public void testEnded() {
        CURSORS.clear();
        cursor = null;
    }

    @Override
    public void testEnded(String host) {
        testEnded();
    }

    public String getShareMode() {
        return getPropertyAsString("shareMode", "shareMode.all");
    }

    public void setShareMode(String mode) {
        setProperty("shareMode", mode == null || mode.isBlank() ? "shareMode.all" : mode, "shareMode.all");
    }

    public String getFilename() {
        return getPropertyAsString("filename");
    }

    public void setFilename(String filename) {
        setProperty("filename", filename);
    }

    public String getVariablePrefix() {
        return getPropertyAsString("variablePrefix");
    }

    public void setVariablePrefix(String prefix) {
        setProperty("variablePrefix", prefix);
    }

    public boolean isRecycle() {
        return getPropertyAsBoolean("recycle", true);
    }

    public void setRecycle(boolean recycle) {
        setProperty("recycle", recycle, true);
    }

    public boolean isStopThread() {
        return getPropertyAsBoolean("stopThread");
    }

    public void setStopThread(boolean stopThread) {
        setProperty("stopThread", stopThread, false);
    }
}
