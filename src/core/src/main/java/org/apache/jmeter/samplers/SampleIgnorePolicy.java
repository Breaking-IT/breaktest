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

/** Controls whether a completed sampler result is reported to listeners and transactions. */
public enum SampleIgnorePolicy {
    NEVER("", "sampler_ignore_never"),
    ON_SUCCESS("on_success", "sampler_ignore_on_success"),
    ALWAYS("always", "sampler_ignore_always");

    public static final String PROPERTY = "Sampler.ignore_policy";

    private final String propertyValue;
    private final String labelResource;

    SampleIgnorePolicy(String propertyValue, String labelResource) {
        this.propertyValue = propertyValue;
        this.labelResource = labelResource;
    }

    public String getLabelResource() {
        return labelResource;
    }

    public void save(Sampler sampler) {
        sampler.setProperty(PROPERTY, propertyValue, "");
    }

    public static SampleIgnorePolicy from(Sampler sampler) {
        String value = sampler.getPropertyAsString(PROPERTY);
        for (SampleIgnorePolicy policy : values()) {
            if (policy.propertyValue.equals(value)) {
                return policy;
            }
        }
        return NEVER;
    }

    /** Apply after assertions so assertion failures remain visible with ON_SUCCESS. */
    public void apply(SampleResult result) {
        if (this == ALWAYS || this == ON_SUCCESS && result.isSuccessful()) {
            result.setIgnore();
        }
    }
}
