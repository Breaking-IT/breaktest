/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jmeter.protocol.http.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestPlan;
import org.junit.jupiter.api.Test;

class PreviousResponseSearchTest {
    @Test
    void includesOnlyEarlierHttpSamplersAndPreservesDetachedAncestorMetadata() {
        var root = new JMeterTreeNode(new TestPlan(), null);
        root.setName("Plan");
        var transaction = new JMeterTreeNode(new TransactionController(), null);
        transaction.setName("Login");
        transaction.getTestElement().setProperty("recording", "source");
        root.add(transaction);
        var previous = new JMeterTreeNode(new HTTPSamplerProxy(), null);
        previous.setName("Token");
        transaction.add(previous);
        var current = new JMeterTreeNode(new HTTPSamplerProxy(), null);
        transaction.add(current);
        transaction.add(new JMeterTreeNode(new HTTPSamplerProxy(), null));

        var candidates = PreviousResponseSearch.previousSamplers(current);
        assertEquals(1, candidates.size());
        var candidate = candidates.get(0);
        assertSame(previous, candidate.target());
        assertEquals("Plan / Login / Token", candidate.path());
        assertNotSame(previous.getTestElement(), candidate.snapshot().getTestElement());
        var parent = (JMeterTreeNode) candidate.snapshot().getParent();
        transaction.getTestElement().setProperty("recording", "changed");
        assertEquals("source", parent.getTestElement().getPropertyAsString("recording"));
        assertTrue(PreviousResponseSearch.previousSamplers(previous).isEmpty());
    }

    @Test
    void findsEveryLiteralOccurrenceIncludingOverlaps() {
        assertEquals(List.of(0, 5), PreviousResponseSearch.findHits(null, "a.b\n a.b", "a.b")
                .stream().map(PreviousResponseSearch.Hit::offset).toList());
        assertEquals(List.of(0, 1), PreviousResponseSearch.findHits(null, "aaa", "aa")
                .stream().map(PreviousResponseSearch.Hit::offset).toList());
        assertTrue(PreviousResponseSearch.findHits(null, "axb", "a.b").isEmpty());
        assertTrue(PreviousResponseSearch.findHits(null, "abc", "").isEmpty());
    }
}
