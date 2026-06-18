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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.jmeter.junit.stubs.TestSampler;
import org.apache.jmeter.samplers.Sampler;
import org.junit.jupiter.api.Test;

class TestParallelController {

    @Test
    void nextReturnsOneParallelSamplerForAllChildren() {
        ParallelController controller = new ParallelController();
        controller.setName("parallel");
        controller.addTestElement(new TestSampler("one"));
        controller.addTestElement(new TestSampler("two"));
        controller.addTestElement(new TestSampler("three"));
        controller.initialize();

        Sampler sampler = controller.next();

        ParallelControllerSampler parallelSampler = assertInstanceOf(ParallelControllerSampler.class, sampler);
        assertEquals("parallel", parallelSampler.getName());
        assertEquals(6, parallelSampler.getMaxParallel());
        assertEquals(3, parallelSampler.getSamplers().size());
        assertNull(controller.next());
    }

    @Test
    void controllerIsReusableAcrossIterationsAndIsNotMarkedDone() {
        ParallelController controller = new ParallelController();
        controller.setName("parallel");
        controller.addTestElement(new TestSampler("one"));
        controller.addTestElement(new TestSampler("two"));
        controller.initialize();

        for (int iteration = 0; iteration < 3; iteration++) {
            Sampler sampler = controller.next();
            ParallelControllerSampler parallelSampler =
                    assertInstanceOf(ParallelControllerSampler.class, sampler);
            assertEquals(2, parallelSampler.getSamplers().size());
            // End of this pass: returns null but must not be "done", otherwise the
            // parent controller would remove it from the tree (bug: 2nd iteration
            // skipped everything).
            assertNull(controller.next());
            assertFalse(controller.isDone());
        }
    }

    @Test
    void maxParallelIsAtLeastOne() {
        ParallelController controller = new ParallelController();

        controller.setMaxParallel(0);

        assertEquals(1, controller.getMaxParallel());
    }
}
