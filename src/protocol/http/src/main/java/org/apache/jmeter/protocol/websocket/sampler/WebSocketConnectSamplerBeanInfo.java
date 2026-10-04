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

import java.beans.BeanDescriptor;
import java.util.Enumeration;

import org.apache.jmeter.testbeans.BeanInfoSupport;

public class WebSocketConnectSamplerBeanInfo extends BeanInfoSupport {
    private final BeanDescriptor descriptor;
    public WebSocketConnectSamplerBeanInfo() {
        super(WebSocketConnectSampler.class);
        BeanDescriptor source = super.getBeanDescriptor();
        descriptor = new BeanDescriptor(WebSocketConnectSampler.class, WebSocketConnectCustomizer.class);
        descriptor.setDisplayName(source.getDisplayName());
        descriptor.setShortDescription(source.getShortDescription());
        Enumeration<String> attributes = source.attributeNames();
        while (attributes.hasMoreElements()) {
            String key = attributes.nextElement();
            descriptor.setValue(key, source.getValue(key));
        }
        property("headers").setHidden(true);
        createPropertyGroup("session", new String[] {"sessionName", "timeout", "url", "existingSessionAction", "countIncoming",
                "failOnDisconnect", "ignoreControlFrames", "textFilter", "binaryFilter", "maxMessageBytes"});
        property("sessionName").setValue(NOT_UNDEFINED, true);
        property("sessionName").setValue(DEFAULT, "default");
        property("timeout").setValue(NOT_UNDEFINED, true);
        property("timeout").setValue(DEFAULT, 10000);
        property("url").setValue(NOT_UNDEFINED, true);
        property("url").setValue(DEFAULT, "ws://localhost:8080/");
        property("existingSessionAction").setValue(NOT_UNDEFINED, true);
        property("existingSessionAction").setValue(DEFAULT, WebSocketConnectSampler.RECONNECT);
        property("existingSessionAction").setValue(TAGS, new String[] {
                WebSocketConnectSampler.RECONNECT, WebSocketConnectSampler.REUSE, WebSocketConnectSampler.FAIL});
        property("existingSessionAction").setValue(NOT_OTHER, true);
        property("countIncoming").setValue(NOT_UNDEFINED, true);
        property("countIncoming").setValue(DEFAULT, true);
        property("failOnDisconnect").setValue(NOT_UNDEFINED, true);
        property("failOnDisconnect").setValue(DEFAULT, true);
        property("ignoreControlFrames").setValue(NOT_UNDEFINED, true);
        property("ignoreControlFrames").setValue(DEFAULT, true);
        property("textFilter").setValue(NOT_UNDEFINED, true);
        property("textFilter").setValue(DEFAULT, "");
        property("binaryFilter").setValue(NOT_UNDEFINED, true);
        property("binaryFilter").setValue(DEFAULT, "");
        property("maxMessageBytes").setValue(NOT_UNDEFINED, true);
        property("maxMessageBytes").setValue(DEFAULT, 1048576);
    }

    @Override
    public BeanDescriptor getBeanDescriptor() {
        return descriptor == null ? super.getBeanDescriptor() : descriptor;
    }
}
