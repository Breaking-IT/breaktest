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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.junit.stubs.TestSampler;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestWhileController extends JMeterTestCase {

    private JMeterContext jmctx;
    private JMeterVariables jmvars;

    @BeforeEach
    public void setUp() {
        jmctx = JMeterContextService.getContext();
        jmctx.setVariables(new JMeterVariables());
        jmvars = jmctx.getVariables();
    }

    private void setLastSampleStatus(boolean status) {
        jmvars.put(JMeterThread.LAST_SAMPLE_OK, Boolean.toString(status));
    }

    private static void setRunning(TestElement el) {
        PropertyIterator pi = el.propertyIterator();
        while (pi.hasNext()) {
            pi.next().setRunningVersion(true);
        }
    }

    // Get next sample and its name
    private static String nextName(GenericController c) {
        Sampler s = c.next();
        if (s == null) {
            return null;
        }
        return s.getName();
    }

    @Test
    public void testWhileIndexCanStartAtOne() {
        WhileController whileController = new WhileController();
        String name = "While Controller";
        whileController.setName(name);
        whileController.setCondition("true");
        whileController.setIndexStartsAtOne(true);
        whileController.addTestElement(new TestSampler("run"));
        whileController.setRunningVersion(true);
        whileController.initialize();

        assertEquals("run", nextName(whileController));
        assertEquals(Integer.valueOf(1), jmvars.getObject(GenericController.getIndexVariableName(name)));
        assertEquals("run", nextName(whileController));
        assertEquals(Integer.valueOf(2), jmvars.getObject(GenericController.getIndexVariableName(name)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    public void testMaxIterationsAcrossConditionModesAndIndexBases(int limit) {
        for (boolean oneBased : new boolean[] {false, true}) {
            for (String mode : new String[] {"blank", "legacy", "all", "any"}) {
                WhileController controller = new WhileController();
                controller.setName("While Controller");
                controller.setMaxIterations(Integer.toString(limit));
                controller.setIndexStartsAtOne(oneBased);
                if ("legacy".equals(mode)) {
                    controller.setCondition("true");
                } else if (!"blank".equals(mode)) {
                    controller.setConditionMatch(mode);
                    controller.addCondition(new WhileControllerCondition("yes", "equals", "yes"));
                }
                controller.addTestElement(new TestSampler("one"));
                controller.addTestElement(new TestSampler("two"));
                LoopController parent = new LoopController();
                parent.setLoops(2);
                parent.addTestElement(controller);
                parent.addTestElement(new TestSampler("after"));
                setLastSampleStatus(true);
                controller.setRunningVersion(true);
                parent.initialize();

                for (int outer = 0; outer < 2; outer++) {
                    for (int iteration = 0; iteration < limit; iteration++) {
                        assertEquals("one", nextName(parent), mode);
                        assertEquals(Integer.valueOf(iteration + (oneBased ? 1 : 0)),
                                jmvars.getObject(GenericController.getIndexVariableName(controller.getName())));
                        assertEquals("two", nextName(parent), mode);
                    }
                    assertEquals("after", nextName(parent), mode);
                }
                assertNull(nextName(parent), mode);
            }
        }
    }

    @Test
    public void testMaxIterationsCountsLoopsSkippedByContinue() {
        WhileController controller = new WhileController();
        controller.setMaxIterations("2");
        controller.setCondition("true");
        controller.addTestElement(new TestSampler("one"));
        controller.addTestElement(new TestSampler("two"));
        controller.setRunningVersion(true);
        controller.initialize();
        assertEquals("one", nextName(controller));
        controller.startNextLoop();
        assertEquals("one", nextName(controller));
        controller.startNextLoop();
        assertNull(nextName(controller));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "invalid", "1.5", "${missing}", "2147483648"})
    public void testInvalidMaxIterationsStopsLoop(String limit) {
        WhileController controller = new WhileController();
        controller.setMaxIterations(limit);
        controller.setCondition("true");
        controller.addTestElement(new TestSampler("one"));
        controller.initialize();
        assertNull(nextName(controller));
    }

    @Test
    public void testMaxIterationsVariableIsReevaluated() throws Exception {
        WhileController controller = new WhileController();
        controller.setMaxIterations("${limit}");
        controller.setCondition("true");
        controller.addTestElement(new TestSampler("one"));
        jmvars.put("limit", "3");
        new ValueReplacer().replaceValues(controller);
        controller.setRunningVersion(true);
        controller.initialize();
        assertEquals("one", nextName(controller));
        jmvars.put("limit", "1");
        assertNull(nextName(controller));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "LAST", "${keepGoing}"})
    public void testConditionCanStopBeforeMaxIterations(String condition) throws Exception {
        WhileController controller = new WhileController();
        controller.setMaxIterations("5");
        controller.setCondition(condition);
        controller.addTestElement(new TestSampler("one"));
        setLastSampleStatus(true);
        jmvars.put("keepGoing", "true");
        new ValueReplacer().replaceValues(controller);
        controller.setRunningVersion(true);
        controller.initialize();
        assertEquals("one", nextName(controller));
        setLastSampleStatus(false);
        jmvars.put("keepGoing", "false");
        assertNull(nextName(controller));
    }

    // While (blank), previous sample OK - should loop until false
    @Test
    public void testBlankPrevOK() throws Exception {
        runtestPrevOK("");
    }

    // While (LAST), previous sample OK - should loop until false
    @Test
    public void testLastPrevOK() throws Exception {
        runtestPrevOK("LAST");
    }

    private static final String OTHER = "X"; // Dummy for testing functions

    // While (LAST), previous sample OK - should loop until false
    @Test
    public void testOtherPrevOK() throws Exception {
        runtestPrevOK(OTHER);
    }

    private void runtestPrevOK(String type) throws Exception {
        GenericController controller = new GenericController();
        WhileController while_cont = new WhileController();
        setLastSampleStatus(true);
        while_cont.setCondition(type);
        while_cont.addTestElement(new TestSampler("one"));
        while_cont.addTestElement(new TestSampler("two"));
        while_cont.addTestElement(new TestSampler("three"));
        controller.addTestElement(while_cont);
        controller.addTestElement(new TestSampler("four"));
        controller.initialize();
        assertEquals("one", nextName(controller));
        assertEquals("two", nextName(controller));
        assertEquals("three", nextName(controller));
        assertEquals("one", nextName(controller));
        assertEquals("two", nextName(controller));
        assertEquals("three", nextName(controller));
        assertEquals("one", nextName(controller));
        setLastSampleStatus(false);
        if (type.equals(OTHER)) {
            while_cont.setCondition("false");
        }
        assertEquals("two", nextName(controller));
        assertEquals("three", nextName(controller));
        setLastSampleStatus(true);
        if (type.equals(OTHER)) {
            while_cont.setCondition(OTHER);
        }
        assertEquals("one", nextName(controller));
        assertEquals("two", nextName(controller));
        assertEquals("three", nextName(controller));
        setLastSampleStatus(false);
        if (type.equals(OTHER)) {
            while_cont.setCondition("false");
        }
        assertEquals("four", nextName(controller));
        assertNull(nextName(controller));
        setLastSampleStatus(true);
        if (type.equals(OTHER)) {
            while_cont.setCondition(OTHER);
        }
        assertEquals("one", nextName(controller));
    }

    // While (blank), previous sample failed - should run once
    @Test
    public void testBlankPrevFailed() throws Exception {
        GenericController controller = new GenericController();
        controller.setRunningVersion(true);
        WhileController while_cont = new WhileController();
        setLastSampleStatus(false);
        while_cont.setCondition("");
        while_cont.addTestElement(new TestSampler("one"));
        while_cont.addTestElement(new TestSampler("two"));
        controller.addTestElement(while_cont);
        controller.addTestElement(new TestSampler("three"));
        controller.initialize();
        assertEquals("one", nextName(controller));
        assertEquals("two", nextName(controller));
        assertEquals("three", nextName(controller));
        assertNull(nextName(controller));
        // Run entire test again
        assertEquals("one", nextName(controller));
        assertEquals("two", nextName(controller));
        assertEquals("three", nextName(controller));
        assertNull(nextName(controller));
    }

    /**
     * Generic Controller
     * - before
     * - While Controller ${VAR}
     * - - one
     * - - two
     * - - Simple Controller
     * - - - three
     * - - - four
     * - after
     */
    @Test
    public void testVariable1() throws Exception {
        GenericController controller = new GenericController();
        WhileController while_cont = new WhileController();
        setLastSampleStatus(false);
        while_cont.setCondition("${VAR}");
        jmvars.put("VAR", "");
        ValueReplacer vr = new ValueReplacer();
        vr.replaceValues(while_cont);
        setRunning(while_cont);
        controller.addTestElement(new TestSampler("before"));
        controller.addTestElement(while_cont);
        while_cont.addTestElement(new TestSampler("one"));
        while_cont.addTestElement(new TestSampler("two"));
        GenericController simple = new GenericController();
        while_cont.addTestElement(simple);
        simple.addTestElement(new TestSampler("three"));
        simple.addTestElement(new TestSampler("four"));
        controller.addTestElement(new TestSampler("after"));
        controller.initialize();
        for (int i = 1; i <= 3; i++) {
            assertEquals("before", nextName(controller), "Loop: " + i);
            assertEquals("one", nextName(controller), "Loop: " + i);
            assertEquals("two", nextName(controller), "Loop: " + i);
            assertEquals("three", nextName(controller), "Loop: " + i);
            assertEquals("four", nextName(controller), "Loop: " + i);
            assertEquals("after", nextName(controller), "Loop: " + i);
            assertNull(nextName(controller), "Loop: " + i);
        }
        jmvars.put("VAR", "LAST"); // Should not enter the loop
        for (int i = 1; i <= 3; i++) {
            assertEquals("before", nextName(controller), "Loop: " + i);
            assertEquals("after", nextName(controller), "Loop: " + i);
            assertNull(nextName(controller), "Loop: " + i);
        }
        jmvars.put("VAR", "");
        for (int i = 1; i <= 3; i++) {
            assertEquals("before", nextName(controller), "Loop: " + i);
            if (i == 1) {
                assertEquals("one", nextName(controller), "Loop: " + i);
                assertEquals("two", nextName(controller), "Loop: " + i);
                assertEquals("three", nextName(controller), "Loop: " + i);
                jmvars.put("VAR", "LAST"); // Should not enter the loop next time
                assertEquals("four", nextName(controller), "Loop: " + i);
            }
            assertEquals("after", nextName(controller), "Loop: " + i);
            assertNull(nextName(controller), "Loop: " + i);
        }
    }

    // Test with SimpleController as first item
    @Test
    public void testVariable2() throws Exception {
        GenericController controller = new GenericController();
        WhileController while_cont = new WhileController();
        setLastSampleStatus(false);
        while_cont.setCondition("${VAR}");
        jmvars.put("VAR", "");
        ValueReplacer vr = new ValueReplacer();
        vr.replaceValues(while_cont);
        setRunning(while_cont);
        controller.addTestElement(new TestSampler("before"));
        controller.addTestElement(while_cont);
        GenericController simple = new GenericController();
        while_cont.addTestElement(simple);
        simple.addTestElement(new TestSampler("one"));
        simple.addTestElement(new TestSampler("two"));
        while_cont.addTestElement(new TestSampler("three"));
        while_cont.addTestElement(new TestSampler("four"));
        controller.addTestElement(new TestSampler("after"));
        controller.initialize();
        for (int i = 1; i <= 3; i++) {
            assertEquals("before", nextName(controller), "Loop: " + i);
            assertEquals("one", nextName(controller), "Loop: " + i);
            assertEquals("two", nextName(controller), "Loop: " + i);
            assertEquals("three", nextName(controller), "Loop: " + i);
            assertEquals("four", nextName(controller), "Loop: " + i);
            assertEquals("after", nextName(controller), "Loop: " + i);
            assertNull(nextName(controller), "Loop: " + i);
        }
        jmvars.put("VAR", "LAST"); // Should not enter the loop
        for (int i = 1; i <= 3; i++) {
            assertEquals("before", nextName(controller), "Loop: " + i);
            assertEquals("after", nextName(controller), "Loop: " + i);
            assertNull(nextName(controller), "Loop: " + i);
        }
        jmvars.put("VAR", "");
        for (int i = 1; i <= 3; i++) {
            assertEquals("before", nextName(controller), "Loop: " + i);
            if (i == 1) {
                assertEquals("one", nextName(controller), "Loop: " + i);
                assertEquals("two", nextName(controller), "Loop: " + i);
                jmvars.put("VAR", "LAST"); // Should not enter the loop next time
                // But should continue to the end of the loop
                assertEquals("three", nextName(controller), "Loop: " + i);
                assertEquals("four", nextName(controller), "Loop: " + i);
            }
            assertEquals("after", nextName(controller), "Loop: " + i);
            assertNull(nextName(controller), "Loop: " + i);
        }
    }

    // While LAST, previous sample failed - should not run
    @Test
    public void testLASTPrevFailed() throws Exception {
        runTestPrevFailed("LAST");
    }

    // While False, previous sample failed - should not run
    @Test
    public void testfalsePrevFailed() throws Exception {
        runTestPrevFailed("False");
    }

    private void runTestPrevFailed(String s) throws Exception {
        GenericController controller = new GenericController();
        WhileController while_cont = new WhileController();
        setLastSampleStatus(false);
        while_cont.setCondition(s);
        while_cont.addTestElement(new TestSampler("one"));
        while_cont.addTestElement(new TestSampler("two"));
        controller.addTestElement(while_cont);
        controller.addTestElement(new TestSampler("three"));
        controller.initialize();
        assertEquals("three", nextName(controller));
        assertNull(nextName(controller));
        assertEquals("three", nextName(controller));
        assertNull(nextName(controller));
    }

    @Test
    public void testLastFailedBlank() throws Exception {
        runTestLastFailed("");
    }

    @Test
    public void testLastFailedLast() throws Exception {
        runTestLastFailed("LAST");
    }

    // Should behave the same for blank and LAST because success on input
    private void runTestLastFailed(String s) throws Exception {
        GenericController controller = new GenericController();
        controller.addTestElement(new TestSampler("1"));
        WhileController while_cont = new WhileController();
        controller.addTestElement(while_cont);
        while_cont.setCondition(s);
        GenericController sub = new GenericController();
        while_cont.addTestElement(sub);
        sub.addTestElement(new TestSampler("2"));
        sub.addTestElement(new TestSampler("3"));

        controller.addTestElement(new TestSampler("4"));

        setLastSampleStatus(true);
        controller.initialize();
        assertEquals("1", nextName(controller));
        assertEquals("2", nextName(controller));
        setLastSampleStatus(false);
        assertEquals("3", nextName(controller));
        assertEquals("4", nextName(controller));
        assertNull(nextName(controller));
    }

    // Tests for Stack Overflow (bug 33954)
    @Test
    public void testAlwaysFailOK() throws Exception {
        runTestAlwaysFail(true); // Should be OK
    }

    @Test
    public void testAlwaysFailBAD() throws Exception {
        runTestAlwaysFail(false);
    }

    private void runTestAlwaysFail(boolean other) {
        LoopController controller = new LoopController();
        controller.setContinueForever(true);
        controller.setLoops(-1);
        WhileController while_cont = new WhileController();
        setLastSampleStatus(false);
        while_cont.setCondition("false");
        while_cont.addTestElement(new TestSampler("one"));
        while_cont.addTestElement(new TestSampler("two"));
        controller.addTestElement(while_cont);
        if (other) {
            controller.addTestElement(new TestSampler("three"));
        }
        controller.initialize();
        try {
            if (other) {
                assertEquals("three", nextName(controller));
            } else {
                assertNull(nextName(controller));
            }
        } catch (StackOverflowError e) {
            fail(e.toString());
        }
    }

    @Test
    public void testStructuredConditionsMatchAll() throws Exception {
        GenericController controller = new GenericController();
        WhileController whileCont = new WhileController();
        whileCont.setMaxIterations("5");
        whileCont.addCondition(new WhileControllerCondition("${keepGoing}", WhileController.Operator.EQUALS.getId(), "true"));
        whileCont.addCondition(new WhileControllerCondition("10", WhileController.Operator.GREATER_THAN.getId(), "2"));
        whileCont.addTestElement(new TestSampler("one"));
        whileCont.addTestElement(new TestSampler("two"));
        controller.addTestElement(whileCont);
        controller.addTestElement(new TestSampler("after"));

        jmvars.put("keepGoing", "true");
        new ValueReplacer().replaceValues(whileCont);
        setRunning(whileCont);
        controller.initialize();

        assertEquals("one", nextName(controller));
        jmvars.put("keepGoing", "false");
        assertEquals("two", nextName(controller));
        assertEquals("after", nextName(controller));
        assertNull(nextName(controller));
    }

    @Test
    public void testStructuredConditionsMatchAny() throws Exception {
        GenericController controller = new GenericController();
        WhileController whileCont = new WhileController();
        whileCont.setConditionMatch(WhileController.MATCH_ANY);
        whileCont.addCondition(new WhileControllerCondition("${value}", WhileController.Operator.MATCHES_REGEX.getId(), "\\d+"));
        whileCont.addCondition(new WhileControllerCondition("${value}", WhileController.Operator.STARTS_WITH.getId(), "a"));
        whileCont.addTestElement(new TestSampler("one"));
        controller.addTestElement(whileCont);
        controller.addTestElement(new TestSampler("after"));

        jmvars.put("value", "abc");
        new ValueReplacer().replaceValues(whileCont);
        setRunning(whileCont);
        controller.initialize();

        assertEquals("one", nextName(controller));
        jmvars.put("value", "nope");
        assertEquals("after", nextName(controller));
        assertNull(nextName(controller));
    }

    @Test
    public void testStructuredConditionsSkipWhenFalseOnEntry() throws Exception {
        GenericController controller = new GenericController();
        WhileController whileCont = new WhileController();
        whileCont.setMaxIterations("5");
        whileCont.addCondition(new WhileControllerCondition("${keepGoing}", WhileController.Operator.EQUALS.getId(), "true"));
        whileCont.addTestElement(new TestSampler("one"));
        controller.addTestElement(whileCont);
        controller.addTestElement(new TestSampler("after"));

        jmvars.put("keepGoing", "false");
        new ValueReplacer().replaceValues(whileCont);
        setRunning(whileCont);
        controller.initialize();

        assertEquals("after", nextName(controller));
        assertNull(nextName(controller));
    }

    @Test
    public void testStructuredVariableExistenceCondition() throws Exception {
        GenericController controller = new GenericController();
        WhileController whileCont = new WhileController();
        whileCont.addCondition(new WhileControllerCondition("${present}", WhileController.Operator.EXISTS.getId(), ""));
        whileCont.addTestElement(new TestSampler("one"));
        controller.addTestElement(whileCont);
        controller.addTestElement(new TestSampler("after"));

        jmvars.put("present", "value");
        new ValueReplacer().replaceValues(whileCont);
        setRunning(whileCont);
        controller.initialize();

        assertEquals("one", nextName(controller));
        jmvars.remove("present");
        assertEquals("after", nextName(controller));
        assertNull(nextName(controller));
    }

    @Test
    public void testLegacyConditionWinsWhenBothModesHaveContent() throws Exception {
        GenericController controller = new GenericController();
        WhileController whileCont = new WhileController();
        whileCont.setCondition("false");
        whileCont.addCondition(new WhileControllerCondition("10", WhileController.Operator.EQUALS.getId(), "10"));
        whileCont.addTestElement(new TestSampler("one"));
        controller.addTestElement(whileCont);
        controller.addTestElement(new TestSampler("after"));

        new ValueReplacer().replaceValues(whileCont);
        setRunning(whileCont);
        controller.initialize();

        assertEquals("after", nextName(controller));
        assertNull(nextName(controller));
    }
}
