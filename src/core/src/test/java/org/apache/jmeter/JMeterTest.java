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

package org.apache.jmeter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.report.config.ConfigurationException;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.MissingTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.HashTreeTraverser;
import org.apache.jorphan.test.JMeterSerialTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class JMeterTest extends JMeterTestCase implements JMeterSerialTest {

    @Test
    void testFailureWhenJmxDoesNotExist() {
        JMeter jmeter = new JMeter();
        try {
            jmeter.runNonGui("testPlan.jmx", null, false);
            Assertions.fail("Expected ConfigurationException to be thrown");
        } catch (ConfigurationException e) {
            Assertions.assertTrue(e.getMessage().contains("doesn't exist or can't be opened"),
                    "When the file doesn't exist, this method 'runNonGui' should have a detailed message");
        }
    }

    @Test
    void testSuccessWhenJmxExists() throws IOException, ConfigurationException {
        File temp = File.createTempFile("testPlan", ".jmx");
        String testPlan = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.2" properties="5.0" jmeter="5.2-SNAPSHOT">
                  <hashTree>
                    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Test Plan" enabled="true">
                      <stringProp name="TestPlan.comments"></stringProp>
                      <boolProp name="TestPlan.functional_mode">false</boolProp>
                      <boolProp name="TestPlan.tearDown_on_shutdown">true</boolProp>
                      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
                      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" \
                testclass="Arguments" testname="User Defined Variables" enabled="true">
                        <collectionProp name="Arguments.arguments"/>
                      </elementProp>
                      <stringProp name="TestPlan.user_define_classpath"></stringProp></TestPlan>\
                    <hashTree/></hashTree></jmeterTestPlan>""";
        try (FileOutputStream os = new FileOutputStream(temp);
                Writer fw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                BufferedWriter out = new BufferedWriter(fw)) {
            out.write(testPlan);
        }

        try {
            JMeter jmeter = new JMeter();
            jmeter.runNonGui(temp.getAbsolutePath(), null, false);
        } finally {
            Assertions.assertTrue(temp.delete(), () ->"File " + temp.getAbsolutePath() + " should have been deleted");
        }
    }

    @Test
    void testLoadJmxWithMissingPluginCreatesPlaceholder() throws IOException {
        File temp = File.createTempFile("testPlan", ".jmx");
        String testPlan = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.2" properties="5.0" jmeter="5.2-SNAPSHOT.20190506">
                  <hashTree>
                    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="Test Plan" enabled="true">
                      <stringProp name="TestPlan.comments"></stringProp>
                      <boolProp name="TestPlan.functional_mode">false</boolProp>
                      <boolProp name="TestPlan.tearDown_on_shutdown">true</boolProp>
                      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
                      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" \
                guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
                        <collectionProp name="Arguments.arguments"/>
                      </elementProp>
                      <stringProp name="TestPlan.user_define_classpath"></stringProp>
                    </TestPlan>
                    <hashTree>
                      <kg.apc.jmeter.samplers.DummySampler guiclass="kg.apc.jmeter.samplers.DummySamplerGui" \
                testclass="kg.apc.jmeter.samplers.DummySampler" testname="jp@gc - Dummy Sampler" enabled="true">
                        <boolProp name="WAITING">true</boolProp>
                        <boolProp name="SUCCESFULL">true</boolProp>
                        <stringProp name="RESPONSE_CODE">200</stringProp>
                        <stringProp name="RESPONSE_MESSAGE">OK</stringProp>
                        <stringProp name="REQUEST_DATA">{&quot;email&quot;:&quot;user1&quot;, &quot;password&quot;:&quot;password1&quot;}；\
                </stringProp>
                        <stringProp name="RESPONSE_DATA">{&quot;successful&quot;: true, &quot;account_id&quot;:&quot;0123456789&quot;}</stringProp>
                        <stringProp name="RESPONSE_TIME">${__Random(50,500)}</stringProp>
                        <stringProp name="LATENCY">${__Random(1,50)}</stringProp>
                        <stringProp name="CONNECT">${__Random(1,5)}</stringProp>
                      </kg.apc.jmeter.samplers.DummySampler>
                      <hashTree/>
                    </hashTree>
                  </hashTree>
                </jmeterTestPlan>""";
        try (FileOutputStream os = new FileOutputStream(temp);
                Writer fw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                BufferedWriter out = new BufferedWriter(fw)) {
            out.write(testPlan);
        }
        try {
            HashTree tree = SaveService.loadTree(temp);
            List<TestElement> missingElements = new ArrayList<>();
            tree.traverse(new HashTreeTraverser() {
                @Override
                public void addNode(Object node, HashTree subTree) {
                    if (MissingTestElement.class.getName().equals(node.getClass().getName())) {
                        missingElements.add((TestElement) node);
                    }
                }

                @Override
                public void subtractNode() {
                }

                @Override
                public void processPath() {
                }
            });

            Assertions.assertEquals(1, missingElements.size(), () -> tree.toString());
            TestElement missingElement = missingElements.get(0);
            Assertions.assertEquals("jp@gc - Dummy Sampler", missingElement.getName());
            Assertions.assertEquals("kg.apc.jmeter.samplers.DummySampler",
                    missingElement.getPropertyAsString(MissingTestElement.MISSING_TEST_CLASS));
            Assertions.assertEquals("kg.apc.jmeter.samplers.DummySamplerGui",
                    missingElement.getPropertyAsString(MissingTestElement.MISSING_GUI_CLASS));
            Assertions.assertFalse(missingElement.isEnabled());

            JMeter jmeter = new JMeter();
            Assertions.assertDoesNotThrow(() -> jmeter.runNonGui(temp.getAbsolutePath(), null, false));
        } finally {
            Assertions.assertTrue(temp.delete(), () -> "File " + temp.getAbsolutePath() + " should have been deleted");
        }
    }
}
