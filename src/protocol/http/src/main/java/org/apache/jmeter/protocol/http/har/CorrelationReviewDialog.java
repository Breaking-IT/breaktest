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

package org.apache.jmeter.protocol.http.har;

import java.util.List;
import java.util.Map;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.StepByStepReviewDialog;

/** Adapts correlations to the shared review wizard. */
final class CorrelationReviewDialog {
    private CorrelationReviewDialog() { }

    static void show(GuiPackage gui, List<HarPredefinedCorrelation> correlations, Map<Integer, JMeterTreeNode> nodes) {
        List<CorrelationReviewStep> steps = CorrelationReviewStep.create(correlations, nodes);
        StepByStepReviewDialog.show(gui, steps,
                requested -> FindPredefinedCorrelationsAction.acceptReviewSteps(gui, requested, steps, nodes));
    }
}
