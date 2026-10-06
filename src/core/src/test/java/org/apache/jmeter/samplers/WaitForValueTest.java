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

package org.apache.jmeter.samplers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.jmeter.control.IfController;
import org.apache.jmeter.control.IfControllerCondition;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.testelement.property.FunctionProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class WaitForValueTest {
    @AfterEach
    void cleanup() {
        JMeterContextService.getContext().clear();
    }

    private WaitForValue action(String operator, String value) {
        WaitForValue action = new WaitForValue();
        action.getConditions().setConditions(List.of(new IfControllerCondition("${ready}", operator, value)));
        action.setTimeout("2000");
        return action;
    }

    @Test
    void immediateSuccessTimeoutAndInvalidConfiguration() {
        JMeterVariables vars = new JMeterVariables();
        JMeterContextService.getContext().setVariables(vars);
        WaitForValue action = action("equals", "yes");
        vars.put("ready", "yes");
        assertTrue(action.sample(null).isSuccessful());
        vars.put("ready", "no");
        action.setTimeout("20");
        SampleResult timeout = action.sample(null);
        assertFalse(timeout.isSuccessful());
        assertEquals("408", timeout.getResponseCode());
        action.setTimeout("0");
        assertEquals("400", action.sample(null).getResponseCode());
        action.setTimeout("invalid");
        assertEquals("400", action.sample(null).getResponseCode());
        action.setTimeout("10");
        action.getConditions().setConditions(List.of());
        assertEquals("400", action.sample(null).getResponseCode());
    }

    @Test
    void invalidNumericFunctionWaitsUntilTimeout() {
        SignallingVariables vars = new SignallingVariables();
        JMeterContextService.getContext().setVariables(vars);
        AtomicInteger executions = new AtomicInteger();
        WaitForValue action = action("greater_than", "5");
        IfControllerCondition condition = (IfControllerCondition) action.getConditions().getConditions().get(0).getObjectValue();
        condition.setProperty(new FunctionProperty(IfControllerCondition.OPERAND1, new CompoundVariable("dynamic lookup") {
            @Override
            public String execute() {
                executions.incrementAndGet();
                return "not a number";
            }
        }));
        action.setTimeout("10");
        SampleResult result = action.sample(null);
        assertEquals("408", result.getResponseCode());
        assertFalse(result.isSuccessful());
        assertTrue(executions.get() >= 1);
    }

    @Test
    void unsetEmptyAndNonNumericVariablesCanBecomeNumbers() throws Exception {
        for (String initial : new String[] {null, "", "not a number"}) {
            for (boolean variableOnLeft : List.of(true, false)) {
                SignallingVariables vars = new SignallingVariables();
                if (initial != null) {
                    vars.put("ready", initial);
                }
                WaitForValue action = action("greater_than_or_equal", "10");
                if (!variableOnLeft) {
                    action.getConditions().setConditions(List.of(
                            new IfControllerCondition("10", "less_than_or_equal", "${ready}")));
                }
                FutureTask<SampleResult> task = run(action, vars);
                assertTrue(vars.sleeping.await(1, TimeUnit.SECONDS));
                vars.put("ready", "10");
                assertTrue(task.get(1, TimeUnit.SECONDS).isSuccessful());
            }
        }
    }

    @Test
    void invalidLiteralNumbersFailEvenWhenVariableIsUnset() {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        for (boolean literalOnLeft : List.of(true, false)) {
            WaitForValue action = action("greater_than", "invalid");
            if (literalOnLeft) {
                action.getConditions().setConditions(List.of(
                        new IfControllerCondition("invalid", "greater_than", "${ready}")));
            }
            assertEquals("400", action.sample(null).getResponseCode());
        }
    }

    @Test
    void failedRegexCompilationDoesNotCorruptCachedPattern() {
        IfControllerCondition condition = new IfControllerCondition("text", "matches_regex", "text");
        assertTrue(condition.matchesRegex("text"));
        condition.setOperand2("[");
        for (int i = 0; i < 2; i++) {
            assertThrows(java.util.regex.PatternSyntaxException.class, () -> condition.matchesRegex("text"));
        }
        condition.setOperand2("other");
        assertFalse(condition.matchesRegex("text"));
        assertTrue(condition.matchesRegex("other"));
    }

    @Test
    void invalidDynamicRegexCanBecomeValid() throws Exception {
        for (String operator : List.of("matches_regex", "not_matches_regex")) {
            SignallingVariables vars = new SignallingVariables();
            vars.put("ready", "text");
            vars.put("target", "[");
            FutureTask<SampleResult> task = run(action(operator, "${target}"), vars);
            assertTrue(vars.sleeping.await(1, TimeUnit.SECONDS));
            // Wake with the same malformed pattern to exercise the regex cache failure path.
            vars.put("ready", "text");
            vars.put("target", operator.equals("matches_regex") ? "text" : "different");
            assertTrue(task.get(1, TimeUnit.SECONDS).isSuccessful());
        }
    }

    @Test
    void invalidLiteralRegexIsRejectedBeforeShortCircuiting() {
        SignallingVariables vars = new SignallingVariables();
        JMeterContextService.getContext().setVariables(vars);
        for (String operator : List.of("matches_regex", "not_matches_regex")) {
            WaitForValue action = action("equals", "yes");
            action.getConditions().addCondition(new IfControllerCondition("text", operator, "["));
            SampleResult result = action.sample(null);
            assertEquals("400", result.getResponseCode());
            assertFalse(result.isSuccessful());
            assertEquals(0, vars.waits.get());
        }
    }

    @Test
    void timeoutReportsAllRowsAndResolvedOperands() {
        JMeterVariables vars = new JMeterVariables();
        vars.put("ready", "no");
        vars.put("expected", "yes");
        JMeterContextService.getContext().setVariables(vars);
        WaitForValue action = action("equals", "${expected}");
        action.setTimeout("10");
        IfControllerCondition first = (IfControllerCondition) action.getConditions().getConditions().get(0).getObjectValue();
        first.setProperty(new FunctionProperty(IfControllerCondition.OPERAND2, new CompoundVariable("${expected}")));
        action.getConditions().addCondition(new IfControllerCondition("2", "greater_than", "1"));
        String message = action.sample(null).getResponseMessage();
        assertTrue(message.contains("after 10 ms (match all)"), message);
        assertTrue(message.contains("\"${ready}\" equals \"${expected}\" -> \"no\" equals \"yes\": false"), message);
        assertTrue(message.contains("2. \"2\" greater_than \"1\" -> \"2\" greater_than \"1\": true"), message);
    }

    @Test
    void timeoutReportsExistenceAndAnyMode() {
        JMeterVariables vars = new JMeterVariables();
        vars.put("present", "");
        JMeterContextService.getContext().setVariables(vars);
        WaitForValue action = action("exists", "");
        action.setTimeout("10");
        action.getConditions().setConditionMatch(IfController.MATCH_ANY);
        action.getConditions().addCondition(new IfControllerCondition("${present}", "not_exists", ""));
        String message = action.sample(null).getResponseMessage();
        assertTrue(message.contains("(match any)"), message);
        assertTrue(message.contains("\"${ready}\" exists -> variable \"ready\" = <missing>: false"), message);
        assertTrue(message.contains("\"${present}\" not_exists -> variable \"present\" = \"\": false"), message);
    }

    @Test
    void timeoutFormattingDoesNotReexecuteFunctions() {
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        AtomicInteger executions = new AtomicInteger();
        WaitForValue action = action("equals", "yes");
        action.setTimeout("10");
        IfControllerCondition condition = (IfControllerCondition) action.getConditions().getConditions().get(0).getObjectValue();
        condition.setProperty(new FunctionProperty(IfControllerCondition.OPERAND1, new CompoundVariable("${ready}") {
            @Override
            public String execute() {
                return "value-" + executions.incrementAndGet();
            }
        }));
        SampleResult result = action.sample(null);
        assertEquals("408", result.getResponseCode());
        String message = result.getResponseMessage();
        // Preparation or scheduling may consume the timeout before the first evaluation.
        assertTrue(executions.get() >= 1);
        assertTrue(message.contains("-> \"value-" + executions.get() + "\" equals \"yes\": false"), message);
    }

    @Test
    void allMutationPathsWakeSleepingWaiter() throws Exception {
        for (Consumer<JMeterVariables> mutation : List.<Consumer<JMeterVariables>>of(
                v -> v.put("ready", "yes"), v -> v.putObject("ready", "yes"),
                v -> v.putAll(Map.of("ready", "yes")), v -> {
                    JMeterVariables other = new JMeterVariables();
                    other.put("ready", "yes");
                    v.putAll(other);
                }, v -> v.remove("ready"), JMeterVariables::clear)) {
            SignallingVariables vars = new SignallingVariables();
            vars.put("ready", "no");
            WaitForValue action = action("not_equals", "no");
            FutureTask<SampleResult> task = run(action, vars);
            assertTrue(vars.sleeping.await(1, TimeUnit.SECONDS));
            mutation.accept(vars);
            assertTrue(task.get(1, TimeUnit.SECONDS).isSuccessful());
        }
    }

    @Test
    void writeBetweenEvaluationAndSleepIsNotLost() throws Exception {
        SignallingVariables vars = new SignallingVariables() {
            @Override
            public void awaitChange(ChangeSubscription subscription, long version, long nanos) throws InterruptedException {
                put("ready", "yes");
                super.awaitChange(subscription, version, nanos);
            }
        };
        assertTrue(run(action("equals", "yes"), vars).get(1, TimeUnit.SECONDS).isSuccessful());
    }

    @Test
    void noPollingAndInterruptStopsWait() throws Exception {
        SignallingVariables vars = new SignallingVariables();
        WaitForValue action = action("equals", "yes");
        FutureTask<SampleResult> task = run(action, vars);
        assertTrue(vars.sleeping.await(1, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.TimeoutException.class, () -> task.get(80, TimeUnit.MILLISECONDS));
        assertEquals(1, vars.waits.get());
        assertTrue(action.interrupt());
        assertEquals("499", task.get(1, TimeUnit.SECONDS).getResponseCode());
        assertFalse(action.interrupt());
    }

    @Test
    void compiledOperandsAndAnyConditionsAreReevaluated() throws Exception {
        SignallingVariables vars = new SignallingVariables();
        WaitForValue action = action("equals", "yes");
        IfControllerCondition condition = (IfControllerCondition) action.getConditions()
                .getConditions().get(0).getObjectValue();
        FunctionProperty compiled = new FunctionProperty(IfControllerCondition.OPERAND1,
                new CompoundVariable("${ready}"));
        compiled.setRunningVersion(true);
        condition.setProperty(compiled);
        action.getConditions().addCondition(new IfControllerCondition("absent", "exists", ""));
        action.getConditions().setConditionMatch(IfController.MATCH_ANY);
        FutureTask<SampleResult> task = run(action, vars);
        assertTrue(vars.sleeping.await(1, TimeUnit.SECONDS));
        vars.put("ready", "yes");
        assertTrue(task.get(1, TimeUnit.SECONDS).isSuccessful());
    }

    @Test
    void oneWriteWakesMultipleBranches() throws Exception {
        SignallingVariables vars = new SignallingVariables();
        FutureTask<SampleResult> first = run(action("exists", ""), vars);
        FutureTask<SampleResult> second = run(action("equals", "yes"), vars);
        assertTrue(vars.sleeping.await(1, TimeUnit.SECONDS));
        vars.put("ready", "yes");
        assertTrue(first.get(1, TimeUnit.SECONDS).isSuccessful());
        assertTrue(second.get(1, TimeUnit.SECONDS).isSuccessful());
    }

    @Test
    void unrelatedWritesDoNotReevaluateKnownDependencies() throws Exception {
        SignallingVariables vars = new SignallingVariables();
        vars.put("target", "yes");
        FutureTask<SampleResult> task = run(action("equals", "${target}"), vars);
        assertTrue(vars.sleeping.await(1, TimeUnit.SECONDS));
        for (int i = 0; i < 1000; i++) {
            vars.put("unrelated", Integer.toString(i));
        }
        assertThrows(java.util.concurrent.TimeoutException.class, () -> task.get(50, TimeUnit.MILLISECONDS));
        assertEquals(1, vars.waits.get());
        // The right-hand operand is a dependency too.
        vars.put("target", "${ready}");
        assertTrue(task.get(1, TimeUnit.SECONDS).isSuccessful());
    }

    @Test
    void customFunctionFallsBackToAllChanges() throws Exception {
        SignallingVariables vars = new SignallingVariables();
        WaitForValue action = action("equals", "yes");
        IfControllerCondition condition = (IfControllerCondition) action.getConditions().getConditions().get(0).getObjectValue();
        condition.setProperty(new FunctionProperty(IfControllerCondition.OPERAND1, new CompoundVariable("dynamic lookup") {
            @Override
            public String execute() {
                String name = vars.get("selected");
                return name == null ? "no" : vars.get(name);
            }
        }));
        FutureTask<SampleResult> task = run(action, vars);
        assertTrue(vars.sleeping.await(1, TimeUnit.SECONDS));
        vars.put("chosen", "yes");
        vars.put("selected", "chosen");
        assertTrue(task.get(1, TimeUnit.SECONDS).isSuccessful());
    }

    private FutureTask<SampleResult> run(WaitForValue action, JMeterVariables vars) {
        FutureTask<SampleResult> task = new FutureTask<>(() -> {
            JMeterContextService.getContext().setVariables(vars);
            try {
                return action.sample(null);
            } finally {
                JMeterContextService.getContext().clear();
            }
        });
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private static class SignallingVariables extends JMeterVariables {
        final CountDownLatch sleeping = new CountDownLatch(1);
        final AtomicInteger waits = new AtomicInteger();

        @Override
        public void awaitChange(ChangeSubscription subscription, long version, long nanos) throws InterruptedException {
            waits.incrementAndGet();
            sleeping.countDown();
            super.awaitChange(subscription, version, nanos);
        }
    }
}
