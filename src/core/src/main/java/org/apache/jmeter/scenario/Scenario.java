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
import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;

/**
 * A named combination of thread group workloads, such as a load test or a stress test.
 * It holds one {@link ScenarioWorkload} per thread group run; the same thread group can appear several times.
 * Exactly one scenario must be enabled for a test to start.
 */
public class Scenario extends AbstractTestElement implements Serializable {
    private static final long serialVersionUID = 1L;

    /** The workloads of this scenario, in run order */
    public static final String WORKLOADS = "Scenario.workloads"; // $NON-NLS-1$

    /** Whether the thread groups of this scenario run one after another instead of together */
    public static final String RUN_CONSECUTIVELY = "Scenario.run_consecutively"; // $NON-NLS-1$

    public Scenario() {
        super();
    }

    public Scenario(String name) {
        setName(name);
    }

    /**
     * @return the workloads of this scenario in run order, including disabled ones
     */
    public List<ScenarioWorkload> getWorkloads() {
        List<ScenarioWorkload> workloads = new ArrayList<>();
        if (getProperty(WORKLOADS) instanceof CollectionProperty collection) {
            for (JMeterProperty property : collection) {
                if (property.getObjectValue() instanceof ScenarioWorkload workload) {
                    workloads.add(workload);
                }
            }
        }
        return workloads;
    }

    public boolean isRunConsecutively() {
        return getPropertyAsBoolean(RUN_CONSECUTIVELY);
    }

    public void setRunConsecutively(boolean runConsecutively) {
        setProperty(RUN_CONSECUTIVELY, runConsecutively, false);
    }

    public void setWorkloads(List<ScenarioWorkload> workloads) {
        if (workloads.isEmpty()) {
            // A new scenario has no workloads property; keep it that way so editing it changes nothing
            removeProperty(WORKLOADS);
        } else {
            setProperty(new CollectionProperty(WORKLOADS, workloads));
        }
    }
}
