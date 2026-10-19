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

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextField;

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
    public void coalescingDefaultsOnButPreservesLegacyAbsenceAndExplicitValues() throws Exception {
        var fresh = gui.createTestElement();
        Assertions.assertTrue(fresh.getPropertyAsBoolean(HTTPSamplerBase.HTTP2_COALESCING));
        var legacy = (ConfigTestElement) fresh.clone();
        legacy.removeProperty(HTTPSamplerBase.HTTP2_COALESCING);
        gui.configure(legacy);
        gui.modifyTestElement(legacy);
        Assertions.assertNull(legacy.getPropertyOrNull(HTTPSamplerBase.HTTP2_COALESCING));
        Field field = HttpDefaultsGui.class.getDeclaredField("http2Coalescing");
        field.setAccessible(true);
        JCheckBox checkbox = (JCheckBox) field.get(gui);
        Assertions.assertTrue(checkbox.isSelected());
        checkbox.doClick();
        gui.modifyTestElement(legacy);
        Assertions.assertEquals("false", legacy.getPropertyAsString(HTTPSamplerBase.HTTP2_COALESCING));
        checkbox.doClick();
        gui.modifyTestElement(legacy);
        Assertions.assertTrue(legacy.getPropertyAsBoolean(HTTPSamplerBase.HTTP2_COALESCING));
        legacy.setProperty(HTTPSamplerBase.HTTP2_COALESCING, false);
        gui.configure(legacy);
        gui.modifyTestElement(legacy);
        Assertions.assertEquals("false", legacy.getPropertyAsString(HTTPSamplerBase.HTTP2_COALESCING));
        legacy.setProperty(HTTPSamplerBase.HTTP2_COALESCING, "${coalescing}");
        gui.configure(legacy);
        gui.modifyTestElement(legacy);
        Assertions.assertEquals("${coalescing}", legacy.getPropertyAsString(HTTPSamplerBase.HTTP2_COALESCING));
        Field protocols = HttpDefaultsGui.class.getDeclaredField("httpProtocol");
        protocols.setAccessible(true);
        JComboBox<?> selector = (JComboBox<?>) protocols.get(gui);
        selector.setSelectedItem(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);
        Assertions.assertFalse(checkbox.isEnabled());
        selector.setSelectedItem(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        Assertions.assertTrue(checkbox.isEnabled());
        gui.clearGui();
        Assertions.assertTrue(gui.createTestElement().getPropertyAsBoolean(HTTPSamplerBase.HTTP2_COALESCING));
    }

    @Test
    public void upgradeDefaultsOnAndLegacyAbsenceSurvivesEditing() throws Exception {
        var fresh = gui.createTestElement();
        Assertions.assertTrue(fresh.getPropertyAsBoolean(HTTPSamplerBase.HTTP3_UPGRADE));
        var legacy = (ConfigTestElement) fresh.clone();
        legacy.removeProperty(HTTPSamplerBase.HTTP3_UPGRADE);
        gui.configure(legacy);
        gui.modifyTestElement(legacy);
        Assertions.assertNull(legacy.getPropertyOrNull(HTTPSamplerBase.HTTP3_UPGRADE));
        Field field = HttpDefaultsGui.class.getDeclaredField("http3Upgrade");
        field.setAccessible(true);
        JCheckBox upgrade = (JCheckBox) field.get(gui);
        Field protocols = HttpDefaultsGui.class.getDeclaredField("httpProtocol");
        protocols.setAccessible(true);
        JComboBox<?> selector = (JComboBox<?>) protocols.get(gui);
        selector.setSelectedItem(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        Assertions.assertFalse(upgrade.isEnabled());
        selector.setSelectedItem(HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT);
        Assertions.assertTrue(upgrade.isEnabled());
        upgrade.doClick();
        gui.modifyTestElement(legacy);
        Assertions.assertEquals(upgrade.isSelected(), legacy.getPropertyAsBoolean(HTTPSamplerBase.HTTP3_UPGRADE));
        gui.configure(legacy);
        gui.modifyTestElement(legacy);
        Assertions.assertEquals(upgrade.isSelected(), legacy.getPropertyAsBoolean(HTTPSamplerBase.HTTP3_UPGRADE));
        gui.clearGui();
        Assertions.assertTrue(gui.createTestElement().getPropertyAsBoolean(HTTPSamplerBase.HTTP3_UPGRADE));
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

    @Test
    void newHttp1DefaultsUseSixConnectionsButLegacyDefaultsStayUnset() throws Exception {
        gui.clearGui();
        httpProtocol().setSelectedItem(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);
        ConfigTestElement fresh = (ConfigTestElement) gui.createTestElement();
        var schema = HTTPSamplerBaseSchema.INSTANCE;
        Assertions.assertEquals(6, fresh.get(schema.getMaxConnectionsPerHost()));
        Assertions.assertNull(fresh.getPropertyOrNull(schema.getConnectionAvailabilityTimeout()));

        ConfigTestElement legacy = (ConfigTestElement) new HttpDefaultsGui().createTestElement();
        legacy.set(schema.getHttpProtocol(), HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1);
        gui.configure(legacy);
        gui.modifyTestElement(legacy);
        Assertions.assertNull(legacy.getPropertyOrNull(schema.getMaxConnectionsPerHost()));
        Assertions.assertNull(legacy.getPropertyOrNull(schema.getConnectionAvailabilityTimeout()));
    }

    @Test
    void poolSettingsRoundTripIncludingZeroTimeoutAndExpressions() throws Exception {
        var schema = HTTPSamplerBaseSchema.INSTANCE;
        ConfigTestElement config = (ConfigTestElement) new HttpDefaultsGui().createTestElement();
        config.set(schema.getMaxConnectionsPerHost(), "${connections}");
        config.set(schema.getConnectionAvailabilityTimeout(), 0);
        gui.configure(config);
        gui.modifyTestElement(config);
        Assertions.assertEquals("${connections}", config.getString(schema.getMaxConnectionsPerHost()));
        Assertions.assertEquals(0, config.get(schema.getConnectionAvailabilityTimeout()));
        textField("maxConnectionsPerHost").setText("2");
        textField("connectionAvailabilityTimeout").setText("500");
        gui.modifyTestElement(config);
        gui.configure(config);
        Assertions.assertEquals("2", textField("maxConnectionsPerHost").getText());
        Assertions.assertEquals("500", textField("connectionAvailabilityTimeout").getText());
        textField("maxConnectionsPerHost").setText("");
        textField("connectionAvailabilityTimeout").setText("");
        gui.modifyTestElement(config);
        Assertions.assertNull(config.getPropertyOrNull(schema.getMaxConnectionsPerHost()));
        Assertions.assertNull(config.getPropertyOrNull(schema.getConnectionAvailabilityTimeout()));
    }

    @Test
    void keepAliveDefaultsPreserveUnsetFalseTrueAndExpressions() throws Exception {
        ConfigTestElement config = (ConfigTestElement) gui.createTestElement();
        gui.configure(config);
        gui.modifyTestElement(config);
        Assertions.assertNull(config.getPropertyOrNull(HTTPSamplerBase.USE_KEEPALIVE));

        for (boolean enabled : new boolean[] {false, true}) {
            config.setProperty(HTTPSamplerBase.USE_KEEPALIVE, enabled);
            gui.configure(config);
            gui.modifyTestElement(config);
            Assertions.assertNotNull(config.getPropertyOrNull(HTTPSamplerBase.USE_KEEPALIVE));
            Assertions.assertEquals(enabled, config.getPropertyAsBoolean(HTTPSamplerBase.USE_KEEPALIVE));
        }
        Field field = HttpDefaultsGui.class.getDeclaredField("keepAlive");
        field.setAccessible(true);
        JComboBox<?> editor = (JComboBox<?>) field.get(gui);
        editor.setSelectedIndex(2);
        gui.modifyTestElement(config);
        Assertions.assertNotNull(config.getPropertyOrNull(HTTPSamplerBase.USE_KEEPALIVE));
        Assertions.assertFalse(config.getPropertyAsBoolean(HTTPSamplerBase.USE_KEEPALIVE));
        editor.getEditor().setItem("${reuse_connections}");
        gui.modifyTestElement(config);
        gui.configure(config);
        gui.modifyTestElement(config);
        Assertions.assertEquals("${reuse_connections}", config.getPropertyAsString(HTTPSamplerBase.USE_KEEPALIVE));
        gui.clearGui();
        gui.modifyTestElement(config);
        Assertions.assertNull(config.getPropertyOrNull(HTTPSamplerBase.USE_KEEPALIVE));
    }

    private JTextField textField(String name) throws Exception {
        Field field = HttpDefaultsGui.class.getDeclaredField(name);
        field.setAccessible(true);
        return (JTextField) field.get(gui);
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
