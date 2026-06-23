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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElementSchema;
import org.apache.jmeter.timers.Timer;
import org.junit.jupiter.api.Test;

class SearchTreeDialogTest {

    @Test
    void matchesSelectedNodeTypes() {
        assertTrue(SearchTreeDialog.matchesAnySelectedNodeType(
                new DummyPreProcessor(), EnumSet.of(SearchTreeDialog.NodeType.PRE_PROCESSOR)));
        assertTrue(SearchTreeDialog.matchesAnySelectedNodeType(
                new DummyPostProcessor(), EnumSet.of(SearchTreeDialog.NodeType.POST_PROCESSOR)));
        assertTrue(SearchTreeDialog.matchesAnySelectedNodeType(
                new DummyAssertion(), EnumSet.of(SearchTreeDialog.NodeType.ASSERTION)));
        assertTrue(SearchTreeDialog.matchesAnySelectedNodeType(
                new DummyTimer(), EnumSet.of(SearchTreeDialog.NodeType.TIMER)));
        assertTrue(SearchTreeDialog.matchesAnySelectedNodeType(
                new ConfigTestElement(), EnumSet.of(SearchTreeDialog.NodeType.CONFIG_ELEMENT)));
    }

    @Test
    void rejectsUnselectedNodeTypes() {
        assertFalse(SearchTreeDialog.matchesAnySelectedNodeType(
                new DummyPreProcessor(), EnumSet.of(SearchTreeDialog.NodeType.POST_PROCESSOR)));
    }

    private static class DummyPreProcessor extends DummyTestElement implements PreProcessor {
        @Override
        public void process() {
            // NOOP
        }
    }

    private static class DummyPostProcessor extends DummyTestElement implements PostProcessor {
        @Override
        public void process() {
            // NOOP
        }
    }

    private static class DummyAssertion extends DummyTestElement implements Assertion {
        @Override
        public AssertionResult getResult(SampleResult response) {
            return new AssertionResult(getName());
        }
    }

    private static class DummyTimer extends DummyTestElement implements Timer {
        @Override
        public long delay() {
            return 0;
        }
    }

    private abstract static class DummyTestElement extends AbstractTestElement {
        @Override
        public TestElementSchema getSchema() {
            return null;
        }
    }
}
