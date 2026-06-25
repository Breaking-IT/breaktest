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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.engine.util.ReplaceStringWithFunctions;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.test.samplers.CollectSamplesListener;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;


public class TestTransactionController extends JMeterTestCase {

    private static final Method COMPUTE_TRANSACTION_DELAY;

    private static final Method COMPUTE_TRANSACTION_PACING_DELAY;

    private static final Method APPLY_TRANSACTION_DELAY;

    private static final Method APPLY_TRANSACTION_PACING;

    private static final Method RECORD_TRANSACTION_START;

    private static final Field NEXT_PACING_START_TIME;

    static {
        try {
            COMPUTE_TRANSACTION_DELAY = TransactionController.class.getDeclaredMethod("computeTransactionDelay");
            COMPUTE_TRANSACTION_DELAY.setAccessible(true);
            COMPUTE_TRANSACTION_PACING_DELAY =
                    TransactionController.class.getDeclaredMethod("computeTransactionPacingDelay", long.class);
            COMPUTE_TRANSACTION_PACING_DELAY.setAccessible(true);
            APPLY_TRANSACTION_DELAY = TransactionController.class.getDeclaredMethod("applyTransactionDelay");
            APPLY_TRANSACTION_DELAY.setAccessible(true);
            APPLY_TRANSACTION_PACING = TransactionController.class.getDeclaredMethod("applyTransactionPacing");
            APPLY_TRANSACTION_PACING.setAccessible(true);
            RECORD_TRANSACTION_START =
                    TransactionController.class.getDeclaredMethod("recordTransactionStart", long.class);
            RECORD_TRANSACTION_START.setAccessible(true);
            NEXT_PACING_START_TIME =
                    TransactionController.class.getDeclaredField("nextPacingStartTime");
            NEXT_PACING_START_TIME.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    public void testIssue57958() throws Exception {
        JMeterContextService.getContext().setVariables(new JMeterVariables());

        CollectSamplesListener listener = new CollectSamplesListener();

        TransactionController transactionController = new TransactionController();
        transactionController.setGenerateParentSample(true);

        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setTestFieldResponseCode();
        assertion.setToEqualsType();
        assertion.addTestString("201");

        DebugSampler debugSampler = new DebugSampler();
        debugSampler.addTestElement(assertion);

        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);

        ListedHashTree hashTree = new ListedHashTree();
        hashTree.add(loop);
        hashTree.add(loop, transactionController);
        hashTree.add(transactionController, debugSampler);
        hashTree.add(transactionController, listener);
        hashTree.add(debugSampler, assertion);

        TestCompiler compiler = new TestCompiler(hashTree);
        hashTree.traverse(compiler);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setNumThreads(1);

        ListenerNotifier notifier = new ListenerNotifier();

        JMeterThread thread = new JMeterThread(hashTree, threadGroup, notifier);
        thread.setThreadGroup(threadGroup);
        thread.setOnErrorStopThread(true);
        thread.run();

        assertEquals(1, listener.getEvents().size(),
                "Must one transaction samples with parent debug sample");
        assertEquals("Number of samples in transaction : 1, number of failing samples : 1",
                listener.getEvents().get(0).getResult().getResponseMessage());
    }

    @Test
    public void testFixedDelay() throws Exception {
        TransactionController controller = new TransactionController();
        controller.setDelayMode(TransactionController.DELAY_FIXED);
        controller.setFixedDelay("15");

        assertEquals(15, computeTransactionDelay(controller));
    }

    @Test
    public void testRandomDelaySupportsSameMinAndMax() throws Exception {
        TransactionController controller = new TransactionController();
        controller.setDelayMode(TransactionController.DELAY_RANDOM);
        controller.setDelayMin("7");
        controller.setDelayMax("7");

        assertEquals(7, computeTransactionDelay(controller));
    }

    @Test
    public void testGaussianRandomDelaySupportsSameMinAndMax() throws Exception {
        TransactionController controller = new TransactionController();
        controller.setDelayMode(TransactionController.DELAY_GAUSSIAN_RANDOM);
        controller.setDelayMin("9");
        controller.setDelayMax("9");

        assertEquals(9, computeTransactionDelay(controller));
    }

    @Test
    public void testRandomDelayRejectsInvalidRange() {
        TransactionController controller = new TransactionController();
        controller.setDelayMode(TransactionController.DELAY_RANDOM);
        controller.setDelayMin("10");
        controller.setDelayMax("5");

        assertThrows(IllegalArgumentException.class, () -> computeTransactionDelay(controller));
    }

    @Test
    public void testDelayValuesSupportVariables() throws Exception {
        JMeterVariables variables = new JMeterVariables();
        variables.put("delayMs", "12");
        JMeterContextService.getContext().setVariables(variables);

        TransactionController controller = new TransactionController();
        controller.setDelayMode(TransactionController.DELAY_FIXED);
        controller.setProperty(functionProperty(
                TransactionControllerSchema.INSTANCE.getFixedDelay().getName(),
                "${delayMs}"));

        assertEquals(12, computeTransactionDelay(controller));
    }

    @Test
    public void testValidationRunSkipsTransactionDelay() throws Exception {
        TransactionController controller = new TransactionController();
        controller.setDelayMode(TransactionController.DELAY_FIXED);
        controller.setFixedDelay("1000");

        JMeterContextService.setValidationRun(true);
        try {
            long start = System.currentTimeMillis();
            invokeVoid(APPLY_TRANSACTION_DELAY, controller);

            assertTrue(System.currentTimeMillis() - start < 500,
                    "Transaction delay should not sleep during validation");
        } finally {
            JMeterContextService.setValidationRun(false);
        }
    }

    @Test
    public void testPacingDoesNotDelayFirstIteration() throws Exception {
        TransactionController controller = new TransactionController();
        controller.setPacingMode(TransactionController.DELAY_FIXED);
        controller.setFixedPacing("100");

        assertEquals(0, computeTransactionPacingDelay(controller, 1000));
    }

    @Test
    public void testFixedPacingUsesDiffFromPreviousTransactionStart() throws Exception {
        TransactionController controller = new TransactionController();
        controller.setPacingMode(TransactionController.DELAY_FIXED);
        controller.setFixedPacing("100");
        setNextPacingStartTime(controller, 1100);

        assertEquals(60, computeTransactionPacingDelay(controller, 1040));
    }

    @Test
    public void testPacingDoesNotDelayWhenCurrentStartExceededTarget() throws Exception {
        TransactionController controller = new TransactionController();
        controller.setPacingMode(TransactionController.DELAY_FIXED);
        controller.setFixedPacing("100");
        setNextPacingStartTime(controller, 1100);

        assertEquals(0, computeTransactionPacingDelay(controller, 1120));
    }

    @Test
    public void testRandomPacingSupportsSameMinAndMax() throws Exception {
        TransactionController controller = new TransactionController();
        controller.setPacingMode(TransactionController.DELAY_RANDOM);
        controller.setPacingMin("70");
        controller.setPacingMax("70");
        setNextPacingStartTime(controller, 1070);

        assertEquals(40, computeTransactionPacingDelay(controller, 1030));
    }

    @Test
    public void testPacingValuesSupportVariables() throws Exception {
        JMeterVariables variables = new JMeterVariables();
        variables.put("pacingMs", "75");
        JMeterContextService.getContext().setVariables(variables);

        TransactionController controller = new TransactionController();
        controller.setPacingMode(TransactionController.DELAY_FIXED);
        controller.setProperty(functionProperty(
                TransactionControllerSchema.INSTANCE.getFixedPacing().getName(),
                "${pacingMs}"));
        setNextPacingStartTime(controller, 1075);

        assertEquals(50, computeTransactionPacingDelay(controller, 1025));
    }

    @Test
    public void testValidationRunSkipsTransactionPacing() throws Exception {
        TransactionController controller = new TransactionController();
        controller.setPacingMode(TransactionController.DELAY_FIXED);
        controller.setFixedPacing("1000");
        setNextPacingStartTime(controller, System.currentTimeMillis() + 1000);

        JMeterContextService.setValidationRun(true);
        try {
            long start = System.currentTimeMillis();
            invokeVoid(APPLY_TRANSACTION_PACING, controller);

            assertTrue(System.currentTimeMillis() - start < 500,
                    "Transaction pacing should not sleep during validation");
        } finally {
            JMeterContextService.setValidationRun(false);
        }
    }

    @Test
    public void testPacingCompensatesForDrift() throws Exception {
        TransactionController controller = new TransactionController();
        controller.setPacingMode(TransactionController.DELAY_FIXED);
        controller.setFixedPacing("100");
        setNextPacingStartTime(controller, 1100);

        recordTransactionStart(controller, 1103);

        assertEquals(97, computeTransactionPacingDelay(controller, 1103));
    }

    @Test
    public void testTimingModeMigratesOldIncludeTimersProperty() {
        TransactionController controller = new TransactionController();
        controller.setProperty(new BooleanProperty(
                TransactionControllerSchema.INSTANCE.getIncludeTimers().getName(), true));

        assertEquals(TransactionController.TIMING_MODE_TOTAL_INCLUDE_TIMERS, controller.getTimingMode());

        controller.setProperty(new BooleanProperty(
                TransactionControllerSchema.INSTANCE.getIncludeTimers().getName(), false));

        assertEquals(TransactionController.TIMING_MODE_SUM_CHILD_SAMPLES, controller.getTimingMode());
    }

    @Test
    public void testTimingModeCanExcludeMergedTimerPauses() {
        TransactionController controller = new TransactionController();
        controller.setTimingMode(TransactionController.TIMING_MODE_TOTAL_EXCLUDE_TIMERS);
        TransactionSampler sampler = new TransactionSampler(controller, "transaction");
        long transactionStart = sampler.getTransactionResult().getStartTime();
        SampleResult child = SampleResult.createTestSample(transactionStart + 25, transactionStart + 225);

        sampler.addSubSamplerResult(child);
        sampler.addTimerPause(transactionStart + 50, transactionStart + 125);
        sampler.addTimerPause(transactionStart + 100, transactionStart + 175);
        sampler.setTransactionDone();

        assertEquals(125, sampler.getTransactionResult().getIdleTime());
        assertEquals(100, sampler.getTransactionResult().getTime());
    }

    private static long computeTransactionDelay(TransactionController controller) throws Exception {
        return invokeLong(COMPUTE_TRANSACTION_DELAY, controller);
    }

    private static long computeTransactionPacingDelay(TransactionController controller, long now) throws Exception {
        return invokeLong(COMPUTE_TRANSACTION_PACING_DELAY, controller, now);
    }

    private static long invokeLong(Method method, TransactionController controller, Object... args) throws Exception {
        try {
            return (Long) method.invoke(controller, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static void invokeVoid(Method method, TransactionController controller) throws Exception {
        try {
            method.invoke(controller);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static void recordTransactionStart(TransactionController controller, long actualStartTime)
            throws Exception {
        RECORD_TRANSACTION_START.invoke(controller, actualStartTime);
    }

    private static void setNextPacingStartTime(TransactionController controller, long startTime)
            throws Exception {
        NEXT_PACING_START_TIME.set(controller, startTime);
    }

    private static JMeterProperty functionProperty(String propertyName, String value) throws Exception {
        ReplaceStringWithFunctions transformer =
                new ReplaceStringWithFunctions(new CompoundVariable(), new HashMap<>());
        JMeterProperty property = transformer.transformValue(new StringProperty(propertyName, value));
        property.setRunningVersion(true);
        return property;
    }
}
