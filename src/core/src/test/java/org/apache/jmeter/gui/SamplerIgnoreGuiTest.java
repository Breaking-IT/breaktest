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

package org.apache.jmeter.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleIgnorePolicy;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;

class SamplerIgnoreGuiTest {
    public static class BeanSampler extends AbstractSampler implements TestBean {
        @Override
        public SampleResult sample(Entry entry) {
            return null;
        }
    }

    private static class SamplerGui extends AbstractSamplerGui {
        SamplerGui() {
            add(makeTitlePanel());
        }

        @Override
        public String getLabelResource() {
            return "sampler_ignore";
        }

        @Override
        public TestElement createTestElement() {
            BeanSampler sampler = new BeanSampler();
            configureTestElement(sampler);
            return sampler;
        }
    }

    @Test
    void policySurvivesBothGuiStylesAndResetsBetweenSamplers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (AbstractJMeterGuiComponent gui : List.of(new SamplerGui(), new TestBeanGUI(BeanSampler.class))) {
                BeanSampler sampler = new BeanSampler();
                for (SampleIgnorePolicy policy : SampleIgnorePolicy.values()) {
                    policy.save(sampler);
                    gui.configure(sampler);
                    JButton button = buttons(gui).stream()
                            .filter(b -> b.getToolTipText() != null && b.getToolTipText().equals(
                                    JMeterUtils.getResString("sampler_ignore_tooltip")))
                            .findFirst().orElseThrow();
                    assertTrue(button.isVisible());
                    assertEquals(JMeterUtils.getResString(policy.getLabelResource()), button.getText());
                    BeanSampler saved = new BeanSampler();
                    gui.modifyTestElement(saved);
                    assertEquals(policy, SampleIgnorePolicy.from(saved));
                }
                gui.clearGui();
                BeanSampler saved = new BeanSampler();
                gui.modifyTestElement(saved);
                assertEquals(SampleIgnorePolicy.NEVER, SampleIgnorePolicy.from(saved));
                assertEquals("", saved.getPropertyAsString(SampleIgnorePolicy.PROPERTY));
                gui.configure(new BeanSampler());
                gui.modifyTestElement(saved);
                assertEquals(SampleIgnorePolicy.NEVER, SampleIgnorePolicy.from(saved));
            }
        });
    }

    private static List<JButton> buttons(Container parent) {
        List<JButton> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button) {
                result.add(button);
            }
            if (child instanceof Container container) {
                result.addAll(buttons(container));
            }
        }
        return result;
    }
}
