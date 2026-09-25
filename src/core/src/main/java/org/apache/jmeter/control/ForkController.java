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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the child flow on a detached worker for the current virtual user and
 * lets the main flow continue immediately.
 */
public class ForkController extends GenericController implements Serializable {
    private static final long serialVersionUID = 240L;

    private static final Logger log = LoggerFactory.getLogger(ForkController.class);

    public enum IterationEndAction {
        LEGACY, IMMEDIATE, GRACEFUL, WAIT, KEEP_RUNNING
    }

    public enum RunningAction {
        SKIP, RESTART, WAIT
    }

    public enum ErrorAction {
        CONTINUE, STOP_FORK, END_ITERATION_GRACEFUL, END_ITERATION_IMMEDIATE
    }

    public enum FinalStopAction {
        GRACEFUL, IMMEDIATE
    }

    private static final String ITERATION_END_ACTION = "ForkController.iteration_end_action";
    private static final String RUNNING_ACTION = "ForkController.running_action";
    private static final String ERROR_ACTION = "ForkController.error_action";
    private static final String FINAL_STOP_ACTION = "ForkController.final_stop_action";

    private transient boolean samplerReturned;
    private transient ForkController sourceController;
    private transient Set<String> warnedOptions;

    void setSourceController(ForkController source) {
        sourceController = source.sourceController == null ? source : source.sourceController;
    }

    /** Missing policy properties identify a plan saved before lifecycle options existed. */
    public boolean hasLifecyclePolicy() {
        return getIterationEndAction() != IterationEndAction.LEGACY;
    }

    public IterationEndAction getIterationEndAction() {
        return option(ITERATION_END_ACTION, IterationEndAction.LEGACY);
    }

    public void setIterationEndAction(IterationEndAction action) {
        if (action == IterationEndAction.LEGACY) {
            removeProperty(ITERATION_END_ACTION);
        } else {
            setProperty(ITERATION_END_ACTION, action.name());
        }
    }

    public RunningAction getRunningAction() {
        return option(RUNNING_ACTION, RunningAction.WAIT);
    }

    public void setRunningAction(RunningAction action) {
        setProperty(RUNNING_ACTION, action.name());
    }

    public FinalStopAction getFinalStopAction() {
        return option(FINAL_STOP_ACTION, FinalStopAction.GRACEFUL);
    }

    public void setFinalStopAction(FinalStopAction action) {
        setProperty(FINAL_STOP_ACTION, action.name());
    }

    public ErrorAction getErrorAction() {
        // Preserve plans saved with the initial names of these options.
        return switch (getPropertyAsString(ERROR_ACTION)) {
            case "STOP_USER_GRACEFUL" -> ErrorAction.END_ITERATION_GRACEFUL;
            case "STOP_USER_IMMEDIATE" -> ErrorAction.END_ITERATION_IMMEDIATE;
            default -> option(ERROR_ACTION, ErrorAction.CONTINUE);
        };
    }

    public void setErrorAction(ErrorAction action) {
        setProperty(ERROR_ACTION, action.name());
    }

    private <T extends Enum<T>> T option(String property, T fallback) {
        String value = getPropertyAsString(property, fallback.name());
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value);
        } catch (IllegalArgumentException e) {
            warnUnknownOption(property, value, fallback);
            return fallback;
        }
    }

    private synchronized void warnUnknownOption(String property, String value, Enum<?> fallback) {
        if (warnedOptions == null) {
            warnedOptions = ConcurrentHashMap.newKeySet();
        }
        if (warnedOptions.add(property)) {
            log.warn("Unknown fork option {}={} on {}; using {}", property, value, getName(), fallback);
        }
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
        return new ForkControllerSampler(sourceController == null ? this : sourceController, getName(), createForkExecutionController());
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
        if (clone instanceof ForkController fork && controller instanceof ForkController source) {
            fork.setSourceController(source);
        }
        for (TestElement nestedChild : controller.getSubControllers()) {
            addForkChild(clone, nestedChild);
        }
        return clone;
    }
}
