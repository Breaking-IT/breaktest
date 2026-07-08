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

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;

/**
 * Internal marker sampler consumed by {@link org.apache.jmeter.threads.JMeterThread}.
 */
public class ForkControllerSampler extends AbstractSampler {
    private static final long serialVersionUID = 240L;

    private final ForkController sourceController;
    private final Controller controller;
    private final IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers;

    public ForkControllerSampler() {
        this(null, "", new GenericController(), new IdentityHashMap<>());
    }

    ForkControllerSampler(ForkController sourceController, String name, Controller controller,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        this.sourceController = sourceController;
        this.controller = controller;
        this.sourceTransactionControllers = new IdentityHashMap<>(sourceTransactionControllers);
        setName(name);
    }

    public ForkController getSourceController() {
        return sourceController;
    }

    public Controller getController() {
        return controller;
    }

    public TransactionController getSourceTransactionController(TransactionController controller) {
        return sourceTransactionControllers.getOrDefault(controller, controller);
    }

    @Override
    public SampleResult sample(Entry e) {
        throw new UnsupportedOperationException("ForkControllerSampler is executed by JMeterThread");
    }
}
