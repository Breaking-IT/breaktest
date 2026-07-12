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

package org.apache.jmeter.threads;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.schema.PropertiesAccessor;
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroupController;
import org.apache.jmeter.threads.openmodel.ThreadSchedule;
import org.apache.jmeter.threads.openmodel.ThreadScheduleProcessGenerator;
import org.apache.jmeter.threads.openmodel.ThreadScheduleUtils;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.util.JMeterStopTestException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ThreadGroup holds the settings for a JMeter thread group.
 *
 * This class is intended to be ThreadSafe.
 */
@GUIMenuSortOrder(1)
@SuppressWarnings("JavaLangClash")
public class ThreadGroup extends AbstractThreadGroup {
    private static final long serialVersionUID = 282L;

    private static final Logger log = LoggerFactory.getLogger(ThreadGroup.class);

    private static final long WAIT_TO_DIE = DEFAULT_THREAD_STOP_TIMEOUT.toMillis();

    /** How often to check for shutdown during ramp-up, default 1000ms */
    private static final int RAMPUP_GRANULARITY =
            JMeterUtils.getPropDefault("jmeterthread.rampup.granularity", 1000); // $NON-NLS-1$

    /** Whether to use Java 21 Virtual Threads for JMeter threads */
    private static final boolean VIRTUAL_THREADS_ENABLED =
            JMeterUtils.getPropDefault("breaktest.threads.virtual.enabled", // $NON-NLS-1$
                    JMeterUtils.getPropDefault("jmeter.threads.virtual.enabled", true)); // $NON-NLS-1$

    //+ JMX entries - do not change the string values

    /** Ramp-up time */
    public static final String RAMP_TIME = "ThreadGroup.ramp_time";

    /** Whether thread startup is delayed until required */
    public static final String DELAYED_START = "ThreadGroup.delayedStart";

    /** Whether scheduler is being used */
    public static final String SCHEDULER = "ThreadGroup.scheduler";

    /** Scheduler duration, overrides end time */
    public static final String DURATION = "ThreadGroup.duration";

    /** Scheduler start delay, overrides start time */
    public static final String DELAY = "ThreadGroup.delay";

    /** Thread group execution model */
    public static final String MODEL = "ThreadGroup.model";

    /** Closed workload model */
    public static final String MODEL_CLOSED = "Closed";

    /** Open workload model */
    public static final String MODEL_OPEN = "Open";

    /** Closed model phase schedule expression */
    public static final String CLOSED_MODEL_SCHEDULE = "ThreadGroup.closed_schedule";

    /** Open model schedule expression */
    public static final String OPEN_MODEL_SCHEDULE = "OpenModelThreadGroup.schedule";

    /** Open model random seed */
    public static final String OPEN_MODEL_RANDOM_SEED = "OpenModelThreadGroup.random_seed";

    /** Open model maximum active threads; blank or 0 means unlimited */
    public static final String OPEN_MODEL_MAX_THREADS = "OpenModelThreadGroup.max_threads";

    /** Open model maximum active threads scope */
    public static final String OPEN_MODEL_MAX_THREADS_SCOPE = "OpenModelThreadGroup.max_threads_scope";

    /** Apply open model maximum active threads to this thread group */
    public static final String OPEN_MODEL_MAX_THREADS_SCOPE_THREAD_GROUP = "ThreadGroup";

    /** Apply open model maximum active threads across all open model thread groups */
    public static final String OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL = "AllOpenModelThreadGroups";
    //- JMX entries

    /**
     * A thread pool for Open Model thread starter threads.
     * It is not re-created across JMeter test restart.
     */
    private static final ExecutorService OPEN_MODEL_HOUSEKEEPING_THREAD_POOL = Executors.newCachedThreadPool();

    /** Counter for naming Open Model virtual threads */
    private static final AtomicLong OPEN_MODEL_VIRTUAL_THREAD_COUNTER = new AtomicLong();

    /** Total active threads started by unified Open Model ThreadGroups */
    private static final AtomicInteger OPEN_MODEL_ACTIVE_THREAD_COUNT = new AtomicInteger();

