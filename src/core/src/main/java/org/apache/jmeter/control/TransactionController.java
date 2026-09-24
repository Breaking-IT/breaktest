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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.schema.PropertiesAccessor;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.JMeterStopThreadException;

/**
 * Transaction Controller to measure transaction times.
 * <p>
 * Every sample inside the transaction is sent to its listeners as soon as it completes, carrying a
 * reference to the transaction it ran in ({@link SampleResult#getParentTransaction()}). Listeners are
 * told when the transaction starts ({@link org.apache.jmeter.samplers.SampleListener#transactionStarted})
 * and receive the transaction sample when it ends ({@link SampleResult#getTransaction()}). The
 * transaction only keeps running totals of its samples, never the samples themselves.
 */
public class TransactionController extends GenericController implements Controller, Serializable {
    /**
     * Start of the response message of every transaction sample, used to identify transaction
     * samples that only keep their message, such as samples read from a results file or summaries
     */
    public static final String NUMBER_OF_SAMPLES_IN_TRANSACTION_PREFIX = "Number of samples in transaction : ";

    private static final long serialVersionUID = 234L;

    private static final String TRUE = Boolean.toString(true); // i.e. "true"

    /**
     * Property of the removed "Generate parent sample" option. It is dropped when a plan is loaded,
     * so it is not saved again.
     */
    private static final String GENERATE_PARENT_SAMPLE = "TransactionController.parent"; // $NON-NLS-1$

    private static final int TIMER_GRANULARITY =
            JMeterUtils.getPropDefault("jmeterthread.timer.granularity", 1000); // $NON-NLS-1$

    public static final String DELAY_DISABLED = "Disabled"; // $NON-NLS-1$

    public static final String DELAY_FIXED = "Fixed"; // $NON-NLS-1$

    public static final String DELAY_RANDOM = "Random"; // $NON-NLS-1$

    public static final String DELAY_GAUSSIAN_RANDOM = "Gaussian Random"; // $NON-NLS-1$

    public static final String TIMING_MODE_SUM_CHILD_SAMPLES = "sum_child_samples"; // $NON-NLS-1$

    public static final String TIMING_MODE_TOTAL_INCLUDE_TIMERS = "total_include_timers"; // $NON-NLS-1$

    public static final String TIMING_MODE_TOTAL_EXCLUDE_TIMERS = "total_exclude_timers"; // $NON-NLS-1$

    /**
     * The transaction this controller is running, {@code null} between transactions
     */
    private transient RunningTransaction runningTransaction;

    /**
     * Next scheduled transaction start time, used to calculate start-to-start pacing without drift.
     */
    private transient long nextPacingStartTime = -1;

    /**
     * The controller this instance was cloned from, set only on the per-branch clones created for
     * parallel and fork execution. Compiled sample packages are keyed by the controller in the
     * compiled test tree, so {@link org.apache.jmeter.threads.TestCompiler} follows this chain
     * when a clone itself has no compiled package.
     */
    private transient TransactionController sourceController;

    /**
     * Creates a Transaction Controller
     */
    public TransactionController() {
    }

    @Override
    public void initialize() {
        nextPacingStartTime = -1;
        runningTransaction = null;
        super.initialize();
    }

    @Override
    public TransactionControllerSchema getSchema() {
        return TransactionControllerSchema.INSTANCE;
    }

    @Override
    public PropertiesAccessor<? extends TransactionController, ? extends TransactionControllerSchema> getProps() {
        return new PropertiesAccessor<>(this, getSchema());
    }

    @Override
    public void setProperty(JMeterProperty property) {
        if (GENERATE_PARENT_SAMPLE.equals(property.getName())) {
            return;
        }
        super.setProperty(property);
    }

    /**
     * @param sourceController the controller this parallel/fork branch clone was created from
     */
    public void setSourceController(TransactionController sourceController) {
        this.sourceController = sourceController;
    }

