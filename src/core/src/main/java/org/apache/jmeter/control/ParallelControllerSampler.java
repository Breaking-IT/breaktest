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

import java.util.List;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;

/**
 * Internal marker sampler consumed by {@link org.apache.jmeter.threads.JMeterThread}.
 */
public class ParallelControllerSampler extends AbstractSampler {
    private static final long serialVersionUID = 240L;

    private final ParallelController controller;
    private final int maxParallel;
    private final List<Sampler> samplers;

    public ParallelControllerSampler() {
        this(null, "", 1, List.of());
    }

    ParallelControllerSampler(ParallelController controller, String name, int maxParallel, List<Sampler> samplers) {
        this.controller = controller;
        this.maxParallel = maxParallel;
        this.samplers = List.copyOf(samplers);
        setName(name);
    }

    public ParallelController getController() {
        return controller;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public List<Sampler> getSamplers() {
        return samplers;
    }

    @Override
    public SampleResult sample(Entry e) {
        throw new UnsupportedOperationException("ParallelControllerSampler is executed by JMeterThread");
    }
}
