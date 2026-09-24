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

package org.apache.jmeter.samplers;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Identifies one execution of a Transaction Controller.
 * <p>
 * Every sample that runs inside a transaction points to the reference of its innermost
 * transaction (see {@link SampleResult#getParentTransaction()}), and the transaction sample
 * carries the same reference as its own identity (see {@link SampleResult#getTransaction()}).
 * Listeners use the id to attach samples to their transaction, and the parent chain to find the
 * enclosing transactions. All samples of one transaction execution share a single instance.
 */
public final class TransactionRef implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Starts at a random value so ids from different engines of a distributed test do not collide.
     */
    private static final AtomicLong NEXT_ID = new AtomicLong(ThreadLocalRandom.current().nextLong() >>> 1);

    private final long id;
    private final String name;
    private final TransactionRef parent;

    /**
     * @param id     unique id of the transaction execution
     * @param name   transaction name
     * @param parent enclosing transaction, or {@code null} for a top level transaction
     */
    public TransactionRef(long id, String name, TransactionRef parent) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.parent = parent;
    }

    /**
     * Creates the reference for a new transaction execution with a fresh id.
     *
     * @param name   transaction name
     * @param parent enclosing transaction, or {@code null} for a top level transaction
     * @return new reference
     */
    public static TransactionRef start(String name, TransactionRef parent) {
        return new TransactionRef(NEXT_ID.getAndIncrement() & Long.MAX_VALUE, name, parent);
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * @return enclosing transaction, or {@code null} for a top level transaction
     */
    public TransactionRef getParent() {
        return parent;
    }

    /**
     * @return transaction names from the outermost transaction down to this one
     */
    public List<String> getPath() {
        List<String> path = new ArrayList<>();
        for (TransactionRef ref = this; ref != null; ref = ref.parent) {
            path.add(ref.name);
        }
        Collections.reverse(path);
        return path;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TransactionRef other && id == other.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return name + "#" + id;
    }
}
