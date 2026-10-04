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

package org.apache.jmeter.protocol.sse;

import org.apache.jmeter.testbeans.BeanInfoSupport;

public class SseMatchControllerBeanInfo extends BeanInfoSupport {
    public SseMatchControllerBeanInfo() {
        super(SseMatchController.class);
        createPropertyGroup("match", new String[] {"eventName", "matchMode", "matchValue", "saveMessageVariable"});
        property("eventName").setValue(NOT_UNDEFINED, true);
        property("eventName").setValue(DEFAULT, "");
        property("matchMode").setValue(NOT_UNDEFINED, true);
        property("matchMode").setValue(DEFAULT, SseMatchController.TEXT);
        property("matchMode").setValue(TAGS, new String[] {
                SseMatchController.TEXT, SseMatchController.REGEX});
        property("matchMode").setValue(NOT_OTHER, true);
        property("matchValue").setValue(NOT_UNDEFINED, true);
        property("matchValue").setValue(DEFAULT, "");
        property("saveMessageVariable").setValue(NOT_UNDEFINED, true);
        property("saveMessageVariable").setValue(DEFAULT, "");
    }
}
