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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleScriptContext;

import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.testelement.schema.PropertiesAccessor;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 *
 * This is a Conditional Controller; it will execute the set of statements
 * (samplers/controllers, etc) while the 'condition' is true.
 * <p>
 * In a programming world - this is equivalent of :
 * <pre>
 * if (condition) {
 *          statements ....
 *          }
 * </pre>
 * In JMeter you may have :
 * <pre>
 * Thread-Group (set to loop a number of times or indefinitely,
 *    ... Samplers ... (e.g. Counter )
 *    ... Other Controllers ....
 *    ... IfController ( condition set to something like - ${counter} &lt; 10)
 *       ... statements to perform if condition is true
 *       ...
 *    ... Other Controllers /Samplers }
 * </pre>
 */

// for unit test code @see TestIfController

public class IfController extends GenericController implements Serializable, ThreadListener {

    private static final Logger log = LoggerFactory.getLogger(IfController.class);

    private static final long serialVersionUID = 242L;

    private static final String GROOVY_ENGINE_NAME = "groovy"; //$NON-NLS-1$

    public static final String MATCH_ALL = "all"; //$NON-NLS-1$

    public static final String MATCH_ANY = "any"; //$NON-NLS-1$

    private static final ThreadLocal<ScriptEngine> GROOVY_ENGINE = ThreadLocal
            .withInitial(() -> getInstance().getEngineByName(GROOVY_ENGINE_NAME));

    public enum Operator {
        EQUALS("equals"),
        NOT_EQUALS("not_equals"),
        CONTAINS("contains"),
        NOT_CONTAINS("not_contains"),
        STARTS_WITH("starts_with"),
        NOT_STARTS_WITH("not_starts_with"),
        ENDS_WITH("ends_with"),
        NOT_ENDS_WITH("not_ends_with"),
        MATCHES_REGEX("matches_regex"),
        NOT_MATCHES_REGEX("not_matches_regex"),
        GREATER_THAN("greater_than"),
        GREATER_THAN_OR_EQUAL("greater_than_or_equal"),
        LESS_THAN("less_than"),
        LESS_THAN_OR_EQUAL("less_than_or_equal"),
        EXISTS("exists"),
        NOT_EXISTS("not_exists");

        private final String id;

        Operator(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static Operator fromId(String id) {
            for (Operator operator : values()) {
                if (operator.id.equals(id)) {
                    return operator;
                }
            }
            return EQUALS;
        }
    }

    /**
     * Initialization On Demand Holder pattern
     */
    private static class LazyHolder {
        private static final ScriptEngineManager INSTANCE = new ScriptEngineManager();
    }

    /**
     * @return ScriptEngineManager singleton
     */
    private static ScriptEngineManager getInstance() {
            return LazyHolder.INSTANCE;
    }
    /**
     * constructor
     */
    public IfController() {
        super();
    }

    /**
     * constructor
     * @param condition The condition for this controller
     */
    public IfController(String condition) {
        super();
        this.setCondition(condition);
    }

    @Override
    public IfControllerSchema getSchema() {
        return IfControllerSchema.INSTANCE;
    }

    @Override
    public PropertiesAccessor<? extends IfController, ? extends IfControllerSchema> getProps() {
        return new PropertiesAccessor<>(this, getSchema());
    }

    /**
     * Condition Accessor - this is gonna be like <code>${count} &lt; 10</code>
     * @param condition The condition for this controller
     */
    public void setCondition(String condition) {
        set(getSchema().getCondition(), condition);
    }

    /**
     * Condition Accessor - this is gonna be like <code>${count} &lt; 10</code>
     * @return the condition associated with this controller
     */
    public String getCondition() {
        return get(getSchema().getCondition()).trim();
    }

    /**
     * evaluate the condition clause log error if bad condition
     */
    private boolean evaluateCondition(String cond) {
        log.debug("    getCondition() : [{}]", cond);
        try {
            ScriptEngine engine = GROOVY_ENGINE.get();
            if (engine == null) {
                throw new IllegalStateException("Groovy JSR223 engine is not available");
            }
            ScriptContext newContext = new SimpleScriptContext();
            newContext.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
            Object result = engine.eval(cond, newContext);
            return computeResultFromString(cond, String.valueOf(result));
        } catch (Exception ex) {
            log.error("{}: error while processing [{}]", getName(), cond, ex);
        }
        return false;
    }

    /**
     * @param condition
     * @param resultStr
     * @return boolean
     * @throws Exception
     */
    private static boolean computeResultFromString(
            String condition, String resultStr) throws Exception {
        boolean result = switch (resultStr) {
            case "false" -> false;
            case "true" -> true;
            default -> throw new Exception(" BAD CONDITION :: " + condition + " :: expected true or false");
        };
        log.debug("    >> evaluate Condition -  [{}] results is  [{}]", condition, result);
        return result;
    }


    private static boolean evaluateExpression(String cond) {
        log.debug("    >> evaluate Expression [{}] equals (ignoring case) 'true'", cond);
        return cond.equalsIgnoreCase("true"); // $NON-NLS-1$
    }

    private boolean evaluateConditionSettings() {
        if (!getCondition().isBlank()) {
            return evaluateLegacyCondition();
        }
        List<IfControllerCondition> conditions = getActiveConditions();
        if (conditions.isEmpty()) {
            return false;
        }
        boolean matchAny = MATCH_ANY.equals(getConditionMatch());
        for (IfControllerCondition condition : conditions) {
            boolean conditionResult = evaluateStructuredCondition(condition);
            if (matchAny && conditionResult) {
                return true;
            }
            if (!matchAny && !conditionResult) {
                return false;
            }
        }
        return !matchAny;
    }

