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

package org.apache.jmeter.readiness.check;

import static org.apache.jmeter.readiness.check.TestPlanBuilder.group;
import static org.apache.jmeter.readiness.check.TestPlanBuilder.sampler;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.gui.action.Start;
import org.apache.jmeter.gui.action.validation.ValidationSettings;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.readiness.PlanFingerprint;
import org.apache.jmeter.readiness.evidence.ReadinessEvidenceStore;
import org.apache.jmeter.readiness.evidence.ReadinessRecorder;
import org.apache.jmeter.readiness.evidence.ReadinessRunListener;
import org.apache.jmeter.readiness.evidence.RunKind;
import org.apache.jmeter.readiness.evidence.RunSegment;
import org.apache.jmeter.readiness.evidence.RunStatus;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs a readiness validation through {@link Start}, as the wizard does, with a GUI test plan. Needs a display.
 */
@Timeout(60)
class StartReadinessIntegrationTest extends JMeterTestCase {
    private GuiPackage previous;
    private MainFrame frame;

    @AfterEach
    void restore() throws Exception {
        ReadinessEvidenceStore.clear();
        ValidationCheckpoints.clear();
        JMeterContextService.setValidationRun(false);
        JMeterContextService.getContext().clear();
        if (frame != null) {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
        Field field = GuiPackage.class.getDeclaredField("guiPack");
        field.setAccessible(true);
        field.set(null, previous);
    }

    private static JMeterTreeNode add(JMeterTreeModel model, JMeterTreeNode parent, TestElement element) {
        JMeterTreeNode node = new JMeterTreeNode(element, model);
        model.insertNodeInto(node, parent, parent.getChildCount());
        return node;
    }

    @Test
    void readinessValidationRunsThroughStartAndMatchesTheWizardFingerprint() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Start needs the main frame");
        previous = GuiPackage.getInstance();
        ThreadGroup group = group("tg");
        AtomicReference<JMeterTreeModel> model = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JMeterTreeModel treeModel = new JMeterTreeModel();
            JMeterTreeListener listener = new JMeterTreeListener(treeModel);
            GuiPackage.initInstance(listener, treeModel);
            frame = new MainFrame(treeModel, listener);
            GuiPackage.getInstance().setMainFrame(frame);
            JMeterTreeNode plan = (JMeterTreeNode) treeModel.getTestPlan().getArray()[0];
            JMeterTreeNode groupNode = add(treeModel, plan, group);
            var login = sampler("login", "/login");
            login.setProperty("response", "token=abc");
            JMeterTreeNode loginNode = add(treeModel, groupNode, login);
            RegexExtractor extractor = new RegexExtractor();
            extractor.setRefName("token");
            extractor.setRegex("token=(\\w+)");
            extractor.setTemplate("$1$");
            extractor.setMatchNumber(1);
            add(treeModel, loginNode, extractor);
            var next = sampler("next", "/next");
            next.setProperty("url", "http://localhost/next?t=${token}&u=${undefined}");
            add(treeModel, groupNode, next);
            model.set(treeModel);
        });
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<RunSegment> result = new AtomicReference<>();
        ValidationSettings settings = new ValidationSettings(1, true, 1, true);
        AbstractThreadGroup[] groups = {group};
        SwingUtilities.invokeAndWait(() -> new Start().doAction(Start.readinessValidationEvent(this, groups,
                settings, RunKind.MAIN, new ReadinessRunListener() {
                    @Override
                    public void runStarted(RunSegment segment) {
                        assertTrue(ReadinessRecorder.isActive());
                    }

                    @Override
                    public void runFinished(RunSegment segment) {
                        result.set(segment);
                        finished.countDown();
                    }

                    @Override
                    public void runNotStarted(String message) {
                        finished.countDown();
                    }
                })));
        assertTrue(finished.await(30, TimeUnit.SECONDS));
        RunSegment segment = result.get();
        assertNotNull(segment, "The readiness validation must start");
        assertEquals(RunStatus.COMPLETED, segment.status(), segment.statusDetails().toString());
        assertEquals(2, segment.samples().size());
        assertEquals(List.of(true), segment.extractions().stream().map(e -> e.matched()).toList());
        assertEquals(List.of("undefined"), segment.unresolved().stream().map(u -> u.variable()).toList());
        assertFalse(ReadinessRecorder.isActive());
        // The wizard recomputes the fingerprint from the GUI tree: unchanged plan, current evidence
        String now = PlanFingerprint.compute(Start.validationTree(model.get().getTestPlan(), groups), settings,
                Start.readinessScope(groups));
        assertEquals(segment.fingerprint(), now);
        for (int i = 0; i < 100 && JMeterUtils.isTestRunning(); i++) {
            Thread.sleep(50);
        }
        assertFalse(JMeterUtils.isTestRunning());
    }
}
