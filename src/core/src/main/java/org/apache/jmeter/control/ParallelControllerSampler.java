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
import org.apache.jmeter.samplers.Sampler;

/**
 * Internal marker sampler consumed by {@link org.apache.jmeter.threads.JMeterThread}.
 */
public class ParallelControllerSampler extends AbstractSampler {
    private static final long serialVersionUID = 240L;

    private final Controller controller;
    private final int maxParallel;
    private final int branchCount;
    private final List<Controller> branches;
    private final transient IntFunction<ParallelBranch> branchFactory;
    private final IdentityHashMap<TransactionController, TransactionController> eagerSourceTransactionControllers;

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
        this.eagerSourceTransactionControllers = new IdentityHashMap<>(sourceTransactionControllers);
        setName(name);
    }

    ParallelControllerSampler(Controller controller, String name, int maxParallel, int branchCount,
            IntFunction<ParallelBranch> branchFactory) {
        this.controller = controller;
        this.maxParallel = maxParallel;
        this.branchCount = branchCount;
        this.branches = List.of();
        this.branchFactory = branchFactory;
        this.eagerSourceTransactionControllers = new IdentityHashMap<>();
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

    public ParallelBranch getParallelBranch(int index) {
        if (index < 0 || index >= branchCount) {
            throw new IndexOutOfBoundsException(index);
        }
        if (branchFactory == null) {
            if (!branches.isEmpty()) {
                return new ParallelBranch(branches.get(index), eagerSourceTransactionControllers);
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
                .mapToObj(index -> getParallelBranch(index).getController())
                .toList();
    }

    /**
     * A materialized parallel branch and the element mappings required only while that branch
     * executes: cloned transaction controllers and cloned samplers are mapped back to their
     * source elements from the compiled test tree.
     */
    public static final class ParallelBranch {
        private final Controller controller;
        private final IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers;
        private final IdentityHashMap<Sampler, Sampler> sourceSamplers;

        ParallelBranch(Controller controller,
                IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
            this(controller, sourceTransactionControllers, null);
        }

        ParallelBranch(Controller controller,
                IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers,
                IdentityHashMap<Sampler, Sampler> sourceSamplers) {
            this.controller = controller;
            this.sourceTransactionControllers = sourceTransactionControllers;
            this.sourceSamplers = sourceSamplers;
        }

        public Controller getController() {
            return controller;
        }

        public TransactionController getSourceTransactionController(TransactionController controller) {
            return sourceTransactionControllers.getOrDefault(controller, controller);
        }

        /**
         * Maps a per-branch sampler clone back to the sampler it was cloned from, so the
         * compiled {@link org.apache.jmeter.threads.SamplePackage} of the source can be found.
         *
         * @param sampler a sampler executed by this branch
         * @return the source sampler, or {@code sampler} itself when it was not cloned
         */
        public Sampler getSourceSampler(Sampler sampler) {
            if (sourceSamplers == null) {
                return sampler;
            }
            return sourceSamplers.getOrDefault(sampler, sampler);
        }
    }

    @Override
    public SampleResult sample(Entry e) {
        throw new UnsupportedOperationException("ParallelControllerSampler is executed by JMeterThread");
    }
}
