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

import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.testelement.schema.PropertiesAccessor;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// @see TestWhileController for unit tests

public class WhileController extends GenericController implements Serializable, IteratingController {
    private static final Logger log = LoggerFactory.getLogger(WhileController.class);

    private static final long serialVersionUID = 233L;

    public static final String MATCH_ALL = "all"; //$NON-NLS-1$

    public static final String MATCH_ANY = "any"; //$NON-NLS-1$

    private boolean breakLoop;

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

    public WhileController() {
        super();
    }

    @Override
    public WhileControllerSchema getSchema() {
        return WhileControllerSchema.INSTANCE;
    }

    @Override
    public PropertiesAccessor<? extends WhileController, ? extends WhileControllerSchema> getProps() {
        return new PropertiesAccessor<>(this, getSchema());
    }

    /**
     * Evaluate the condition, which can be:
     * blank or LAST = was the last sampler OK?
     * otherwise, evaluate the condition to see if it is not "false"
     * If blank, only evaluate at the end of the loop
     *
     * Must only be called at start and end of loop
     *
     * @param loopEnd - are we at loop end?
     * @return true means end of loop has been reached
     */
    private boolean endOfLoop(boolean loopEnd) {
        if(breakLoop) {
            return true;
        }
        String cnd = getCondition().trim();
        log.debug("Condition string: '{}'", cnd);
        boolean res;
        if (!cnd.isEmpty()) {
            res = evaluateLegacyCondition(cnd);
        } else {
            List<WhileControllerCondition> conditions = getActiveConditions();
            res = conditions.isEmpty() ? evaluateBlankCondition(loopEnd) : !evaluateStructuredConditions(conditions);
        }
        log.debug("Condition value: '{}'", res);
        return res;
    }

    private static boolean evaluateBlankCondition(boolean loopEnd) {
        if (loopEnd) {
            JMeterVariables threadVars = JMeterContextService.getContext().getVariables();
            return "false".equalsIgnoreCase(threadVars.get(JMeterThread.LAST_SAMPLE_OK));// $NON-NLS-1$
        }
        return false;
    }

    private static boolean evaluateLegacyCondition(String condition) {
        if ("LAST".equalsIgnoreCase(condition)) {// $NON-NLS-1$
            JMeterVariables threadVars = JMeterContextService.getContext().getVariables();
            return "false".equalsIgnoreCase(threadVars.get(JMeterThread.LAST_SAMPLE_OK));// $NON-NLS-1$
        }
        return "false".equalsIgnoreCase(condition);// $NON-NLS-1$
    }

    private boolean evaluateStructuredConditions(List<WhileControllerCondition> conditions) {
        boolean matchAny = MATCH_ANY.equals(getConditionMatch());
        for (WhileControllerCondition condition : conditions) {
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

    private boolean evaluateStructuredCondition(WhileControllerCondition condition) {
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

    /**
     * Only called at End of Loop
     * <p>
     * {@inheritDoc}
     */
    @Override
    protected Sampler nextIsNull() throws NextIsNullException {
        reInitialize();
        if (endOfLoop(true)){
            resetBreakLoop();
            resetLoopCount();
            return null;
        }
        return next();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void triggerEndOfLoop() {
        super.triggerEndOfLoop();
        endOfLoop(true);
        resetLoopCount();
    }

    /**
     * This skips controller entirely if the condition is false on first entry.
     * <p>
     * {@inheritDoc}
     */
    @Override
    public Sampler next(){
        updateIterationIndex(getName(), getIterCount());
        try {
            if (isFirst() && endOfLoop(false)) {
                resetBreakLoop();
                resetLoopCount();
                return null;
            }
            return super.next();
        } finally {
            updateIterationIndex(getName(), getIterCount());
        }
    }

    protected void resetLoopCount() {
        resetIterCount();
    }

    /**
     * @param string
     *            the condition to save
     */
    public void setCondition(String string) {
        log.debug("setCondition({})", string);
        set(getSchema().getCondition(), string);
    }

    /**
     * @return the condition
     */
    public String getCondition() {
        JMeterProperty prop=getProperty(getSchema().getCondition().getName());
        prop.recoverRunningVersion(this);
        return prop.getStringValue();
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

    public void setConditions(List<WhileControllerCondition> conditions) {
        set(getSchema().getConditions(), conditions);
    }

    public void addCondition(WhileControllerCondition condition) {
        TestElementProperty conditionProperty = new TestElementProperty(condition.getName(), condition);
        if (isRunningVersion()) {
            setTemporary(conditionProperty);
        }
        getConditions().addItem(conditionProperty);
    }

    private List<WhileControllerCondition> getActiveConditions() {
        List<WhileControllerCondition> result = new ArrayList<>();
        CollectionProperty conditions = getSchema().getConditions().getOrNull(this);
        if (conditions == null) {
            return result;
        }
        PropertyIterator iterator = conditions.iterator();
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            Object value = property.getObjectValue();
            if (value instanceof WhileControllerCondition condition && !condition.isBlank()) {
                result.add(condition);
            }
        }
        return result;
    }

    @Override
    public void startNextLoop() {
        reInitialize();
    }

    private void resetBreakLoop() {
        if(breakLoop) {
            breakLoop = false;
        }
    }

    @Override
    public void breakLoop() {
        breakLoop = true;
        setFirst(true);
        resetCurrent();
        resetLoopCount();
        recoverRunningVersion();
    }

    @Override
    public void iterationStart(LoopIterationEvent iterEvent) {
        reInitialize();
        resetLoopCount();
    }
}
