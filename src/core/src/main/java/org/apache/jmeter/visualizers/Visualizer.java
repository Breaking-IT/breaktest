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

package org.apache.jmeter.visualizers;

import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.SampleResultMetadataConsumer;

/**
 * Implement this method to be a Visualizer for JMeter. This interface defines a
 * single method, "add()", that provides the means by which
 * {@link org.apache.jmeter.samplers.SampleResult SampleResults} are passed to
 * the implementing visualizer for display/logging. The easiest way to create
 * the visualizer is to extend the
 * {@link org.apache.jmeter.visualizers.gui.AbstractVisualizer} class.
 *
 */
public interface Visualizer extends SampleResultMetadataConsumer {
    /**
     * This method is called by sampling thread to inform the visualizer about
     * the arrival of a new sample.
     *
     * @param sample
     *            the newly arrived sample
     */
    void add(SampleResult sample);

    /**
     * This method is called by sampling thread to inform the visualizer about
     * the arrival of a new sample event. Visualizers that need event metadata
     * can override it; others receive the contained {@link SampleResult}.
     *
     * @param event
     *            the newly arrived sample event
     */
    default void add(SampleEvent event) {
        add(event.getResult());
    }

    /**
     * This method is called by sampling thread when a Transaction Controller starts a transaction.
     * The event holds an unfinished placeholder that must not be treated as a sample; see
     * {@link org.apache.jmeter.samplers.SampleListener#transactionStarted(SampleEvent)}.
     *
     * @param event
     *            the event holding the unfinished transaction
     */
    default void addStartedTransaction(SampleEvent event) {
    }

    /**
     * This method is called by sampling thread when a sampler is about to send its request. The
     * event holds an unfinished placeholder; see
     * {@link org.apache.jmeter.samplers.SampleListener#sampleStarted(SampleEvent)}.
     *
     * @param event
     *            the event holding the unfinished sample
     */
    default void addStartedSample(SampleEvent event) {
    }

    /**
     * This method is called by sampling thread when a sampler given to
     * {@link #addStartedSample(SampleEvent)} has finished, whether or not it produced a sample.
     *
     * @param event
     *            the event holding the placeholder
     */
    default void removeStartedSample(SampleEvent event) {
    }

    /**
     * @return {@code true} to receive {@link #addStartedTransaction(SampleEvent)},
     * {@link #addStartedSample(SampleEvent)} and {@link #removeStartedSample(SampleEvent)}
     */
    default boolean needsStartedResults() {
        return false;
    }

    /**
     * @return true when this visualizer needs rich metadata attached to
     * {@link SampleResult} instances, such as variable snapshots or source tree paths
     */
    default boolean needsSampleResultMetadata() {
        return false;
    }

    /**
     * @return true when this visualizer needs variable snapshots attached to
     * {@link SampleResult} instances
     * @since 2026.08
     */
    @Override
    default boolean needsJMeterVariables() {
        return needsSampleResultMetadata();
    }

    /**
     * @return true when this visualizer needs source test-element paths attached to
     * {@link SampleResult} instances
     * @since 2026.08
     */
    @Override
    default boolean needsSourceTestElementPath() {
        return needsSampleResultMetadata();
    }

    /**
     * This method is used to indicate a visualizer generates statistics.
     *
     * @return true if visualiser generates statistics
     */
    boolean isStats();
}
