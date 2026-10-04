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

package org.apache.jmeter.protocol.websocket.sampler;

import org.apache.jmeter.testbeans.BeanInfoSupport;
import org.apache.jmeter.testbeans.gui.GenericTestBeanCustomizer;
import org.apache.jmeter.testbeans.gui.TypeEditor;

public class WebSocketMatchControllerBeanInfo extends BeanInfoSupport {
    public WebSocketMatchControllerBeanInfo() {
        super(WebSocketMatchController.class);
        createPropertyGroup("match", new String[] {"matchMode", "matchValue", "variablePrefix", "captureVariable"});
        property("matchMode").setValue(NOT_UNDEFINED, true);
        property("matchMode").setValue(DEFAULT, WebSocketMatchController.TEXT);
        property("matchMode").setValue(TAGS, new String[] {
                WebSocketMatchController.TEXT, WebSocketMatchController.REGEX, WebSocketMatchController.BINARY});
        property("matchMode").setValue(NOT_OTHER, true);
        property("matchValue", TypeEditor.TextAreaEditor).setValue(NOT_UNDEFINED, true);
        property("matchValue").setValue(DEFAULT, "");
        property("matchValue").setValue(MULTILINE, true);
        property("variablePrefix").setValue(NOT_UNDEFINED, true);
        property("variablePrefix").setValue(DEFAULT, "ws");
        property("captureVariable").setValue(NOT_UNDEFINED, true);
        property("captureVariable").setValue(DEFAULT, "");
        property("captureVariable").setValue(GenericTestBeanCustomizer.ENABLED_WHEN_PROPERTY, "matchMode");
        property("captureVariable").setValue(GenericTestBeanCustomizer.ENABLED_WHEN_VALUE, WebSocketMatchController.REGEX);
    }
}
