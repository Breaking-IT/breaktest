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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.RecordedHarExchangeResolver;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

class LoadTest {

    @Test
    void collectBreakTestHarReferencesReadsGuiTreeNodes() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_FILENAME, "recording.har");
        threadGroup.setProperty(RecordedHarExchangeResolver.HAR_MD5, "0123456789abcdef0123456789abcdef");

        JMeterTreeNode testPlanNode = new JMeterTreeNode(new TestPlan("Test Plan"), null);
        JMeterTreeNode threadGroupNode = new JMeterTreeNode(threadGroup, null);
        ListedHashTree tree = new ListedHashTree();
        tree.add(testPlanNode);
        tree.add(testPlanNode, threadGroupNode);

        List<Load.BreakTestHarReference> references = Load.collectBreakTestHarReferences(tree);

        assertEquals(1, references.size());
        assertEquals("Thread Group", references.get(0).sourceName());
        assertEquals("recording.har", references.get(0).filename());
        assertEquals("0123456789abcdef0123456789abcdef", references.get(0).expectedMd5());
    }
}
