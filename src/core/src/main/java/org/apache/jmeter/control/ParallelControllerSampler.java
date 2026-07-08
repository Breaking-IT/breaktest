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

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;

/**
 * Internal marker sampler consumed by {@link org.apache.jmeter.threads.JMeterThread}.
 */
public class ParallelControllerSampler extends AbstractSampler {
    private static final long serialVersionUID = 240L;

    private final ParallelController controller;
    private final int maxParallel;
    private final List<Controller> branches;
    private final IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers;

    public ParallelControllerSampler() {
        this(null, "", 1, List.of(), new IdentityHashMap<>());
    }

    ParallelControllerSampler(ParallelController controller, String name, int maxParallel, List<Controller> branches,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        this.controller = controller;
        this.maxParallel = maxParallel;
        this.branches = List.copyOf(branches);
        this.sourceTransactionControllers = new IdentityHashMap<>(sourceTransactionControllers);
        setName(name);
    }

    public ParallelController getController() {
        return controller;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public List<Controller> getBranches() {
        return branches;
    }

    public TransactionController getSourceTransactionController(TransactionController controller) {
        return sourceTransactionControllers.getOrDefault(controller, controller);
    }

    @Override
    public SampleResult sample(Entry e) {
        throw new UnsupportedOperationException("ParallelControllerSampler is executed by JMeterThread");
    }
}
