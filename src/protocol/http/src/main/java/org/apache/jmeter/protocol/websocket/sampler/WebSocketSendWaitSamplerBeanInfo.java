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

public class WebSocketSendWaitSamplerBeanInfo extends BeanInfoSupport {
    public WebSocketSendWaitSamplerBeanInfo() {
        super(WebSocketSendWaitSampler.class);
        createPropertyGroup("session", new String[] {"sessionName", "timeout", "binary", "payload", "action",
                "waitMode", "responsePattern", "responseBinary", "waitTimeout"});
        property("action").setValue(NOT_UNDEFINED, true);
        property("action").setValue(DEFAULT, WebSocketSendWaitSampler.SEND_AND_WAIT);
        property("action").setValue(TAGS, new String[] {
                WebSocketSendWaitSampler.SEND_ONLY, WebSocketSendWaitSampler.SEND_AND_WAIT});
        property("action").setValue(NOT_OTHER, true);
        // HAR replay timing is persisted internally, not edited in the sampler settings.
        property("sendOffset").setHidden(true);
        property("sendOffset").setValue(NOT_UNDEFINED, true);
        property("sendOffset").setValue(DEFAULT, "");
        for (String name : new String[] {"waitMode", "waitTimeout"}) {
            property(name).setValue(GenericTestBeanCustomizer.ENABLED_WHEN_PROPERTY, "action");
            property(name).setValue(GenericTestBeanCustomizer.ENABLED_WHEN_VALUE, WebSocketSendWaitSampler.SEND_AND_WAIT);
        }
        property("sessionName").setValue(NOT_UNDEFINED, true);
        property("sessionName").setValue(DEFAULT, "default");
        property("timeout").setValue(NOT_UNDEFINED, true);
        property("timeout").setValue(DEFAULT, 10000);
        property("payload").setValue(NOT_UNDEFINED, true);
        property("payload").setPropertyEditorClass(WebSocketPayloadEditor.class);
        property("payload").setValue(DEFAULT, "");
        property("payload").setValue(MULTILINE, true);
        property("binary").setPropertyEditorClass(WebSocketContentEditor.class);
        property("binary").setValue(NOT_EXPRESSION, true);
        property("binary").setValue(NOT_OTHER, true);
        property("binary").setValue(NOT_UNDEFINED, true);
        property("binary").setValue(DEFAULT, false);
        property("waitTimeout").setValue(NOT_UNDEFINED, true);
        property("waitTimeout").setValue(DEFAULT, 10000);
        property("waitMode").setValue(NOT_UNDEFINED, true);
        property("waitMode").setValue(DEFAULT, WebSocketSendWaitSampler.NEXT_MESSAGE);
        property("waitMode").setValue(TAGS, new String[] {
                WebSocketSendWaitSampler.NEXT_MESSAGE, WebSocketSendWaitSampler.MATCHING_MESSAGE,
                WebSocketSendWaitSampler.BINARY_MESSAGE});
        property("waitMode").setValue(NOT_OTHER, true);
        property("responsePattern").setValue(NOT_UNDEFINED, true);
        property("responsePattern").setValue(DEFAULT, "");
        property("responseBinary").setValue(NOT_UNDEFINED, true);
        property("responseBinary").setPropertyEditorClass(WebSocketPayloadEditor.class);
        property("responseBinary").setValue(DEFAULT, "");
        property("responseBinary").setValue(MULTILINE, true);
        property("responsePattern").setValue(GenericTestBeanCustomizer.ENABLED_WHEN_PROPERTY, "waitMode");
        property("responsePattern").setValue(GenericTestBeanCustomizer.ENABLED_WHEN_VALUE,
                WebSocketSendWaitSampler.MATCHING_MESSAGE);
        property("responseBinary").setValue(GenericTestBeanCustomizer.ENABLED_WHEN_PROPERTY, "waitMode");
        property("responseBinary").setValue(GenericTestBeanCustomizer.ENABLED_WHEN_VALUE,
                WebSocketSendWaitSampler.BINARY_MESSAGE);
    }
}
