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

package org.apache.jmeter.readiness.gui;

import java.util.List;
import java.util.Set;

import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.readiness.AcceptedException;
import org.apache.jmeter.readiness.CheckId;
import org.apache.jmeter.readiness.ElementRef;
import org.apache.jmeter.readiness.check.ReadinessOptions;
import org.apache.jmeter.readiness.check.ReadinessReport;

/**
 * What the readiness wizard asks of the application. The GUI implementation drives validation through
 * {@link org.apache.jmeter.gui.action.Start}; tests provide their own. All methods are called on the EDT.
 */
public interface ReadinessController {

    /**
     * A thread group the user can put in scope.
     *
     * @param id identity for the session (source id)
     * @param name display name
     * @param selected whether it is selected in the tree
     */
    record GroupChoice(String id, String name, boolean selected) {
    }

    /**
     * A readiness run.
     *
     * @param groupIds ids of the thread groups in scope
     * @param checks checks to run
     * @param runValidation whether to run a fresh readiness validation for the runtime checks
     * @param settings validation settings of the run
     * @param timeLimitSeconds time limit of the validation run
     * @param options check options (extraction budget, offline runs, repeatability)
     */
    record RunRequest(List<String> groupIds, Set<CheckId> checks, boolean runValidation, ValidationSettings settings,
            int timeLimitSeconds, ReadinessOptions options) {
        public RunRequest {
            groupIds = List.copyOf(groupIds);
            checks = Set.copyOf(checks);
        }
    }

    /** Receives the progress of a run. */
    interface Listener {
        void progress(String message);

        void finished(ReadinessReport report);

        /**
         * @param report the latest report rebuilt without a run, for instance after an exception changed
         */
        void updated(ReadinessReport report);

        void failed(String message);
    }

    /**
     * @return enabled thread groups that can be validated
     */
    List<GroupChoice> threadGroups();

    void setListener(Listener listener);

    void start(RunRequest request);

    void cancel();

    boolean isRunning();

    /**
     * Rebuilds the latest report against the current plan, without running anything.
     */
    void refresh();

    /**
     * @return the latest report of the GUI session, or {@code null}
     */
    ReadinessReport latestReport();

    void navigate(ElementRef element);

    /**
     * Records an exception on an element, as an undoable plan edit.
     *
     * @param element element holding it
     * @param exception the exception
     */
    void accept(ElementRef element, AcceptedException exception);

    /**
     * Removes an exception, as an undoable plan edit.
     *
     * @param element element holding it
     * @param exception the exception
     */
    void remove(ElementRef element, AcceptedException exception);

    /**
     * @return the default author of new exceptions, may be empty
     */
    String defaultAuthor();
}