    /**
     * @return the controller this parallel/fork branch clone was created from, or {@code null}
     */
    public TransactionController getSourceController() {
        return sourceController;
    }

    /**
     * The "Generate parent sample" option has been removed: samples are always sent to listeners
     * as they complete and point to their transaction.
     *
     * @param generateParent ignored
     * @deprecated has no effect
     */
    @Deprecated
    public void setGenerateParentSample(boolean generateParent) {
        // The option was removed, see class documentation
    }

    /**
     * @return always {@code false}
     * @deprecated the "Generate parent sample" option has been removed
     */
    @Deprecated
    public boolean isGenerateParentSample() {
        return false;
    }

    /**
     * @see org.apache.jmeter.control.Controller#next()
     */
    @Override
    public Sampler next() {
        if (isFirst()) { // must be the start of the subtree
            applyTransactionPacing();
            applyTransactionDelay();
            recordTransactionStart(System.currentTimeMillis());
            startTransaction();
        }
        boolean isLast = current == super.subControllersAndSamplers.size();
        Sampler returnValue = super.next();
        if (returnValue == null && isLast) { // Must be the end of the controller
            endTransaction(true);
        }
        return returnValue;
    }

    private void startTransaction() {
        if (runningTransaction != null) {
            // The previous execution never reached its end, e.g. an enclosing loop restarted
            // without ending this controller: report it before starting the next one
            endTransaction(true);
        }
        // Not getThreadContext(): it can return a context cached for an earlier virtual user when a
        // thread is reused, while JMeterContextService holds the context of the flow running now,
        // including parallel and fork workers
        runningTransaction = JMeterContextService.getContext().startTransaction(this, getTimingMode());
    }

    private void endTransaction(boolean successful) {
        RunningTransaction transaction = runningTransaction;
        if (transaction != null) {
            runningTransaction = null;
            JMeterContextService.getContext().endTransaction(transaction, successful);
        }
    }

    /**
     * @param res {@link SampleResult}
     * @return true if res is the sample generated by a Transaction Controller
     */
    public static boolean isFromTransactionController(SampleResult res) {
        // Samples read back from a results file only have the response message
        return res.isTransaction()
                || res.getResponseMessage() != null
                && res.getResponseMessage().startsWith(NUMBER_OF_SAMPLES_IN_TRANSACTION_PREFIX);
    }

    /**
     * Ends the running transaction when an error or a Flow Control Action leaves this controller
     * early. The transaction fails when the sample that caused it failed.
     *
     * @see org.apache.jmeter.control.GenericController#triggerEndOfLoop()
     */
    @Override
    public void triggerEndOfLoop() {
        endTransaction(TRUE.equals(
                JMeterContextService.getContext().getVariables().get(JMeterThread.LAST_SAMPLE_OK)));
        super.triggerEndOfLoop();
    }

    /**
     * Whether to include timers and pre/post processor time in overall sample.
     * @param includeTimers Flag whether timers and pre/post processor should be included in overall sample
     */
    public void setIncludeTimers(boolean includeTimers) {
        set(getSchema().getIncludeTimers(), includeTimers);
        setTimingMode(includeTimers ? TIMING_MODE_TOTAL_INCLUDE_TIMERS : TIMING_MODE_SUM_CHILD_SAMPLES);
    }

    /**
     * Whether to include timer and pre/post processor time in overall sample.
     *
     * @return boolean (defaults to true for backwards compatibility)
     */
    public boolean isIncludeTimers() {
        return !TIMING_MODE_SUM_CHILD_SAMPLES.equals(getTimingMode());
    }

