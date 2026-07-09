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

import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.IntFunction;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;

/**
 * Internal marker sampler consumed by {@link org.apache.jmeter.threads.JMeterThread}.
 */
public class ParallelControllerSampler extends AbstractSampler {
    private static final long serialVersionUID = 240L;

    private final Controller controller;
    private final int maxParallel;
    private final int branchCount;
    private final List<Controller> branches;
    private final transient IntFunction<Controller> branchFactory;
    private final IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers;

    public ParallelControllerSampler() {
        this(null, "", 1, List.of(), new IdentityHashMap<>());
    }

    ParallelControllerSampler(Controller controller, String name, int maxParallel, List<Controller> branches,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        this.controller = controller;
        this.maxParallel = maxParallel;
        this.branchCount = branches.size();
        this.branches = List.copyOf(branches);
        this.branchFactory = null;
        this.sourceTransactionControllers = new IdentityHashMap<>(sourceTransactionControllers);
        setName(name);
    }

    ParallelControllerSampler(Controller controller, String name, int maxParallel, int branchCount,
            IntFunction<Controller> branchFactory,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        this.controller = controller;
        this.maxParallel = maxParallel;
        this.branchCount = branchCount;
        this.branches = List.of();
        this.branchFactory = branchFactory;
        this.sourceTransactionControllers = new IdentityHashMap<>(sourceTransactionControllers);
        setName(name);
    }

    public Controller getController() {
        return controller;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public int getBranchCount() {
        return branchCount;
    }

    public Controller getBranch(int index) {
        if (index < 0 || index >= branchCount) {
            throw new IndexOutOfBoundsException(index);
        }
        if (branchFactory == null) {
            if (!branches.isEmpty()) {
                return branches.get(index);
            }
            throw new IllegalStateException("Parallel branches are no longer available");
        }
        return branchFactory.apply(index);
    }

    public List<Controller> getBranches() {
        if (branchFactory == null && !branches.isEmpty()) {
            return branches;
        }
        return java.util.stream.IntStream.range(0, branchCount)
                .mapToObj(this::getBranch)
                .toList();
    }

    public TransactionController getSourceTransactionController(TransactionController controller) {
        return sourceTransactionControllers.getOrDefault(controller, controller);
    }

    @Override
    public SampleResult sample(Entry e) {
        throw new UnsupportedOperationException("ParallelControllerSampler is executed by JMeterThread");
    }
}
