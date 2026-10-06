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

import java.util.concurrent.ThreadLocalRandom;

import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;

/** Selects one direct child per iteration using non-negative relative weights. */
public class WeightedSwitchController extends GenericController {
    private static final long serialVersionUID = 1L;

    // Store the weight on the child so renaming, reordering and duplicate names are safe.
    // The BreakTest namespace also preserves this metadata when a child's GUI clears its properties.
    public static final String CHILD_WEIGHT = "BreakTest.WeightedSwitchController.weight";

    public static String getWeight(TestElement child) {
        return child.getPropertyAsString(CHILD_WEIGHT, "1");
    }

    public static void setWeight(TestElement child, String weight) {
        parseWeight(weight);
        child.setProperty(CHILD_WEIGHT, weight.trim(), "1");
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
                try {
                    weights[i] = parseWeight(getWeight(child));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid weight for child '" + child.getName()
                            + "' in Weighted Switch Controller '" + getName() + "'", e);
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
