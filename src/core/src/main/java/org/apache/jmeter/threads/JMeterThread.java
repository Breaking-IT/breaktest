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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.ForkController;
import org.apache.jmeter.control.ForkController.ErrorAction;
import org.apache.jmeter.control.ForkController.FinalStopAction;
import org.apache.jmeter.control.ForkController.IterationEndAction;
import org.apache.jmeter.control.ForkController.RunningAction;
import org.apache.jmeter.control.ForkControllerSampler;
import org.apache.jmeter.control.IteratingController;
import org.apache.jmeter.control.ParallelContextModifier;
import org.apache.jmeter.control.ParallelControllerSampler;
import org.apache.jmeter.control.RunningTransaction;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleMonitor;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.samplers.StoppableSampler;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestIterationListener;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.threads.JMeterContext.TestLogicalAction;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.timers.TimerService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.HashTreeTraverser;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.collections.SearchByClass;
import org.apache.jorphan.util.JMeterError;
import org.apache.jorphan.util.JMeterStopTestException;
import org.apache.jorphan.util.JMeterStopTestNowException;
import org.apache.jorphan.util.JMeterStopThreadException;
import org.apiguardian.api.API;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JMeter interface to the sampling process, allowing JMeter to see the
 * timing, add listeners for sampling events and to stop the sampling process.
 */
public class JMeterThread implements Runnable, Interruptible {
    private static final Logger log = LoggerFactory.getLogger(JMeterThread.class);

    public static final String PACKAGE_OBJECT = "JMeterThread.pack"; // $NON-NLS-1$

    public static final String LAST_SAMPLE_OK = "JMeterThread.last_sample_ok"; // $NON-NLS-1$

    private static final String TRUE = Boolean.toString(true); // i.e. "true"

    private static final int JMETER_VARIABLES_METADATA = 1;

    private static final int SOURCE_TEST_ELEMENT_PATH_METADATA = 1 << 1;

    private static final int ALL_SAMPLE_RESULT_METADATA =
            JMETER_VARIABLES_METADATA | SOURCE_TEST_ELEMENT_PATH_METADATA;

    /** How often to check for shutdown during ramp-up, default 1000ms */
    private static final int RAMPUP_GRANULARITY =
            JMeterUtils.getPropDefault("jmeterthread.rampup.granularity", 1000); // $NON-NLS-1$

    /** How often to check for shutdown during timer delay, default 1000ms */
    private static final int TIMER_GRANULARITY =
            JMeterUtils.getPropDefault("jmeterthread.timer.granularity", 1000); // $NON-NLS-1$

    private static final float TIMER_FACTOR = JMeterUtils.getPropDefault("timer.factor", 1.0f);

    private static final TimerService TIMER_SERVICE = TimerService.getInstance();

    private static final float ONE_AS_FLOAT = 1.0f;

    private static final boolean APPLY_TIMER_FACTOR = Float.compare(TIMER_FACTOR,ONE_AS_FLOAT) != 0;

    private static final boolean VIRTUAL_THREADS_ENABLED =
            JMeterUtils.getPropDefault("breaktest.threads.virtual.enabled", // $NON-NLS-1$
                    JMeterUtils.getPropDefault("jmeter.threads.virtual.enabled", true)); // $NON-NLS-1$

    private static final ThreadLocal<Boolean> FORK_WORKER_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final ThreadLocal<Future<?>> CURRENT_FORK_TASK = new ThreadLocal<>();

    private final Controller threadGroupLoopController;

    private final HashTree testTree;

    private final TestCompiler compiler;

    private final JMeterThreadMonitor monitor;

    private final JMeterVariables threadVars;

    private final Map<String, Object> initialVariables;

    // Note: this is only used to implement TestIterationListener#testIterationStart
    // Since this is a frequent event, it makes sense to create the list once rather than scanning each time
    // The memory used will be released when the thread finishes
    private final Collection<TestIterationListener> testIterationStartListeners;

    private final Collection<SampleMonitor> sampleMonitors;

    private final ListenerNotifier notifier;

    /*
     * The following variables are set by StandardJMeterEngine.
     * This is done before start() is called, so the values will be published to the thread safely
     * TODO - consider passing them to the constructor, so that they can be made final
     * (to avoid adding lots of parameters, perhaps have a parameter wrapper object.
     */
    private String threadName;

    private int initialDelay = 0;

    private int threadNum = 0;

    private long startTime = 0;

    private long endTime = 0;

    private final boolean isSameUserOnNextIteration;

    // based on this scheduler is enabled or disabled
    private boolean scheduler = false;

    // Gives access to parent thread threadGroup
    private AbstractThreadGroup threadGroup;

    /**
     * Next scheduled Thread Group iteration start time, used to calculate start-to-start pacing without drift.
     */
    private long nextThreadGroupPacingStartTime = -1;

    private StandardJMeterEngine engine = null; // For access to stop methods.

    /*
     * The following variables may be set/read from multiple threads.
     */
    private volatile boolean running; // may be set from a different thread

    private volatile List<? extends Timer> currentTimersForInterruption;

    private final List<List<? extends Timer>> currentForkTimersForInterruption =
            Collections.synchronizedList(new ArrayList<>());

    private volatile boolean onErrorStopTest;

    private volatile boolean onErrorStopTestNow;

    private volatile boolean onErrorStopThread;

    private volatile boolean onErrorStartNextLoop;

    private volatile Sampler currentSamplerForInterruption;

    private final List<Sampler> currentSamplersForInterruption = Collections.synchronizedList(new ArrayList<>());

    private final List<Sampler> currentForkSamplersForInterruption = Collections.synchronizedList(new ArrayList<>());

    private final List<Thread> currentForkThreadsForInterruption = Collections.synchronizedList(new ArrayList<>());

    private final ReentrantLock interruptLock = new ReentrantLock(); // ensure that interrupt cannot overlap with shutdown

    // Serializes the shared TestCompiler/controller bookkeeping (sampler configuration and
    // SamplePackage#recoverRunningVersion) so that samplers run concurrently by the
    // ParallelController cannot corrupt the shared controllers and compiler state. The actual
    // sampling stays parallel. Uncontended (and therefore cheap) for normal sequential execution.
    private final ReentrantLock compilerLock = new ReentrantLock();

    private volatile boolean mainFlowFinished;

    private final Object forkLifecycleLock = new Object();

    private volatile ErrorAction forkIterationEndAction;
    private volatile boolean suppressEndedIterationResults;
    private final Map<Thread, Sampler> activeSampleWorkers = new ConcurrentHashMap<>();
    private final Map<Thread, List<? extends Timer>> activeTimerWorkers = new ConcurrentHashMap<>();

    private final IdentityHashMap<ForkController, ReentrantLock> forkStartLocks = new IdentityHashMap<>();

    private final List<ForkExecution> forkExecutions = Collections.synchronizedList(new ArrayList<>());

    private record ForkExecution(Future<?> task, IterationEndAction iterationEnd, FinalStopAction finalStop,
            boolean legacy) {
    }

    private final Map<Thread, ForkWorker> forkWorkers = new ConcurrentHashMap<>();

    /** Owns cleanup even when cancellation prevents the callable from ever starting. */
    private final class ForkTask extends FutureTask<Void> {
        private final ExecutorService executor;
        private final ForkController controller;
        private final ErrorAction errorAction;
        private final ForkTask parentTask;
        private boolean started;
        private final CountDownLatch exited = new CountDownLatch(1);
        private volatile boolean stopRequested;
        private volatile boolean suppressResult;

        private ForkTask(Callable<Void> callable, ExecutorService executor, ForkController controller) {
            super(callable);
            this.executor = executor;
            this.controller = controller;
            errorAction = controller == null ? ErrorAction.CONTINUE : controller.getErrorAction();
            parentTask = CURRENT_FORK_TASK.get() instanceof ForkTask parent ? parent : null;
        }

        @Override
        public void run() {
            synchronized (this) {
                started = true;
            }
            try {
                super.run();
            } finally {
                cleanupFinishedFork(this, executor, controller);
                exited.countDown();
            }
        }

        @Override
        protected void done() {
            synchronized (this) {
                if (!started) {
                    cleanupFinishedFork(this, executor, controller);
                    exited.countDown();
                }
            }
        }
    }

    private static final class ForkWorker {
        private final Future<?> task;
        private volatile Sampler sampler;
        private volatile List<? extends Timer> timers = List.of();
        private volatile boolean waitingInTimer;

        private ForkWorker(Future<?> task) {
            this.task = task;
        }
    }

    private final List<Future<?>> forkTasks = Collections.synchronizedList(new ArrayList<>());

    private final List<ExecutorService> forkExecutors = Collections.synchronizedList(new ArrayList<>());

    private final Map<ForkController, Future<?>> activeForkTasksByController =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private final IdentityHashMap<Object, List<Controller>> parentControllersToRootCache = new IdentityHashMap<>();

    // Runtime sampler clones (for example parallel ForEach branches) are not traversed as
    // ThreadListeners when the source test tree finishes. Resources created by those clones can
    // register one cleanup action on the owning virtual user instead.
    private final Map<Object, Runnable> threadCleanupActions = new ConcurrentHashMap<>();

    public JMeterThread(HashTree test, JMeterThreadMonitor monitor, ListenerNotifier note) {
        this(test, monitor, note, false);
    }

    public JMeterThread(HashTree test, JMeterThreadMonitor monitor, ListenerNotifier note,Boolean isSameUserOnNextIteration) {
        this.monitor = monitor;
        threadVars = new JMeterVariables();
        initialVariables = snapshotVariableObjects(threadVars);
        testTree = test;
        compiler = new TestCompiler(testTree);
        threadGroupLoopController = (Controller) testTree.getArray()[0];
        SearchByClass<TestIterationListener> threadListenerSearcher = new SearchByClass<>(TestIterationListener.class); // TL - IS
        test.traverse(threadListenerSearcher);
        testIterationStartListeners = threadListenerSearcher.getSearchResults();
        SearchByClass<SampleMonitor> sampleMonitorSearcher = new SearchByClass<>(SampleMonitor.class);
        test.traverse(sampleMonitorSearcher);
        sampleMonitors = sampleMonitorSearcher.getSearchResults();
        notifier = note;
        running = true;
        this.isSameUserOnNextIteration = isSameUserOnNextIteration;
    }

    @Deprecated
    @API(status = API.Status.DEPRECATED, since = "5.5")
    public void setInitialContext(JMeterContext context) {
        putVariables(context.getVariables());
    }

