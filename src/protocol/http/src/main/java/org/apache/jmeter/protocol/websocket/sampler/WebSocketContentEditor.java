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

import java.beans.PropertyEditorSupport;

/** Displays descriptive content choices while preserving the saved boolean property. */
public class WebSocketContentEditor extends PropertyEditorSupport {
    private static final String TEXT = "Text";
    private static final String HEX = "Binary (hex)";

    @Override
    public String[] getTags() {
        return new String[] {TEXT, HEX};
    }

    @Override
    public String getAsText() {
        return Boolean.TRUE.equals(getValue()) ? HEX : TEXT;
    }

    @Override
    public void setAsText(String text) {
        if (!TEXT.equals(text) && !HEX.equals(text)) {
            throw new IllegalArgumentException("Unknown WebSocket content type: " + text);
        }
        setValue(HEX.equals(text));
    }
}
