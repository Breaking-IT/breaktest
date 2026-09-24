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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.engine.util.ReplaceStringWithFunctions;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.test.samplers.CollectSamplesListener;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.TestCompiler;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.timers.Timer;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


public class TestTransactionController extends JMeterTestCase {

    private static final String GENERATE_PARENT_SAMPLE = "TransactionController.parent";

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
        transactionController.setName("transaction");

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
        HashTree transactionTree = hashTree.add(loop).add(transactionController);
        transactionTree.add(debugSampler).add(assertion);
        transactionTree.add(listener);

        TestCompiler compiler = new TestCompiler(hashTree);
        hashTree.traverse(compiler);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setNumThreads(1);

        ListenerNotifier notifier = new ListenerNotifier();

        JMeterThread thread = new JMeterThread(hashTree, threadGroup, notifier);
        thread.setThreadGroup(threadGroup);
        thread.setOnErrorStopThread(true);
        thread.run();

        assertEquals(2, listener.getEvents().size(),
                "Must receive the failing debug sample and the transaction stopped by it");
        SampleResult debugResult = listener.getEvents().get(0).getResult();
        SampleResult transaction = listener.getEvents().get(1).getResult();
        assertTrue(transaction.isTransaction());
        assertEquals(transaction.getTransaction(), debugResult.getParentTransaction());
        assertEquals("Number of samples in transaction : 1, number of failing samples : 1",
                transaction.getResponseMessage());
    }

    @Test
    public void testTimingModeExcludeTimersExcludesTimerBlockingInsideDelay() throws Exception {
        JMeterContextService.getContext().setVariables(new JMeterVariables());

        CollectSamplesListener listener = new CollectSamplesListener();

        TransactionController transactionController = new TransactionController();
        transactionController.setTimingMode(TransactionController.TIMING_MODE_TOTAL_EXCLUDE_TIMERS);

        DebugSampler debugSampler = new DebugSampler();
        // Like the Synchronizing Timer: waits inside delay() and returns no delay
        BlockingTimer timer = new BlockingTimer(300);

        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setContinueForever(false);

        ListedHashTree hashTree = new ListedHashTree();
        HashTree transactionTree = hashTree.add(loop).add(transactionController);
        transactionTree.add(debugSampler).add(timer);
        transactionTree.add(listener);

        TestCompiler compiler = new TestCompiler(hashTree);
        hashTree.traverse(compiler);

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setNumThreads(1);

        JMeterThread thread = new JMeterThread(hashTree, threadGroup, new ListenerNotifier());
        thread.setThreadGroup(threadGroup);
        thread.setOnErrorStopThread(true);
        thread.run();

        assertEquals(2, listener.getEvents().size());
        SampleResult transaction = listener.getEvents().get(1).getResult();
        assertTrue(transaction.isTransaction());
        // Timer pauses use millisecond timestamps, while sample boundaries can use the nano clock.
        // Their intersection can be slightly shorter than the requested sleep (299 ms in CI).
        assertTrue(transaction.getIdleTime() >= 290,
                () -> "Blocking timer wait must be idle time, got " + transaction.getIdleTime());
        assertTrue(transaction.getTime() < 300,
                () -> "Blocking timer wait must not count as transaction time, got " + transaction.getTime());
    }

    private static class BlockingTimer extends AbstractTestElement implements Timer {
        private static final long serialVersionUID = 1L;
        private final long blockMillis;

        BlockingTimer(long blockMillis) {
            this.blockMillis = blockMillis;
        }

        @Override
        public long delay() {
            try {
                Thread.sleep(blockMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
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
        RunningTransaction transaction = startTransaction(controller);
        long transactionStart = transaction.createStartedResult().getStartTime();

        transaction.addSample(successfulSample(transactionStart + 25, transactionStart + 225));
        transaction.addTimerPause(transactionStart + 50, transactionStart + 125);
        transaction.addTimerPause(transactionStart + 100, transactionStart + 175);
        SampleResult result = transaction.finish(transactionStart + 225, true);

        assertEquals(125, result.getIdleTime());
        assertEquals(100, result.getTime());
    }

    @Test
    public void testTimingModeExcludeTimersExcludesTrailingThinkTime() {
        TransactionController controller = new TransactionController();
        controller.setTimingMode(TransactionController.TIMING_MODE_TOTAL_EXCLUDE_TIMERS);
        RunningTransaction transaction = startTransaction(controller);
        long transactionStart = transaction.createStartedResult().getStartTime();

        transaction.addSample(successfulSample(transactionStart, transactionStart + 1000));
        // Think time as the last child: the transaction ends after it, but it is excluded
        transaction.addTimerPause(transactionStart + 1000, transactionStart + 4000);
        SampleResult result = transaction.finish(transactionStart + 4000, true);

        assertEquals(3000, result.getIdleTime());
        assertEquals(1000, result.getTime());
    }

    @Test
    public void testTimingModeExcludeTimersClampsPauseStraddlingEnd() {
        TransactionController controller = new TransactionController();
        controller.setTimingMode(TransactionController.TIMING_MODE_TOTAL_EXCLUDE_TIMERS);
        RunningTransaction transaction = startTransaction(controller);
        long transactionStart = transaction.createStartedResult().getStartTime();

        transaction.addTimerPause(transactionStart, transactionStart + 200);
        transaction.addSample(successfulSample(transactionStart + 200, transactionStart + 500));
        transaction.addTimerPause(transactionStart + 400, transactionStart + 3000);
        SampleResult result = transaction.finish(transactionStart + 500, true);

        assertEquals(300, result.getIdleTime());
        assertEquals(200, result.getTime());
    }

    @Test
    public void testTimingModeSumChildSamplesExcludesGaps() {
        TransactionController controller = new TransactionController();
        controller.setTimingMode(TransactionController.TIMING_MODE_SUM_CHILD_SAMPLES);
        RunningTransaction transaction = startTransaction(controller);
        long transactionStart = transaction.createStartedResult().getStartTime();

        transaction.addSample(successfulSample(transactionStart + 100, transactionStart + 250));
        transaction.addSample(successfulSample(transactionStart + 400, transactionStart + 450));
        SampleResult result = transaction.finish(transactionStart + 600, true);

        assertEquals(200, result.getTime());
        assertEquals("Number of samples in transaction : 2, number of failing samples : 0",
                result.getResponseMessage());
        assertTrue(result.isSuccessful());
    }

    @Test
    public void testTimingModeIncludeTimersMeasuresWholeTransaction() {
        TransactionController controller = new TransactionController();
        controller.setTimingMode(TransactionController.TIMING_MODE_TOTAL_INCLUDE_TIMERS);
        RunningTransaction transaction = startTransaction(controller);
        long transactionStart = transaction.createStartedResult().getStartTime();

        transaction.addSample(successfulSample(transactionStart + 100, transactionStart + 250));
        transaction.addTimerPause(transactionStart, transactionStart + 100);
        SampleResult result = transaction.finish(transactionStart + 600, true);

        assertEquals(600, result.getTime());
    }

    @Test
    public void testTransactionFailsWhenASampleFailsAndIgnoresSamplesAfterItEnds() {
        TransactionController controller = new TransactionController();
        RunningTransaction transaction = startTransaction(controller);
        long transactionStart = transaction.createStartedResult().getStartTime();
        SampleResult failed = successfulSample(transactionStart, transactionStart + 10);
        failed.setSuccessful(false);

        transaction.addSample(failed);
        SampleResult result = transaction.finish(transactionStart + 20, true);
        transaction.addSample(successfulSample(transactionStart + 20, transactionStart + 30));

        assertEquals("Number of samples in transaction : 1, number of failing samples : 1",
                result.getResponseMessage());
        assertEquals(null, transaction.finish(transactionStart + 40, true), "A transaction ends only once");
        assertTrue(TransactionController.isFromTransactionController(result));
        assertTrue(result.isTransaction());
        assertEquals(transaction.getRef(), result.getTransaction());
    }

    @Test
    public void testGenerateParentSamplePropertyIsDropped() {
        TransactionController controller = new TransactionController();
        controller.setProperty(new BooleanProperty(GENERATE_PARENT_SAMPLE, true));

        assertTrue(controller.getProperty(GENERATE_PARENT_SAMPLE) instanceof NullProperty);
    }

    @Test
    public void testGenerateParentSampleIsNotSavedAgain(@TempDir Path directory) throws Exception {
        String oldJmx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.2" properties="5.0">
                  <hashTree>
                    <TransactionController guiclass="TransactionControllerGui" testclass="TransactionController" testname="transaction">
                      <boolProp name="TransactionController.parent">true</boolProp>
                      <boolProp name="TransactionController.includeTimers">false</boolProp>
                    </TransactionController>
                    <hashTree/>
                  </hashTree>
                </jmeterTestPlan>
                """;
        Path oldPlan = Files.writeString(directory.resolve("old.jmx"), oldJmx);

        HashTree loaded = SaveService.loadTree(oldPlan.toFile());
        TransactionController loadedController = (TransactionController) loaded.getArray()[0];
        Path resaved = directory.resolve("resaved.jmx");
        try (OutputStream out = Files.newOutputStream(resaved)) {
            SaveService.saveTree(loaded, out);
        }
        String savedJmx = readPlanXml(resaved);

        assertTrue(loadedController.getProperty(GENERATE_PARENT_SAMPLE) instanceof NullProperty);
        assertEquals(TransactionController.TIMING_MODE_SUM_CHILD_SAMPLES, loadedController.getTimingMode(),
                "Other properties must load as before");
        assertFalse(savedJmx.contains(GENERATE_PARENT_SAMPLE), savedJmx);
        assertTrue(savedJmx.contains("TransactionController.includeTimers"), savedJmx);
    }

    /** Saved plans are archives holding the plan XML, older plans are plain XML */
    private static String readPlanXml(Path plan) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(plan))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith(".jmx")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return Files.readString(plan, StandardCharsets.UTF_8);
    }

    private static SampleResult successfulSample(long start, long end) {
        SampleResult sample = SampleResult.createTestSample(start, end);
        sample.setSuccessful(true);
        return sample;
    }

    private static RunningTransaction startTransaction(TransactionController controller) {
        return new RunningTransaction(controller, "transaction", controller.getTimingMode(), null);
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
