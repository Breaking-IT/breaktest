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

package org.apache.jmeter.threads;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Variables view used by parallel and fork workers of a virtual user.
 *
 * <p>All variables are shared with the parent flow, except the engine-internal keys
 * {@link JMeterThread#LAST_SAMPLE_OK} and {@link JMeterThread#PACKAGE_OBJECT}, which stay local
 * to the worker: several workers of the same virtual user run concurrently, and a worker writing
 * these keys into the shared map would clobber the state of the main flow and of the other
 * workers (e.g. a failing fork sample flipping the main thread's {@code last_sample_ok}, or a
 * non-parent transaction controller notifying the listeners of whatever package a concurrent
 * worker executed last).</p>
 *
 * <p>Reads of the worker-local keys fall through to the parent until the worker writes them, so
 * a worker inherits the state from before the parallel section. Removing a worker-local key only
 * clears the worker's value, never the parent's.</p>
 */
class ParallelWorkerVariables extends JMeterVariables {

    private static final Object NOT_SET = new Object();

    private final JMeterVariables parent;
    private volatile Object lastSampleOk = NOT_SET;
    private volatile Object samplePackage = NOT_SET;

    ParallelWorkerVariables(JMeterVariables parent) {
        this.parent = parent;
    }

    private static boolean isWorkerLocal(String key) {
        return JMeterThread.LAST_SAMPLE_OK.equals(key) || JMeterThread.PACKAGE_OBJECT.equals(key);
    }

    private Object getLocal(String key) {
        return JMeterThread.LAST_SAMPLE_OK.equals(key) ? lastSampleOk : samplePackage;
    }

    private void setLocal(String key, Object value) {
        if (JMeterThread.LAST_SAMPLE_OK.equals(key)) {
            lastSampleOk = value;
        } else {
            samplePackage = value;
        }
    }

    @Override
    public void put(String key, String value) {
        if (isWorkerLocal(key)) {
            setLocal(key, value);
        } else {
            parent.put(key, value);
        }
    }

    @Override
    public void putObject(String key, Object value) {
        if (isWorkerLocal(key)) {
            setLocal(key, value);
        } else {
            parent.putObject(key, value);
        }
    }

    @Override
    public void putAll(Map<String, ?> vars) {
        for (Map.Entry<String, ?> entry : vars.entrySet()) {
            putObject(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void putAll(JMeterVariables vars) {
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            putObject(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public String get(String key) {
        if (isWorkerLocal(key)) {
            Object local = getLocal(key);
            if (local != NOT_SET) {
                return local == null ? null : local.toString();
            }
        }
        return parent.get(key);
    }

    @Override
    public Object getObject(String key) {
        if (isWorkerLocal(key)) {
            Object local = getLocal(key);
            if (local != NOT_SET) {
                return local;
            }
        }
        return parent.getObject(key);
    }

    @Override
    public Object remove(String key) {
        if (isWorkerLocal(key)) {
            Object local = getLocal(key);
            setLocal(key, NOT_SET);
            return local == NOT_SET ? parent.getObject(key) : local;
        }
        return parent.remove(key);
    }

    @Override
    public void clear() {
        lastSampleOk = NOT_SET;
        samplePackage = NOT_SET;
        parent.clear();
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parent.entrySet()) {
            merged.put(entry.getKey(), entry.getValue());
        }
        Object localLastSampleOk = lastSampleOk;
        if (localLastSampleOk != NOT_SET) {
            merged.put(JMeterThread.LAST_SAMPLE_OK, localLastSampleOk);
        }
        Object localSamplePackage = samplePackage;
        if (localSamplePackage != NOT_SET) {
            merged.put(JMeterThread.PACKAGE_OBJECT, localSamplePackage);
        }
        return Collections.unmodifiableMap(merged).entrySet();
    }

    @Override
    public int getIteration() {
        return parent.getIteration();
    }

    @Override
    public void incIteration() {
        parent.incIteration();
    }

    @Override
    public boolean isSameUserOnNextIteration() {
        return parent.isSameUserOnNextIteration();
    }
}
