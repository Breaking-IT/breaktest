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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import org.apache.jmeter.util.JMeterUtils;

/**
 * Class which defines JMeter variables.
 * These are normally local to a single virtual-user thread, but the
 * {@link org.apache.jmeter.control.ParallelController} runs several samplers of
 * the same virtual user concurrently and lets them share this instance. The
 * backing map is therefore accessed under one monitor so concurrent updates only race on
 * values (last writer wins) instead of corrupting the map structure. Unlike
 * {@link java.util.concurrent.ConcurrentHashMap} a synchronized map still allows
 * {@code null} values, which JMeter relies on.
 */
public class JMeterVariables {
    private final Map<String, Object> variables = new LinkedHashMap<>();

    // Mutations hold the map lock; volatile enables inactive checks from delegating views.
    private volatile SubscriptionRegistry subscriptions;

    private static final Object COPY_TIE_LOCK = new Object();

    private int iteration = 0;

    // Property names to preload into JMeter variables:
    private static final String [] PRE_LOAD = {
      "START.MS",     // $NON-NLS-1$
      "START.YMD",    // $NON-NLS-1$
      "START.HMS",    //$NON-NLS-1$
      "TESTSTART.MS", // $NON-NLS-1$
    };

    static final String VAR_IS_SAME_USER_KEY = "__jmv_SAME_USER";

    /**
     * Constructor, that preloads the variables from the JMeter properties
     */
    public JMeterVariables() {
        preloadVariables();
    }

    private void preloadVariables(){
        for (String property : PRE_LOAD) {
            String value = JMeterUtils.getProperty(property);
            if (value != null) {
                variables.put(property, value);
            }
        }
    }

    /**
     * @return the name of the currently running thread
     */
    public String getThreadName() {
        return Thread.currentThread().getName();
    }

    /**
     * @return the current number of iterations
     */
    public int getIteration() {
        return iteration;
    }

    /**
     * Increase the current number of iterations
     */
    public void incIteration() {
        iteration++;
    }

    /**
     * Remove a variable.
     *
     * @param key the variable name to remove
     *
     * @return the variable value, or {@code null} if there was no such variable
     */
    public Object remove(String key) {
        synchronized (variables) {
            Object previous = variables.remove(key);
            changed(key);
            return previous;
        }
    }

    /**
     * Remove all variables.
     */
    public void clear() {
        synchronized (variables) {
            variables.clear();
            if (subscriptions != null) {
                subscriptions.all.forEach(ChangeSubscription::signal);
            }
        }
    }

    /**
     * Creates or updates a variable with a String value.
     *
     * @param key the variable name
     * @param value the variable value
     */
    public void put(String key, String value) {
        synchronized (variables) {
            variables.put(key, value);
            changed(key);
        }
    }

    /**
     * Creates or updates a variable with a value that does not have to be a String.
     *
     * @param key the variable name
     * @param value the variable value
     */
    public void putObject(String key, Object value) {
        synchronized (variables) {
            variables.put(key, value);
            changed(key);
        }
    }

    /**
     * Updates the variables with all entries found in the {@link Map} {@code vars}
     * @param vars map with the entries to be updated
     */
    public void putAll(Map<String, ?> vars) {
        synchronized (variables) {
            variables.putAll(vars);
            changedKeys(vars);
        }
    }

    /**
     * Updates the variables with all entries found in the variables in {@code vars}
     * @param vars {@link JMeterVariables} with the entries to be updated
     */
    public void putAll(JMeterVariables vars) {
        if (vars == this) {
            return;
        }
        if (vars.getClass() != JMeterVariables.class) {
            // Delegating variable views define their values through entrySet(), not this backing map.
            Map<String, Object> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
            putAll(snapshot);
            return;
        }
        int destinationHash = System.identityHashCode(variables);
        int sourceHash = System.identityHashCode(vars.variables);
        if (destinationHash == sourceHash) {
            synchronized (COPY_TIE_LOCK) {
                copyWithLocks(vars, variables, vars.variables);
            }
        } else if (destinationHash < sourceHash) {
            copyWithLocks(vars, variables, vars.variables);
        } else {
            copyWithLocks(vars, vars.variables, variables);
        }
    }

    private void copyWithLocks(JMeterVariables source, Object first, Object second) {
        // Ordered locks provide a consistent source view without a snapshot allocation
        // or deadlock when two forks copy in opposite directions.
        synchronized (first) {
            synchronized (second) {
                variables.putAll(source.variables);
                changedKeys(source.variables);
            }
        }
    }

    /**
     * Announces a write performed by a delegating view without changing this store's value.
     * Views publish their value before calling this method. An inactive store takes no lock.
     * @param key changed parameter name
     */
    public void signalChange(String key) {
        if (subscriptions != null) {
            synchronized (variables) {
                changed(key);
            }
        }
    }

    private void changedKeys(Map<String, ?> values) {
        if (subscriptions != null) {
            subscriptions.broad.forEach(ChangeSubscription::signal);
            for (String key : values.keySet()) {
                signalKey(key);
            }
        }
    }