    /**
     * Updates the variables with all entries found in the variables in {@code vars}
     * @param variables {@link JMeterVariables} with the entries to be updated
     */
    @API(status = API.Status.STABLE, since = "5.5")
    public void putVariables(JMeterVariables variables) {
        threadVars.putAll(variables);
        synchronized (initialVariables) {
            initialVariables.clear();
            initialVariables.putAll(snapshotVariableObjects(threadVars));
        }
    }

    /**
     * Enable the scheduler for this JMeterThread.
     *
     * @param sche
     *            flag whether the scheduler should be enabled
     */
    public void setScheduled(boolean sche) {
        this.scheduler = sche;
    }

    /**
     * Set the StartTime for this Thread.
     *
     * @param stime the StartTime value.
     */
    public void setStartTime(long stime) {
        startTime = stime;
    }

    /**
     * Get the start time value.
     *
     * @return the start time value.
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * Set the EndTime for this Thread.
     *
     * @param etime
     *            the EndTime value.
     */
    public void setEndTime(long etime) {
        endTime = etime;
    }

    /**
     * Get the end time value.
     *
     * @return the end time value.
     */
    public long getEndTime() {
        return endTime;
    }

    /**
     * Check if the scheduled time is completed.
     */
    private void stopSchedulerIfNeeded() {
        long now = System.currentTimeMillis();
        if (now >= endTime) {
            running = false;
            log.info("Stopping because end time detected by thread: {}", threadName);
        }
    }

