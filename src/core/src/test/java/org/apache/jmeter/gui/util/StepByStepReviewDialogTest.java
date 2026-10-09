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

package org.apache.jmeter.gui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.Test;

class StepByStepReviewDialogTest {
    private static final class Step implements ReviewStep {
        private State state = State.PENDING;
        private final boolean visible;
        private boolean highlighted;

        private Step(boolean visible) { this.visible = visible; }
        @Override
        public State state() { return state; }
        @Override
        public JMeterTreeNode node() { return null; }
        @Override
        public boolean current() { return true; }
        @Override
        public String description() { return "match"; }
        @Override
        public void reject() { state = State.REJECTED; }
        @Override
        public void markStale() { state = State.STALE; }
        @Override
        public Runnable highlight(Component component) {
            highlighted = visible;
            return visible ? () -> highlighted = false : null;
        }
    }

    @Test
    void acceptAllAppliesOnlyVisiblePendingStepsAndClearsEditorsBeforeMutation() {
        Step skippedEarlier = new Step(true);
        Step invisible = new Step(false);
        Step rejected = new Step(true);
        rejected.reject();
        Step current = new Step(true);
        List<Step> steps = List.of(skippedEarlier, invisible, rejected, current);
        List<Step> revealed = new ArrayList<>();
        StepByStepReviewDialog.applyVisibleSteps(steps, step -> {
            revealed.add(step);
            return step.highlight(null);
        }, accepted -> {
            assertEquals(List.of(skippedEarlier, current), accepted);
            assertTrue(steps.stream().noneMatch(step -> step.highlighted));
            accepted.forEach(step -> step.state = ReviewStep.State.ACCEPTED);
        });
        assertEquals(List.of(skippedEarlier, invisible, current), revealed);
        assertEquals(ReviewStep.State.PENDING, invisible.state());
        assertEquals(ReviewStep.State.REJECTED, rejected.state());
    }
}
