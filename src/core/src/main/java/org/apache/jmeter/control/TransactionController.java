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

import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.schema.PropertiesAccessor;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jorphan.util.JMeterStopThreadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transaction Controller to measure transaction times
 *
 * There are two different modes for the controller:
 * - generate additional total sample after nested samples (as in JMeter 2.2)
 * - generate parent sampler containing the nested samples
 *
 */
public class TransactionController extends GenericController implements SampleListener, Controller, Serializable {
    /**
     * Used to identify Transaction Controller Parent Sampler
     */
    static final String NUMBER_OF_SAMPLES_IN_TRANSACTION_PREFIX = "Number of samples in transaction : ";

    private static final long serialVersionUID = 234L;

    private static final String TRUE = Boolean.toString(true); // i.e. "true"

    public static final String DELAY_DISABLED = "Disabled"; // $NON-NLS-1$

    public static final String DELAY_FIXED = "Fixed"; // $NON-NLS-1$

    public static final String DELAY_RANDOM = "Random"; // $NON-NLS-1$

    public static final String DELAY_GAUSSIAN_RANDOM = "Gaussian Random"; // $NON-NLS-1$

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    /**
     * Only used in parent Mode
     */
    private transient TransactionSampler transactionSampler;

    /**
     * Only used in NON parent Mode
     */
    private transient ListenerNotifier lnf;

    /**
     * Only used in NON parent Mode
     */
    private transient SampleResult res;

    /**
     * Only used in NON parent Mode
     */
    private transient int calls;

    /**
     * Only used in NON parent Mode
     */
    private transient int noFailingSamples;

    /**
     * Cumulated pause time to exclude timer and post/pre processor times
     * Only used in NON parent Mode
     */
    private transient long pauseTime;

    /**
     * Previous end time
     * Only used in NON parent Mode
     */
    private transient long prevEndTime;

    /**
     * Next scheduled transaction start time, used to calculate start-to-start pacing without drift.
     */
    private transient long nextPacingStartTime = -1;

    /**
     * Creates a Transaction Controller
     */
    public TransactionController() {
        lnf = new ListenerNotifier();
    }

