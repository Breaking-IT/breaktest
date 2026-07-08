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

package org.apache.jmeter.gui.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.junit.jupiter.api.Test;

class JMeterCellRendererTest {

    @Test
    void postProcessorWithoutPostProcessorInClassNameUsesPostProcessorIcon() {
        JMeterTreeNode node = new JMeterTreeNode(new DummyExtractor(), null);

        assertEquals(
                JMeterCellRenderer.ModernTreeIcon.Kind.POST_PROCESSOR,
                JMeterCellRenderer.ModernTreeIcon.kindFor(node));
    }

    @Test
    void regexExtractorGuiClassUsesPostProcessorIcon() {
        DummyTestElement element = new DummyTestElement();
        element.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.extractor.gui.RegexExtractorGui");
        JMeterTreeNode node = new JMeterTreeNode(element, null);

        assertEquals(
                JMeterCellRenderer.ModernTreeIcon.Kind.POST_PROCESSOR,
                JMeterCellRenderer.ModernTreeIcon.kindFor(node));
    }

    private static class DummyExtractor extends AbstractTestElement implements PostProcessor {
        private static final long serialVersionUID = 1L;

        @Override
        public void process() {
            // NOOP
        }
    }

    private static class DummyTestElement extends AbstractTestElement {
        private static final long serialVersionUID = 1L;
    }
}
