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
import java.util.List;

import javax.swing.JComboBox;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.JEnumPropertyEditor;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.gui.HeaderTablePanel;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
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

    @Test
    public void testBlankHttpProtocolRemovesProperty() throws Exception {
        ConfigTestElement config = (ConfigTestElement) gui.createTestElement();
        config.set(HTTPSamplerBaseSchema.INSTANCE.getHttpProtocol(), HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        gui.configure(config);

        httpProtocol().setSelectedItem(HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT);
        gui.modifyTestElement(config);

        Assertions.assertNull(config.getPropertyOrNull(HTTPSamplerBaseSchema.INSTANCE.getHttpProtocol().getName()));
    }

    @Test
    public void headersCanBeLoadedEditedRemovedAndCleared() throws Exception {
        ConfigTestElement config = (ConfigTestElement) gui.createTestElement();
        config.set(HTTPSamplerBaseSchema.INSTANCE.getHeaders(), List.of(new Header("Accept", "application/json")));
        gui.configure(config);
        HeaderTablePanel headers = headersPanel();
        Assertions.assertEquals("application/json", headers.getHeaders().get(0).getValue());
        headers.setHeaders(List.of(new Header("Accept", "text/plain"), new Header("X-Token", "${token}")));
        gui.modifyTestElement(config);
        gui.clearGui();
        Assertions.assertEquals(0, headers.getHeaderCount());
        gui.configure(config);
        Assertions.assertEquals(2, headers.getHeaderCount());
        Assertions.assertEquals("text/plain", headers.getHeaders().get(0).getValue());
        Assertions.assertEquals("${token}", headers.getHeaders().get(1).getValue());
        headers.clear();
        gui.modifyTestElement(config);
        Assertions.assertNull(config.getPropertyOrNull(HTTPSamplerBase.HEADERS));
        gui.configure(new HttpDefaultsGui().createTestElement());
        Assertions.assertEquals(0, headers.getHeaderCount());
    }

    private HeaderTablePanel headersPanel() throws Exception {
        Field url = HttpDefaultsGui.class.getDeclaredField("urlConfigGui");
        url.setAccessible(true);
        Field headers = UrlConfigGui.class.getDeclaredField("headersPanel");
        headers.setAccessible(true);
        return (HeaderTablePanel) headers.get(url.get(gui));
    }

    @SuppressWarnings("unchecked")
    private JEnumPropertyEditor<ResponseProcessingMode> responseProcessingModeEditor() throws Exception {
        Field field = HttpDefaultsGui.class.getDeclaredField("responseProcessingMode");
        field.setAccessible(true);
        return (JEnumPropertyEditor<ResponseProcessingMode>) field.get(gui);
    }

    @SuppressWarnings("unchecked")
    private JComboBox<String> httpProtocol() throws Exception {
        Field field = HttpDefaultsGui.class.getDeclaredField("httpProtocol");
        field.setAccessible(true);
        return (JComboBox<String>) field.get(gui);
    }
}
