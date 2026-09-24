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

package org.apache.jmeter.samplers;

/**
 * Allows notification on events occurring during the sampling process.
 * Specifically, when sampling is started, when a specific sample is obtained,
 * and when sampling is stopped.
 *
 */
public interface SampleListener extends SampleResultMetadataConsumer {
    /**
     * A sample has started and stopped.
     *
     * @param e
     *            the {@link SampleEvent} that has occurred
     */
    void sampleOccurred(SampleEvent e);

    /**
     * A sampler is about to send its request. Only called when {@link #needsStartEvents()} returns
     * {@code true}.
     * <p>
     * The event result is an unfinished placeholder holding the sampler name, thread, start time and
     * {@link SampleResult#getParentTransaction() transaction}. It is not a sample and must not be
     * counted as one. The finished sample arrives through {@link #sampleOccurred(SampleEvent)} with
     * the placeholder in {@link SampleEvent#getStartedSample()}, followed by
     * {@link #sampleStopped(SampleEvent)}.
     *
     * @param e
     *            the {@link SampleEvent} holding the unfinished sample
     */
    void sampleStarted(SampleEvent e);

    /**
     * A sampler started with {@link #sampleStarted(SampleEvent)} has finished, whether or not it
     * produced a sample. Only called when {@link #needsStartEvents()} returns {@code true}.
     *
     * @param e
     *            the {@link SampleEvent} holding the placeholder given to {@link #sampleStarted(SampleEvent)}
     */
    void sampleStopped(SampleEvent e);

    /**
     * A Transaction Controller has started a transaction.
     * <p>
     * The event result is an unfinished placeholder: it carries the transaction name, start time and
     * {@link SampleResult#getTransaction() transaction reference}, but no timing or status yet. It is
     * not a sample and must not be counted as one. The samples of the transaction follow through
     * {@link #sampleOccurred(SampleEvent)} with a matching {@link SampleResult#getParentTransaction()},
     * and the finished transaction arrives last through {@link #sampleOccurred(SampleEvent)} with the
     * same {@link SampleResult#getTransaction()}.
     * <p>
     * This event only helps live views show a running transaction early. It is only delivered when
     * {@link #needsStartEvents()} returns {@code true}, and not in every setup (for example not
     * in distributed mode), so listeners must not depend on it.
     *
     * @param e the {@link SampleEvent} holding the unfinished transaction
     */
    default void transactionStarted(SampleEvent e) {
    }

    /**
     * @return {@code true} to receive {@link #transactionStarted(SampleEvent)},
     * {@link #sampleStarted(SampleEvent)} and {@link #sampleStopped(SampleEvent)}. These events are
     * only created when a listener in scope asks for them, so they cost nothing otherwise. Meant for
     * live views; evaluated once per sampler for the whole run.
     */
    default boolean needsStartEvents() {
        return false;
    }
}
