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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.PatternSyntaxException;

import org.apache.jmeter.control.IfController;
import org.apache.jmeter.control.IfControllerCondition;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.testelement.property.FunctionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.threads.JMeterVariables;

/** An interruptible, change-triggered wait on the current virtual user's variables. */
public class WaitForValue extends AbstractSampler implements Interruptible {
    private static final long serialVersionUID = 1L;
    private static final String CONDITIONS = "WaitForValue.conditions";
    private static final String TIMEOUT = "WaitForValue.timeout";
    private transient Thread waitingThread;

    public WaitForValue() {
        setConditions(new IfController());
    }

    public IfController getConditions() {
        return (IfController) getProperty(CONDITIONS).getObjectValue();
    }

    public void setConditions(IfController conditions) {
        setProperty(new TestElementProperty(CONDITIONS, conditions));
    }

    public String getTimeout() {
        return getPropertyAsString(TIMEOUT, "30000");
    }

    public void setTimeout(String timeout) {
        setProperty(TIMEOUT, timeout);
    }

    @Override
    public SampleResult sample(Entry entry) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(getName());
        result.sampleStart();
        synchronized (this) {
            waitingThread = Thread.currentThread();
        }
        try {
            long timeout = Long.parseLong(getTimeout().trim());
            if (timeout <= 0 || getConditions().getConditions().isEmpty()) {
                throw new IllegalArgumentException("Specify conditions and a positive timeout in milliseconds");
            }
            long duration = TimeUnit.MILLISECONDS.toNanos(timeout);
            long start = System.nanoTime();
            JMeterVariables variables = getThreadContext().getVariables();
            List<PreparedCondition> conditions = prepareConditions();
            try (JMeterVariables.ChangeSubscription subscription = variables.watchChanges(dependencies(conditions))) {
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                    long version = subscription.getVersion();
                    // Collect diagnostics only for the final check, not on every wakeup.
                    StringBuilder diagnostics = System.nanoTime() - start >= duration ? new StringBuilder() : null;
                    if (matches(conditions, diagnostics)) {
                        result.setSuccessful(true);
                        result.setResponseCodeOK();
                        result.setResponseMessage("Condition met");
                        break;
                    }
                    if (diagnostics != null) {
                        result.setResponseCode("408");
                        result.setResponseMessage("Timed out waiting for value after " + timeout + " ms (match "
                                + (IfController.MATCH_ANY.equals(getConditions().getConditionMatch()) ? "any" : "all")
                                + "). Last evaluation:" + diagnostics);
                        break;
                    }
                    long remaining = duration - (System.nanoTime() - start);
                    variables.awaitChange(subscription, version, remaining);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.setResponseCode("499");
            result.setResponseMessage("Wait interrupted");
        } catch (RuntimeException e) {
            result.setResponseCode("400");
            result.setResponseMessage("Invalid wait configuration: " + e.getMessage());
        } finally {
            synchronized (this) {
                waitingThread = null;
            }
            result.sampleEnd();
        }
        return result;
    }

    private boolean matches(List<PreparedCondition> conditions, StringBuilder diagnostics) {
        IfController settings = getConditions();
        boolean any = IfController.MATCH_ANY.equals(settings.getConditionMatch());
        boolean combined = !any;
        int row = 0;
        for (PreparedCondition condition : conditions) {
            String operator = condition.operator;
            boolean existence = condition.variableName != null;
            ResolvedCondition resolved = condition.resolved;
            if (!existence) {
                resolved.left = condition.left.execute();
                resolved.right = condition.right.execute();
            }
            boolean match;
            String variableName = null;
            Object variableValue = null;
            if (existence) {
                variableName = condition.variableName;
                variableValue = getThreadContext().getVariables().getObject(variableName);
                match = IfController.Operator.EXISTS.getId().equals(operator)
                        ? variableValue != null : variableValue == null;
            } else {
                try {
                    match = IfController.evaluateStructuredConditionStrict(resolved);
                } catch (NumberFormatException | PatternSyntaxException e) {
                    // Literal operands were validated during preparation. Dynamic values may
                    // be unset, empty, or temporarily invalid until another branch updates them.
                    match = false;
                }
            }
            if (diagnostics != null) {
                diagnostics.append("\n").append(++row).append(". ")
                        .append(quote(condition.rawLeft)).append(' ').append(operator);
                if (existence) {
                    diagnostics.append(" -> variable ").append(quote(variableName)).append(" = ")
                            .append(variableValue == null ? "<missing>" : quote(String.valueOf(variableValue)));
                } else {
                    diagnostics.append(' ').append(quote(condition.rawRight))
                            .append(" -> ").append(quote(resolved.getOperand1())).append(' ').append(operator)
                            .append(' ').append(quote(resolved.getOperand2()));
                }
                diagnostics.append(": ").append(match);
            }
            if (match == any) {
                if (diagnostics == null || any) {
                    return any;
                }
                combined = any;
            }
        }
        return combined;
    }

