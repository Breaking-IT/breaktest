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

import java.io.Serializable;
import java.util.List;
import java.util.function.BiConsumer;

import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes sample events. <br>
 * The current implementation processes events in the calling thread
 * using {@link #notifyListeners(SampleEvent, List)} <br>
 * Thread safe class
 */
public class ListenerNotifier implements Serializable {
    /**
     *
     */
    private static final long serialVersionUID = -4861457279068497917L;
    private static final Logger log = LoggerFactory.getLogger(ListenerNotifier.class);


    /**
     * Notify a list of listeners that a sample has occurred.
     *
     * @param res
     *            the sample event that has occurred. Must be non-null.
     * @param listeners
     *            a list of the listeners which should be notified. This list
     *            must not be null and must contain only SampleListener
     *            elements.
     */
    public void notifyListeners(SampleEvent res, List<SampleListener> listeners) {
        for (SampleListener sampleListener : listeners) {
            try {
                TestBeanHelper.prepare((TestElement) sampleListener);
                sampleListener.sampleOccurred(res);
            } catch (RuntimeException e) {
                log.error("Detected problem in Listener.", e);
                log.info("Continuing to process further listeners");
            }
        }
    }

    /**
     * Notify a list of listeners that a transaction has started.
     *
     * @param event
     *            the event holding the unfinished transaction. Must be non-null.
     * @param listeners
     *            the listeners which should be notified
     * @see SampleListener#transactionStarted(SampleEvent)
     */
    public void notifyTransactionStarted(SampleEvent event, List<SampleListener> listeners) {
        notifyStartEvent(event, listeners, SampleListener::transactionStarted);
    }

    /**
     * Notify the listeners that asked for start events that a sampler is about to send its request.
     *
     * @param event     the event holding the unfinished sample
     * @param listeners the listeners in scope of the sampler
     * @see SampleListener#sampleStarted(SampleEvent)
     */
    public void notifySampleStarted(SampleEvent event, List<SampleListener> listeners) {
        notifyStartEvent(event, listeners, SampleListener::sampleStarted);
    }

    /**
     * Notify the listeners that asked for start events that a started sampler has finished.
     *
     * @param event     the event holding the placeholder sent when the sampler started
     * @param listeners the listeners in scope of the sampler
     * @see SampleListener#sampleStopped(SampleEvent)
     */
    public void notifySampleStopped(SampleEvent event, List<SampleListener> listeners) {
        notifyStartEvent(event, listeners, SampleListener::sampleStopped);
    }

    private static void notifyStartEvent(SampleEvent event, List<SampleListener> listeners,
            BiConsumer<SampleListener, SampleEvent> callback) {
        for (SampleListener sampleListener : listeners) {
            if (!sampleListener.needsStartEvents()) {
                continue;
            }
            try {
                TestBeanHelper.prepare((TestElement) sampleListener);
                callback.accept(sampleListener, event);
            } catch (RuntimeException e) {
                log.error("Detected problem in Listener.", e);
                log.info("Continuing to process further listeners");
            }
        }
    }

}