    private static final Pattern CLOSED_MODEL_PHASE_PATTERN = Pattern.compile(
            "threadsPhase\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private transient Thread threadStarter;

    // List of active threads
    private final ConcurrentHashMap<JMeterThread, Thread> allThreads = new ConcurrentHashMap<>();

    private transient ExecutorService openModelExecutorService;

    @SuppressWarnings("FieldCanBeFinal")
    private transient AtomicReference<Future<?>> openModelThreadStarterFuture = new AtomicReference<>();

    private final ConcurrentHashMap<JMeterThread, Future<?>> openModelActiveThreads = new ConcurrentHashMap<>();

    private transient volatile ClosedModelScheduleCache closedModelScheduleCache;

    private transient Object addThreadLock = new Object();

    /** Is test (still) running? */
    private volatile boolean running = false;

    /** Thread Group number */
    private int groupNumber;

    /** Are we using delayed startup? */
    private boolean delayedStartup;

    /** Thread safe class */
    private ListenerNotifier notifier;

    /** This property will be cloned */
    private ListedHashTree threadGroupTree;

    /**
     * No-arg constructor.
     */
    public ThreadGroup() {
        super();
    }

    @Override
    public ThreadGroupSchema getSchema() {
        return ThreadGroupSchema.INSTANCE;
    }

    @Override
    public @NotNull PropertiesAccessor<? extends ThreadGroup, ? extends ThreadGroupSchema> getProps() {
        return new PropertiesAccessor<>(this, getSchema());
    }

    /**
     * Set whether scheduler is being used
     *
     * @param scheduler true is scheduler is to be used
     */
    public void setScheduler(boolean scheduler) {
        setProperty(new BooleanProperty(SCHEDULER, scheduler));
    }

    public void setThreadGroupModel(String model) {
        setProperty(MODEL, MODEL_OPEN.equals(model) ? MODEL_OPEN : MODEL_CLOSED);
    }

    public String getThreadGroupModel() {
        String model = getPropertyAsString(MODEL);
        return MODEL_OPEN.equals(model) ? MODEL_OPEN : MODEL_CLOSED;
    }

    public boolean isOpenModel() {
        return MODEL_OPEN.equals(getThreadGroupModel());
    }

    @Override
    public int getNumThreads() {
        if (isOpenModel()) {
            return 0;
        }
        ClosedModelScheduleCache schedule = getClosedModelScheduleCache();
        List<ClosedModelPhase> phases = schedule.phases();
        if (phases.isEmpty()) {
            return super.getNumThreads();
        }
        return schedule.maxThreads();
    }

    public String getClosedModelSchedule() {
        return getPropertyAsString(CLOSED_MODEL_SCHEDULE);
    }

    public void setClosedModelSchedule(String schedule) {
        closedModelScheduleCache = null;
        setProperty(CLOSED_MODEL_SCHEDULE, schedule == null ? "" : schedule);
    }

    public String getOpenModelSchedule() {
        return getPropertyAsString(OPEN_MODEL_SCHEDULE);
    }

    public void setOpenModelSchedule(String schedule) {
        setProperty(OPEN_MODEL_SCHEDULE, schedule == null ? "" : schedule);
    }

    public long getOpenModelRandomSeed() {
        return getPropertyAsLong(OPEN_MODEL_RANDOM_SEED);
    }

    public String getOpenModelRandomSeedString() {
        return getPropertyAsString(OPEN_MODEL_RANDOM_SEED);
    }

    public void setOpenModelRandomSeedString(String randomSeed) {
        setProperty(OPEN_MODEL_RANDOM_SEED, randomSeed == null ? "0" : randomSeed);
    }

    public long getOpenModelMaxThreads() {
        String maxThreads = getOpenModelMaxThreadsString().trim();
        if (maxThreads.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(maxThreads);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String getOpenModelMaxThreadsString() {
        return getPropertyAsString(OPEN_MODEL_MAX_THREADS);
    }

    public void setOpenModelMaxThreadsString(String maxThreads) {
        setProperty(OPEN_MODEL_MAX_THREADS, maxThreads == null ? "" : maxThreads);
    }

    public String getOpenModelMaxThreadsScope() {
        return OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL.equals(getPropertyAsString(OPEN_MODEL_MAX_THREADS_SCOPE))
                ? OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL
                : OPEN_MODEL_MAX_THREADS_SCOPE_THREAD_GROUP;
    }

    public void setOpenModelMaxThreadsScope(String maxThreadsScope) {
        setProperty(OPEN_MODEL_MAX_THREADS_SCOPE,
                OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL.equals(maxThreadsScope)
                        ? OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL
                        : OPEN_MODEL_MAX_THREADS_SCOPE_THREAD_GROUP);
    }

    public static List<ClosedModelPhase> parseClosedModelSchedule(String schedule) {
        List<ClosedModelPhase> phases = new ArrayList<>();
        if (schedule == null || schedule.trim().isEmpty()) {
            return phases;
        }
        Matcher matcher = CLOSED_MODEL_PHASE_PATTERN.matcher(schedule);
        int position = 0;
        long previousTimeSeconds = 0;
        while (matcher.find()) {
            String betweenPhases = schedule.substring(position, matcher.start()).trim();
            if (!betweenPhases.isEmpty()) {
                throw new IllegalArgumentException("Invalid closed model schedule near: " + betweenPhases);
            }
            long targetThreads = Long.parseLong(matcher.group(1));
            long timeSeconds = Long.parseLong(matcher.group(2));
            if (!phases.isEmpty() && timeSeconds < previousTimeSeconds) {
                throw new IllegalArgumentException("Closed model phase times must not decrease");
            }
            phases.add(new ClosedModelPhase(targetThreads, timeSeconds));
            previousTimeSeconds = timeSeconds;
            position = matcher.end();
        }
        String trailingText = schedule.substring(position).trim();
        if (!trailingText.isEmpty()) {
            throw new IllegalArgumentException("Invalid closed model schedule near: " + trailingText);
        }
        return phases;
    }

    private ClosedModelScheduleCache getClosedModelScheduleCache() {
        String schedule = getClosedModelSchedule();
        ClosedModelScheduleCache cache = closedModelScheduleCache;
        if (cache != null && Objects.equals(schedule, cache.schedule())) {
            return cache;
        }
        ClosedModelScheduleCache parsed = parseClosedModelScheduleForRuntime(schedule);
        closedModelScheduleCache = parsed;
        return parsed;
    }

    private ClosedModelScheduleCache parseClosedModelScheduleForRuntime(String schedule) {
        try {
            List<ClosedModelPhase> phases = List.copyOf(parseClosedModelSchedule(schedule));
            return new ClosedModelScheduleCache(schedule, phases, maxClosedModelThreads(phases));
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring invalid closed model schedule for Thread Group '{}': {}", getName(), e.getMessage());
            return new ClosedModelScheduleCache(schedule, List.of(), 0);
        }
    }

    private static int maxClosedModelThreads(List<ClosedModelPhase> phases) {
        long maxThreads = 0;
        for (ClosedModelPhase phase : phases) {
            maxThreads = Math.max(maxThreads, phase.targetThreads());
        }
        return maxThreads > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) maxThreads;
    }

    /**
     * Get whether scheduler is being used
     *
     * @return true if scheduler is being used
     */
    public boolean getScheduler() {
        return getPropertyAsBoolean(SCHEDULER);
    }

    /**
     * Get the desired duration of the thread group test run
     *
     * @return the duration (in secs)
     */
    public long getDuration() {
        return getPropertyAsLong(DURATION);
    }

    /**
     * Set the desired duration of the thread group test run
     *
     * @param duration
     *            in seconds
     */
    public void setDuration(long duration) {
        setProperty(new LongProperty(DURATION, duration));
    }

    /**
     * Get the startup delay
     *
     * @return the delay (in secs)
     */
    public long getDelay() {
        return getPropertyAsLong(DELAY);
    }

    /**
     * Set the startup delay
     *
     * @param delay
     *            in seconds
     */
    public void setDelay(long delay) {
        setProperty(new LongProperty(DELAY, delay));
    }

    /**
     * Set the ramp-up value.
     *
     * @param rampUp
     *            the ramp-up value.
     */
    public void setRampUp(int rampUp) {
        setProperty(new IntegerProperty(RAMP_TIME, rampUp));
    }

    /**
     * Get the ramp-up value.
     *
     * @return the ramp-up value.
     */
    public int getRampUp() {
        return getPropertyAsInt(ThreadGroup.RAMP_TIME);
    }

    private boolean isDelayedStartup() {
        return get(getSchema().getDelayedStart());
    }

    private long getStartupDelayInMillis() {
        long delay = getDelay();
        if (delay < 0) {
            throw new JMeterStopTestException("Invalid delay " + delay + " set in Thread Group:" + getName());
        }
        return TimeUnit.SECONDS.toMillis(delay);
    }

    /**
     * This will schedule the time for the JMeterThread.
     *
     * @param thread JMeterThread
     * @param now in milliseconds
     */
    private void scheduleThread(JMeterThread thread, long now) {

        if (!getScheduler()) { // if the Scheduler is not enabled
            return;
        }

        if (getDelay() >= 0) { // Duration is in seconds
            thread.setStartTime(getDelay() * 1000 + now);
        } else {
            throw new JMeterStopTestException("Invalid delay " + getDelay() + " set in Thread Group:" + getName());
        }

        // set the endtime for the Thread
        if (getDuration() > 0) {// Duration is in seconds
            thread.setEndTime(getDuration() * 1000 + thread.getStartTime());
        } else {
            throw new JMeterStopTestException("Invalid duration " + getDuration() + " set in Thread Group:" + getName());
        }
        // Enables the scheduler
        thread.setScheduled(true);
    }

    @Override
    public void start(int groupNum, ListenerNotifier notifier, ListedHashTree threadGroupTree, StandardJMeterEngine engine) {
        if (isOpenModel()) {
            startOpenModel(groupNum, notifier, threadGroupTree, engine);
            return;
        }
        List<ClosedModelPhase> phases = getClosedModelScheduleCache().phases();
        if (!phases.isEmpty()) {
            startClosedModel(groupNum, notifier, threadGroupTree, engine, phases);
            return;
        }
        this.running = true;
        this.groupNumber = groupNum;
        this.notifier = notifier;
        this.threadGroupTree = threadGroupTree;
        int numThreads = getNumThreads();
        int rampUpPeriodInSeconds = getRampUp();
        delayedStartup = isDelayedStartup(); // Fetch once; needs to stay constant
        log.info("Starting thread group... number={} threads={} ramp-up={} delayedStart={}", groupNumber,
                numThreads, rampUpPeriodInSeconds, delayedStartup);
        if (delayedStartup) {
            threadStarter = new Thread(new ThreadStarter(notifier, threadGroupTree, engine), getName()+"-ThreadStarter");
            threadStarter.setDaemon(true);
            threadStarter.start();
            // N.B. we don't wait for the thread to complete, as that would prevent parallel TGs
        } else {
            final JMeterVariables variables = JMeterContextService.getContext().getVariables();
            long lastThreadStartInMillis = 0;
            int delayForNextThreadInMillis = 0;
            final int perThreadDelayInMillis = Math.round((float) rampUpPeriodInSeconds * 1000 / numThreads);
            final long startupDelayInMillis = getScheduler() ? 0 : getStartupDelayInMillis();
            for (int threadNum = 0; running && threadNum < numThreads; threadNum++) {
                long nowInMillis = System.currentTimeMillis();
                if(threadNum > 0) {
                    long timeElapsedToStartLastThread = nowInMillis - lastThreadStartInMillis;
                    // Note: `int += long` assignment hides lossy cast to int
                    delayForNextThreadInMillis = (int) (delayForNextThreadInMillis +
                            (perThreadDelayInMillis - timeElapsedToStartLastThread));
                }
                if (log.isDebugEnabled()) {
                    log.debug("Computed delayForNextThreadInMillis:{} for thread:{}", delayForNextThreadInMillis, Thread.currentThread().threadId());
                }
                lastThreadStartInMillis = nowInMillis;
                startNewThread(notifier, threadGroupTree, engine, threadNum, variables, nowInMillis,
                        toInitialDelay(startupDelayInMillis + Math.max(0, delayForNextThreadInMillis)));
            }
        }
        log.info("Started thread group number {}", groupNumber);
    }

    private static int toInitialDelay(long delayInMillis) {
        return delayInMillis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) delayInMillis;
    }

    private void startClosedModel(int threadGroupIndex, ListenerNotifier notifier,
            ListedHashTree threadGroupTree, StandardJMeterEngine engine, List<ClosedModelPhase> phases) {
        this.running = true;
        this.groupNumber = threadGroupIndex;
        this.notifier = notifier;
        this.threadGroupTree = threadGroupTree;
        delayedStartup = true;
        log.info("Starting Closed Model ThreadGroup#{} with phases {}", threadGroupIndex, getClosedModelSchedule());
        threadStarter = new Thread(
                new ClosedModelThreadsStarter(notifier, threadGroupTree, engine, phases),
                getName() + "-ClosedModelStarter");
        threadStarter.setDaemon(true);
        threadStarter.start();
        log.info("Started Closed Model thread group number {}", threadGroupIndex);
    }

    private void startOpenModel(int threadGroupIndex, ListenerNotifier notifier,
            ListedHashTree threadGroupTree, StandardJMeterEngine engine) {
        try {
            this.running = true;
            this.groupNumber = threadGroupIndex;
            this.notifier = notifier;
            this.threadGroupTree = threadGroupTree;
            setOpenModelController();
            JMeterVariables variables = JMeterContextService.getContext().getVariables();
            String schedule = getOpenModelSchedule();
            log.info("Starting Open Model ThreadGroup#{} with schedule {}", threadGroupIndex, schedule);
            ThreadSchedule parsedSchedule = ThreadScheduleUtils.ThreadSchedule(schedule);
            Random random = getOpenModelRandomSeed() == 0 ? new Random() : new Random(getOpenModelRandomSeed());
            ThreadScheduleProcessGenerator generator = new ThreadScheduleProcessGenerator(random, parsedSchedule);
            ExecutorService executorService = createOpenModelExecutorService();
            openModelExecutorService = executorService;
            OpenModelThreadsStarter starter = new OpenModelThreadsStarter(
                    getStartTime(), engine, executorService, generator, threadNumber -> {
                        ListedHashTree clonedTree = cloneTree(threadGroupTree);
                        return makeThread(engine, this, notifier, threadGroupIndex, threadNumber, clonedTree, variables);
                    });
            openModelThreadStarterFuture.set(OPEN_MODEL_HOUSEKEEPING_THREAD_POOL.submit(() -> {
                Thread.currentThread().setName("open-model-thread-starter-" + getName() + "-" + threadGroupIndex);
                starter.run();
            }));
        } catch (Throwable t) {
            t.addSuppressed(new IllegalArgumentException("Failed to start Open Model ThreadGroup "
                    + getName() + "-" + threadGroupIndex));
            if (t instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (t instanceof Error error) {
                throw error;
            }
            throw new IllegalArgumentException("Failed to start Open Model ThreadGroup "
                    + getName() + "-" + threadGroupIndex, t);
        }
    }

    private void setOpenModelController() {
        JMeterProperty mainController = getProperty(AbstractThreadGroup.MAIN_CONTROLLER);
        if (!(mainController.getObjectValue() instanceof OpenModelThreadGroupController)) {
            set(getSchema().getMainController(), new OpenModelThreadGroupController());
        }
    }

    private static ExecutorService createOpenModelExecutorService() {
        if (VIRTUAL_THREADS_ENABLED) {
            ThreadFactory factory = runnable -> Thread.ofVirtual()
                    .name("OpenModel-vt-" + OPEN_MODEL_VIRTUAL_THREAD_COUNTER.incrementAndGet())
                    .unstarted(runnable);
            log.debug("Created virtual thread executor for Open Model ThreadGroup");
            return Executors.newThreadPerTaskExecutor(factory);
        }
        return Executors.newCachedThreadPool();
    }

    @FunctionalInterface
    private interface OpenModelJMeterThreadFactory {
        JMeterThread create(int threadNumber);
    }

    public static final class ClosedModelPhase {
        private final long targetThreads;
        private final long timeSeconds;

        public ClosedModelPhase(long targetThreads, long timeSeconds) {
            this.targetThreads = targetThreads;
            this.timeSeconds = timeSeconds;
        }

        public long targetThreads() {
            return targetThreads;
        }

        public long timeSeconds() {
            return timeSeconds;
        }

        @Override
        public String toString() {
            return "threadsPhase(" + targetThreads + ", " + timeSeconds + ")";
        }
    }

    private record ClosedModelScheduleCache(String schedule, List<ClosedModelPhase> phases, int maxThreads) {
    }

    private class ClosedModelThreadsStarter implements Runnable {
        private final ListenerNotifier notifier;
        private final ListedHashTree threadGroupTree;
        private final StandardJMeterEngine engine;
        private final List<ClosedModelPhase> phases;
        private final JMeterVariables variables;
        private final Set<JMeterThread> stoppingThreads = ConcurrentHashMap.newKeySet();
        private int threadNumber;

        ClosedModelThreadsStarter(ListenerNotifier notifier, ListedHashTree threadGroupTree,
                StandardJMeterEngine engine, List<ClosedModelPhase> phases) {
            this.notifier = notifier;
            this.threadGroupTree = threadGroupTree;
            this.engine = engine;
            this.phases = phases;
            this.variables = JMeterContextService.getContext().getVariables();
        }

        @Override
        public void run() {
            try {
                JMeterContextService.getContext().setVariables(variables);
                long startupDelay = getStartupDelayInMillis();
                if (startupDelay > 0 && sleepUntil(System.currentTimeMillis() + startupDelay, engine) < 0) {
                    return;
                }

                long previousTargetThreads = 0;
                long previousTimeSeconds = 0;
                for (ClosedModelPhase phase : phases) {
                    if (!running || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    runPhase(previousTargetThreads, previousTimeSeconds, phase);
                    previousTargetThreads = phase.targetThreads();
                    previousTimeSeconds = phase.timeSeconds();
                }
                finishClosedModelScheduling();
            } catch (Exception ex) {
                log.error("An error occurred scheduling Closed Model phases for Thread Group: {}", getName(), ex);
                finishClosedModelScheduling();
            }
        }

        private void finishClosedModelScheduling() {
            running = false;
            stopActiveThreads(allThreads.size(), false);
        }

        private void runPhase(long previousTargetThreads, long previousTimeSeconds, ClosedModelPhase phase) {
            long targetThreads = phase.targetThreads();
            long phaseMillis = secondsToMillis(Math.max(0, phase.timeSeconds() - previousTimeSeconds));
            long phaseStart = System.currentTimeMillis();
            long phaseEnd = phaseStart + phaseMillis;

            while (running && !Thread.currentThread().isInterrupted() && System.currentTimeMillis() < phaseEnd) {
                long pauseTime = waitIfSchedulingPaused(engine);
                if (pauseTime < 0) {
                    return;
                }
                phaseStart += pauseTime;
                phaseEnd += pauseTime;
                long elapsed = System.currentTimeMillis() - phaseStart;
                long currentTargetThreads = currentClosedModelTarget(
                        previousTargetThreads, targetThreads, phaseMillis, elapsed);
                adjustActiveThreads(currentTargetThreads);
                pauseTime = sleepUntil(Math.min(phaseEnd, System.currentTimeMillis() + RAMPUP_GRANULARITY), engine);
                if (pauseTime < 0) {
                    return;
                }
                phaseStart += pauseTime;
                phaseEnd += pauseTime;
            }
            if (running && !Thread.currentThread().isInterrupted()) {
                adjustActiveThreads(targetThreads);
            }
        }

        private void adjustActiveThreads(long targetThreads) {
            stoppingThreads.removeIf(thread -> !allThreads.containsKey(thread));
            int activeThreads = allThreads.size() - stoppingThreads.size();
            if (activeThreads < targetThreads) {
                startThreads(Math.min(targetThreads - activeThreads, Integer.MAX_VALUE));
            } else if (activeThreads > targetThreads) {
                stopRunningThreads(activeThreads - targetThreads);
            }
        }

        private void stopRunningThreads(long threadsToStop) {
            long stopped = 0;
            for (Map.Entry<JMeterThread, Thread> threadEntry : allThreads.entrySet()) {
                if (stopped >= threadsToStop) {
                    return;
                }
                JMeterThread jmeterThread = threadEntry.getKey();
                if (stoppingThreads.add(jmeterThread)) {
                    stopThread(jmeterThread, threadEntry.getValue(), false);
                    stopped++;
                }
            }
        }

        private void startThreads(long threadsToStart) {
            for (long i = 0; running && i < threadsToStart; i++) {
                JMeterThread jmThread = makeThread(
                        engine, ThreadGroup.this, notifier, groupNumber, threadNumber++, cloneTree(threadGroupTree), variables);
                jmThread.setInitialDelay(0);
                Thread newThread = createThread(jmThread, jmThread.getThreadName());
                if (!VIRTUAL_THREADS_ENABLED) {
                    newThread.setDaemon(false);
                }
                registerStartedThread(jmThread, newThread);
                newThread.start();
            }
        }
    }

    private static long currentClosedModelTarget(
            long previousTargetThreads, long targetThreads, long rampMillis, long elapsedMillis) {
        if (rampMillis <= 0 || elapsedMillis >= rampMillis) {
            return targetThreads;
        }
        double progress = elapsedMillis / (double) rampMillis;
        return Math.round(previousTargetThreads + (targetThreads - previousTargetThreads) * progress);
    }

    private static long secondsToMillis(long seconds) {
        return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
    }

    private static long sleepUntil(long endTime, StandardJMeterEngine engine) {
        long pauseTime = 0;
        long adjustedEndTime = endTime;
        while (true) {
            long paused = waitIfSchedulingPaused(engine);
            if (paused < 0) {
                return -1;
            }
            pauseTime += paused;
            adjustedEndTime += paused;
            long delay = adjustedEndTime - System.currentTimeMillis();
            if (delay <= 0) {
                long finalPaused = waitIfSchedulingPaused(engine);
                if (finalPaused < 0) {
                    return -1;
                }
                if (finalPaused == 0) {
                    return pauseTime;
                }
                pauseTime += finalPaused;
                adjustedEndTime += finalPaused;
                continue;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(Math.min(delay, RAMPUP_GRANULARITY));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    private static long waitIfSchedulingPaused(StandardJMeterEngine engine) {
        if (Thread.currentThread().isInterrupted()) {
            return -1;
        }
        return engine == null ? 0 : engine.waitIfPaused();
    }

    private void stopActiveThreads(long threadsToStop, boolean interrupt) {
        long stopped = 0;
        for (Map.Entry<JMeterThread, Thread> threadEntry : allThreads.entrySet()) {
            if (stopped >= threadsToStop) {
                return;
            }
            stopThread(threadEntry.getKey(), threadEntry.getValue(), interrupt);
            stopped++;
        }
    }

    private class OpenModelThreadsStarter implements Runnable {
        private final long testStartTime;
        private final StandardJMeterEngine engine;
        private final ExecutorService executorService;
        private final ThreadScheduleProcessGenerator generator;
        private final OpenModelJMeterThreadFactory jmeterThreadFactory;

        private OpenModelThreadsStarter(long testStartTime, StandardJMeterEngine engine, ExecutorService executorService,
                ThreadScheduleProcessGenerator generator, OpenModelJMeterThreadFactory jmeterThreadFactory) {
            this.testStartTime = testStartTime;
            this.engine = engine;
            this.executorService = executorService;
            this.generator = generator;
            this.jmeterThreadFactory = jmeterThreadFactory;
        }

        @Override
        public void run() {
            log.info("Open Model thread starting init");
            long scheduleOffsetInMillis = 0;
            long endTime = testStartTime + Math.round(generator.getTotalDuration() * 1000);
            int threadNumber = 0;
            long previousTime = 0;
            while (running && !Thread.currentThread().isInterrupted() && generator.hasNext()) {
                long pauseTime = waitIfSchedulingPaused(engine);
                if (pauseTime < 0) {
                    shutdownAfterStop();
                    return;
                }
                scheduleOffsetInMillis += pauseTime;
                endTime += pauseTime;
                long scheduledTime = testStartTime + scheduleOffsetInMillis + Math.round(generator.nextDouble() * 1000);
                if (scheduledTime >= previousTime) {
                    previousTime = System.currentTimeMillis();
                    long nextDelay = scheduledTime - previousTime;
                    if (nextDelay > 0) {
                        pauseTime = sleepUntil(scheduledTime, engine);
                        if (pauseTime < 0) {
                            shutdownAfterStop();
                            return;
                        }
                        scheduleOffsetInMillis += pauseTime;
                        endTime += pauseTime;
                    }
                }
                if (!running || Thread.currentThread().isInterrupted()) {
                    shutdownAfterStop();
                    return;
                }
                pauseTime = waitIfSchedulingPaused(engine);
                if (pauseTime < 0) {
                    shutdownAfterStop();
                    return;
                }
                scheduleOffsetInMillis += pauseTime;
                endTime += pauseTime;
                if (!reserveOpenModelThreadSlot()) {
                    log.debug("Skipping Open Model arrival because the active thread limit is reached");
                    continue;
                }
                JMeterThread jmeterThread = jmeterThreadFactory.create(threadNumber++);
                jmeterThread.setEndTime(endTime);
                FutureTask<Void> task = new FutureTask<>(() -> {
                    Thread.currentThread().setName(jmeterThread.getThreadName());
                    jmeterThread.run();
                    return null;
                });
                openModelActiveThreads.put(jmeterThread, task);
                try {
                    executorService.execute(task);
                } catch (RuntimeException e) {
                    removeOpenModelThread(jmeterThread);
                    throw e;
                }
            }
            if (!running || Thread.currentThread().isInterrupted()) {
                shutdownAfterStop();
                return;
            }
            long timeLeft = endTime - System.currentTimeMillis();
            if (timeLeft > 0) {
                log.info("There will be no more events, so will wait for {} sec till the end of the schedule",
                        TimeUnit.MILLISECONDS.toSeconds(timeLeft));
                if (sleepUntil(endTime, engine) < 0) {
                    shutdownAfterStop();
                    return;
                }
            } else {
                log.info("Thread schedule finished {} ms ago", -timeLeft);
            }
            int threadsStillRunning = openModelActiveThreads.size();
            if (threadsStillRunning == 0) {
                log.info("There will be no more events, will shutdown the thread pool");
            } else {
                log.info("Test schedule finished, however, there are {} thread(s) still running."
                        + " Will interrupt the threads."
                        + " If you want to keep some time for the threads to complete, consider"
                        + " adding pause(10 min) at the end of the schedule.", threadsStillRunning);
                openModelActiveThreads.forEach((thread, future) -> {
                    log.info("Terminating thread {}", thread);
                    thread.stop();
                    thread.interrupt();
                    future.cancel(true);
                    removeOpenModelThread(thread);
                });
            }
            executorService.shutdownNow();
            log.info("Open Model thread starting done");
        }

        private void shutdownAfterStop() {
            log.info("Open Model thread scheduling stopped");
            executorService.shutdown();
        }

    }

    /**
     * Creates a thread (virtual or platform) based on configuration.
     * When {@code breaktest.threads.virtual.enabled} is true, creates a virtual thread.
     * Otherwise creates a platform thread.
     *
     * @param runnable the runnable to execute
     * @param name the thread name
     * @return an unstarted Thread
     */
    private static Thread createThread(Runnable runnable, String name) {
        if (VIRTUAL_THREADS_ENABLED) {
            return Thread.ofVirtual().name(name).unstarted(runnable);
        }
        return new Thread(runnable, name);
    }

    /**
     * Start a new {@link JMeterThread} and registers it
     * @param notifier {@link ListenerNotifier}
     * @param threadGroupTree {@link ListedHashTree}
     * @param engine {@link StandardJMeterEngine}
     * @param threadNum Thread number
     * @param variables initial values for the variables in the thread
     * @param now Nom in milliseconds
     * @param delay int delay in milliseconds
     * @return {@link JMeterThread} newly created
     */
    private JMeterThread startNewThread(ListenerNotifier notifier, ListedHashTree threadGroupTree, StandardJMeterEngine engine,
            int threadNum, JMeterVariables variables, long now, int delay) {
        JMeterThread jmThread = makeThread(engine, this, notifier, groupNumber, threadNum, cloneTree(threadGroupTree), variables);
        scheduleThread(jmThread, now); // set start and end time
        jmThread.setInitialDelay(delay);
        Thread newThread = createThread(jmThread, jmThread.getThreadName());
        registerStartedThread(jmThread, newThread);
        newThread.start();
        return jmThread;
    }

    /*
     * Fix NPE for addThreadLock transient object (BZ60829)
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        addThreadLock = new Object();
        openModelThreadStarterFuture = new AtomicReference<>();
    }

    /**
     * Register Thread when it starts
     * @param jMeterThread {@link JMeterThread}
     * @param newThread Thread
     */
    private void registerStartedThread(JMeterThread jMeterThread, Thread newThread) {
        allThreads.put(jMeterThread, newThread);
    }

    @Override
    @SuppressWarnings("SynchronizeOnNonFinalField")
    public JMeterThread addNewThread(int delay, StandardJMeterEngine engine) {
        if (isOpenModel()) {
            throw new UnsupportedOperationException("Adding threads dynamically is not supported for Open Model Thread Group");
        }
        long now = System.currentTimeMillis();
        JMeterContext context = JMeterContextService.getContext();
        JMeterThread newJmThread;
        int numThreads;
        synchronized (addThreadLock) {
            numThreads = getNumThreads();
            setNumThreads(numThreads + 1);
        }
        newJmThread = startNewThread(notifier, threadGroupTree, engine, numThreads, context.getVariables(), now, delay);
        JMeterContextService.addTotalThreads( 1 );
        log.info("Started new thread in group {}", groupNumber);
        return newJmThread;
    }

    /**
     * Stop thread called threadName:
     * <ol>
     *  <li>stop JMeter thread</li>
     *  <li>interrupt JMeter thread</li>
     *  <li>interrupt underlying thread</li>
     * </ol>
     * @param threadName String thread name
     * @param now boolean for stop
     * @return true if thread stopped
     */
    @Override
    public boolean stopThread(String threadName, boolean now) {
        if (isOpenModel()) {
            for (Map.Entry<JMeterThread, Future<?>> threadEntry : openModelActiveThreads.entrySet()) {
                JMeterThread jMeterThread = threadEntry.getKey();
                if (jMeterThread.getThreadName().equals(threadName)) {
                    jMeterThread.stop();
                    jMeterThread.interrupt();
                    if (now) {
                        threadEntry.getValue().cancel(true);
                    }
                    return true;
                }
            }
            return false;
        }
        for (Map.Entry<JMeterThread, Thread> threadEntry : allThreads.entrySet()) {
            JMeterThread jMeterThread = threadEntry.getKey();
            if (jMeterThread.getThreadName().equals(threadName)) {
                stopThread(jMeterThread, threadEntry.getValue(), now);
                return true;
            }
        }
        return false;
    }

    /**
     * Hard Stop JMeterThread thread and interrupt JVM Thread if interrupt is {@code true}
     * @param jmeterThread {@link JMeterThread}
     * @param jvmThread {@link Thread}
     * @param interrupt Interrupt thread or not
     */
    private static void stopThread(JMeterThread jmeterThread, Thread jvmThread, boolean interrupt) {
        jmeterThread.stop();
        jmeterThread.interrupt(); // interrupt sampler if possible
        if (interrupt && jvmThread != null) { // Bug 49734
            jvmThread.interrupt(); // also interrupt JVM thread
        }
    }

    /**
     * Called by JMeterThread when it finishes
     */
    @Override
    public void threadFinished(JMeterThread thread) {
        if (isOpenModel()) {
            removeOpenModelThread(thread);
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("Ending thread {}", thread.getThreadName());
        }
        allThreads.remove(thread);
    }

    private boolean reserveOpenModelThreadSlot() {
        return reserveOpenModelThreadSlot(
                getOpenModelMaxThreadsScope(),
                getOpenModelMaxThreads(),
                openModelActiveThreads.size());
    }

    public static boolean reserveOpenModelThreadSlot(String scope, long maxThreads, int activeThreadsInGroup) {
        if (maxThreads <= 0) {
            OPEN_MODEL_ACTIVE_THREAD_COUNT.incrementAndGet();
            return true;
        }
        if (OPEN_MODEL_MAX_THREADS_SCOPE_ALL_OPEN_MODEL.equals(scope)) {
            return reserveOpenModelThreadSlotForTotal(maxThreads);
        }
        if (activeThreadsInGroup >= maxThreads) {
            return false;
        }
        OPEN_MODEL_ACTIVE_THREAD_COUNT.incrementAndGet();
        return true;
    }

    private static boolean reserveOpenModelThreadSlotForTotal(long maxThreads) {
        while (true) {
            int activeThreads = OPEN_MODEL_ACTIVE_THREAD_COUNT.get();
            if (activeThreads >= maxThreads) {
                return false;
            }
            if (OPEN_MODEL_ACTIVE_THREAD_COUNT.compareAndSet(activeThreads, activeThreads + 1)) {
                return true;
            }
        }
    }

    private void removeOpenModelThread(JMeterThread thread) {
        if (thread != null && openModelActiveThreads.remove(thread) != null) {
            releaseOpenModelThreadSlot();
        }
    }

    public static void releaseOpenModelThreadSlot() {
        OPEN_MODEL_ACTIVE_THREAD_COUNT.decrementAndGet();
    }

    public void tellThreadsToStop(boolean now) {
        running = false;
        if (delayedStartup) {
            try {
                threadStarter.interrupt();
            } catch (Exception e) {
                log.warn("Exception occurred interrupting ThreadStarter", e);
            }
        }

        allThreads.forEach((key, value) -> stopThread(key, value, now));
    }

    /**
     * This is an immediate stop interrupting:
     * <ul>
     *  <li>current running threads</li>
     *  <li>current running samplers</li>
     * </ul>
     * For each thread, invoke:
     * <ul>
     * <li>{@link JMeterThread#stop()} - set stop flag</li>
     * <li>{@link JMeterThread#interrupt()} - interrupt sampler</li>
     * <li>{@link Thread#interrupt()} - interrupt JVM thread</li>
     * </ul>
     */
    @Override
    public void tellThreadsToStop() {
        if (isOpenModel()) {
            stop();
            log.info("Interrupting the Open Model threads");
            openModelActiveThreads.forEach((thread, future) -> {
                log.info("Interrupting thread {}", thread);
                thread.interrupt();
                future.cancel(true);
                removeOpenModelThread(thread);
            });
            if (openModelExecutorService != null) {
                openModelExecutorService.shutdownNow();
            }
            return;
        }
        tellThreadsToStop(true);
    }

    /**
     * This is a clean shutdown.
     * For each thread, invoke:
     * <ul>
     * <li>{@link JMeterThread#stop()} - set stop flag</li>
     * </ul>
     */
    @Override
    public void stop() {
        if (isOpenModel()) {
            running = false;
            log.info("Gracefully stopping the Open Model threads");
            Future<?> starter = openModelThreadStarterFuture.getAndSet(null);
            if (starter != null) {
                starter.cancel(true);
            }
            openModelActiveThreads.forEach((thread, future) -> {
                log.info("Gracefully stopping thread {}", thread);
                thread.stop();
            });
            if (openModelExecutorService != null) {
                openModelExecutorService.shutdown();
            }
            return;
        }
        running = false;
        if (delayedStartup) {
            try {
                threadStarter.interrupt();
            } catch (Exception e) {
                log.warn("Exception occurred interrupting ThreadStarter", e);
            }
        }
        allThreads.keySet().forEach(JMeterThread::stop);
    }

    /**
     * @return number of active threads
     */
    @Override
    public int numberOfActiveThreads() {
        if (isOpenModel()) {
            return openModelActiveThreads.size();
        }
        return allThreads.size();
    }

    /**
     * @return boolean true if all threads stopped
     */
    @Override
    public boolean verifyThreadsStopped() {
        if (isOpenModel()) {
            return openModelExecutorService == null || openModelExecutorService.isTerminated();
        }
        boolean stoppedAll = true;
        if (delayedStartup) {
            stoppedAll = verifyThreadStopped(threadStarter);
        }
        if(stoppedAll) {
            for (Thread t : allThreads.values()) {
                if(!verifyThreadStopped(t)) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Verify thread stopped and return true if stopped successfully
     * @param thread Thread
     * @return boolean
     */
    private static boolean verifyThreadStopped(Thread thread) {
        boolean stopped = true;
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(WAIT_TO_DIE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                stopped = false;
                if (log.isWarnEnabled()) {
                    log.warn("Thread won't exit: {}", thread.getName());
                }
            }
        }
        return stopped;
    }

    /**
     * Wait for all Group Threads to stop
     */
    @Override
    public void waitThreadsStopped() {
        if (isOpenModel()) {
            if (openModelExecutorService != null) {
                try {
                    openModelExecutorService.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return;
        }
        if (delayedStartup) {
            waitThreadStopped(threadStarter);
        }
        /* @Bugzilla 60933
         * Threads can be added on the fly during a test into allThreads
         * we have to check if allThreads is really empty before stopping
         */
        while (!allThreads.isEmpty()) {
            allThreads.values().forEach(ThreadGroup::waitThreadStopped);
        }

    }

    /**
     * Wait for thread to stop
     * @param thread Thread
     */
    private static void waitThreadStopped(Thread thread) {
        if (thread == null) {
            return;
        }
        while (thread.isAlive()) {
            try {
                thread.join(WAIT_TO_DIE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Starts Threads using ramp up
     */
    class ThreadStarter implements Runnable {

        private final ListenerNotifier notifier;
        private final ListedHashTree threadGroupTree;
        private final StandardJMeterEngine engine;
        private final JMeterVariables variables;

        public ThreadStarter(ListenerNotifier notifier, ListedHashTree threadGroupTree, StandardJMeterEngine engine) {
            super();
            this.notifier = notifier;
            this.threadGroupTree = threadGroupTree;
            this.engine = engine;
            // Store context from Root Thread to pass it to created threads
            this.variables = JMeterContextService.getContext().getVariables();
        }

        /**
         * Pause ms milliseconds
         * @param ms long milliseconds
         */
        /**
         * Wait for delay with RAMPUP_GRANULARITY
         * @param delay delay in ms
         * @return time spent paused while waiting
         */
        private long delayBy(long delay) {
            if (delay > 0) {
                long pauseTime = sleepUntil(System.currentTimeMillis() + delay, engine);
                return pauseTime;
            }
            return 0;
        }

        @Override
        public void run() {
            try {
                // Copy in ThreadStarter thread context from calling Thread
                JMeterContextService.getContext().setVariables(variables);
                long endtime = 0;
                final boolean usingScheduler = getScheduler();
                long startupDelay = getStartupDelayInMillis();
                if (startupDelay > 0) {
                    if (delayBy(startupDelay) < 0) {
                        return;
                    }
                }
                if (usingScheduler) {
                    // set the endtime for the Thread
                    endtime = getDuration();
                    if (endtime > 0) {// Duration is in seconds, starting from when the threads start
                        endtime = endtime *1000 + System.currentTimeMillis();
                    }
                }
                final int numThreads = getNumThreads();
                final float rampUpOriginInMillis = (float) getRampUp() * 1000;
                final long startTimeInMillis = System.currentTimeMillis();
                long pausedDuringRampInMillis = 0;
                for (int threadNumber = 0; running && threadNumber < numThreads; threadNumber++) {
                    if (threadNumber > 0) {
                        long elapsedInMillis = System.currentTimeMillis() - startTimeInMillis - pausedDuringRampInMillis;
                        final int perThreadDelayInMillis =
                                Math.round((rampUpOriginInMillis - elapsedInMillis) / (float) (numThreads - threadNumber));
                        long pauseTime = delayBy(Math.max(0, perThreadDelayInMillis)); // ramp-up delay (except first)
                        if (pauseTime < 0) {
                            break;
                        }
                        pausedDuringRampInMillis += pauseTime;
                        if (!running) {
                            break;
                        }
                    }
                    if (usingScheduler && System.currentTimeMillis() > endtime) {
                        break; // no point continuing beyond the end time
                    }
                    JMeterThread jmThread = makeThread(engine, ThreadGroup.this, notifier, groupNumber, threadNumber, cloneTree(threadGroupTree), variables);
                    jmThread.setInitialDelay(0);   // Already waited
                    if (usingScheduler) {
                        jmThread.setScheduled(true);
                        jmThread.setEndTime(endtime);
                    }
                    Thread newThread = createThread(jmThread, jmThread.getThreadName());
                    if (!VIRTUAL_THREADS_ENABLED) {
                        newThread.setDaemon(false); // ThreadStarter is daemon, but we don't want sampler threads to be so too
                    }
                    registerStartedThread(jmThread, newThread);
                    newThread.start();
                }
            } catch (Exception ex) {
                log.error("An error occurred scheduling delay start of threads for Thread Group: {}", getName(), ex);
            }
        }
    }
}