    private boolean evaluateLegacyCondition() {
        return isUseExpression() ?
                evaluateExpression(getCondition())
                :
                evaluateCondition(getCondition());
    }

    private boolean evaluateStructuredCondition(IfControllerCondition condition) {
        String operand1 = condition.getOperand1();
        String operand2 = condition.getOperand2();
        Operator operator = Operator.fromId(condition.getOperator());
        try {
            return switch (operator) {
                case EQUALS -> operand1.equals(operand2);
                case NOT_EQUALS -> !operand1.equals(operand2);
                case CONTAINS -> operand1.contains(operand2);
                case NOT_CONTAINS -> !operand1.contains(operand2);
                case STARTS_WITH -> operand1.startsWith(operand2);
                case NOT_STARTS_WITH -> !operand1.startsWith(operand2);
                case ENDS_WITH -> operand1.endsWith(operand2);
                case NOT_ENDS_WITH -> !operand1.endsWith(operand2);
                case MATCHES_REGEX -> condition.matchesRegex(operand1);
                case NOT_MATCHES_REGEX -> !condition.matchesRegex(operand1);
                case GREATER_THAN -> compareNumbers(operand1, operand2) > 0;
                case GREATER_THAN_OR_EQUAL -> compareNumbers(operand1, operand2) >= 0;
                case LESS_THAN -> compareNumbers(operand1, operand2) < 0;
                case LESS_THAN_OR_EQUAL -> compareNumbers(operand1, operand2) <= 0;
                case EXISTS -> variableExists(condition.getRawOperand1());
                case NOT_EXISTS -> !variableExists(condition.getRawOperand1());
            };
        } catch (NumberFormatException | PatternSyntaxException ex) {
            log.error("{}: error while processing structured condition [{} {} {}]",
                    getName(), operand1, operator.getId(), operand2, ex);
            return false;
        }
    }

    private static int compareNumbers(String operand1, String operand2) {
        return Double.compare(Double.parseDouble(operand1.trim()), Double.parseDouble(operand2.trim()));
    }

    private static boolean variableExists(String operand) {
        String variableName = variableNameFromOperand(operand);
        JMeterVariables variables = JMeterContextService.getContext().getVariables();
        return variables != null && variables.getObject(variableName) != null;
    }

    private static String variableNameFromOperand(String operand) {
        String trimmed = operand.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}") && trimmed.length() > 3) { // $NON-NLS-1$ // $NON-NLS-2$
            return trimmed.substring(2, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public boolean isDone() {
        // bug 26672 : the isDone result should always be false and not based on the expression evaluation
        // if an IfController ever gets evaluated to false it gets removed from the test tree.
        // The problem is that the condition might get evaluated to true the next iteration,
        // which we don't get the opportunity for
        return false;
    }

    /**
     * @see org.apache.jmeter.control.Controller#next()
     */
    @Override
    public Sampler next() {
        // We should only evaluate the condition if it is the first
        // time ( first "iteration" ) we are called.
        // For subsequent calls, we are inside the IfControllerGroup,
        // so then we just pass the control to the next item inside the if control
        boolean result = true;
        if(isEvaluateAll() || isFirst()) {
            result = evaluateConditionSettings();
        }

        if (result) {
            return super.next();
        }
        // If-test is false, need to re-initialize indexes
        try {
            initializeSubControllers();
            return nextIsNull();
        } catch (NextIsNullException e1) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void triggerEndOfLoop() {
        super.initializeSubControllers();
        super.triggerEndOfLoop();
    }

    public boolean isEvaluateAll() {
        return get(getSchema().getEvaluateAll());
    }

    public void setEvaluateAll(boolean b) {
        set(getSchema().getEvaluateAll(), b);
    }

    public boolean isUseExpression() {
        return get(getSchema().getUseExpression());
    }

    public void setUseExpression(boolean selected) {
        set(getSchema().getUseExpression(), selected);
    }

    public String getConditionMatch() {
        return get(getSchema().getConditionMatch());
    }

    public void setConditionMatch(String conditionMatch) {
        set(getSchema().getConditionMatch(), MATCH_ANY.equals(conditionMatch) ? MATCH_ANY : MATCH_ALL);
    }

    public CollectionProperty getConditions() {
        return getSchema().getConditions().getOrCreate(this, ArrayList::new);
    }

    public void setConditions(List<IfControllerCondition> conditions) {
        set(getSchema().getConditions(), conditions);
    }

    public void addCondition(IfControllerCondition condition) {
        TestElementProperty conditionProperty = new TestElementProperty(condition.getName(), condition);
        if (isRunningVersion()) {
            setTemporary(conditionProperty);
        }
        getConditions().addItem(conditionProperty);
    }

    private List<IfControllerCondition> getActiveConditions() {
        List<IfControllerCondition> result = new ArrayList<>();
        CollectionProperty conditions = getSchema().getConditions().getOrNull(this);
        if (conditions == null) {
            return result;
        }
        PropertyIterator iterator = conditions.iterator();
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            Object value = property.getObjectValue();
            if (value instanceof IfControllerCondition condition && !condition.isBlank()) {
                result.add(condition);
            }
        }
        return result;
    }

    @Override
    public void threadStarted() {}

    @Override
    public void threadFinished() {
       GROOVY_ENGINE.remove();
    }
}
