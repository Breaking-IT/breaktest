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

package org.apache.jmeter.protocol.http.config.gui;

import java.lang.reflect.Field;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.JEnumPropertyEditor;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase.ResponseProcessingMode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBaseSchema;
import org.apache.jorphan.locale.LocalizedValue;
import org.apache.jorphan.locale.ResourceKeyed;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestHttpDefaultsGui {
    private HttpDefaultsGui gui;

    @BeforeEach
    public void setUp() {
        gui = new HttpDefaultsGui();
    }

    @Test
    public void testResponseProcessingModeIsPersisted() throws Exception {
        ConfigTestElement config = (ConfigTestElement) gui.createTestElement();
        gui.configure(config);

        responseProcessingModeEditor().setValue(new LocalizedValue<>(
                ResponseProcessingMode.FETCH_AND_DISCARD,
                resourceKey -> resourceKey
        ));
        Assertions.assertEquals(
                ResponseProcessingMode.FETCH_AND_DISCARD.getResourceKey(),
                ((ResourceKeyed) responseProcessingModeEditor().getValue()).getResourceKey());
        gui.modifyTestElement(config);

        Assertions.assertEquals(
                ResponseProcessingMode.FETCH_AND_DISCARD.getResourceKey(),
                ((ResourceKeyed) responseProcessingModeEditor().getValue()).getResourceKey());
        Assertions.assertEquals(
                ResponseProcessingMode.FETCH_AND_DISCARD.getResourceKey(),
                config.get(HTTPSamplerBaseSchema.INSTANCE.getResponseProcessingMode()));
    }

    @SuppressWarnings("unchecked")
    private JEnumPropertyEditor<ResponseProcessingMode> responseProcessingModeEditor() throws Exception {
        Field field = HttpDefaultsGui.class.getDeclaredField("responseProcessingMode");
        field.setAccessible(true);
        return (JEnumPropertyEditor<ResponseProcessingMode>) field.get(gui);
    }
}
