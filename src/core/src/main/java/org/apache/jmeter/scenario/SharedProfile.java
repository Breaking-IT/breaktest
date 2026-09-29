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
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;

/**
 * The configuration every thread group uses, whichever profile it runs with, such as the Cookie Manager.
 * At run time its elements behave as if they were placed directly under the test plan.
 */
public class SharedProfile extends AbstractTestElement implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * On User Defined Variables of the shared profile: the ids of the thread groups they came after in a plan made
     * before scenarios existed, in the order of that plan. Variables apply in tree order, so they are evaluated
     * after the variables of the last of those thread groups that runs, or before all thread groups when none does.
     */
    public static final String AFTER_THREAD_GROUPS = "BreakTest.sharedVariables.afterThreadGroups"; // $NON-NLS-1$

    /**
     * @param variables User Defined Variables of the shared profile
     * @return the ids of the thread groups they came after in an old plan, see {@link #AFTER_THREAD_GROUPS}
     */
    public static List<String> threadGroupsBefore(TestElement variables) {
        List<String> ids = new ArrayList<>();
        if (variables.getProperty(AFTER_THREAD_GROUPS) instanceof CollectionProperty threadGroups) {
            threadGroups.iterator().forEachRemaining(id -> ids.add(id.getStringValue()));
        }
        return ids;
    }

    /**
     * @return whether threads start with the values of the shared variables rather than those of User Defined
     *     Variables in their thread group; by default the thread group, being more specific, wins
     */
    public boolean isOverridingThreadGroupVariables() {
        return getPropertyAsBoolean(Profile.OVERRIDES_THREAD_GROUP_VARIABLES, false);
    }

    public void setOverridingThreadGroupVariables(boolean overriding) {
        setProperty(Profile.OVERRIDES_THREAD_GROUP_VARIABLES, overriding, false);
    }
}