    private static String raw(JMeterProperty property) {
        if (property instanceof FunctionProperty function) {
            return function.getRawValue();
        }
        return property.getStringValue();
    }

    private static String quote(String value) {
        // Bound individual values and keep multiline parameters readable in result messages.
        String abbreviated = value.length() > 512 ? value.substring(0, 512) + "... [truncated]" : value;
        return "\"" + abbreviated.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private List<PreparedCondition> prepareConditions() {
        List<PreparedCondition> result = new ArrayList<>();
        for (JMeterProperty property : getConditions().getConditions()) {
            result.add(new PreparedCondition((IfControllerCondition) property.getObjectValue()));
        }
        return result;
    }

    private static Set<String> dependencies(List<PreparedCondition> conditions) {
        Set<String> result = new HashSet<>();
        for (PreparedCondition condition : conditions) {
            if (condition.variableName != null) {
                result.add(condition.variableName);
                continue;
            }
            Set<String> left = condition.left.getVariableDependencies();
            Set<String> right = condition.right.getVariableDependencies();
            if (left == null || right == null) {
                return null;
            }
            result.addAll(left);
            result.addAll(right);
        }
        return result;
    }

    private static CompoundVariable compile(JMeterProperty property) {
        if (property instanceof FunctionProperty functionProperty
                && property.getObjectValue() instanceof CompoundVariable function
                && java.util.Objects.equals(functionProperty.getRawValue(), function.getRawParameters())) {
            return function;
        }
        return new CompoundVariable(raw(property));
    }

    private static final class PreparedCondition {
        final String rawLeft;
        final String rawRight;
        final String operator;
        final String variableName;
        final CompoundVariable left;
        final CompoundVariable right;
        final ResolvedCondition resolved;

        PreparedCondition(IfControllerCondition source) {
            rawLeft = raw(source.getProperty(IfControllerCondition.OPERAND1));
            rawRight = raw(source.getProperty(IfControllerCondition.OPERAND2));
            operator = source.getOperator();
            resolved = new ResolvedCondition(operator);
            if (IfController.Operator.EXISTS.getId().equals(operator)
                    || IfController.Operator.NOT_EXISTS.getId().equals(operator)) {
                String name = rawLeft.trim();
                variableName = name.startsWith("${") && name.endsWith("}") && name.length() > 3
                        ? name.substring(2, name.length() - 1) : name;
                left = null;
                right = null;
            } else {
                variableName = null;
                left = compile(source.getProperty(IfControllerCondition.OPERAND1));
                right = compile(source.getProperty(IfControllerCondition.OPERAND2));
                switch (IfController.Operator.fromId(operator)) {
                    case GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL -> {
                        validateLiteralNumber(left);
                        validateLiteralNumber(right);
                    }
                    default -> {
                        // Other operators do not require numeric operands.
                    }
                }
                Set<String> rightDependencies = right.getVariableDependencies();
                if ((IfController.Operator.MATCHES_REGEX.getId().equals(operator)
                        || IfController.Operator.NOT_MATCHES_REGEX.getId().equals(operator))
                        && rightDependencies != null && rightDependencies.isEmpty()) {
                    // Validate and cache literal patterns once, even in short-circuited rows.
                    resolved.right = right.execute();
                    resolved.matchesRegex("");
                }
            }
        }
    }

    private static void validateLiteralNumber(CompoundVariable operand) {
        Set<String> dependencies = operand.getVariableDependencies();
        if (dependencies != null && dependencies.isEmpty()) {
            Double.parseDouble(operand.execute().trim());
        }
    }

    private static final class ResolvedCondition extends IfControllerCondition {
        private static final long serialVersionUID = 1L;
        private String left;
        private String right;

        ResolvedCondition(String operator) {
            setOperator(operator);
        }

        @Override
        public String getOperand1() {
            return left;
        }

        @Override
        public String getOperand2() {
            return right;
        }
    }

    @Override
    public synchronized boolean interrupt() {
        if (waitingThread == null) {
            return false;
        }
        waitingThread.interrupt();
        return true;
    }
}
