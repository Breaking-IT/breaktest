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

package org.apache.jmeter.control;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.functions.InvalidVariableException;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.testelement.property.FunctionProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selects one direct child per iteration using non-negative relative weights. */
public class WeightedSwitchController extends GenericController implements TestStateListener {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(WeightedSwitchController.class);
    private static final String INVALID_WEIGHT_MESSAGE = "Invalid weight '{}' for child '{}' in Weighted Switch "
            + "Controller '{}'; treating it as zero for this iteration";

    // Store the weight on the child so renaming, reordering and duplicate names are safe.
    // The BreakTest namespace also preserves this metadata when a child's GUI clears its properties.
    public static final String CHILD_WEIGHT = "BreakTest.WeightedSwitchController.weight";

    // Indexes of children whose invalid runtime weight was already logged at WARN. Shared by clone()
    // across the virtual users of a group and reset by testStarted(), so a bad variable is reported
    // once per child per test instead of once per selection per thread.
    private transient Set<Integer> warnedChildren = ConcurrentHashMap.newKeySet();

    public static String getWeight(TestElement child) {
        return child.getPropertyAsString(CHILD_WEIGHT, "1");
    }

    public static void setWeight(TestElement child, String weight) {
        validateWeight(weight);
        child.setProperty(CHILD_WEIGHT, weight.trim(), "1");
    }

    /** Validates a configured weight without evaluating variables or functions. */
    public static void validateWeight(String weight) {
        if (!isExpression(weight)) {
            parseWeight(weight);
        }
    }

    private static boolean isExpression(String weight) {
        if (!weight.contains("${")) {
            return false;
        }
        CompoundVariable expression = new CompoundVariable();
        try {
            expression.setParameters(weight.trim());
        } catch (InvalidVariableException e) {
            throw new IllegalArgumentException("Invalid weight expression", e);
        }
        return expression.hasFunction();
    }

    public static double parseWeight(String weight) {
        double value;
        try {
            value = Double.parseDouble(weight.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Weight must be a finite, non-negative number", e);
        }
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Weight must be a finite, non-negative number");
        }
        return value;
    }

    /**
     * Fails fast on invalid literal weights, which can only come from a hand-edited plan.
     * Expressions are checked when they are resolved, since their value can change per iteration.
     */
    @Override
    public void initialize() {
        for (TestElement child : getSubControllers()) {
            if (child.isEnabled() && !(child.getProperty(CHILD_WEIGHT) instanceof FunctionProperty)) {
                try {
                    validateWeight(getWeight(child));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid weight '" + getWeight(child) + "' for child '"
                            + child.getName() + "' in Weighted Switch Controller '" + getName() + "'", e);
                }
            }
        }
        super.initialize();
    }

    @Override
    public Sampler next() {
        if (isFirst()) {
            current = selectChild(ThreadLocalRandom.current().nextDouble());
        }
        return super.next();
    }

    // Scaling by the largest weight prevents overflow when several large weights are added.
    int selectChild(double chance) {
        double[] weights = new double[getSubControllers().size()];
        double maximum = 0;
        for (int i = 0; i < weights.length; i++) {
            TestElement child = getSubControllers().get(i);
            if (child.isEnabled()) {
                String weight = getWeight(child);
                try {
                    weights[i] = parseWeight(weight);
                } catch (IllegalArgumentException e) {
                    logInvalidWeight(i, child, weight);
                }
                maximum = Math.max(maximum, weights[i]);
            }
        }
        if (maximum == 0) {
            return Integer.MAX_VALUE;
        }
        double total = 0;
        for (double weight : weights) {
            total += weight / maximum;
        }
        double remaining = chance * total;
        int lastPositive = Integer.MAX_VALUE;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > 0) {
                lastPositive = i;
                remaining -= weights[i] / maximum;
                if (remaining < 0) {
                    return i;
                }
            }
        }
        return lastPositive;
    }

    private void logInvalidWeight(int index, TestElement child, String weight) {
        if (warnedChildren.add(index)) {
            log.warn(INVALID_WEIGHT_MESSAGE + ". Repeats for this child are logged at DEBUG.",
                    weight, child.getName(), getName());
        } else {
            log.debug(INVALID_WEIGHT_MESSAGE, weight, child.getName(), getName());
        }
    }

    @Override
    public Object clone() {
        WeightedSwitchController clone = (WeightedSwitchController) super.clone();
        clone.warnedChildren = warnedChildren;
        return clone;
    }

    @Override
    public void testStarted() {
        warnedChildren = ConcurrentHashMap.newKeySet();
    }

    @Override
    public void testStarted(String host) {
        testStarted();
    }

    @Override
    public void testEnded() {
        // NOOP
    }

    @Override
    public void testEnded(String host) {
        // NOOP
    }

    @Override
    protected Object readResolve() {
        super.readResolve();
        warnedChildren = ConcurrentHashMap.newKeySet();
        return this;
    }

    @Override
    protected void incrementCurrent() {
        current = Integer.MAX_VALUE;
    }

    @Override
    protected void currentReturnedNull(Controller controller) {
        super.currentReturnedNull(controller);
        // Even a finished/empty child consumes this selection; never fall through to its sibling.
        current = Integer.MAX_VALUE;
    }
}
