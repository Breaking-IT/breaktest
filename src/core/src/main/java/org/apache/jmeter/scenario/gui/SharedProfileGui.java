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


package org.apache.jmeter.scenario.gui;

import org.apache.jmeter.scenario.SharedProfile;
import org.apache.jmeter.testelement.TestElement;

public class SharedProfileGui extends AbstractProfileGui {
    private static final long serialVersionUID = 1L;

    public SharedProfileGui() {
        // The shared profile is a fixed part of the Profiles section
        setNameEditable(false);
    }

    @Override
    public String getLabelResource() {
        return "shared_profile"; // $NON-NLS-1$
    }

    @Override
    public TestElement makeTestElement() {
        return new SharedProfile();
    }

    @Override
    protected boolean isOverriding(TestElement element) {
        return ((SharedProfile) element).isOverridingThreadGroupVariables();
    }

    @Override
    protected void setOverriding(TestElement element, boolean overriding) {
        ((SharedProfile) element).setOverridingThreadGroupVariables(overriding);
    }

    @Override
    protected boolean isRemovable() {
        return false;
    }
}
