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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.schema.PropertiesAccessor;

/**
 * Executes all samplers below this controller concurrently within the current
 * virtual user.
 */
public class ParallelController extends GenericController implements Serializable {
    private static final long serialVersionUID = 240L;

    private transient boolean samplerReturned;

    @Override
    public ParallelControllerSchema getSchema() {
        return ParallelControllerSchema.INSTANCE;
    }

    @Override
    public PropertiesAccessor<? extends ParallelController, ? extends ParallelControllerSchema> getProps() {
        return new PropertiesAccessor<>(this, getSchema());
    }

    @Override
    public void initialize() {
        samplerReturned = false;
        super.initialize();
    }

    @Override
    public Sampler next() {
        if (samplerReturned) {
            // The marker has already been handed out for this pass. Return null
            // without marking the controller done, so the parent controller keeps
            // it in the tree (a done child would be removed) and re-runs it on the
            // next iteration.
            samplerReturned = false;
            return null;
        }

        List<Sampler> samplers = new ArrayList<>();
        Sampler sampler;
        // Draining super.next() collects every sampler of a single pass; the last
        // call hits nextIsNull() which already reInitialises this controller.
        while ((sampler = super.next()) != null) {
            samplers.add(sampler);
        }
        if (samplers.isEmpty()) {
            return null;
        }

        samplerReturned = true;
        return new ParallelControllerSampler(getName(), getMaxParallel(), samplers);
    }

    public int getMaxParallel() {
        try {
            return Math.max(1, get(getSchema().getMaxParallel()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public String getMaxParallelString() {
        return getString(getSchema().getMaxParallel());
    }

    public void setMaxParallel(int maxParallel) {
        set(getSchema().getMaxParallel(), Math.max(1, maxParallel));
    }

    public void setMaxParallel(String maxParallel) {
        set(getSchema().getMaxParallel(), maxParallel);
    }
}