    private void changed(String key) {
        if (subscriptions != null) {
            subscriptions.broad.forEach(ChangeSubscription::signal);
            signalKey(key);
        }
    }

    private void signalKey(String key) {
        Set<ChangeSubscription> listeners = subscriptions.byKey.get(key);
        if (listeners != null) {
            listeners.forEach(ChangeSubscription::signal);
        }
    }

    /**
     * Registers before condition evaluation so writes during evaluation cannot be lost.
     * @param keys exact parameter names, or null for unknown/dynamic dependencies
     * @return a subscription owned by the calling thread; close it after the wait
     */
    public ChangeSubscription watchChanges(Set<String> keys) {
        ChangeSubscription subscription = new ChangeSubscription(keys);
        synchronized (variables) {
            if (keys == null || !keys.isEmpty()) {
                if (subscriptions == null) {
                    subscriptions = new SubscriptionRegistry();
                }
                subscriptions.all.add(subscription);
                if (keys == null) {
                    subscriptions.broad.add(subscription);
                } else {
                    for (String key : subscription.keys) {
                        subscriptions.byKey.computeIfAbsent(key, ignored -> new HashSet<>()).add(subscription);
                    }
                }
            }
        }
        return subscription;
    }

    /**
     * Parks outside the map lock. LockSupport supports unmounting virtual threads on Java 21.
     * The revision check plus the retained unpark permit closes the check-to-park race.
     * @param subscription subscription owned by the calling thread
     * @param version revision captured before evaluation
     * @param timeoutNanos maximum wait in nanoseconds
     * @throws InterruptedException when the executing thread is stopped
     */
    public void awaitChange(ChangeSubscription subscription, long version, long timeoutNanos)
            throws InterruptedException {
        if (subscription.owner != Thread.currentThread()) {
            throw new IllegalStateException("A change subscription must be awaited by its owner");
        }
        long start = System.nanoTime();
        long remaining = timeoutNanos;
        while (!subscription.closed && subscription.version == version && remaining > 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            LockSupport.parkNanos(subscription, remaining);
            remaining = timeoutNanos - (System.nanoTime() - start);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    /** A short-lived, thread-owned subscription; only relevant writes advance its revision. */
    public final class ChangeSubscription implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Set<String> keys;
        private volatile long version;
        private volatile boolean closed;

        private ChangeSubscription(Set<String> keys) {
            this.keys = keys == null ? null : Set.copyOf(keys);
        }

        public long getVersion() {
            return version;
        }

        @SuppressWarnings("NonAtomicVolatileUpdate") // All writes hold the enclosing variables monitor.
        private void signal() {
            version++;
            LockSupport.unpark(owner);
        }

        @Override
        public void close() {
            synchronized (variables) {
                if (closed) {
                    return;
                }
                closed = true;
                if (subscriptions != null) {
                    subscriptions.all.remove(this);
                    subscriptions.broad.remove(this);
                    if (keys != null) {
                        for (String key : keys) {
                            Set<ChangeSubscription> listeners = subscriptions.byKey.get(key);
                            if (listeners != null) {
                                listeners.remove(this);
                                if (listeners.isEmpty()) {
                                    subscriptions.byKey.remove(key);
                                }
                            }
                        }
                    }
                    if (subscriptions.all.isEmpty()) {
                        subscriptions = null;
                    }
                }
            }
            LockSupport.unpark(owner);
        }
    }

    private static class SubscriptionRegistry {
        final Set<ChangeSubscription> all = new HashSet<>();
        final Set<ChangeSubscription> broad = new HashSet<>();
        final Map<String, Set<ChangeSubscription>> byKey = new HashMap<>();
    }

    /**
     * Gets the value of a variable, converted to a String.
     *
     * @param key the name of the variable
     * @return the value of the variable or a toString called on it if it's non String, or {@code null} if it does not exist
     */
    public String get(String key) {
        Object o = getObject(key);
        if (o instanceof String string) {
            return string;
        } else if (o != null) {
            return o.toString();
        } else {
            return null;
        }
    }

    /**
     * Gets the value of a variable (not converted to String).
     *
     * @param key the name of the variable
     * @return the value of the variable, or {@code null} if it does not exist
     */
    public Object getObject(String key) {
        synchronized (variables) {
            return variables.get(key);
        }
    }

    /**
     * Gets a read-only Iterator over the variables.
     *
     * @return the iterator
     */
    public Iterator<Map.Entry<String, Object>> getIterator(){
        return entrySet().iterator() ;
    }

    // Used by DebugSampler
    /**
     * @return an unmodifiable view of the entries contained in {@link JMeterVariables}
     */
    public Set<Map.Entry<String, Object>> entrySet(){
        // Snapshot under the map lock so iteration is not affected by concurrent
        // updates from samplers running in parallel for the same virtual user.
        synchronized (variables) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(variables)).entrySet();
        }
    }

    /**
     * @return boolean true if user is the same on next iteration of Thread loop, false otherwise
     */
    public boolean isSameUserOnNextIteration() {
        return Boolean.TRUE.equals(getObject(VAR_IS_SAME_USER_KEY));
    }
}
