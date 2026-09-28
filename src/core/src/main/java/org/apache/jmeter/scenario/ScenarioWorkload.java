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
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
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

    // Typed access to the settings, for callers that build or read scenarios without a thread group editor, such as
    // other plan formats and the AI agent. Values are strings where the editor accepts expressions such as ${users}.

    private static final String PACING_MODE = "ThreadGroup.pacingMode"; // $NON-NLS-1$
    private static final String FIXED_PACING = "ThreadGroup.fixedPacing"; // $NON-NLS-1$
    private static final String PACING_MIN = "ThreadGroup.pacingMin"; // $NON-NLS-1$
    private static final String PACING_MAX = "ThreadGroup.pacingMax"; // $NON-NLS-1$

    /** @return {@link ThreadGroup#MODEL_CLOSED} (a number of threads) or {@link ThreadGroup#MODEL_OPEN} (arrivals) */
    public String getModel() {
        return ThreadGroup.MODEL_OPEN.equals(getPropertyAsString(ThreadGroup.MODEL))
                ? ThreadGroup.MODEL_OPEN
                : ThreadGroup.MODEL_CLOSED;
    }

    public void setModel(String model) {
        setProperty(ThreadGroup.MODEL, ThreadGroup.MODEL_OPEN.equals(model) ? ThreadGroup.MODEL_OPEN : ThreadGroup.MODEL_CLOSED);
    }

    /** @return the number of threads of the closed model */
    public String getThreads() {
        return getPropertyAsString(AbstractThreadGroup.NUM_THREADS);
    }

    public void setThreads(String threads) {
        setProperty(AbstractThreadGroup.NUM_THREADS, threads);
    }

    /** @return the ramp-up period in seconds */
    public String getRampUp() {
        return getPropertyAsString(ThreadGroup.RAMP_TIME);
    }

    public void setRampUp(String seconds) {
        setProperty(ThreadGroup.RAMP_TIME, seconds);
    }

    /** @return how long the thread group runs, in seconds, or an empty string when it runs until its loops end */
    public String getDuration() {
        return getPropertyAsBoolean(ThreadGroup.SCHEDULER) ? getPropertyAsString(ThreadGroup.DURATION) : "";
    }

    /** @param seconds how long the thread group runs, or an empty string to run until its loops end */
    public void setDuration(String seconds) {
        boolean limited = seconds != null && !seconds.isBlank();
        setProperty(ThreadGroup.SCHEDULER, limited);
        if (limited) {
            setProperty(ThreadGroup.DURATION, seconds);
        } else {
            removeProperty(ThreadGroup.DURATION);
        }
    }

    /** @return the startup delay in seconds */
    public String getDelay() {
        return getPropertyAsString(ThreadGroup.DELAY);
    }

    public void setDelay(String seconds) {
        setProperty(ThreadGroup.DELAY, seconds);
    }

    /** @return whether threads are only created when they start */
    public boolean isDelayedStart() {
        return getPropertyAsBoolean(ThreadGroup.DELAYED_START);
    }

    public void setDelayedStart(boolean delayedStart) {
        setProperty(ThreadGroup.DELAYED_START, delayedStart);
    }

    /** @return the iterations per thread, {@code -1} for forever */
    public String getLoops() {
        return getProperty(AbstractThreadGroup.MAIN_CONTROLLER).getObjectValue() instanceof LoopController loopController
                ? loopController.getPropertyAsString(LoopController.LOOPS)
                : "";
    }

    /** @param loops the iterations per thread, {@code -1} for forever */
    public void setLoops(String loops) {
        LoopController loopController;
        if (getProperty(AbstractThreadGroup.MAIN_CONTROLLER).getObjectValue() instanceof LoopController existing) {
            loopController = existing;
        } else {
            loopController = new LoopController();
            loopController.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.LoopControlPanel"); // $NON-NLS-1$
            loopController.setProperty(TestElement.TEST_CLASS, LoopController.class.getName());
            setProperty(new TestElementProperty(AbstractThreadGroup.MAIN_CONTROLLER, loopController));
        }
        loopController.setProperty(LoopController.LOOPS, loops);
        loopController.setContinueForever(false);
    }

    /** @return the closed model phases, such as {@code threadsPhase(10, 60)}, or an empty string for a fixed load */
    public String getClosedSchedule() {
        return ThreadGroup.CLOSED_MODEL_MODE_CUSTOM.equals(getPropertyAsString(ThreadGroup.CLOSED_MODEL_MODE))
                ? getPropertyAsString(ThreadGroup.CLOSED_MODEL_SCHEDULE)
                : "";
    }

    /** @param schedule closed model phases, or an empty string for a fixed number of threads */
    public void setClosedSchedule(String schedule) {
        boolean custom = schedule != null && !schedule.isBlank();
        setProperty(ThreadGroup.CLOSED_MODEL_MODE,
                custom ? ThreadGroup.CLOSED_MODEL_MODE_CUSTOM : ThreadGroup.CLOSED_MODEL_MODE_STANDARD);
        setProperty(ThreadGroup.CLOSED_MODEL_SCHEDULE, custom ? schedule : "");
    }

    /** @return the open model arrival schedule, such as {@code rate(10/min) random_arrivals(10 min)} */
    public String getOpenSchedule() {
        return getPropertyAsString(ThreadGroup.OPEN_MODEL_SCHEDULE);
    }

    public void setOpenSchedule(String schedule) {
        setProperty(ThreadGroup.OPEN_MODEL_SCHEDULE, schedule);
    }

    /** @return the maximum active threads of the open model; empty or 0 is unlimited */
    public String getOpenMaxThreads() {
        return getPropertyAsString(ThreadGroup.OPEN_MODEL_MAX_THREADS);
    }

    public void setOpenMaxThreads(String maxThreads) {
        setProperty(ThreadGroup.OPEN_MODEL_MAX_THREADS, maxThreads);
    }

    /** @return {@link AbstractThreadGroup#PACING_DISABLED}, {@code PACING_FIXED}, {@code PACING_RANDOM} or {@code PACING_GAUSSIAN_RANDOM} */
    public String getPacingMode() {
        String mode = getPropertyAsString(PACING_MODE);
        return mode.isEmpty() ? AbstractThreadGroup.PACING_DISABLED : mode;
    }

    /** @return the fixed time between iteration starts of a thread, in milliseconds */
    public String getFixedPacing() {
        return getPropertyAsString(FIXED_PACING);
    }

    /** @return the minimum random time between iteration starts of a thread, in milliseconds */
    public String getPacingMin() {
        return getPropertyAsString(PACING_MIN);
    }

    /** @return the maximum random time between iteration starts of a thread, in milliseconds */
    public String getPacingMax() {
        return getPropertyAsString(PACING_MAX);
    }

    /** @param millis the fixed time between iteration starts of a thread */
    public void setFixedPacing(String millis) {
        setProperty(PACING_MODE, AbstractThreadGroup.PACING_FIXED);
        setProperty(FIXED_PACING, millis);
    }

    /**
     * @param minMillis the minimum time between iteration starts of a thread
     * @param maxMillis the maximum time between iteration starts of a thread
     * @param gaussian whether the times follow a Gaussian distribution rather than a uniform one
     */
    public void setRandomPacing(String minMillis, String maxMillis, boolean gaussian) {
        setProperty(PACING_MODE, gaussian ? AbstractThreadGroup.PACING_GAUSSIAN_RANDOM : AbstractThreadGroup.PACING_RANDOM);
        setProperty(PACING_MIN, minMillis);
        setProperty(PACING_MAX, maxMillis);
    }

    public void disablePacing() {
        setProperty(PACING_MODE, AbstractThreadGroup.PACING_DISABLED);
    }

    /** @return what a thread does after a sampler error, one of the {@code AbstractThreadGroup.ON_SAMPLE_ERROR_*} values */
    public String getOnSampleError() {
        String action = getPropertyAsString(AbstractThreadGroup.ON_SAMPLE_ERROR);
        return action.isEmpty() ? AbstractThreadGroup.ON_SAMPLE_ERROR_CONTINUE : action;
    }

    public void setOnSampleError(String action) {
        setProperty(AbstractThreadGroup.ON_SAMPLE_ERROR, action);
    }

    /** @return whether each iteration of a thread continues as the same user, keeping cookies and caches */
    public boolean isSameUserOnEachIteration() {
        return getPropertyAsBoolean(AbstractThreadGroup.IS_SAME_USER_ON_NEXT_ITERATION, true);
    }

    public void setSameUserOnEachIteration(boolean sameUser) {
        setProperty(AbstractThreadGroup.IS_SAME_USER_ON_NEXT_ITERATION, sameUser);
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
