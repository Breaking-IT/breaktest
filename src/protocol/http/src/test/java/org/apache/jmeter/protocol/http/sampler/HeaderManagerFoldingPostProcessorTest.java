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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

/**
 * Folding of legacy child Header Managers into native sampler headers on tree load.
 */
public class HeaderManagerFoldingPostProcessorTest {

    private final HeaderManagerFoldingPostProcessor processor = new HeaderManagerFoldingPostProcessor();

    private static HTTPSamplerProxy newSampler(String name) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName(name);
        return sampler;
    }

    private static HeaderManager manager(String name, Header... headers) {
        HeaderManager manager = new HeaderManager();
        manager.setName(name);
        for (Header header : headers) {
            manager.add(header);
        }
        return manager;
    }

    @Test
    public void childManagerIsFoldedIntoSamplerAndRemovedFromTree() {
        ListedHashTree tree = new ListedHashTree();
        TestPlan plan = new TestPlan();
        ThreadGroup group = new ThreadGroup();
        HTTPSamplerProxy sampler = newSampler("request");
        HeaderManager child = manager("child", new Header("X-Api-Key", "secret"));

        HashTree samplerTree = tree.add(plan).add(group).add(sampler);
        samplerTree.add(child);

        processor.process(tree);

        List<Header> nativeHeaders = sampler.getNativeHeaderList();
        assertEquals(1, nativeHeaders.size());
        assertEquals("secret", nativeHeaders.get(0).getValue());
        assertTrue(samplerTree.list().isEmpty(), "child manager should be removed from the tree");
    }

    @Test
    public void samplerSiblingsSurviveFolding() {
        ListedHashTree tree = new ListedHashTree();
        HTTPSamplerProxy sampler = newSampler("request");
        HeaderManager child = manager("child", new Header("Accept", "application/json"));
        ResponseAssertion assertion = new ResponseAssertion();

        HashTree samplerTree = tree.add(new TestPlan()).add(new ThreadGroup()).add(sampler);
        samplerTree.add(child);
        samplerTree.add(assertion);

        processor.process(tree);

        assertEquals(1, samplerTree.list().size(), "only the header manager should be removed");
        assertTrue(samplerTree.list().contains(assertion), "assertion must stay a child");
        assertEquals(1, sampler.getNativeHeaderList().size());
    }

    @Test
    public void disabledChildManagerIsLeftInPlace() {
        ListedHashTree tree = new ListedHashTree();
        HTTPSamplerProxy sampler = newSampler("request");
        HeaderManager child = manager("child", new Header("Accept", "application/json"));
        child.setEnabled(false);

        HashTree samplerTree = tree.add(new TestPlan()).add(new ThreadGroup()).add(sampler);
        samplerTree.add(child);

        processor.process(tree);

        assertTrue(sampler.getNativeHeaderList().isEmpty(), "disabled manager must not be folded");
        assertTrue(samplerTree.list().contains(child), "disabled manager must stay in the tree");
    }

    @Test
    public void firstChildManagerWinsOnNameConflicts() {
        ListedHashTree tree = new ListedHashTree();
        HTTPSamplerProxy sampler = newSampler("request");

        HashTree samplerTree = tree.add(new TestPlan()).add(new ThreadGroup()).add(sampler);
        samplerTree.add(manager("first", new Header("Accept", "application/json")));
        samplerTree.add(manager("second",
                new Header("ACCEPT", "text/html"),
                new Header("User-Agent", "BreakTest")));

        processor.process(tree);

        List<Header> nativeHeaders = sampler.getNativeHeaderList();
        assertEquals(2, nativeHeaders.size());
        assertEquals("application/json", nativeHeaders.get(0).getValue());
        assertEquals("User-Agent", nativeHeaders.get(1).getName());
        assertTrue(samplerTree.list().isEmpty());
    }

    @Test
    public void scopedManagerAtThreadGroupLevelIsNotFolded() {
        ListedHashTree tree = new ListedHashTree();
        HTTPSamplerProxy sampler = newSampler("request");
        HeaderManager scoped = manager("scoped", new Header("Accept", "application/json"));

        HashTree groupTree = tree.add(new TestPlan()).add(new ThreadGroup());
        groupTree.add(scoped);
        groupTree.add(sampler);

        processor.process(tree);

        assertTrue(sampler.getNativeHeaderList().isEmpty(), "scoped manager must not be folded");
        assertTrue(groupTree.list().contains(scoped), "scoped manager must stay in the tree");
    }

    @Test
    public void legacyPersistedHeaderManagerPropertyIsFolded() {
        ListedHashTree tree = new ListedHashTree();
        HTTPSamplerProxy sampler = newSampler("request");
        sampler.setHeaderManager(manager("legacy", new Header("X-Trace", "1")));
        tree.add(new TestPlan()).add(new ThreadGroup()).add(sampler);

        processor.process(tree);

        assertEquals(1, sampler.getNativeHeaderList().size());
        assertNull(sampler.getHeaderManager(), "legacy runtime property must be removed");
    }
}