    @Override
    public void initialize() {
        nextPacingStartTime = -1;
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
    protected Object readResolve(){
        super.readResolve();
        lnf = new ListenerNotifier();
        return this;
    }

    /**
     * @param generateParent flag whether a parent sample should be generated.
     */
    public void setGenerateParentSample(boolean generateParent) {
        set(getSchema().getGenearteParentSample(), generateParent);
    }

    /**
     * @return {@code true} if a parent sample will be generated
     */
    public boolean isGenerateParentSample() {
        return get(getSchema().getGenearteParentSample());
    }

    /**
     * @see org.apache.jmeter.control.Controller#next()
     */
    @Override
    public Sampler next(){
        if (isGenerateParentSample()){
            return nextWithTransactionSampler();
        }
        return nextWithoutTransactionSampler();
    }

///////////////// Transaction Controller - parent ////////////////

    private Sampler nextWithTransactionSampler() {
        // Check if transaction is done
        if(transactionSampler != null && transactionSampler.isTransactionDone()) {
            if (log.isDebugEnabled()) {
                log.debug("End of transaction {}", getName());
            }
            // This transaction is done
            transactionSampler = null;
            return null;
        }

        // Check if it is the start of a new transaction
        if (isFirst()) // must be the start of the subtree
        {
            if (log.isDebugEnabled()) {
                log.debug("Start of transaction {}", getName());
            }
            applyTransactionPacing();
            applyTransactionDelay();
            recordTransactionStart(System.currentTimeMillis());
            transactionSampler = new TransactionSampler(this, getName());
        }

        // Sample the children of the transaction
        Sampler subSampler = super.next();
        transactionSampler.setSubSampler(subSampler);
        // If we do not get any sub samplers, the transaction is done
        if (subSampler == null) {
            transactionSampler.setTransactionDone();
        }
        return transactionSampler;
    }

    @Override
    protected Sampler nextIsAController(Controller controller) throws NextIsNullException {
        if (!isGenerateParentSample()) {
            return super.nextIsAController(controller);
        }
        Sampler returnValue;
        Sampler sampler = controller.next();
        if (sampler == null) {
            currentReturnedNull(controller);
            // We need to call the super.next, instead of this.next, which is done in GenericController,
            // because if we call this.next(), it will return the TransactionSampler, and we do not want that.
            // We need to get the next real sampler or controller
            returnValue = super.next();
        } else {
            returnValue = sampler;
        }
        return returnValue;
    }

////////////////////// Transaction Controller - additional sample //////////////////////////////

    private Sampler nextWithoutTransactionSampler() {
        if (isFirst()) // must be the start of the subtree
        {
            applyTransactionPacing();
            applyTransactionDelay();
            recordTransactionStart(System.currentTimeMillis());
            calls = 0;
            noFailingSamples = 0;
            res = new SampleResult();
            res.setSampleLabel(getName());
            // Assume success
            res.setSuccessful(true);
            res.sampleStart();
            prevEndTime = res.getStartTime();//???
            pauseTime = 0;
        }
        boolean isLast = current==super.subControllersAndSamplers.size();
        Sampler returnValue = super.next();
        if (returnValue == null && isLast) // Must be the end of the controller
        {
            if (res != null) {
                // See BUG 55816
                if (!isIncludeTimers()) {
                    long processingTimeOfLastChild = res.currentTimeInMillis() - prevEndTime;
                    pauseTime += processingTimeOfLastChild;
                }
                res.setIdleTime(pauseTime+res.getIdleTime());
                res.sampleEnd();
                res.setResponseMessage(
                        TransactionController.NUMBER_OF_SAMPLES_IN_TRANSACTION_PREFIX
                                + calls + ", number of failing samples : "
                                + noFailingSamples);
                if(res.isSuccessful()) {
                    res.setResponseCodeOK();
                }
                notifyListeners();
            }
        }
        else {
            // We have sampled one of our children
            calls++;
        }

        return returnValue;
    }

    /**
     * @param res {@link SampleResult}
     * @return true if res is the ParentSampler transactions
     */
    public static boolean isFromTransactionController(SampleResult res) {
        return res.getResponseMessage() != null &&
                res.getResponseMessage().startsWith(
                        TransactionController.NUMBER_OF_SAMPLES_IN_TRANSACTION_PREFIX);
    }

    /**
     * @see org.apache.jmeter.control.GenericController#triggerEndOfLoop()
     */
    @Override
    public void triggerEndOfLoop() {
        if(!isGenerateParentSample()) {
            if (res != null) {
                res.setIdleTime(pauseTime + res.getIdleTime());
                res.sampleEnd();
                res.setSuccessful(TRUE.equals(JMeterContextService.getContext().getVariables().get(JMeterThread.LAST_SAMPLE_OK)));
                res.setResponseMessage(
                        TransactionController.NUMBER_OF_SAMPLES_IN_TRANSACTION_PREFIX
                                + calls + ", number of failing samples : "
                                + noFailingSamples);
                notifyListeners();
            }
        } else if (transactionSampler != null) {
            Sampler subSampler = transactionSampler.getSubSampler();
            // See Bug 56811
            // triggerEndOfLoop is called when error occurs to end Main Loop
            // in this case normal workflow doesn't happen, so we need
            // to notify the children of TransactionController and
            // update them with SubSamplerResult
            if(subSampler instanceof TransactionSampler tc) {
                transactionSampler.addSubSamplerResult(tc.getTransactionResult());
            }
            transactionSampler.setTransactionDone();
            // This transaction is done
            transactionSampler = null;
        }
        super.triggerEndOfLoop();
    }

    /**
     * Create additional SampleEvent in NON Parent Mode
     */
    protected void notifyListeners() {
        // TODO could these be done earlier (or just once?)
        JMeterContext threadContext = getThreadContext();
        JMeterVariables threadVars = threadContext.getVariables();
        SamplePackage pack = (SamplePackage) threadVars.getObject(JMeterThread.PACKAGE_OBJECT);
        if (pack == null) {
            // If child of TransactionController is a ThroughputController and TPC does
            // not sample its children, then we will have this
            // TODO Should this be at warn level ?
            log.warn("Could not fetch SamplePackage");
        } else {
            SampleEvent event = new SampleEvent(res, threadContext.getThreadGroup().getName(),threadVars, true);
            // We must set res to null now, before sending the event for the transaction,
            // so that we can ignore that event in our sampleOccurred method
            res = null;
            lnf.notifyListeners(event, pack.getSampleListeners());
        }
    }

    @Override
    public void sampleOccurred(SampleEvent se) {
        if (!isGenerateParentSample()) {
            // Check if we are still sampling our children
            if(res != null && !se.isTransactionSampleEvent()) {
                SampleResult sampleResult = se.getResult();
                res.setThreadName(sampleResult.getThreadName());
                res.setBytes(res.getBytesAsLong() + sampleResult.getBytesAsLong());
                res.setSentBytes(res.getSentBytes() + sampleResult.getSentBytes());
                if (!isIncludeTimers()) {// Accumulate waiting time for later
                    pauseTime += sampleResult.getEndTime() - sampleResult.getTime() - prevEndTime;
                    prevEndTime = sampleResult.getEndTime();
                }
                if(!sampleResult.isSuccessful()) {
                    res.setSuccessful(false);
                    noFailingSamples++;
                }
                res.setAllThreads(sampleResult.getAllThreads());
                res.setGroupThreads(sampleResult.getGroupThreads());
                res.setLatency(res.getLatency() + sampleResult.getLatency());
                res.setConnectTime(res.getConnectTime() + sampleResult.getConnectTime());
            }
        }
    }

    @Override
    public void sampleStarted(SampleEvent e) {
    }

    @Override
    public void sampleStopped(SampleEvent e) {
    }

    /**
     * Whether to include timers and pre/post processor time in overall sample.
     * @param includeTimers Flag whether timers and pre/post processor should be included in overall sample
     */
    public void setIncludeTimers(boolean includeTimers) {
        set(getSchema().getIncludeTimers(), includeTimers);
    }

    /**
     * Whether to include timer and pre/post processor time in overall sample.
     *
     * @return boolean (defaults to true for backwards compatibility)
     */
    public boolean isIncludeTimers() {
        return get(getSchema().getIncludeTimers());
    }

    public void setDelayMode(String delayMode) {
        set(getSchema().getDelayMode(), delayMode);
    }

    public String getDelayMode() {
        return get(getSchema().getDelayMode());
    }

    public void setFixedDelay(String fixedDelay) {
        set(getSchema().getFixedDelay(), fixedDelay);
    }

    public String getFixedDelay() {
        return getString(getSchema().getFixedDelay());
    }

    public void setDelayMin(String delayMin) {
        set(getSchema().getDelayMin(), delayMin);
    }

    public String getDelayMin() {
        return getString(getSchema().getDelayMin());
    }

    public void setDelayMax(String delayMax) {
        set(getSchema().getDelayMax(), delayMax);
    }

    public String getDelayMax() {
        return getString(getSchema().getDelayMax());
    }

    public void setPacingMode(String pacingMode) {
        set(getSchema().getPacingMode(), pacingMode);
    }

    public String getPacingMode() {
        return get(getSchema().getPacingMode());
    }

    public void setFixedPacing(String fixedPacing) {
        set(getSchema().getFixedPacing(), fixedPacing);
    }

    public String getFixedPacing() {
        return getString(getSchema().getFixedPacing());
    }

    public void setPacingMin(String pacingMin) {
        set(getSchema().getPacingMin(), pacingMin);
    }

    public String getPacingMin() {
        return getString(getSchema().getPacingMin());
    }

    public void setPacingMax(String pacingMax) {
        set(getSchema().getPacingMax(), pacingMax);
    }

    public String getPacingMax() {
        return getString(getSchema().getPacingMax());
    }

    private void applyTransactionPacing() {
        long pacingDelay = computeTransactionPacingDelay();
        if (pacingDelay <= 0) {
            return;
        }
        sleep(pacingDelay, "pacing");
    }

    private void applyTransactionDelay() {
        long delay = computeTransactionDelay();
        if (delay <= 0) {
            return;
        }
        sleep(delay, "delay");
    }

    private static void sleep(long delay, String reason) {
        try {
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JMeterStopThreadException("Transaction Controller " + reason + " was interrupted");
        }
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