    /**
     * Wait until the scheduled start time if necessary
     */
    private void startScheduler() {
        long delay = startTime - System.currentTimeMillis();
        delayBy(delay, "startScheduler");
    }

    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }
    @Override
    @SuppressWarnings("removal")
    public void run() {
        // threadContext is not thread-safe, so keep within thread
        JMeterContext threadContext = JMeterContextService.getContext();
        LoopIterationListener iterationListener = null;
        try {
            iterationListener = initRun(threadContext);
            while (running) {
                Sampler sam = threadGroupLoopController.next();
                while (running && sam != null) {
                    processSampler(sam, threadContext);
                    threadContext.cleanAfterSample();

                    // Do not unwind controllers through an error or loop action, or advance them to
                    // another sampler, once the thread has stopped. Transactions that are still
                    // running are reported when the thread finishes.
                    if (!running) {
                        break;
                    }

                    if (forkIterationEndAction != null) {
                        completeFailedForkIteration(threadContext);
                        triggerLoopLogicalActionOnParentControllers(sam, path -> {
                            for (Controller controller : path.getControllersToRoot()) {
                                if (controller instanceof AbstractThreadGroup group) {
                                    group.startNextLoop();
                                } else if (controller == threadGroupLoopController
                                        && controller instanceof IteratingController loop) {
                                    loop.startNextLoop();
                                } else {
                                    controller.triggerEndOfLoop();
                                }
                            }
                        });
                        sam = null;
                        continue;
                    }

                    boolean lastSampleOk = TRUE.equals(threadContext.getVariables().get(LAST_SAMPLE_OK));
                    // restart of the next loop
                    // - was requested through threadContext
                    // - or the last sample failed AND the onErrorStartNextLoop option is enabled
                    if (threadContext.getTestLogicalAction() != TestLogicalAction.CONTINUE
                            || (onErrorStartNextLoop && !lastSampleOk)) {
                        if (log.isDebugEnabled() && onErrorStartNextLoop
                                && threadContext.getTestLogicalAction() != TestLogicalAction.CONTINUE) {
                            log.debug("Start Next Thread Loop option is on, Last sample failed, starting next thread loop");
                        }
                        if(onErrorStartNextLoop && !lastSampleOk){
                            triggerLoopLogicalActionOnParentControllers(sam, JMeterThread::continueOnThreadLoop);
                        } else {
                            switch (threadContext.getTestLogicalAction()) {
                                case BREAK_CURRENT_LOOP ->
                                        triggerLoopLogicalActionOnParentControllers(sam, JMeterThread::breakOnCurrentLoop);
                                case START_NEXT_ITERATION_OF_THREAD ->
                                        triggerLoopLogicalActionOnParentControllers(sam, JMeterThread::continueOnThreadLoop);
                                case START_NEXT_ITERATION_OF_CURRENT_LOOP ->
                                        triggerLoopLogicalActionOnParentControllers(sam, JMeterThread::continueOnCurrentLoop);
                                default -> {
                                }
                            }
                        }
                        threadContext.setTestLogicalAction(TestLogicalAction.CONTINUE);
                        sam = null;
                        setLastSampleOk(threadContext.getVariables(), true);
                    }
                    else {
                        sam = threadGroupLoopController.next();
                    }
                }

                // It would be possible to add finally for Thread Loop here
                if (threadGroupLoopController.isDone()) {
                    log.info("Thread is done: {}", threadName);
                    break;
                }
            }
        }
        // Might be found by controller.next()
        catch (JMeterStopTestException e) { // NOSONAR
            if (log.isInfoEnabled()) {
                log.info("Stopping Test: {}", e.toString());
            }
            shutdownTest();
        }
        catch (JMeterStopTestNowException e) { // NOSONAR
            if (log.isInfoEnabled()) {
                log.info("Stopping Test Now: {}", e.toString());
            }
            stopTestNow();
        } catch (JMeterStopThreadException e) { // NOSONAR
            if (log.isInfoEnabled()) {
                log.info("Stop Thread seen for thread {}, reason: {}", getThreadName(), e.toString());
            }
        } catch (Exception | JMeterError e) {
            log.error("Test failed!", e);
        } catch (ThreadDeath e) {
            throw e; // Must not ignore this one
        } finally {
            if (forkIterationEndAction != null) {
                // Our own cancellation must not prevent waiting for workers before user cleanup.
                Thread.interrupted();
            }
            finishForks();
            endRunningTransactions(threadContext, null);
            running = false;
            currentSamplerForInterruption = null; // prevent any further interrupts
            currentSamplersForInterruption.clear();
            interruptLock.lock();  // make sure current interrupt is finished, prevent another starting yet
            try {
                threadContext.clear();
                log.info("Thread finished: {}", threadName);
                try {
                    threadFinished(iterationListener);
                } finally {
                    runThreadCleanupActions();
                }
                monitor.threadFinished(this); // Tell the monitor we are done
                JMeterContextService.removeContext(); // Remove the ThreadLocal entry
            } finally {
                interruptLock.unlock(); // Allow any pending interrupt to complete (OK because currentSampler == null)
            }
        }
    }

    /**
     * Trigger break/continue/switch to next thread Loop  depending on consumer implementation
     * @param sampler Sampler Base sampler
     * @param consumer Consumer that will process the tree of elements up to root node
     */
    private void triggerLoopLogicalActionOnParentControllers(Sampler sampler,
            Consumer<? super FindTestElementsUpToRootTraverser> consumer) {
        Object nodeToFind = findRealSampler(sampler);
        if (nodeToFind == null) {
            throw new IllegalStateException(
                    "Got null subSampler calling findRealSampler for:" +
                    (sampler != null ? sampler.getName() : "null") + ", sampler:" + sampler);
        }
        // Transaction Controllers on the path report their transaction when their loop ends (bug 52968)
        consumer.accept(pathToRootTraverser(nodeToFind));
    }

    private FindTestElementsUpToRootTraverser pathToRootTraverser(Object nodeToFind) {
        List<Controller> cachedControllers = parentControllersToRootCache.get(nodeToFind);
        if (cachedControllers != null) {
            return new CachedPathToRootTraverser(nodeToFind, cachedControllers);
        }

        FindTestElementsUpToRootTraverser pathToRootTraverser = new FindTestElementsUpToRootTraverser(nodeToFind);
        testTree.traverse(pathToRootTraverser);
        parentControllersToRootCache.put(nodeToFind, pathToRootTraverser.getControllersToRoot());
        return pathToRootTraverser;
    }

    private static final class CachedPathToRootTraverser extends FindTestElementsUpToRootTraverser {
        private final List<Controller> controllersToRoot;

        private CachedPathToRootTraverser(Object nodeToFind, List<Controller> controllersToRoot) {
            super(nodeToFind);
            this.controllersToRoot = controllersToRoot;
        }

        @Override
        public List<Controller> getControllersToRoot() {
            return new ArrayList<>(controllersToRoot);
        }
    }

    /**
     * Executes a continue of current loop, equivalent of "continue" in algorithm.
     * As a consequence it ends the first loop it finds on the path to root
     * @param pathToRootTraverser {@link FindTestElementsUpToRootTraverser}
     */
    private static void continueOnCurrentLoop(FindTestElementsUpToRootTraverser pathToRootTraverser) {
        List<Controller> controllersToReinit = pathToRootTraverser.getControllersToRoot();
        for (Controller parentController : controllersToReinit) {
            if (parentController instanceof AbstractThreadGroup tg) {
                tg.startNextLoop();
            } else if (parentController instanceof IteratingController iterController) {
                iterController.startNextLoop();
                break;
            } else {
                parentController.triggerEndOfLoop();
            }
        }
    }

    /**
     * Executes a break of current loop, equivalent of "break" in algorithm.
     * As a consequence it ends the first loop it finds on the path to root
     * @param pathToRootTraverser {@link FindTestElementsUpToRootTraverser}
     */
    private static void breakOnCurrentLoop(FindTestElementsUpToRootTraverser pathToRootTraverser) {
        List<Controller> controllersToReinit = pathToRootTraverser.getControllersToRoot();
        for (Controller parentController : controllersToReinit) {
            if (parentController instanceof AbstractThreadGroup tg) {
                tg.breakThreadLoop();
            } else if (parentController instanceof IteratingController iterController) {
                iterController.breakLoop();
                break;
            } else {
                parentController.triggerEndOfLoop();
            }
        }
    }

    /**
     * Executes a restart of Thread loop, equivalent of "continue" in algorithm but on Thread Loop.
     * As a consequence it ends all loop on the path to root
     * @param pathToRootTraverser {@link FindTestElementsUpToRootTraverser}
     */
    private static void continueOnThreadLoop(FindTestElementsUpToRootTraverser pathToRootTraverser) {
        List<Controller> controllersToReinit = pathToRootTraverser.getControllersToRoot();
        for (Controller parentController : controllersToReinit) {
            if (parentController instanceof AbstractThreadGroup tg) {
                tg.startNextLoop();
            } else {
                parentController.triggerEndOfLoop();
            }
        }
    }

    /**
     * Find the test tree node of the sampler that generated an error. A synthetic
     * {@link ParallelControllerSampler} resolves to its {@link org.apache.jmeter.control.ParallelController}.
     * @return tree node that should be used to find the sampler's parent controllers
     */
    private static Object findRealSampler(Sampler sampler) {
        if (sampler instanceof ForkControllerSampler forkSampler && forkSampler.getSourceController() != null) {
            return forkSampler.getSourceController();
        }
        if (sampler instanceof ParallelControllerSampler parallelSampler && parallelSampler.getController() != null) {
            return parallelSampler.getController();
        }
        return sampler;
    }

    /**
     * Process the current sampler.
     *
     * @param current sampler
     * @param threadContext
     */
    private void processSampler(Sampler current, JMeterContext threadContext) {
        processSampler(current, threadContext, Function.identity(), true);
    }

    private void processSampler(Sampler current, JMeterContext threadContext,
            Function<? super Sampler, ? extends Sampler> sourceSampler,
            boolean recoverControllers) {
        try {
            if (current instanceof ForkControllerSampler forkSampler) {
                startForkSampler(forkSampler, threadContext, sourceSampler);
            } else if (current instanceof ParallelControllerSampler parallelSampler) {
                // The ParallelController is transparent: its children are executed exactly as if
                // they were direct children of the ParallelController's parent, including the
                // transaction they run in.
                processParallelSampler(parallelSampler, threadContext, sourceSampler);
            } else {
                executeSamplePackage(current, threadContext, sourceSampler, recoverControllers);
            }

            if (scheduler) {
                // checks the scheduler to stop the iteration
                stopSchedulerIfNeeded();
            }

        } catch (JMeterStopTestException e) { // NOSONAR
            if (isCurrentSampleCancelledWithoutResult()) {
                return;
            }
            if (log.isInfoEnabled()) {
                log.info("Stopping Test: {}", e.toString());
            }
            shutdownTest();
        } catch (JMeterStopTestNowException e) { // NOSONAR
            if (isCurrentSampleCancelledWithoutResult()) {
                return;
            }
            if (log.isInfoEnabled()) {
                log.info("Stopping Test with interruption of current samplers: {}", e.toString());
            }
            stopTestNow();
        } catch (JMeterStopThreadException e) { // NOSONAR
            if (isCurrentSampleCancelledWithoutResult()) {
                return;
            }
            if (log.isInfoEnabled()) {
                log.info("Stopping Thread: {}", e.toString());
            }
            stopThread();
        } catch (Exception e) {
            if (isCurrentSampleCancelledWithoutResult()) {
                return;
            }
            if (current != null) {
                log.error("Error while processing sampler: '{}'.", current.getName(), e);
            } else {
                log.error("Error while processing sampler.", e);
            }
        }
    }

    /**
     * Starts the child flow of a {@link ForkControllerSampler} on its own worker and returns
     * immediately so the main virtual-user flow can continue.
     */
    private void startForkSampler(ForkControllerSampler forkSampler, JMeterContext parentContext,
            Function<? super Sampler, ? extends Sampler> sourceSampler) {
        ForkController source = forkSampler.getSourceController();
        if (source == null) {
            startForkSamplerLocked(forkSampler, parentContext, sourceSampler);
            return;
        }
        // Blocking under a Java monitor pins virtual-thread carriers on JDK 21-23.
        ReentrantLock startLock;
        synchronized (forkStartLocks) {
            startLock = forkStartLocks.computeIfAbsent(source, ignored -> new ReentrantLock());
        }
        try {
            while (!startLock.tryLock(50, TimeUnit.MILLISECONDS)) {
                if (!running || forkIterationEndAction != null || isCurrentForkStopRequested()) {
                    return;
                }
            }
            try {
                startForkSamplerLocked(forkSampler, parentContext, sourceSampler);
            } finally {
                startLock.unlock();
            }
        } catch (InterruptedException e) {
            handleForkWaitInterrupted();
        }
    }

    private void startForkSamplerLocked(ForkControllerSampler forkSampler, JMeterContext parentContext,
            Function<? super Sampler, ? extends Sampler> sourceSampler) {
        if (isCurrentForkStopRequested()
                || forkSampler.getController().isDone()) {
            return;
        }
        ForkController sourceController = forkSampler.getSourceController();
        if (mainFlowFinished && !isForkWorkerThread()) {
            return;
        }
        if (!waitForPreviousForkFromSameController(forkSampler)) {
            return;
        }
        if (!running || forkIterationEndAction != null || isCurrentForkStopRequested()) {
            return;
        }

        // Captured now: the main flow may have left the transaction by the time the fork runs
        RunningTransaction enclosingTransaction = parentContext.getCurrentTransaction();
        ExecutorService executor = createForkExecutor(forkSampler);
        AtomicReference<Future<?>> taskReference = new AtomicReference<>();
        ForkTask task = new ForkTask(() -> {
            try {
                FORK_WORKER_THREAD.set(Boolean.TRUE);
                CURRENT_FORK_TASK.set(taskReference.get());
                currentForkThreadsForInterruption.add(Thread.currentThread());
                forkWorkers.put(Thread.currentThread(), new ForkWorker(taskReference.get()));
                runForkSampler(forkSampler, parentContext, enclosingTransaction, sourceSampler);
                return null;
            } finally {
                currentForkThreadsForInterruption.remove(Thread.currentThread());
                forkWorkers.remove(Thread.currentThread());
                CURRENT_FORK_TASK.remove();
                FORK_WORKER_THREAD.remove();
            }
        }, executor, sourceController);
        taskReference.set(task);

        synchronized (forkLifecycleLock) {
            if (!running || forkIterationEndAction != null || isCurrentForkStopRequested()
                    || (mainFlowFinished && !isForkWorkerThread())) {
                executor.shutdown();
                return;
            }
            forkExecutors.add(executor);
            forkExecutions.add(new ForkExecution(task,
                    sourceController == null ? IterationEndAction.GRACEFUL : sourceController.getIterationEndAction(),
                    sourceController == null ? FinalStopAction.GRACEFUL : sourceController.getFinalStopAction(),
                    sourceController != null && !sourceController.hasLifecyclePolicy()));
            forkTasks.add(task);
            if (sourceController != null) {
                activeForkTasksByController.put(sourceController, task);
            }
            executor.execute(task);
        }
    }

    ExecutorService createForkExecutor(ForkControllerSampler sampler) {
        return Executors.newThreadPerTaskExecutor(createForkThreadFactory(sampler));
    }

    private void cleanupFinishedFork(Future<?> task, ExecutorService executor, ForkController sourceController) {
        forkTasks.removeIf(candidate -> candidate == task);
        forkExecutions.removeIf(execution -> execution.task() == task);
        forkExecutors.remove(executor);
        if (sourceController != null) {
            synchronized (activeForkTasksByController) {
                if (activeForkTasksByController.get(sourceController) == task) {
                    activeForkTasksByController.remove(sourceController);
                }
            }
        }
        executor.shutdown();
    }

    private boolean waitForPreviousForkFromSameController(ForkControllerSampler forkSampler) {
        ForkController sourceController = forkSampler.getSourceController();
        if (sourceController == null) {
            return true;
        }
        Future<?> previousTask = activeForkTasksByController.get(sourceController);
        if (previousTask == null) {
            return true;
        }
        if (isForkFinished(previousTask)) {
            activeForkTasksByController.remove(sourceController);
            return true;
        }
        return switch (sourceController.getRunningAction()) {
            case SKIP -> false;
            case RESTART -> stopForkTasks(List.of(previousTask), List.of(previousTask));
            case WAIT -> waitForForkTask(previousTask);
        };
    }

    private void runForkSampler(ForkControllerSampler forkSampler, JMeterContext parentContext,
            RunningTransaction enclosingTransaction, Function<? super Sampler, ? extends Sampler> sourceSampler) {
        JMeterContext workerContext = createParallelContext(parentContext, enclosingTransaction);
        JMeterContextService.replaceContext(workerContext);
        try {
            Controller controller = forkSampler.getController();
            Sampler sampler;
            while (running && forkIterationEndAction == null && !isCurrentForkStopRequested() && (sampler = controller.next()) != null) {
                processSampler(sampler, workerContext, sourceSampler, false);
                workerContext.cleanAfterSample();
                if (isCurrentForkStopRequested()) {
                    return;
                }
                if (shouldStopForkAfterSample(workerContext)) {
                    return;
                }
            }
        } finally {
            endRunningTransactions(workerContext, enclosingTransaction);
            workerContext.cleanAfterSample();
            JMeterContextService.removeContext();
        }
    }

    private static boolean shouldStopForkAfterSample(JMeterContext workerContext) {
        if (workerContext.getTestLogicalAction() != TestLogicalAction.CONTINUE) {
            workerContext.setTestLogicalAction(TestLogicalAction.CONTINUE);
            setLastSampleOk(workerContext.getVariables(), true);
            return true;
        }
        return false;
    }

    private void completeFailedForkIteration(JMeterContext context) {
        // All workers must leave the old iteration before its cancellation state is reset.
        Thread.interrupted();
        applyForkBoundary(false);
        endRunningTransactions(context, null);
        synchronized (forkLifecycleLock) {
            forkIterationEndAction = null;
            suppressEndedIterationResults = false;
        }
        Thread.interrupted();
        context.setTestLogicalAction(TestLogicalAction.CONTINUE);
        setLastSampleOk(context.getVariables(), true);
    }

    private void finishForks() {
        synchronized (forkLifecycleLock) {
            mainFlowFinished = true;
        }
        applyForkBoundary(true);
    }

    private void applyForkBoundary(boolean finalBoundary) {
        while (true) {
            List<ForkExecution> executions;
            synchronized (forkExecutions) {
                executions = forkExecutions.stream().filter(execution -> !isForkFinished(execution.task())).toList();
            }
            List<Future<?>> stop = new ArrayList<>();
            List<Future<?>> hardStop = new ArrayList<>();
            List<Future<?>> wait = new ArrayList<>();
            for (ForkExecution execution : executions) {
                if (!finalBoundary && execution.legacy() && forkIterationEndAction == null) {
                    continue;
                }
                IterationEndAction action = execution.legacy() ? IterationEndAction.WAIT : execution.iterationEnd();
                if (!running && action == IterationEndAction.WAIT) {
                    // WAIT has no configurable final-stop mode. Ignore any old hidden choice.
                    action = IterationEndAction.GRACEFUL;
                }
                if (action == IterationEndAction.KEEP_RUNNING && (finalBoundary || !isSameUserOnNextIteration)) {
                    action = execution.finalStop() == FinalStopAction.IMMEDIATE
                            ? IterationEndAction.IMMEDIATE : IterationEndAction.GRACEFUL;
                }
                if (forkIterationEndAction != null) {
                    action = forkIterationEndAction == ErrorAction.END_ITERATION_IMMEDIATE
                            ? IterationEndAction.IMMEDIATE : IterationEndAction.GRACEFUL;
                }
                switch (action) {
                    case IMMEDIATE -> {
                        stop.add(execution.task());
                        hardStop.add(execution.task());
                    }
                    case GRACEFUL -> stop.add(execution.task());
                    case WAIT -> wait.add(execution.task());
                    case KEEP_RUNNING, LEGACY -> { /* Survives this iteration. */ }
                }
            }
            if (stop.isEmpty() && wait.isEmpty()) {
                return;
            }
            if (!stopForkTasks(stop, hardStop)) {
                return;
            }
            for (Future<?> task : wait) {
                if (!waitForForkTask(task)) {
                    return;
                }
            }
        }
    }

    private boolean stopForkTasks(List<Future<?>> tasks, List<Future<?>> hardStop) {
        requestStopForkTasks(tasks, hardStop);
        for (Future<?> task : tasks) {
            if (!waitForForkTask(task)) {
                return false;
            }
        }
        return true;
    }

    private void requestStopForkTasks(List<Future<?>> tasks, List<Future<?>> hardStop) {
        synchronized (forkLifecycleLock) {
            for (Future<?> task : tasks) {
                requestForkStop(task, containsIdentity(hardStop, task));
            }
        }
        for (Map.Entry<Thread, ForkWorker> entry : forkWorkers.entrySet()) {
            ForkWorker worker = entry.getValue();
            if (!containsIdentity(tasks, worker.task)) {
                continue;
            }
            stopTimers(worker.timers);
            boolean immediate = containsIdentity(hardStop, worker.task);
            if (!immediate) {
                if (worker.waitingInTimer) {
                    entry.getKey().interrupt();
                }
                continue;
            }
            Sampler sampler = worker.sampler;
            if (sampler instanceof StoppableSampler stoppableSampler) {
                stoppableSampler.stop();
            }
            if (sampler instanceof Interruptible interruptible) {
                try {
                    interruptible.interrupt();
                } catch (Exception e) {
                    log.debug("Could not interrupt fork sampler", e);
                }
            }
            entry.getKey().interrupt();
        }
    }

    private void handleForkError(ForkTask failedTask) {
        if (failedTask.errorAction == ErrorAction.CONTINUE) {
            return;
        }
        if (failedTask.errorAction == ErrorAction.STOP_FORK) {
            List<Future<?>> descendants = new ArrayList<>();
            synchronized (forkLifecycleLock) {
                for (Future<?> candidate : runningForkTasks()) {
                    if (candidate instanceof ForkTask child) {
                        for (ForkTask ancestor = child; ancestor != null; ancestor = ancestor.parentTask) {
                            if (ancestor == failedTask) {
                                requestForkStop(child, false);
                                descendants.add(child);
                                break;
                            }
                        }
                    }
                }
            }
            requestStopForkTasks(descendants, List.of());
            return;
        }
        synchronized (forkLifecycleLock) {
            if (forkIterationEndAction == null || failedTask.errorAction == ErrorAction.END_ITERATION_IMMEDIATE) {
                forkIterationEndAction = failedTask.errorAction;
            }
            suppressEndedIterationResults |= forkIterationEndAction == ErrorAction.END_ITERATION_IMMEDIATE;
            for (Future<?> task : runningForkTasks()) {
                requestForkStop(task, suppressEndedIterationResults);
            }
        }
        for (Map.Entry<Thread, List<? extends Timer>> entry : activeTimerWorkers.entrySet()) {
            stopTimers(entry.getValue());
            interruptActiveWorker(activeTimerWorkers, entry);
        }
        if (suppressEndedIterationResults) {
            for (Map.Entry<Thread, Sampler> entry : activeSampleWorkers.entrySet()) {
                Sampler sampler = entry.getValue();
                try {
                    if (sampler instanceof StoppableSampler stoppable) {
                        stoppable.stop();
                    }
                    if (sampler instanceof Interruptible interruptible) {
                        interruptible.interrupt();
                    }
                } catch (Exception e) {
                    log.debug("Could not interrupt sampler after fork failure", e);
                }
                interruptActiveWorker(activeSampleWorkers, entry);
            }
        }
    }

    private static <T> void interruptActiveWorker(Map<Thread, T> workers, Map.Entry<Thread, T> entry) {
        synchronized (workers) {
            // Never deliver a late interrupt after the operation exits and user cleanup starts.
            if (workers.get(entry.getKey()) == entry.getValue()) {
                entry.getKey().interrupt();
            }
        }
    }

    private static void requestForkStop(Future<?> task, boolean suppressResult) {
        if (task instanceof ForkTask forkTask) {
            if (suppressResult) {
                forkTask.suppressResult = true;
            }
            forkTask.stopRequested = true;
        }
    }

    private boolean isCurrentSampleCancelledWithoutResult() {
        return suppressEndedIterationResults
                || CURRENT_FORK_TASK.get() instanceof ForkTask task && task.suppressResult;
    }

    private void handleForkWaitInterrupted() {
        Thread.currentThread().interrupt();
        if (CURRENT_FORK_TASK.get() instanceof ForkTask task) {
            task.stopRequested = true;
        } else {
            stopForksNow();
        }
    }

    private static boolean isForkFinished(Future<?> task) {
        return task instanceof ForkTask fork ? fork.exited.getCount() == 0 : task.isDone();
    }

    private boolean waitForForkTask(Future<?> task) {
        try {
            while (true) {
                if (isCurrentForkStopRequested()) {
                    return false;
                }
                if (scheduler && running && !mainFlowFinished && System.currentTimeMillis() >= endTime) {
                    stopSchedulerIfNeeded();
                    return false;
                }
                try {
                    if (task instanceof ForkTask fork && !fork.exited.await(50, TimeUnit.MILLISECONDS)) {
                        continue;
                    }
                    task.get(50, TimeUnit.MILLISECONDS);
                    return true;
                } catch (TimeoutException e) {
                    // Recheck the caller's stop request and scheduled deadline.
                }
            }
        } catch (InterruptedException e) {
            handleForkWaitInterrupted();
            return false;
        } catch (CancellationException e) {
            log.debug("Fork sampler task was cancelled during shutdown.");
            return true;
        } catch (ExecutionException e) {
            if (!suppressEndedIterationResults && !(task instanceof ForkTask fork && fork.suppressResult)) {
                log.error("Error while processing fork sampler.", e.getCause());
            }
            return true;
        }
    }

    private void fillThreadInformation(SampleResult result,
            int nbActiveThreadsInThreadGroup,
            int nbTotalActiveThreads) {
        result.setGroupThreads(nbActiveThreadsInThreadGroup);
        result.setAllThreads(nbTotalActiveThreads);
        result.setThreadName(threadName);
    }

    /**
     * Runs every branch of the {@link ParallelControllerSampler} concurrently. Each branch walks
     * its own controller state, while leaf samplers run in the transaction that encloses the
     * {@link org.apache.jmeter.control.ParallelController} (if any), so the children are recorded
     * and attributed exactly as if they were direct, sequential children of that parent.
     */
    private void processParallelSampler(ParallelControllerSampler parallelSampler, JMeterContext parentContext,
            Function<? super Sampler, ? extends Sampler> enclosingSourceSampler) {
        int branchCount = parallelSampler.getBranchCount();
        if (branchCount == 0) {
            return;
        }

        int maxParallel = Math.min(parallelSampler.getMaxParallel(), branchCount);
        ExecutorService executor = Executors.newThreadPerTaskExecutor(createParallelThreadFactory(parallelSampler));
        CompletionService<SampleResult> completionService = new ExecutorCompletionService<>(executor);
        int nextBranch = 0;
        int activeBranches = 0;
        boolean startNextLoop = false;
        boolean anyResult = false;
        boolean anyFailure = false;
        try {
            while (nextBranch < branchCount && activeBranches < maxParallel) {
                completionService.submit(parallelTask(
                        parallelSampler.getParallelBranch(nextBranch++), parentContext, enclosingSourceSampler));
                activeBranches++;
            }

            while (activeBranches > 0) {
                SampleResult result = null;
                try {
                    result = completionService.take().get();
                } catch (ExecutionException e) {
                    if (!isCurrentSampleCancelledWithoutResult()) {
                        log.error("Error while processing parallel sampler: '{}'.", parallelSampler.getName(), e.getCause());
                    }
                }
                activeBranches--;
                if (result != null) {
                    anyResult = true;
                    parentContext.setPreviousResult(result);
                    if (result.getTestLogicalAction() != TestLogicalAction.CONTINUE) {
                        parentContext.setTestLogicalAction(result.getTestLogicalAction());
                    }
                    if (!result.isSuccessful()) {
                        anyFailure = true;
                        if (onErrorStartNextLoop && !isForkWorkerThread()) {
                            startNextLoop = true;
                        }
                    }
                }
                if (running && forkIterationEndAction == null && !isCurrentForkStopRequested() && !startNextLoop && nextBranch < branchCount) {
                    completionService.submit(parallelTask(
                            parallelSampler.getParallelBranch(nextBranch++), parentContext, enclosingSourceSampler));
                    activeBranches++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!isCurrentForkStopRequested()) {
                stopThread();
            }
        } finally {
            executor.shutdownNow();
            if (isForkWorkerThread() || forkIterationEndAction != null) {
                // Account for submitted branches that have not registered in forkWorkers yet.
                // The parent task must remain alive until every nested worker has exited.
                boolean interrupted = Thread.interrupted();
                while (!executor.isTerminated()) {
                    try {
                        executor.awaitTermination(1, TimeUnit.DAYS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (anyResult) {
            // Workers keep LAST_SAMPLE_OK to themselves (see ParallelWorkerVariables), so the
            // parent state is derived deterministically from the branch outcomes instead of
            // whichever branch happened to write last.
            setLastSampleOk(parentContext.getVariables(), !anyFailure);
        }
    }

    private Callable<SampleResult> parallelTask(ParallelControllerSampler.ParallelBranch parallelBranch,
            JMeterContext parentContext, Function<? super Sampler, ? extends Sampler> enclosingSourceSampler) {
        boolean forkWorker = isForkWorkerThread();
        Future<?> forkTask = CURRENT_FORK_TASK.get();
        RunningTransaction enclosingTransaction = parentContext.getCurrentTransaction();
        // Resolves this branch's sampler clones to their source and keeps resolving through the
        // enclosing scope, so nested parallel sections map clone-of-clone back to the tree sampler.
        Function<Sampler, Sampler> sourceSampler =
                sampler -> enclosingSourceSampler.apply(parallelBranch.getSourceSampler(sampler));
        return () -> {
            Controller branch = parallelBranch.getController();
            if (forkWorker) {
                FORK_WORKER_THREAD.set(Boolean.TRUE);
                CURRENT_FORK_TASK.set(forkTask);
                currentForkThreadsForInterruption.add(Thread.currentThread());
                forkWorkers.put(Thread.currentThread(), new ForkWorker(forkTask));
            }
            JMeterContext workerContext = createParallelContext(parentContext, enclosingTransaction);
            if (branch instanceof ParallelContextModifier contextModifier) {
                contextModifier.prepareParallelContext(workerContext);
            }
            JMeterContextService.replaceContext(workerContext);
            SampleResult branchResult = null;
            try {
                Sampler sampler;
                while (running && forkIterationEndAction == null && !isCurrentForkStopRequested() && (sampler = branch.next()) != null) {
                    SampleResult result = executeParallelBranchSampler(sampler, workerContext, sourceSampler);
                    if (result != null) {
                        if (branchResult == null || branchResult.isSuccessful()) {
                            branchResult = result;
                        }
                        if (result.getTestLogicalAction() != TestLogicalAction.CONTINUE) {
                            workerContext.setTestLogicalAction(result.getTestLogicalAction());
                        }
                        if (!result.isSuccessful() && onErrorStartNextLoop && !isForkWorkerThread()) {
                            return result;
                        }
                    }
                    workerContext.cleanAfterSample();
                    if (workerContext.getTestLogicalAction() != TestLogicalAction.CONTINUE) {
                        return branchResult;
                    }
                }
                return branchResult;
            } finally {
                endRunningTransactions(workerContext, enclosingTransaction);
                workerContext.cleanAfterSample();
                JMeterContextService.removeContext();
                if (forkWorker) {
                    currentForkThreadsForInterruption.remove(Thread.currentThread());
                    forkWorkers.remove(Thread.currentThread());
                    CURRENT_FORK_TASK.remove();
                    FORK_WORKER_THREAD.remove();
                }
            }
        };
    }

    private SampleResult executeParallelBranchSampler(Sampler sampler, JMeterContext workerContext,
            Function<? super Sampler, ? extends Sampler> sourceSampler) {
        if (sampler instanceof ForkControllerSampler forkSampler) {
            startForkSampler(forkSampler, workerContext, sourceSampler);
            return null;
        }
        if (sampler instanceof ParallelControllerSampler nestedParallelSampler) {
            processParallelSampler(nestedParallelSampler, workerContext, sourceSampler);
            return workerContext.getPreviousResult();
        }
        return executeSamplePackage(sampler, workerContext, sourceSampler, false);
    }

    private static JMeterContext createParallelContext(JMeterContext parentContext,
            RunningTransaction enclosingTransaction) {
        JMeterContext workerContext = new JMeterContext();
        // Share the virtual user's variables, but keep the engine-internal per-sample keys
        // (current package, last_sample_ok) local to this worker so concurrent workers do not
        // clobber the main flow's state.
        workerContext.setVariables(new ParallelWorkerVariables(parentContext.getVariables()));
        workerContext.setPreviousResult(parentContext.getPreviousResult());
        workerContext.setThreadNum(parentContext.getThreadNum());
        workerContext.setThread(parentContext.getThread());
        workerContext.setThreadGroup(parentContext.getThreadGroup());
        workerContext.setEngine(parentContext.getEngine());
        workerContext.setSamplingStarted(parentContext.isSamplingStarted());
        workerContext.setRecording(parentContext.isRecording());
        workerContext.setCurrentTransaction(enclosingTransaction);
        return workerContext;
    }

    private ThreadFactory createParallelThreadFactory(ParallelControllerSampler parallelSampler) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            String name = threadName + "-" + parallelSampler.getName() + "-parallel-" + counter.incrementAndGet();
            if (VIRTUAL_THREADS_ENABLED) {
                return Thread.ofVirtual().name(name).unstarted(runnable);
            }
            return new Thread(runnable, name);
        };
    }

    private ThreadFactory createForkThreadFactory(ForkControllerSampler forkSampler) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            String name = threadName + "-" + forkSampler.getName() + "-fork-" + counter.incrementAndGet();
            if (VIRTUAL_THREADS_ENABLED) {
                return Thread.ofVirtual().name(name).unstarted(runnable);
            }
            return new Thread(runnable, name);
        };
    }

    private SampleResult executeSamplePackage(Sampler current,
            JMeterContext threadContext,
            Function<? super Sampler, ? extends Sampler> sourceSampler,
            boolean recoverControllers) {

        threadContext.setCurrentSampler(current);
        // Get the sampler ready to sample
        SamplePackage pack = configureSamplerLocked(current, sourceSampler);
        boolean packageDone = false;
        SampleResult startedSample = null;
        try {
            runPreProcessors(pack.getPreProcessors());

            // Save the package of the current sampler for elements that need it. Written through the
            // executing context's variables so parallel/fork workers keep it to themselves
            // instead of clobbering the main flow's current package.
            threadContext.getVariables().putObject(PACKAGE_OBJECT, pack);

            TimerPause timerPause = delay(pack.getTimers());
            if (timerPause != null) {
                for (RunningTransaction transaction = threadContext.getCurrentTransaction(); transaction != null;
                        transaction = transaction.getEnclosing()) {
                    transaction.addTimerPause(timerPause.startTime, timerPause.endTime);
                }
            }
            SampleResult result = null;
            if (running && forkIterationEndAction == null && !isCurrentForkStopRequested()) {
                Sampler sampler = pack.getSampler();
                markTransactionStarted(threadContext.getCurrentTransaction());
                // Only live views ask for this, so it costs a field read otherwise
                if (pack.needsStartEvents()) {
                    startedSample = notifySampleStarted(pack, sampler, threadContext);
                }
                result = doSampling(threadContext, sampler);
            }
            if (isCurrentSampleCancelledWithoutResult()) {
                // An aborted request is neither a failed sample nor an on-error action.
                result = null;
            }
            // If we got any results, then perform processing on the result
            if (result != null) {
                RunningTransaction currentTransaction = threadContext.getCurrentTransaction();
                if (currentTransaction != null) {
                    result.setParentTransaction(currentTransaction.getRef());
                }
                if (!result.isIgnore()) {
                    int nbActiveThreadsInThreadGroup = threadGroup.getNumberOfThreads();
                    int nbTotalActiveThreads = JMeterContextService.getNumberOfThreads();
                    fillThreadInformation(result, nbActiveThreadsInThreadGroup, nbTotalActiveThreads);
                    SampleResult[] subResults = result.getSubResults();
                    if (subResults != null) {
                        for (SampleResult subResult : subResults) {
                            fillThreadInformation(subResult, nbActiveThreadsInThreadGroup, nbTotalActiveThreads);
                        }
                    }
                    threadContext.setPreviousResult(result);
                    runPostProcessors(pack.getPostProcessors());
                    JMeterThreadAssertions.check(pack.getAssertions(), result, threadContext);
                    if (isCurrentSampleCancelledWithoutResult()) {
                        packageDone = true;
                        doneLocked(pack, recoverControllers);
                        return null;
                    }
                    // PostProcessors can call setIgnore, so reevaluate here
                    if (!result.isIgnore()) {
                        List<SampleListener> sampleListeners = pack.getSampleListeners();
                        int metadataRequirements = sampleResultMetadataRequirements(sampleListeners);
                        if (needsSourceTestElementPath(metadataRequirements)) {
                            setSourceTestElementPath(result, pack.getSourceTestElementPath());
                        }
                        notifyListeners(sampleListeners, result, metadataRequirements, false, startedSample);
                        // Transactions only keep running totals, so the result can be released
                        // as soon as the next sample replaces it as the previous result
                        for (RunningTransaction transaction = currentTransaction; transaction != null;
                                transaction = transaction.getEnclosing()) {
                            transaction.addSample(result);
                        }
                    }
                    packageDone = true;
                    doneLocked(pack, recoverControllers);
                } else {
                    // This call is done by JMeterThreadAssertions.check(), as we don't call it
                    // for isIgnore, we explictely call it here
                    setLastSampleOk(threadContext.getVariables(), result.isSuccessful());
                    packageDone = true;
                    doneLocked(pack, recoverControllers);
                }
                if (!result.isSuccessful() && CURRENT_FORK_TASK.get() instanceof ForkTask task) {
                    handleForkError(task);
                }
                // Explicit sampler actions still apply; thread-group error policy is main-flow only.
                boolean mainFlowError = !isForkWorkerThread() && !result.isSuccessful() && forkIterationEndAction == null;
                if (result.isStopThread() || (mainFlowError && onErrorStopThread)) {
                    stopThread();
                }
                if (result.isStopTest() || (mainFlowError && onErrorStopTest)) {
                    shutdownTest();
                }
                if (result.isStopTestNow() || (mainFlowError && onErrorStopTestNow)) {
                    stopTestNow();
                }
                if (result.getTestLogicalAction() != TestLogicalAction.CONTINUE) {
                    threadContext.setTestLogicalAction(result.getTestLogicalAction());
                }
            } else {
                packageDone = true;
                doneLocked(pack, recoverControllers);
            }
            return result;
        } finally {
            if (!packageDone) {
                doneLocked(pack, recoverControllers);
            }
            if (startedSample != null) {
                notifier.notifySampleStopped(
                        new SampleEvent(startedSample, threadGroup.getName(), threadVars), pack.getSampleListeners());
            }
        }
    }

    /**
     * Tells the listeners that asked for start events that the sampler is about to send its request.
     *
     * @return the placeholder sent to the listeners
     */
    private SampleResult notifySampleStarted(SamplePackage pack, Sampler sampler, JMeterContext threadContext) {
        SampleResult started = new SampleResult();
        started.setSampleLabel(sampler.getName());
        started.setThreadName(threadName);
        started.setSuccessful(true);
        RunningTransaction transaction = threadContext.getCurrentTransaction();
        if (transaction != null) {
            started.setParentTransaction(transaction.getRef());
        }
        List<SampleListener> listeners = pack.getSampleListeners();
        if (needsSourceTestElementPath(sampleResultMetadataRequirements(listeners))) {
            setSourceTestElementPath(started, pack.getSourceTestElementPath());
        }
        started.sampleStart();
        notifier.notifySampleStarted(new SampleEvent(started, threadGroup.getName(), threadVars), listeners);
        return started;
    }

    /**
     * Configures the sampler holding {@link #compilerLock} so that concurrent samplers run by a
     * {@link org.apache.jmeter.control.ParallelController} do not mutate the shared
     * {@link TestCompiler} state at the same time.
     */
    private SamplePackage configureSamplerLocked(Sampler current,
            Function<? super Sampler, ? extends Sampler> sourceSampler) {
        compilerLock.lock();
        try {
            return compiler.configureSampler(current, sourceSampler);
        } finally {
            compilerLock.unlock();
        }
    }

    /**
     * Calls {@link TestCompiler#done(SamplePackage)} holding {@link #compilerLock}.
     * {@code done} recovers the running version of the sampler's scope, including the shared
     * parent controllers, which is not thread-safe when several samplers finish in parallel.
     */
    private void doneLocked(SamplePackage pack, boolean recoverControllers) {
        compilerLock.lock();
        try {
            compiler.done(pack, recoverControllers);
        } finally {
            compilerLock.unlock();
        }
    }

    /**
     * Call sample on Sampler handling:
     * <ul>
     *  <li>setting up ThreadContext</li>
     *  <li>initializing sampler if needed</li>
     *  <li>positioning currentSamplerForInterruption for potential interruption</li>
     *  <li>Playing SampleMonitor before and after sampling</li>
     *  <li>resetting currentSamplerForInterruption</li>
     * </ul>
     * @param threadContext {@link JMeterContext}
     * @param sampler {@link Sampler}
     * @return {@link SampleResult}
     */
    private SampleResult doSampling(JMeterContext threadContext, Sampler sampler) {
        sampler.setThreadContext(threadContext);
        setSamplerThreadName(sampler);
        TestBeanHelper.prepare(sampler);

        // Perform the actual sample
        currentSamplerForInterruption = sampler;
        currentSamplersForInterruption.add(sampler);
        activeSampleWorkers.put(Thread.currentThread(), sampler);
        ForkWorker forkWorker = forkWorkers.get(Thread.currentThread());
        if (forkWorker != null) {
            forkWorker.sampler = sampler;
        }
        if (isForkWorkerThread()) {
            currentForkSamplersForInterruption.add(sampler);
        }
        if (!sampleMonitors.isEmpty()) {
            for (SampleMonitor sampleMonitor : sampleMonitors) {
                if(sampleMonitor instanceof TestElement testElement) {
                    TestBeanHelper.prepare(testElement);
                }
                sampleMonitor.sampleStarting(sampler);
            }
        }
        try {
            if (!running || forkIterationEndAction != null || isCurrentForkStopRequested()) {
                return null;
            }
            return sampler.sample(null);
        } finally {
            if (!sampleMonitors.isEmpty()) {
                for (SampleMonitor sampleMonitor : sampleMonitors) {
                    sampleMonitor.sampleEnded(sampler);
                }
            }
            currentSamplerForInterruption = null;
            synchronized (activeSampleWorkers) {
                activeSampleWorkers.remove(Thread.currentThread());
            }
            // Remove by identity: TestElement.equals() compares configuration, so a sibling
            // sampler with identical settings must not be removed in place of this one.
            currentSamplersForInterruption.removeIf(candidate -> candidate == sampler);
            currentForkSamplersForInterruption.removeIf(candidate -> candidate == sampler);
            if (forkWorker != null) {
                forkWorker.sampler = null;
            }
        }
    }

    private void markTransactionStarted(RunningTransaction transaction) {
        if (transaction == null) {
            return;
        }
        markTransactionStarted(transaction.getEnclosing());
        synchronized (transaction) {
            // finish() uses the same monitor: listeners must see start before completion,
            // including when a fork samples while its enclosing main transaction ends.
            if (transaction.samplerStarted()) {
                notifyTransactionStarted(transaction);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void setSamplerThreadName(Sampler sampler) {
        sampler.setThreadName(threadName);
    }

    /**
     * Ends the transactions still running in the context, down to {@code boundary}, when the thread,
     * or a parallel or fork worker, finishes before reaching their end.
     */
    private static void endRunningTransactions(JMeterContext context, RunningTransaction boundary) {
        try {
            context.endTransactionsUntil(boundary);
        } catch (RuntimeException e) {
            log.error("Error while reporting unfinished transactions", e);
        }
    }

    /**
     * Tells the listeners in scope of the transaction controller that a transaction has started.
     * Called when the first sampler starts, or a naturally empty transaction finishes.
     */
    void notifyTransactionStarted(RunningTransaction transaction) {
        SamplePackage pack = compiler.getTransactionControllerPackage(transaction.getController());
        if (pack == null) {
            return;
        }
        if (!pack.needsStartEvents()) {
            return;
        }
        List<SampleListener> listeners = pack.getSampleListeners();
        SampleResult started = transaction.createStartedResult();
        fillThreadInformation(started, threadGroup.getNumberOfThreads(), JMeterContextService.getNumberOfThreads());
        if (needsSourceTestElementPath(sampleResultMetadataRequirements(listeners))) {
            setSourceTestElementPath(started, pack.getSourceTestElementPath());
        }
        notifier.notifyTransactionStarted(new SampleEvent(started, threadGroup.getName(), threadVars, true), listeners);
    }

    /**
     * Sends the finished transaction sample to the listeners in scope of the transaction controller.
     * Called by {@link JMeterContext#endTransaction} and {@link JMeterContext#endTransactionsUntil}.
     */
    void notifyTransactionFinished(RunningTransaction transaction, SampleResult result) {
        if (!transaction.hasStartedSampler()) {
            if (!isIterationRunning()) {
                return;
            }
            // Naturally empty transactions still report their normal completion.
            notifyTransactionStarted(transaction);
        }
        fillThreadInformation(result, threadGroup.getNumberOfThreads(), JMeterContextService.getNumberOfThreads());
        SamplePackage pack = compiler.getTransactionControllerPackage(transaction.getController());
        if (pack == null) {
            log.warn("Could not find the listeners of transaction {}", transaction.getRef().getName());
            return;
        }
        List<SampleListener> listeners = pack.getSampleListeners();
        int metadataRequirements = sampleResultMetadataRequirements(listeners);
        if (needsSourceTestElementPath(metadataRequirements)) {
            setSourceTestElementPath(result, pack.getSourceTestElementPath());
        }
        notifyListeners(listeners, result, metadataRequirements, true);
    }

    /**
     * Store {@link JMeterThread#LAST_SAMPLE_OK} in JMeter Variables context
     */
    private static void setLastSampleOk(JMeterVariables variables, boolean value) {
        variables.put(LAST_SAMPLE_OK, Boolean.toString(value));
    }

    /**
     * @param threadContext
     * @return the iteration listener
     */
    private IterationListener initRun(JMeterContext threadContext) {
        threadVars.putObject(JMeterVariables.VAR_IS_SAME_USER_KEY, isSameUserOnNextIteration);
        threadContext.setVariables(threadVars);
        threadContext.setThreadNum(getThreadNum());
        setLastSampleOk(threadVars, true);
        threadContext.setThread(this);
        threadContext.setThreadGroup(threadGroup);
        threadContext.setEngine(engine);
        threadContext.setCurrentTransaction(null);
        testTree.traverse(compiler);
        if (scheduler) {
            // set the scheduler to start
            startScheduler();
        }

        rampUpDelay(); // TODO - how to handle thread stopped here
        if (log.isInfoEnabled()) {
            log.info("Thread started: {}", Thread.currentThread().getName());
        }
        // Setting SamplingStarted before the controllers are initialised allows
        // them to access the running values of functions and variables (however
        // it does not seem to help with the listeners)
        threadContext.setSamplingStarted(true);

        threadGroupLoopController.initialize();
        nextThreadGroupPacingStartTime = -1;
        IterationListener iterationListener = new IterationListener();
        threadGroupLoopController.addIterationListener(iterationListener);

        threadStarted();
        return iterationListener;
    }

    private void threadStarted() {
        JMeterContextService.incrNumberOfThreads();
        threadGroup.incrNumberOfThreads();
        updateGuiCounts();
        ThreadListenerTraverser startup = new ThreadListenerTraverser(true);
        testTree.traverse(startup); // call ThreadListener.threadStarted()
    }

    private void threadFinished(LoopIterationListener iterationListener) {
        ThreadListenerTraverser shut = new ThreadListenerTraverser(false);
        testTree.traverse(shut); // call ThreadListener.threadFinished()
        JMeterContextService.decrNumberOfThreads();
        threadGroup.decrNumberOfThreads();
        updateGuiCounts();
        if (iterationListener != null) { // probably not possible, but check anyway
            threadGroupLoopController.removeIterationListener(iterationListener);
        }
    }

    private static void updateGuiCounts() {
        GuiPackage gp = GuiPackage.getInstance();
        if (gp != null) { // check there is a GUI
            MainFrame mainFrame = gp.getMainFrame();
            if (mainFrame != null) {
                mainFrame.updateCounts();
            }
        }
    }

    // N.B. This is only called at the start and end of a thread, so there is not
    // necessary to cache the search results, thus saving memory
    static class ThreadListenerTraverser implements HashTreeTraverser {
        private final boolean isStart;

        ThreadListenerTraverser(boolean start) {
            isStart = start;
        }

        @Override
        public void addNode(Object node, HashTree subTree) {
            if (node instanceof ThreadListener tl) {
                if (isStart) {
                    try {
                        tl.threadStarted();
                    } catch (Exception e) {
                        log.error("Error calling threadStarted", e);
                    }
                } else {
                    try {
                        tl.threadFinished();
                    } catch (Exception e) {
                        log.error("Error calling threadFinished", e);
                    }
                }
            }
        }

        @Override
        public void subtractNode() {
            // NOOP
        }

        @Override
        public void processPath() {
            // NOOP
        }
    }

    public String getThreadName() {
        return threadName;
    }

    /** @return whether the calling flow may start another sampler in this iteration */
    public boolean isIterationRunning() {
        return running && forkIterationEndAction == null && !isCurrentForkStopRequested();
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Registers an action that runs once after detached workers and thread listeners have finished.
     * Registrations with the same key are deduplicated.
     *
     * @param key resource-owner key used to deduplicate registrations
     * @param cleanup action to run during virtual-user cleanup
     */
    @API(status = API.Status.INTERNAL)
    public void registerThreadCleanup(Object key, Runnable cleanup) {
        threadCleanupActions.putIfAbsent(
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(cleanup, "cleanup"));
    }

    private void runThreadCleanupActions() {
        List<Runnable> actions = new ArrayList<>(threadCleanupActions.values());
        threadCleanupActions.clear();
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.error("Error running thread cleanup action", e);
            }
        }
    }

    /**
     * Set running flag to false which will interrupt JMeterThread on next flag test.
     * This is a clean shutdown.
     */
    public void stop() { // Called by StandardJMeterEngine, TestAction and AccessLogSampler
        running = false;
        stopTimers();
        stopSampler();
        stopForksNow();
        log.info("Stopping: {}", threadName);
    }

    /**
     * Requests all currently running forks of this virtual user to end gracefully. Running fork
     * samplers are allowed to return normally, while fork timers and pacing waits are woken so the
     * fork flow can stop before starting another sampler. This method is intentionally public so
     * JSR223/Groovy scripts can call {@code ctx.getThread().stopForks()}.
     */
    public void stopForks() {
        List<Future<?>> tasks = runningForkTasks();
        for (Future<?> task : tasks) {
            requestForkStop(task, false);
        }
        stopForkTimers();
        interruptForkThreads();
    }

    /**
     * Immediately stops all currently running forks of this virtual user. This wakes fork timers,
     * calls {@link StoppableSampler#stop()} on active fork samplers that support it, cancels fork
     * tasks and interrupts fork workers. This method is intentionally public so JSR223/Groovy
     * scripts can call {@code ctx.getThread().stopForksNow()}.
     */
    public void stopForksNow() {
        List<Future<?>> tasks;
        List<ExecutorService> executors;
        synchronized (forkLifecycleLock) {
            tasks = runningForkTasks();
            synchronized (forkExecutors) {
                executors = new ArrayList<>(forkExecutors);
            }
        }
        for (Future<?> task : tasks) {
            requestForkStop(task, false);
        }
        stopForkTimers();
        stopForkSamplers();
        // Cancellation can synchronously clean up a task that has not started. Iterate snapshots.
        for (Future<?> task : tasks) {
            task.cancel(true);
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    private void stopTimers() {
        List<? extends Timer> timers = currentTimersForInterruption;
        if (timers != null) {
            stopTimers(timers);
        }
        stopForkTimers();
    }

    private void stopSampler() {
        Sampler sampler = currentSamplerForInterruption;
        if (sampler instanceof StoppableSampler stoppableSampler) {
            stoppableSampler.stop();
        }
        stopSamplers(currentSamplersForInterruption, sampler);
        stopForkSamplers();
    }

    private void stopForkTimers() {
        synchronized (currentForkTimersForInterruption) {
            for (List<? extends Timer> timers : currentForkTimersForInterruption) {
                stopTimers(timers);
            }
        }
    }

    private static void stopTimers(List<? extends Timer> timers) {
        for (Timer timer : timers) {
            timer.stop();
        }
    }

    private void stopForkSamplers() {
        stopSamplers(currentForkSamplersForInterruption, null);
    }

    private void interruptForkThreads() {
        synchronized (currentForkThreadsForInterruption) {
            for (Thread thread : currentForkThreadsForInterruption) {
                thread.interrupt();
            }
        }
    }

    private List<Future<?>> runningForkTasks() {
        synchronized (forkTasks) {
            return new ArrayList<>(forkTasks);
        }
    }

    private static boolean isForkWorkerThread() {
        return Boolean.TRUE.equals(FORK_WORKER_THREAD.get());
    }

    private static boolean isCurrentForkStopRequested() {
        return CURRENT_FORK_TASK.get() instanceof ForkTask task && task.stopRequested;
    }

    private static void stopSamplers(List<Sampler> samplers, Sampler excludedSampler) {
        synchronized (samplers) {
            for (Sampler currentSampler : samplers) {
                if (currentSampler != excludedSampler && currentSampler instanceof StoppableSampler stoppableSampler) {
                    stoppableSampler.stop();
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean interrupt(){
        interruptLock.lock();
        try {
            return interruptSamplers(samplersForInterruption());
        } finally {
            interruptLock.unlock();
        }
    }

    private List<Sampler> samplersForInterruption() {
        List<Sampler> samplers = new ArrayList<>();
        Sampler sampler = currentSamplerForInterruption; // fetch once; must be done under lock
        if (sampler != null) {
            samplers.add(sampler);
        }
        synchronized (currentSamplersForInterruption) {
            for (Sampler currentSampler : currentSamplersForInterruption) {
                // Dedup by identity: TestElement.equals() compares configuration, so distinct
                // concurrent samplers with identical settings must not collapse into one here.
                if (!containsIdentity(samplers, currentSampler)) {
                    samplers.add(currentSampler);
                }
            }
        }
        return samplers;
    }

    private static boolean containsIdentity(Collection<?> items, Object target) {
        synchronized (items) {
            for (Object item : items) {
                if (item == target) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean interruptSamplers(List<Sampler> samplers) {
        boolean interrupted = false;
        synchronized (samplers) {
            for (Sampler sampler : samplers) {
                interrupted |= interruptSampler(sampler);
            }
        }
        return interrupted;
    }

    private boolean interruptSampler(Sampler sampler) {
        if (sampler instanceof Interruptible interruptible) {
            if (log.isWarnEnabled()) {
                log.warn("Interrupting: {} sampler: {}", threadName, sampler.getName());
            }
            try {
                boolean found = interruptible.interrupt();
                if (!found) {
                    log.warn("No operation pending");
                }
                return found;
            } catch (Exception e) { // NOSONAR
                if (log.isWarnEnabled()) {
                    log.warn("Caught Exception interrupting sampler: {}", e.toString());
                }
            }
        } else if (sampler != null) {
            if (log.isWarnEnabled()) {
                log.warn("Sampler is not Interruptible: {}", sampler.getName());
            }
        }
        return false;
    }

    /**
     * Clean shutdown of test, which means wait for end of current running samplers
     */
    private void shutdownTest() {
        running = false;
        log.info("Shutdown Test detected by thread: {}", threadName);
        if (engine != null) {
            engine.askThreadsToStop();
        }
    }

    /**
     * Stop test immediately by interrupting running samplers
     */
    private void stopTestNow() {
        running = false;
        log.info("Stop Test Now detected by thread: {}", threadName);
        stopForksNow();
        if (engine != null) {
            engine.stopTest();
        }
    }

    /**
     * Clean Exit of current thread
     */
    private void stopThread() {
        running = false;
        log.info("Stop Thread detected by thread: {}", threadName);
    }

    private static void runPostProcessors(List<? extends PostProcessor> extractors) {
        for (PostProcessor ex : extractors) {
            TestBeanHelper.prepare((TestElement) ex);
            ex.process();
        }
    }

    private static void runPreProcessors(List<? extends PreProcessor> preProcessors) {
        for (PreProcessor ex : preProcessors) {
            if (log.isDebugEnabled()) {
                log.debug("Running preprocessor: {}", ((AbstractTestElement) ex).getName());
            }
            TestBeanHelper.prepare((TestElement) ex);
            ex.process();
        }
    }

    /**
     * Run all configured timers and sleep the total amount of time.
     * <p>
     * If the amount of time would amount to an ending after endTime, then
     * end the current thread by setting {@code running} to {@code false} and
     * return immediately.
     *
     * @param timers to be used for calculating the delay
     */
    private TimerPause delay(List<? extends Timer> timers) {
        currentTimersForInterruption = timers;
        activeTimerWorkers.put(Thread.currentThread(), timers);
        ForkWorker forkWorker = forkWorkers.get(Thread.currentThread());
        if (forkWorker != null) {
            forkWorker.timers = timers;
            forkWorker.waitingInTimer = true;
        }
        if (isForkWorkerThread()) {
            currentForkTimersForInterruption.add(timers);
        }
        try {
            if (!running || forkIterationEndAction != null || isCurrentForkStopRequested()) {
                return null;
            }
            // Timers such as the Synchronizing Timer block inside delay() and return 0, so the
            // pause starts before the timers are evaluated, not when the sleep starts
            long start = System.currentTimeMillis();
            long totalDelay = 0;
            for (Timer timer : timers) {
                TestBeanHelper.prepare((TestElement) timer);
                long delay = timer.delay();
                if (APPLY_TIMER_FACTOR && timer.isModifiable()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Applying TIMER_FACTOR:{} on timer:{} for thread:{}", TIMER_FACTOR,
                                ((TestElement) timer).getName(), getThreadName());
                    }
                    delay = Math.round(delay * TIMER_FACTOR);
                }
                totalDelay += delay;
            }
            if (totalDelay > 0) {
                if (scheduler) {
                    // We reduce pause to ensure end of test is not delayed by a sleep ending after test scheduled end
                    // See Bug 60049
                    totalDelay = TIMER_SERVICE.adjustDelay(totalDelay, endTime, false);
                    if (totalDelay < 0) {
                        log.debug("The delay would be longer than the scheduled period, so stop thread now.");
                        running = false;
                        return null;
                    }
                }
                // Use granular sleeps to allow quick response to shutdown
                long end = System.currentTimeMillis() + totalDelay;
                long now;
                long pause = TIMER_GRANULARITY;
                while (running && forkIterationEndAction == null && !isCurrentForkStopRequested() && (now = System.currentTimeMillis()) < end) {
                    long togo = end - now;
                    if (togo < pause) {
                        pause = togo;
                    }
                    try {
                        TimeUnit.MILLISECONDS.sleep(pause);
                    } catch (InterruptedException e) {
                        if (log.isDebugEnabled() && running && forkIterationEndAction == null && !isCurrentForkStopRequested()) {
                            log.debug("The delay timer was interrupted - Loss of delay for {} was {}ms out of {}ms",
                                    threadName, end - System.currentTimeMillis(), totalDelay);
                        }
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            long pauseEnd = System.currentTimeMillis();
            return pauseEnd > start ? new TimerPause(start, pauseEnd) : null;
        } finally {
            currentTimersForInterruption = null;
            synchronized (activeTimerWorkers) {
                activeTimerWorkers.remove(Thread.currentThread());
            }
            // Remove by identity: List#remove uses equals(), and equally-configured timer lists
            // from sibling branches would otherwise remove the wrong entry.
            currentForkTimersForInterruption.removeIf(candidate -> candidate == timers);
            if (forkWorker != null) {
                forkWorker.waitingInTimer = false;
                forkWorker.timers = List.of();
            }
        }
    }

    private record TimerPause(long startTime, long endTime) {
    }

    void notifyTestListeners() {
        resetUserVariablesForNewIteration();
        threadVars.incIteration();
        for (TestIterationListener listener : testIterationStartListeners) {
            listener.testIterationStart(new LoopIterationEvent(threadGroupLoopController, threadVars.getIteration()));
            if (listener instanceof TestElement testElement) {
                testElement.recoverRunningVersion();
            }
        }
    }

    private void resetUserVariablesForNewIteration() {
        if (isSameUserOnNextIteration || threadVars.getIteration() == 0) {
            return;
        }
        synchronized (initialVariables) {
            threadVars.clear();
            threadVars.putAll(initialVariables);
        }
        threadVars.putObject(JMeterVariables.VAR_IS_SAME_USER_KEY, false);
        setLastSampleOk(threadVars, true);
    }

    void applyThreadGroupPacing() {
        if (JMeterContextService.isValidationRun() || threadGroup == null) {
            return;
        }
        long pacingDelay = computeThreadGroupPacingDelay(System.currentTimeMillis());
        if (pacingDelay > 0) {
            delayBy(pacingDelay, "ThreadGroup pacing");
        }
        if (scheduler) {
            stopSchedulerIfNeeded();
        }
        if (running) {
            recordThreadGroupIterationStart(System.currentTimeMillis());
        }
    }

    long computeThreadGroupPacingDelay(long now) {
        if (nextThreadGroupPacingStartTime < 0) {
            return 0;
        }
        return Math.max(0, nextThreadGroupPacingStartTime - now);
    }

    void recordThreadGroupIterationStart(long actualStartTime) {
        long targetPacing = computeThreadGroupPacingTarget();
        if (targetPacing <= 0) {
            nextThreadGroupPacingStartTime = actualStartTime;
        } else if (nextThreadGroupPacingStartTime < 0) {
            nextThreadGroupPacingStartTime = actualStartTime + targetPacing;
        } else {
            nextThreadGroupPacingStartTime += targetPacing;
        }
    }

    private long computeThreadGroupPacingTarget() {
        String mode = threadGroup.getPacingMode();
        return switch (mode) {
            case AbstractThreadGroup.PACING_FIXED -> readPacingValue(threadGroup.getFixedPacing(), "fixed pacing");
            case AbstractThreadGroup.PACING_RANDOM -> randomThreadGroupPacing();
            case AbstractThreadGroup.PACING_GAUSSIAN_RANDOM -> gaussianRandomThreadGroupPacing();
            default -> 0;
        };
    }

    private long randomThreadGroupPacing() {
        long min = readPacingValue(threadGroup.getPacingMin(), "minimum pacing");
        long max = readPacingValue(threadGroup.getPacingMax(), "maximum pacing");
        validatePacingRange(min, max);
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private long gaussianRandomThreadGroupPacing() {
        long min = readPacingValue(threadGroup.getPacingMin(), "minimum pacing");
        long max = readPacingValue(threadGroup.getPacingMax(), "maximum pacing");
        validatePacingRange(min, max);
        if (min == max) {
            return min;
        }
        double midpoint = min + (max - min) / 2.0d;
        double standardDeviation = (max - min) / 6.0d;
        long pacing = Math.round(midpoint + ThreadLocalRandom.current().nextGaussian() * standardDeviation);
        return Math.clamp(pacing, min, max);
    }

    private static long readPacingValue(String rawValue, String fieldName) {
        try {
            long value = Long.parseLong(rawValue.trim());
            if (value < 0) {
                throw new IllegalArgumentException("Thread Group " + fieldName + " must be >= 0");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Thread Group " + fieldName
                    + " must resolve to a whole number of milliseconds: " + rawValue, e);
        }
    }

    private static void validatePacingRange(long min, long max) {
        if (max < min) {
            throw new IllegalArgumentException("Thread Group maximum pacing must be >= minimum pacing");
        }
        if (max == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Thread Group maximum pacing must be less than Long.MAX_VALUE");
        }
    }

    private void notifyListeners(List<SampleListener> listeners, SampleResult result, int metadataRequirements,
            boolean transaction) {
        notifyListeners(listeners, result, metadataRequirements, transaction, null);
    }

    private void notifyListeners(List<SampleListener> listeners, SampleResult result, int metadataRequirements,
            boolean transaction, SampleResult startedSample) {
        if (needsJMeterVariables(metadataRequirements)) {
            setJMeterVariables(result, snapshotVariables(threadVars));
        }
        SampleEvent event = new SampleEvent(result, threadGroup.getName(), threadVars, transaction);
        event.setStartedSample(startedSample);
        notifier.notifyListeners(event, listeners);
    }

    private static int sampleResultMetadataRequirements(List<SampleListener> listeners) {
        int metadataRequirements = 0;
        for (SampleListener listener : listeners) {
            if (listener.needsJMeterVariables()) {
                metadataRequirements |= JMETER_VARIABLES_METADATA;
            }
            if (listener.needsSourceTestElementPath()) {
                metadataRequirements |= SOURCE_TEST_ELEMENT_PATH_METADATA;
            }
            if (metadataRequirements == ALL_SAMPLE_RESULT_METADATA) {
                break;
            }
        }
        return metadataRequirements;
    }

    private static boolean needsJMeterVariables(int metadataRequirements) {
        return (metadataRequirements & JMETER_VARIABLES_METADATA) != 0;
    }

    private static boolean needsSourceTestElementPath(int metadataRequirements) {
        return (metadataRequirements & SOURCE_TEST_ELEMENT_PATH_METADATA) != 0;
    }

    private static Map<String, String> snapshotVariables(JMeterVariables variables) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            Object value = entry.getValue();
            snapshot.put(entry.getKey(), value == null ? "" : value.toString()); // $NON-NLS-1$
        }
        return snapshot;
    }

    private static Map<String, Object> snapshotVariableObjects(JMeterVariables variables) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue());
        }
        return snapshot;
    }

    private static void setJMeterVariables(SampleResult sample, Map<String, String> variables) {
        if (!sample.hasJMeterVariables()) {
            sample.setJMeterVariables(variables);
        }
        for (SampleResult subResult : sample.getSubResults()) {
            setJMeterVariables(subResult, variables);
        }
    }

    private static void setSourceTestElementPath(SampleResult sample,
            List<SampleResult.TestElementPathEntry> sourceTestElementPath) {
        if (sample.getSourceTestElementPath().isEmpty()) {
            sample.setSourceTestElementPath(sourceTestElementPath);
        }
        for (SampleResult subResult : sample.getSubResults()) {
            setSourceTestElementPath(subResult, sourceTestElementPath);
        }
    }

    /**
     * Set rampup delay for JMeterThread Thread
     *
     * @param delay Rampup delay for JMeterThread
     */
    public void setInitialDelay(int delay) {
        initialDelay = delay;
    }

    /**
     * Initial delay if ramp-up period is active for this threadGroup.
     */
    private void rampUpDelay() {
        delayBy(initialDelay, "RampUp");
    }

    /**
     * Wait for delay with RAMPUP_GRANULARITY
     *
     * @param delay delay in ms
     * @param type  Delay type
     */
    protected final void delayBy(long delay, String type) {
        if (delay > 0) {
            long start = System.currentTimeMillis();
            long end = start + delay;
            long now;
            long pause = RAMPUP_GRANULARITY;
            while (running && (now = System.currentTimeMillis()) < end) {
                long togo = end - now;
                if (togo < pause) {
                    pause = togo;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(pause); // delay between checks
                } catch (InterruptedException e) {
                    if (running) { // NOSONAR running may have been changed from another thread
                        log.warn("{} delay for {} was interrupted. Waited {} milli-seconds out of {}", type, threadName,
                                now - start, delay);
                    }
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Returns the threadNum.
     *
     * @return the threadNum
     */
    public int getThreadNum() {
        return threadNum;
    }

    /**
     * Sets the threadNum.
     *
     * @param threadNum the threadNum to set
     */
    public void setThreadNum(int threadNum) {
        this.threadNum = threadNum;
    }

    private class IterationListener implements LoopIterationListener {
        /**
         * {@inheritDoc}
         */
        @Override
        public void iterationStart(LoopIterationEvent iterEvent) {
            if (threadVars.getIteration() > 0) {
                applyForkBoundary(false);
                if (forkIterationEndAction != null) {
                    completeFailedForkIteration(JMeterContextService.getContext());
                }
            }
            applyThreadGroupPacing();
            if (running) {
                notifyTestListeners();
            }
        }
    }

    /**
     * Save the engine instance for access to the stop methods
     *
     * @param engine the engine which is used
     */
    public void setEngine(StandardJMeterEngine engine) {
        this.engine = engine;
    }

    /**
     * Should Test stop on sampler error?
     *
     * @param b true or false
     */
    public void setOnErrorStopTest(boolean b) {
        onErrorStopTest = b;
    }

    /**
     * Should Test stop abruptly on sampler error?
     *
     * @param b true or false
     */
    public void setOnErrorStopTestNow(boolean b) {
        onErrorStopTestNow = b;
    }

    /**
     * Should Thread stop on Sampler error?
     *
     * @param b true or false
     */
    public void setOnErrorStopThread(boolean b) {
        onErrorStopThread = b;
    }

    /**
     * Should Thread start next loop on Sampler error?
     *
     * @param b true or false
     */
    public void setOnErrorStartNextLoop(boolean b) {
        onErrorStartNextLoop = b;
    }

    public void setThreadGroup(AbstractThreadGroup group) {
        this.threadGroup = group;
    }

    /**
     * @return {@link ListedHashTree}
     */
    public ListedHashTree getTestTree() {
        return (ListedHashTree) testTree;
    }

    /**
     * @return {@link ListenerNotifier}
     */
    public ListenerNotifier getNotifier() {
        return notifier;
    }

}
