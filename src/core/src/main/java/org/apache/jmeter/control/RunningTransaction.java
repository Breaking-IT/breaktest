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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.TransactionRef;

/**
 * One executing transaction of a {@link TransactionController}.
 * <p>
 * It keeps running totals of the samples that complete inside it, but never the samples
 * themselves, so a sample can be released as soon as its listeners are done with it.
 * Running transactions form a chain through {@link #getEnclosing()}: the engine adds each
 * completed sample to the innermost running transaction and all transactions enclosing it.
 * <p>
 * Thread-safe: parallel branches and fork workers add samples concurrently.
 */
public final class RunningTransaction {

    private final TransactionController controller;

    private final RunningTransaction enclosing;

    private final TransactionRef ref;

    private final String timingMode;

    private final SampleResult result;

    private final List<long[]> timerPauses = new ArrayList<>();

    private int samples;

    private int failingSamples;

    /** Sum of the sample times, used by {@link TransactionController#TIMING_MODE_SUM_CHILD_SAMPLES} */
    private long samplesTime;

    private boolean finished;

    private boolean startedSampler;

    /**
     * Starts a transaction now.
     *
     * @param controller the controller running the transaction
     * @param name       transaction name
     * @param timingMode one of the {@code TransactionController.TIMING_MODE_*} values
     * @param enclosing  the running transaction this one is nested in, or {@code null}
     */
    public RunningTransaction(TransactionController controller, String name, String timingMode,
            RunningTransaction enclosing) {
        this.controller = controller;
        this.enclosing = enclosing;
        this.timingMode = timingMode;
        this.ref = TransactionRef.start(name, enclosing == null ? null : enclosing.ref);
        this.result = new SampleResult();
        result.setSampleLabel(name);
        result.setSuccessful(true);
        result.setTransaction(ref);
        result.sampleStart();
    }

    public TransactionController getController() {
        return controller;
    }

    /**
     * @return the running transaction this one is nested in, or {@code null}
     */
    public RunningTransaction getEnclosing() {
        return enclosing;
    }

    public TransactionRef getRef() {
        return ref;
    }

    /**
     * @return an unfinished copy of the transaction sample for
     * {@link org.apache.jmeter.samplers.SampleListener#transactionStarted}
     */
    public synchronized SampleResult createStartedResult() {
        return new SampleResult(result);
    }

    /** @return true only for the first sampler to start in this transaction */
    public synchronized boolean samplerStarted() {
        if (startedSampler || finished) {
            return false;
        }
        startedSampler = true;
        return true;
    }

    public synchronized boolean hasStartedSampler() {
        return startedSampler;
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    /**
     * Adds a completed sample to the running totals. Ignored once the transaction has finished,
     * which happens for samples of a fork that outlives the transaction.
     *
     * @param sample completed sample that ran inside this transaction
     */
    public synchronized void addSample(SampleResult sample) {
        if (finished) {
            return;
        }
        startedSampler = true;
        samples++;
        if (!sample.isSuccessful()) {
            if (failingSamples == 0) {
                // A failed transaction reports the response code of its first failing sample
                result.setResponseCode(sample.getResponseCode());
            }
            result.setSuccessful(false);
            failingSamples++;
        }
        samplesTime += sample.getTime();
        result.setBytes(result.getBytesAsLong() + sample.getBytesAsLong());
        result.setSentBytes(result.getSentBytes() + sample.getSentBytes());
        result.setLatency(result.getLatency() + sample.getLatency());
        result.setConnectTime(result.getConnectTime() + sample.getConnectTime());
    }

    /**
     * Records a timer pause, used by {@link TransactionController#TIMING_MODE_TOTAL_EXCLUDE_TIMERS}.
     *
     * @param startTime pause start in milliseconds
     * @param endTime   pause end in milliseconds
     */
    public synchronized void addTimerPause(long startTime, long endTime) {
        if (!finished && endTime > startTime
                && TransactionController.TIMING_MODE_TOTAL_EXCLUDE_TIMERS.equals(timingMode)) {
            timerPauses.add(new long[] { startTime, endTime });
        }
    }

    /**
     * Ends the transaction now.
     *
     * @param successful {@code false} to fail the transaction even if none of its samples failed
     * @return the finished transaction sample, or {@code null} if it had already finished
     */
    public synchronized SampleResult finish(boolean successful) {
        return finish(result.currentTimeInMillis(), successful);
    }

    /**
     * Ends the transaction at the given time.
     *
     * @param endTime    end of the transaction in milliseconds
     * @param successful {@code false} to fail the transaction even if none of its samples failed
     * @return the finished transaction sample, or {@code null} if it had already finished
     */
    public synchronized SampleResult finish(long endTime, boolean successful) {
        if (finished) {
            return null;
        }
        finished = true;
        long startTime = result.getStartTime();
        long end = Math.max(endTime, startTime);
        if (TransactionController.TIMING_MODE_SUM_CHILD_SAMPLES.equals(timingMode)) {
            result.setIdleTime(end - startTime - samplesTime);
        } else if (TransactionController.TIMING_MODE_TOTAL_EXCLUDE_TIMERS.equals(timingMode)) {
            result.setIdleTime(getMergedTimerPauseTime(startTime, end));
        }
        result.setEndTime(end);
        if (!successful) {
            result.setSuccessful(false);
        }
        result.setResponseMessage(TransactionController.NUMBER_OF_SAMPLES_IN_TRANSACTION_PREFIX
                + samples + ", number of failing samples : " + failingSamples);
        if (result.isSuccessful()) {
            result.setResponseCodeOK();
        }
        return result;
    }

    /**
     * @return total time of the merged timer pauses that fall inside {@code [windowStart, windowEnd]}
     */
    private long getMergedTimerPauseTime(long windowStart, long windowEnd) {
        List<long[]> clamped = new ArrayList<>(timerPauses.size());
        for (long[] interval : timerPauses) {
            long start = Math.max(interval[0], windowStart);
            long end = Math.min(interval[1], windowEnd);
            if (end > start) {
                clamped.add(new long[] { start, end });
            }
        }
        if (clamped.isEmpty()) {
            return 0;
        }
        clamped.sort(Comparator.comparingLong(interval -> interval[0]));
        long pause = 0;
        long currentStart = clamped.get(0)[0];
        long currentEnd = clamped.get(0)[1];
        for (int i = 1; i < clamped.size(); i++) {
            long[] interval = clamped.get(i);
            if (interval[0] <= currentEnd) {
                currentEnd = Math.max(currentEnd, interval[1]);
            } else {
                pause += currentEnd - currentStart;
                currentStart = interval[0];
                currentEnd = interval[1];
            }
        }
        return pause + currentEnd - currentStart;
    }
}
