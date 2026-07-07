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

package org.apache.jmeter.protocol.http.sampler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JMX persistence of native sampler headers: saving stores them inside the
 * HTTPSamplerProxy element, loading legacy files folds child Header Managers.
 */
public class NativeHeadersJmxRoundTripTest extends JMeterTestCase {

    @TempDir
    Path tempDir;

    private static final String LEGACY_JMX = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<jmeterTestPlan version=\"1.2\" properties=\"5.0\">\n"
            + "  <hashTree>\n"
            + "    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\"Test Plan\"/>\n"
            + "    <hashTree>\n"
            + "      <ThreadGroup guiclass=\"ThreadGroupGui\" testclass=\"ThreadGroup\" testname=\"Thread Group\"/>\n"
            + "      <hashTree>\n"
            + "        <HTTPSamplerProxy guiclass=\"HttpTestSampleGui\" testclass=\"HTTPSamplerProxy\" testname=\"HTTP Request\">\n"
            + "          <stringProp name=\"HTTPSampler.domain\">example.com</stringProp>\n"
            + "          <stringProp name=\"HTTPSampler.path\">/api</stringProp>\n"
            + "          <stringProp name=\"HTTPSampler.method\">GET</stringProp>\n"
            + "        </HTTPSamplerProxy>\n"
            + "        <hashTree>\n"
            + "          <HeaderManager guiclass=\"HeaderPanel\" testclass=\"HeaderManager\" testname=\"HTTP Header Manager\">\n"
            + "            <collectionProp name=\"HeaderManager.headers\">\n"
            + "              <elementProp name=\"X-Api-Key\" elementType=\"Header\">\n"
            + "                <stringProp name=\"Header.name\">X-Api-Key</stringProp>\n"
            + "                <stringProp name=\"Header.value\">secret</stringProp>\n"
            + "              </elementProp>\n"
            + "            </collectionProp>\n"
            + "          </HeaderManager>\n"
            + "          <hashTree/>\n"
            + "        </hashTree>\n"
            + "      </hashTree>\n"
            + "    </hashTree>\n"
            + "  </hashTree>\n"
            + "</jmeterTestPlan>\n";

    private static HTTPSamplerProxy findSampler(HashTree tree) {
        for (Object key : tree.list()) {
            if (key instanceof HTTPSamplerProxy sampler) {
                return sampler;
            }
            HashTree subTree = tree.getTree(key);
            if (subTree != null) {
                HTTPSamplerProxy found = findSampler(subTree);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean containsHeaderManager(HashTree tree) {
        for (Object key : tree.list()) {
            if (key instanceof HeaderManager) {
                return true;
            }
            HashTree subTree = tree.getTree(key);
            if (subTree != null && containsHeaderManager(subTree)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void savedJmxStoresHeadersInsideSamplerElement() throws Exception {
        ListedHashTree tree = new ListedHashTree();
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("HTTP Request");
        sampler.setDomain("example.com");
        sampler.setNativeHeaders(Arrays.asList(new Header("X-Api-Key", "secret")));
        sampler.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        TestPlan plan = new TestPlan();
        plan.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.TestPlanGui");
        ThreadGroup group = new ThreadGroup();
        group.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        tree.add(plan).add(group).add(sampler);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SaveService.saveTree(tree, out);
        String xml = out.toString(StandardCharsets.UTF_8);

        assertTrue(xml.contains("HTTPSampler.headers"), "headers should be a sampler property:\n" + xml);
        assertFalse(xml.contains("<HeaderManager"), "no separate HeaderManager element expected:\n" + xml);

        File file = tempDir.resolve("native-headers.jmx").toFile();
        Files.write(file.toPath(), out.toByteArray());
        HashTree loaded = SaveService.loadTree(file);

        HTTPSamplerProxy reloaded = findSampler(loaded);
        List<Header> headers = reloaded.getNativeHeaderList();
        assertEquals(1, headers.size());
        assertEquals("X-Api-Key", headers.get(0).getName());
        assertEquals("secret", headers.get(0).getValue());
        assertFalse(containsHeaderManager(loaded));
    }

    @Test
    public void loadingLegacyJmxFoldsChildHeaderManagerIntoSampler() throws Exception {
        File file = tempDir.resolve("legacy.jmx").toFile();
        Files.write(file.toPath(), LEGACY_JMX.getBytes(StandardCharsets.UTF_8));

        HashTree loaded = SaveService.loadTree(file);

        HTTPSamplerProxy sampler = findSampler(loaded);
        List<Header> headers = sampler.getNativeHeaderList();
        assertEquals(1, headers.size());
        assertEquals("secret", headers.get(0).getValue());
        assertFalse(containsHeaderManager(loaded), "child Header Manager should be folded away");
    }
}
