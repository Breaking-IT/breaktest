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

package org.apache.jmeter.protocol.http.control.gui;

import java.lang.reflect.Field;
import java.nio.file.Path;

import javax.swing.JComboBox;
import javax.swing.JTabbedPane;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JEnumPropertyEditor;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.JSyntaxTextArea;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase.ResponseProcessingMode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBaseSchema;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jorphan.locale.LocalizedValue;
import org.apache.jorphan.locale.ResourceKeyed;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestHttpTestSampleGui {
    @TempDir
    private Path tempDir;

    private HttpTestSampleGui gui;

    @BeforeEach
    public void setUp() {
        gui = new HttpTestSampleGui();
    }

    @Test
    public void testCloneSampler() throws Exception {
        HTTPSamplerBase sampler = (HTTPSamplerBase) gui.createTestElement();
        sampler.addArgument("param", "value");
        HTTPSamplerBase clonedSampler = (HTTPSamplerBase) sampler.clone();
        clonedSampler.setRunningVersion(true);
        sampler.getArguments().getArgument(0).setValue("new value");
        Assertions.assertEquals("new value", sampler.getArguments().getArgument(0).getValue(), "Sampler didn't clone correctly");
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testResponseProcessingModeSurvivesLegacyMd5Property() throws Exception {
        HTTPSamplerBase sampler = (HTTPSamplerBase) gui.createTestElement();
        sampler.setProperty(new BooleanProperty(HTTPSamplerBaseSchema.INSTANCE.getStoreAsMD5().getName(), false));
        gui.configure(sampler);

        responseProcessingModeEditor().setValue(new LocalizedValue<>(
                ResponseProcessingMode.FETCH_AND_DISCARD,
                resourceKey -> resourceKey
        ));
        Assertions.assertEquals(
                ResponseProcessingMode.FETCH_AND_DISCARD.getResourceKey(),
                ((ResourceKeyed) responseProcessingModeEditor().getValue()).getResourceKey());
        gui.modifyTestElement(sampler);

        Assertions.assertEquals(
                ResponseProcessingMode.FETCH_AND_DISCARD.getResourceKey(),
                ((ResourceKeyed) responseProcessingModeEditor().getValue()).getResourceKey());
        Assertions.assertEquals(
                ResponseProcessingMode.FETCH_AND_DISCARD.getResourceKey(),
                sampler.getString(HTTPSamplerBaseSchema.INSTANCE.getResponseProcessingMode()));
        Assertions.assertEquals(ResponseProcessingMode.FETCH_AND_DISCARD, sampler.getResponseProcessingMode());
    }

    @Test
    public void testBlankHttpProtocolRemovesProperty() throws Exception {
        HTTPSamplerBase sampler = (HTTPSamplerBase) gui.createTestElement();
        sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        gui.configure(sampler);

        httpProtocol().setSelectedItem(HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT);
        gui.modifyTestElement(sampler);

        Assertions.assertNull(sampler.getPropertyOrNull(HTTPSamplerBaseSchema.INSTANCE.getHttpProtocol().getName()));
    }

    @Test
    public void testModifyTestElementPreservesBreakTestHarMetadata() {
        HTTPSamplerBase sampler = (HTTPSamplerBase) gui.createTestElement();
        sampler.setProperty("BreakTest.har.entryIndex", "1");
        sampler.setProperty("BreakTest.har.startedDateTime", "2026-06-25T09:33:35.535+02:00");
        sampler.setProperty("BreakTest.har.effectiveStartDateTime", "2026-06-25T09:33:35.535+02:00");
        sampler.setProperty("BreakTest.har.requestMethod", "GET");
        sampler.setProperty("BreakTest.har.requestUrl", "https://example.invalid/style.css");
        gui.configure(sampler);

        gui.modifyTestElement(sampler);

        Assertions.assertEquals("1", sampler.getPropertyAsString("BreakTest.har.entryIndex"));
        Assertions.assertEquals("2026-06-25T09:33:35.535+02:00",
                sampler.getPropertyAsString("BreakTest.har.startedDateTime"));
        Assertions.assertEquals("2026-06-25T09:33:35.535+02:00",
                sampler.getPropertyAsString("BreakTest.har.effectiveStartDateTime"));
        Assertions.assertEquals("GET", sampler.getPropertyAsString("BreakTest.har.requestMethod"));
        Assertions.assertEquals("https://example.invalid/style.css",
                sampler.getPropertyAsString("BreakTest.har.requestUrl"));
    }

    @Test
    public void testRecordedHarTabsShowMissingFileDiagnostic() throws Exception {
        HTTPSamplerBase sampler = (HTTPSamplerBase) gui.createTestElement();
        sampler.setName("sampler");
        sampler.setProperty("BreakTest.har.entryIndex", "1");

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setProperty("BreakTest.har.filename", "missing.har");
        threadGroup.setProperty("BreakTest.har.md5", "00000000000000000000000000000000");

        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        GuiPackage.initInstance(new JMeterTreeListener(treeModel), treeModel);
        setTestPlanFile(tempDir.resolve("plan.jmx"));
        JMeterTreeNode threadGroupNode = new JMeterTreeNode(threadGroup, treeModel);
        JMeterTreeNode samplerNode = new JMeterTreeNode(sampler, treeModel);
        ((JMeterTreeNode) treeModel.getRoot()).add(threadGroupNode);
        threadGroupNode.add(samplerNode);

        gui.configure(sampler);

        Assertions.assertTrue(configTabbedPane().indexOfTab("Recorded Request") >= 0);
        Assertions.assertTrue(configTabbedPane().indexOfTab("Recorded Response") >= 0);
        Assertions.assertTrue(recordedRequestData().getText().contains(
                tempDir.resolve("missing.har").toAbsolutePath().normalize().toString()));
    }

    @SuppressWarnings("unchecked")
    private JEnumPropertyEditor<ResponseProcessingMode> responseProcessingModeEditor() throws Exception {
        Field field = HttpTestSampleGui.class.getDeclaredField("responseProcessingMode");
        field.setAccessible(true);
        return (JEnumPropertyEditor<ResponseProcessingMode>) field.get(gui);
    }

    @SuppressWarnings("unchecked")
    private JComboBox<String> httpProtocol() throws Exception {
        Field field = HttpTestSampleGui.class.getDeclaredField("httpProtocol");
        field.setAccessible(true);
        return (JComboBox<String>) field.get(gui);
    }

    private JTabbedPane configTabbedPane() throws Exception {
        Field field = HttpTestSampleGui.class.getDeclaredField("configTabbedPane");
        field.setAccessible(true);
        return (JTabbedPane) field.get(gui);
    }

    private JSyntaxTextArea recordedRequestData() throws Exception {
        Field field = HttpTestSampleGui.class.getDeclaredField("recordedRequestData");
        field.setAccessible(true);
        return (JSyntaxTextArea) field.get(gui);
    }

    private static void setTestPlanFile(Path testPlanFile) throws Exception {
        Field testPlanFileField = GuiPackage.class.getDeclaredField("testPlanFile");
        testPlanFileField.setAccessible(true);
        testPlanFileField.set(GuiPackage.getInstance(), testPlanFile.toString());
    }
}
