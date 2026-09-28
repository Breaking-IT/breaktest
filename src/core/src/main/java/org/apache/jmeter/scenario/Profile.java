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

import org.apache.jmeter.testelement.AbstractTestElement;

/**
 * A named set of configuration, such as the variables, CSV data sets, defaults and headers of one environment.
 * Scenario rows choose a profile by name; its elements then apply to that thread group only.
 */
public class Profile extends AbstractTestElement implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Whether this profile is used when a thread group is validated on its own */
    public static final String DEFAULT = "Profile.default"; // $NON-NLS-1$

    public Profile() {
        super();
    }

    public Profile(String name) {
        setName(name);
    }

    /** Whether the variables of this profile override User Defined Variables inside thread groups */
    public static final String OVERRIDES_THREAD_GROUP_VARIABLES = "Profile.overrides_thread_group_variables"; // $NON-NLS-1$

    /**
     * @return whether threads start with the values of this profile rather than those of User Defined Variables in
     *     their thread group; an environment profile overrides the defaults of a script unless set otherwise
     */
    public boolean isOverridingThreadGroupVariables() {
        return getPropertyAsBoolean(OVERRIDES_THREAD_GROUP_VARIABLES, true);
    }

    public void setOverridingThreadGroupVariables(boolean overriding) {
        setProperty(OVERRIDES_THREAD_GROUP_VARIABLES, overriding, true);
    }

    public boolean isDefault() {
        return getPropertyAsBoolean(DEFAULT);
    }

    public void setDefault(boolean isDefault) {
        setProperty(DEFAULT, isDefault, false);
    }
}
