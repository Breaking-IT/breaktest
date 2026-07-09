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

import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
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
            GenericController clone = cloneController(controller, sourceTransactionControllers);
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
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        GenericController clone = (GenericController) controller.clone();
        if (clone instanceof TransactionController parallelTransactionController
                && controller instanceof TransactionController sourceTransactionController) {
            sourceTransactionControllers.put(parallelTransactionController, sourceTransactionController);
        }
        for (TestElement nestedChild : controller.getSubControllers()) {
            addParallelChild(clone, nestedChild, sourceTransactionControllers);
        }
        return clone;
    }

    /**
     * Adds a child to a synthetic parallel branch using the same cloning and
     * transaction-source tracking rules as {@link ParallelController}.
     */
    public static void addParallelChild(GenericController parent, TestElement child,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        TestElement parallelChild = parallelChild(child, sourceTransactionControllers);
        parent.addTestElement(parallelChild);
        if (parallelChild instanceof LoopIterationListener listener) {
            parent.addIterationListener(listener);
        }
    }

    private static TestElement parallelChild(TestElement child,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        if (!(child instanceof GenericController controller)) {
            return child;
        }
        return cloneController(controller, sourceTransactionControllers);
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
