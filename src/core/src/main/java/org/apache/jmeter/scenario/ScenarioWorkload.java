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


package org.apache.jmeter.scenario;

import java.io.Serializable;
import java.util.List;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;

/**
 * Runs one thread group inside a {@link Scenario} with its own settings: thread model, schedule, duration, pacing,
 * error handling and whether each iteration is a new user. Workloads are stored in their scenario, not in the tree.
 * <p>
 * The workload is stored with the same property names a thread group uses, so it can be copied onto a clone of the
 * referenced thread group at run time without changing how thread groups execute.
 */
public class ScenarioWorkload extends AbstractTestElement implements Serializable {
    private static final long serialVersionUID = 1L;

    /** {@link AbstractThreadGroup#getThreadGroupId()} of the thread group this workload runs */
    public static final String THREAD_GROUP_ID = "ScenarioWorkload.thread_group_id"; // $NON-NLS-1$

    /** Name of the profile this thread group runs with; may be an expression such as {@code ${__P(profile)}} */
    public static final String PROFILE = "ScenarioWorkload.profile"; // $NON-NLS-1$

    /** Properties that describe how a thread group is run rather than the script, and therefore belong to the scenario. */
    public static final List<String> WORKLOAD_PROPERTIES = List.of(
            AbstractThreadGroup.NUM_THREADS,
            AbstractThreadGroup.MAIN_CONTROLLER,
            AbstractThreadGroup.ON_SAMPLE_ERROR,
            AbstractThreadGroup.IS_SAME_USER_ON_NEXT_ITERATION,
            "ThreadGroup.pacingMode", // $NON-NLS-1$
            "ThreadGroup.fixedPacing", // $NON-NLS-1$
            "ThreadGroup.pacingMin", // $NON-NLS-1$
            "ThreadGroup.pacingMax", // $NON-NLS-1$
            ThreadGroup.RAMP_TIME,
            ThreadGroup.DELAYED_START,
            ThreadGroup.SCHEDULER,
            ThreadGroup.DURATION,
            ThreadGroup.DELAY,
            ThreadGroup.MODEL,
            ThreadGroup.CLOSED_MODEL_MODE,
            ThreadGroup.CLOSED_MODEL_SCHEDULE,
            ThreadGroup.OPEN_MODEL_SCHEDULE,
            ThreadGroup.OPEN_MODEL_RANDOM_SEED,
            ThreadGroup.OPEN_MODEL_MAX_THREADS,
            ThreadGroup.OPEN_MODEL_MAX_THREADS_SCOPE);

    public ScenarioWorkload() {
        super();
    }

    public String getThreadGroupId() {
        return getPropertyAsString(THREAD_GROUP_ID);
    }

    public void setThreadGroupId(String id) {
        setProperty(THREAD_GROUP_ID, id);
    }

    /**
     * @return the profile name expression, empty when the thread group runs with the shared configuration only
     */
    public String getProfile() {
        return getPropertyAsString(PROFILE);
    }

    public void setProfile(String profile) {
        setProperty(PROFILE, profile, "");
    }

    /**
     * Copies the workload properties of the given thread group into this workload, replacing the current ones.
     * @param threadGroup the thread group to read the workload from
     */
    public void copyWorkloadFrom(AbstractThreadGroup threadGroup) {
        copyWorkloadProperties(threadGroup, this);
    }

    /**
     * Replaces the workload properties of the given thread group with the ones of this workload.
     * Properties this workload does not set are removed, so the thread group falls back to its defaults.
     * @param threadGroup the thread group to configure, normally a run-time clone
     */
    public void applyTo(AbstractThreadGroup threadGroup) {
        copyWorkloadProperties(this, threadGroup);
        Object mainController = threadGroup.getProperty(AbstractThreadGroup.MAIN_CONTROLLER).getObjectValue();
        if (mainController instanceof LoopController loopController) {
            // A thread group's loop controller must end after its iterations, as setSamplerController enforces
            threadGroup.setSamplerController(loopController);
        } else if (mainController == null) {
            ensureMainController(threadGroup);
        }
    }

    /**
     * Gives a thread group without a main controller one that runs a single iteration.
     * @param threadGroup the thread group to complete
     */
    public static void ensureMainController(AbstractThreadGroup threadGroup) {
        if (threadGroup.getProperty(AbstractThreadGroup.MAIN_CONTROLLER).getObjectValue() == null) {
            LoopController loopController = new LoopController();
            loopController.setLoops(1);
            threadGroup.setSamplerController(loopController);
        }
    }

    /**
     * Removes the workload from a thread group whose workload is defined by scenarios.
     * The thread group keeps a single-iteration loop controller, used when it is validated on its own.
     * @param threadGroup the thread group to clean up
     */
    public static void removeWorkload(AbstractThreadGroup threadGroup) {
        if (usesOwnSettings(threadGroup)) {
            return;
        }
        for (String name : WORKLOAD_PROPERTIES) {
            threadGroup.removeProperty(name);
        }
        ensureMainController(threadGroup);
    }

    /**
     * Scenarios set the workload of BreakTest's own thread groups. Other thread groups, such as those of plugins,
     * keep the settings they were configured with: a scenario only decides whether they run.
     * @param threadGroup a thread group
     * @return whether the thread group keeps its own settings
     */
    public static boolean usesOwnSettings(AbstractThreadGroup threadGroup) {
        return !(threadGroup instanceof ThreadGroup);
    }

    private static void copyWorkloadProperties(AbstractTestElement source, AbstractTestElement target) {
        for (String name : WORKLOAD_PROPERTIES) {
            JMeterProperty property = source.getProperty(name);
            if (property instanceof NullProperty) {
                target.removeProperty(name);
            } else {
                target.setProperty(property.clone());
            }
        }
    }
}
