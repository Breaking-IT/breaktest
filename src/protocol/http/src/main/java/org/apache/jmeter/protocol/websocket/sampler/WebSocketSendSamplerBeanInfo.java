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
import org.apache.jmeter.testbeans.gui.TypeEditor;

public class WebSocketSendSamplerBeanInfo extends BeanInfoSupport {
    public WebSocketSendSamplerBeanInfo() {
        super(WebSocketSendSampler.class);
        createPropertyGroup("session", new String[] {"sessionName", "timeout", "payload", "binary"});
        property("sessionName").setValue(NOT_UNDEFINED, true);
        property("sessionName").setValue(DEFAULT, "default");
        property("timeout").setValue(NOT_UNDEFINED, true);
        property("timeout").setValue(DEFAULT, 10000);
        property("payload", TypeEditor.TextAreaEditor).setValue(NOT_UNDEFINED, true);
        property("payload").setValue(DEFAULT, "");
        property("payload").setValue(MULTILINE, true);
        property("binary").setValue(NOT_UNDEFINED, true);
        property("binary").setValue(DEFAULT, false);
    }
}
