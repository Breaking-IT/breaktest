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

import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;

/**
 * Starts the child flow on a detached worker for the current virtual user and
 * lets the main flow continue immediately.
 */
public class ForkController extends GenericController implements Serializable {
    private static final long serialVersionUID = 240L;

    public enum IterationEndAction {
        IMMEDIATE, GRACEFUL, WAIT, KEEP_RUNNING
    }

    public enum RunningAction {
        SKIP, RESTART, WAIT
    }

    public enum FinalStopAction {
        GRACEFUL, IMMEDIATE
    }

    private static final String ITERATION_END_ACTION = "ForkController.iteration_end_action";
    private static final String RUNNING_ACTION = "ForkController.running_action";
    private static final String FINAL_STOP_ACTION = "ForkController.final_stop_action";

    private transient boolean samplerReturned;

    /** Missing policy properties identify a plan saved before lifecycle options existed. */
    public boolean hasLifecyclePolicy() {
        return !getPropertyAsString(ITERATION_END_ACTION, "").isEmpty();
    }

    public IterationEndAction getIterationEndAction() {
        return IterationEndAction.valueOf(getPropertyAsString(ITERATION_END_ACTION, IterationEndAction.KEEP_RUNNING.name()));
    }

    public void setIterationEndAction(IterationEndAction action) {
        setProperty(ITERATION_END_ACTION, action.name());
    }

    public RunningAction getRunningAction() {
        return RunningAction.valueOf(getPropertyAsString(RUNNING_ACTION, RunningAction.WAIT.name()));
    }

    public void setRunningAction(RunningAction action) {
        setProperty(RUNNING_ACTION, action.name());
    }

    public FinalStopAction getFinalStopAction() {
        return FinalStopAction.valueOf(getPropertyAsString(FINAL_STOP_ACTION, FinalStopAction.GRACEFUL.name()));
    }

    public void setFinalStopAction(FinalStopAction action) {
        setProperty(FINAL_STOP_ACTION, action.name());
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
        return new ForkControllerSampler(this, getName(), createForkExecutionController());
    }

    private Controller createForkExecutionController() {
        GenericController controller = new GenericController();
        controller.setName(getName());
        for (TestElement child : getSubControllers()) {
            addForkChild(controller, child);
        }
        controller.initialize();
        return controller;
    }

    private static void addForkChild(GenericController parent, TestElement child) {
        TestElement forkChild = forkChild(child);
        parent.addTestElement(forkChild);
        if (forkChild instanceof LoopIterationListener listener) {
            parent.addIterationListener(listener);
        }
    }

    private static TestElement forkChild(TestElement child) {
        if (!(child instanceof GenericController controller)) {
            return child;
        }
        GenericController clone = (GenericController) controller.clone();
        if (clone instanceof TransactionController forkTransactionController
                && controller instanceof TransactionController sourceTransactionController) {
            forkTransactionController.setSourceController(sourceTransactionController);
        }
        for (TestElement nestedChild : controller.getSubControllers()) {
            addForkChild(clone, nestedChild);
        }
        return clone;
    }
}
