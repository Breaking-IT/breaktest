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
import java.util.IdentityHashMap;

import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;

/**
 * Starts the child flow on a detached worker for the current virtual user and
 * lets the main flow continue immediately.
 */
public class ForkController extends GenericController implements Serializable {
    private static final long serialVersionUID = 240L;

    private transient boolean samplerReturned;

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
    public Sampler next() {
        if (samplerReturned) {
            samplerReturned = false;
            return null;
        }

        if (getSubControllers().isEmpty()) {
            setDone(true);
            return null;
        }

        samplerReturned = true;
        IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers =
                new IdentityHashMap<>();
        return new ForkControllerSampler(
                this,
                getName(),
                createForkExecutionController(sourceTransactionControllers),
                sourceTransactionControllers);
    }

    private Controller createForkExecutionController(
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        GenericController controller = new GenericController();
        controller.setName(getName());
        for (TestElement child : getSubControllers()) {
            addForkChild(controller, child, sourceTransactionControllers);
        }
        controller.initialize();
        return controller;
    }

    private static void addForkChild(GenericController parent, TestElement child,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        TestElement forkChild = forkChild(child, sourceTransactionControllers);
        parent.addTestElement(forkChild);
        if (forkChild instanceof LoopIterationListener listener) {
            parent.addIterationListener(listener);
        }
    }

    private static TestElement forkChild(TestElement child,
            IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers) {
        if (!(child instanceof GenericController controller)) {
            return child;
        }
        GenericController clone = (GenericController) controller.clone();
        if (clone instanceof TransactionController forkTransactionController
                && controller instanceof TransactionController sourceTransactionController) {
            sourceTransactionControllers.put(forkTransactionController, sourceTransactionController);
            forkTransactionController.setSourceController(sourceTransactionController);
        }
        for (TestElement nestedChild : controller.getSubControllers()) {
            addForkChild(clone, nestedChild, sourceTransactionControllers);
        }
        return clone;
    }
}