    public void setTimingMode(String timingMode) {
        if (TIMING_MODE_TOTAL_EXCLUDE_TIMERS.equals(timingMode)) {
            set(getSchema().getTimingMode(), TIMING_MODE_TOTAL_EXCLUDE_TIMERS);
            set(getSchema().getIncludeTimers(), true);
        } else if (TIMING_MODE_SUM_CHILD_SAMPLES.equals(timingMode)) {
            set(getSchema().getTimingMode(), TIMING_MODE_SUM_CHILD_SAMPLES);
            set(getSchema().getIncludeTimers(), false);
        } else {
            set(getSchema().getTimingMode(), TIMING_MODE_TOTAL_INCLUDE_TIMERS);
            set(getSchema().getIncludeTimers(), true);
        }
    }

    public String getTimingMode() {
        String timingMode = getString(getSchema().getTimingMode());
        if (TIMING_MODE_SUM_CHILD_SAMPLES.equals(timingMode)
                || TIMING_MODE_TOTAL_INCLUDE_TIMERS.equals(timingMode)
                || TIMING_MODE_TOTAL_EXCLUDE_TIMERS.equals(timingMode)) {
            return timingMode;
        }
        return get(getSchema().getIncludeTimers())
                ? TIMING_MODE_TOTAL_INCLUDE_TIMERS
                : TIMING_MODE_SUM_CHILD_SAMPLES;
    }

    public void setDelayMode(String delayMode) {
        setProperty(getSchema().getDelayMode().getName(), delayMode, DELAY_DISABLED);
    }

    public String getDelayMode() {
        return get(getSchema().getDelayMode());
    }

    public void setFixedDelay(String fixedDelay) {
        setProperty(getSchema().getFixedDelay().getName(), fixedDelay, "0"); // $NON-NLS-1$
    }

    public String getFixedDelay() {
        return getString(getSchema().getFixedDelay());
    }

    public void setDelayMin(String delayMin) {
        setProperty(getSchema().getDelayMin().getName(), delayMin, "0"); // $NON-NLS-1$
    }

    public String getDelayMin() {
        return getString(getSchema().getDelayMin());
    }

    public void setDelayMax(String delayMax) {
        setProperty(getSchema().getDelayMax().getName(), delayMax, "0"); // $NON-NLS-1$
    }

    public String getDelayMax() {
        return getString(getSchema().getDelayMax());
    }

    public void setPacingMode(String pacingMode) {
        setProperty(getSchema().getPacingMode().getName(), pacingMode, DELAY_DISABLED);
    }

    public String getPacingMode() {
        return get(getSchema().getPacingMode());
    }

    public void setFixedPacing(String fixedPacing) {
        setProperty(getSchema().getFixedPacing().getName(), fixedPacing, "0"); // $NON-NLS-1$
    }

    public String getFixedPacing() {
        return getString(getSchema().getFixedPacing());
    }

    public void setPacingMin(String pacingMin) {
        setProperty(getSchema().getPacingMin().getName(), pacingMin, "0"); // $NON-NLS-1$
    }

    public String getPacingMin() {
        return getString(getSchema().getPacingMin());
    }

    public void setPacingMax(String pacingMax) {
        setProperty(getSchema().getPacingMax().getName(), pacingMax, "0"); // $NON-NLS-1$
    }

    public String getPacingMax() {
        return getString(getSchema().getPacingMax());
    }

    private void applyTransactionPacing() {
        if (JMeterContextService.isValidationRun()) {
            return;
        }
        long pacingDelay = computeTransactionPacingDelay();
        if (pacingDelay <= 0) {
            return;
        }
        sleep(pacingDelay, "pacing");
    }

    private void applyTransactionDelay() {
        if (JMeterContextService.isValidationRun()) {
            return;
        }
        long delay = computeTransactionDelay();
        if (delay <= 0) {
            return;
        }
        sleep(delay, "delay");
    }

    private static void sleep(long delay, String reason) {
        JMeterThread thread = JMeterContextService.getContext().getThread();
        long start = System.currentTimeMillis();
        long end = start + delay;
        long now;
        long pause = TIMER_GRANULARITY;
        while (isRunning(thread) && (now = System.currentTimeMillis()) < end) {
            long togo = end - now;
            if (togo < pause) {
                pause = togo;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (isRunning(thread)) {
                    throw new JMeterStopThreadException("Transaction Controller " + reason + " was interrupted");
                }
                break;
            }
        }
    }

