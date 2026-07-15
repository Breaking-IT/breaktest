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
import java.util.IdentityHashMap;
import java.util.List;

import org.apache.jmeter.engine.TreeCloner;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.schema.PropertiesAccessor;

/**
 * Executes all samplers below this controller concurrently within the current
 * virtual user.
 */
public class ParallelController extends GenericController implements Serializable, LoopIterationListener {
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
    public void triggerEndOfLoop() {
        samplerReturned = false;
        super.triggerEndOfLoop();
    }

    @Override
    public void iterationStart(LoopIterationEvent iterEvent) {
        samplerReturned = false;
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

        if (getSubControllers().isEmpty()) {
            return null;
        }

        samplerReturned = true;
        IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers =
                new IdentityHashMap<>();
        return new ParallelControllerSampler(
                this,
                getName(),
                getMaxParallel(),
                createParallelBranches(sourceTransactionControllers),
                sourceTransactionControllers);
    }

    private List<Controller> createParallelBranches(
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        List<Controller> branches = new ArrayList<>();
        for (TestElement child : getSubControllers()) {
            branches.add(createParallelBranch(child, sourceTransactionControllers));
        }
        return branches;
    }

    private static Controller createParallelBranch(TestElement child,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        if (child instanceof GenericController controller) {
            GenericController clone = cloneController(controller, sourceTransactionControllers, null);
            clone.initialize();
            return clone;
        }
        GenericController branch = new GenericController();
        branch.setName(child.getName());
        addParallelChild(branch, child, sourceTransactionControllers);
        branch.initialize();
        return branch;
    }

    private static GenericController cloneController(GenericController controller,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers,
            IdentityHashMap<Sampler, Sampler> sourceSamplers) {
        GenericController clone = (GenericController) controller.clone();
        if (clone instanceof TransactionController parallelTransactionController
                && controller instanceof TransactionController sourceTransactionController) {
            sourceTransactionControllers.put(parallelTransactionController, sourceTransactionController);
            parallelTransactionController.setSourceController(sourceTransactionController);
        }
        for (TestElement nestedChild : controller.getSubControllers()) {
            addParallelChild(clone, nestedChild, sourceTransactionControllers, sourceSamplers);
        }
        return clone;
    }

    /**
     * Adds a child to a synthetic parallel branch using the same cloning and
     * transaction-source tracking rules as {@link ParallelController}. The child subtree is
     * assumed to run in exactly one branch, so samplers are shared, not cloned.
     */
    public static void addParallelChild(GenericController parent, TestElement child,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        addParallelChild(parent, child, sourceTransactionControllers, null);
    }

    /**
     * Adds a child to a synthetic parallel branch. When {@code sourceSamplers} is non-null the
     * child subtree is replicated into every branch (ForEach parallel mode), so samplers are
     * cloned per branch — several branches executing the same sampler instance concurrently
     * would race on its merged config properties — and each clone is mapped back to its source
     * so the compiled sample package can be resolved.
     */
    public static void addParallelChild(GenericController parent, TestElement child,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers,
            IdentityHashMap<Sampler, Sampler> sourceSamplers) {
        TestElement parallelChild = parallelChild(child, sourceTransactionControllers, sourceSamplers);
        parent.addTestElement(parallelChild);
        if (parallelChild instanceof LoopIterationListener listener) {
            parent.addIterationListener(listener);
        }
    }

    private static TestElement parallelChild(TestElement child,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers,
            IdentityHashMap<Sampler, Sampler> sourceSamplers) {
        if (child instanceof GenericController controller) {
            return cloneController(controller, sourceTransactionControllers, sourceSamplers);
        }
        if (sourceSamplers != null && child instanceof Sampler sampler && !(child instanceof NoThreadClone)) {
            TestElement clonedChild = TreeCloner.cloneTestElement(sampler);
            if (clonedChild instanceof Sampler clonedSampler) {
                sourceSamplers.put(clonedSampler, sampler);
                return clonedSampler;
            }
        }
        return child;
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