    private static boolean isRunning(JMeterThread thread) {
        return thread == null || thread.isRunning();
    }

    private long computeTransactionDelay() {
        String mode = getDelayMode();
        return switch (mode) {
            case DELAY_FIXED -> readDelayValue(getFixedDelay(), "fixed delay");
            case DELAY_RANDOM -> randomDelay();
            case DELAY_GAUSSIAN_RANDOM -> gaussianRandomDelay();
            default -> 0;
        };
    }

    private long computeTransactionPacingDelay() {
        return computeTransactionPacingDelay(System.currentTimeMillis());
    }

    private long computeTransactionPacingDelay(long now) {
        if (nextPacingStartTime < 0) {
            return 0;
        }
        return Math.max(0, nextPacingStartTime - now);
    }

    private long computePacingTarget() {
        String mode = getPacingMode();
        return switch (mode) {
            case DELAY_FIXED -> readDelayValue(getFixedPacing(), "fixed pacing");
            case DELAY_RANDOM -> randomPacing();
            case DELAY_GAUSSIAN_RANDOM -> gaussianRandomPacing();
            default -> 0;
        };
    }

    private long randomDelay() {
        long min = readDelayValue(getDelayMin(), "minimum delay");
        long max = readDelayValue(getDelayMax(), "maximum delay");
        validateDelayRange(min, max);
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private long randomPacing() {
        long min = readDelayValue(getPacingMin(), "minimum pacing");
        long max = readDelayValue(getPacingMax(), "maximum pacing");
        validateDelayRange(min, max);
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private long gaussianRandomDelay() {
        long min = readDelayValue(getDelayMin(), "minimum delay");
        long max = readDelayValue(getDelayMax(), "maximum delay");
        validateDelayRange(min, max);
        if (min == max) {
            return min;
        }
        double midpoint = min + (max - min) / 2.0d;
        double standardDeviation = (max - min) / 6.0d;
        long delay = Math.round(midpoint + ThreadLocalRandom.current().nextGaussian() * standardDeviation);
        return Math.clamp(delay, min, max);
    }

    private long gaussianRandomPacing() {
        long min = readDelayValue(getPacingMin(), "minimum pacing");
        long max = readDelayValue(getPacingMax(), "maximum pacing");
        validateDelayRange(min, max);
        if (min == max) {
            return min;
        }
        double midpoint = min + (max - min) / 2.0d;
        double standardDeviation = (max - min) / 6.0d;
        long pacing = Math.round(midpoint + ThreadLocalRandom.current().nextGaussian() * standardDeviation);
        return Math.clamp(pacing, min, max);
    }

    private void recordTransactionStart(long actualStartTime) {
        long targetPacing = computePacingTarget();
        if (targetPacing <= 0) {
            nextPacingStartTime = actualStartTime;
        } else if (nextPacingStartTime < 0) {
            nextPacingStartTime = actualStartTime + targetPacing;
        } else {
            nextPacingStartTime += targetPacing;
        }
    }

    private static long readDelayValue(String rawValue, String fieldName) {
        try {
            long value = Long.parseLong(rawValue.trim());
            if (value < 0) {
                throw new IllegalArgumentException("Transaction Controller " + fieldName + " must be >= 0");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Transaction Controller " + fieldName
                    + " must resolve to a whole number of milliseconds: " + rawValue, e);
        }
    }

    private static void validateDelayRange(long min, long max) {
        if (max < min) {
            throw new IllegalArgumentException("Transaction Controller maximum delay must be >= minimum delay");
        }
        if (max == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Transaction Controller maximum delay must be less than Long.MAX_VALUE");
        }
    }
}
