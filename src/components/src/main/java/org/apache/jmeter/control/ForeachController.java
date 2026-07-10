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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ForeachController that iterates over a list of variables named XXXX_NN stored in {@link JMeterVariables}
 * where NN is a number starting from 1 to number of occurrences.
 * This list of variable is usually set by PostProcessor (Regexp PostProcessor or {@link org.apache.jmeter.extractor.HtmlExtractor})
 * Iteration can take the full list or only a subset (configured through indexes)
 *
 */
@GUIMenuSortOrder(5)
public class ForeachController extends GenericController implements Serializable, IteratingController {

    private static final Logger log = LoggerFactory.getLogger(ForeachController.class);

    private static final long serialVersionUID = 241L;

    private static final String INPUTVAL = "ForeachController.inputVal";// $NON-NLS-1$

    private static final String START_INDEX = "ForeachController.startIndex";// $NON-NLS-1$

    private static final String END_INDEX = "ForeachController.endIndex";// $NON-NLS-1$

    private static final String RETURNVAL = "ForeachController.returnVal";// $NON-NLS-1$

    private static final String USE_SEPARATOR = "ForeachController.useSeparator";// $NON-NLS-1$

    private static final String PARALLEL = "ForeachController.parallel";// $NON-NLS-1$

    private static final String MAX_PARALLEL = "ForeachController.maxParallel";// $NON-NLS-1$

    private static final int DEFAULT_MAX_PARALLEL = 6;

    private static final String INDEX_DEFAULT_VALUE = ""; // start/end index default value for string getters and setters

    private int loopCount = 0;

    private boolean breakLoop;

    private transient boolean parallelSamplerReturned;

    private static final String DEFAULT_SEPARATOR = "_";// $NON-NLS-1$

    public ForeachController() {
    }

    /**
     * @param startIndex Start index  of loop
     */
    public void setStartIndex(String startIndex) {
        setProperty(START_INDEX, startIndex, INDEX_DEFAULT_VALUE);
    }

    /**
     * @return start index of loop
     */
    private int getStartIndex() {
        // Although the default is not the same as for the string value, it is only used internally
        return getPropertyAsInt(START_INDEX, 0);
    }

    /**
     * @return start index of loop as String
     */
    public String getStartIndexAsString() {
        return getPropertyAsString(START_INDEX, INDEX_DEFAULT_VALUE);
    }

    /**
     * @param endIndex End index  of loop
     */
    public void setEndIndex(String endIndex) {
        setProperty(END_INDEX, endIndex, INDEX_DEFAULT_VALUE);
    }

    /**
     * @return end index of loop
     */
    private int getEndIndex() {
        // Although the default is not the same as for the string value, it is only used internally
        return getPropertyAsInt(END_INDEX, Integer.MAX_VALUE);
    }

    /**
     * @return end index of loop
     */
    public String getEndIndexAsString() {
        return getPropertyAsString(END_INDEX, INDEX_DEFAULT_VALUE);
    }

    public void setInputVal(String inputValue) {
        setProperty(new StringProperty(INPUTVAL, inputValue));
    }

    private String getInputVal() {
        getProperty(INPUTVAL).recoverRunningVersion(null);
        return getInputValString();
    }

    public String getInputValString() {
        return getPropertyAsString(INPUTVAL);
    }

    public void setReturnVal(String inputValue) {
        setProperty(new StringProperty(RETURNVAL, inputValue));
    }

    private String getReturnVal() {
        getProperty(RETURNVAL).recoverRunningVersion(null);
        return getReturnValString();
    }

    public String getReturnValString() {
        return getPropertyAsString(RETURNVAL);
    }

    private String getSeparator() {
        return getUseSeparator() ? DEFAULT_SEPARATOR : "";// $NON-NLS-1$
    }

    public void setUseSeparator(boolean b) {
        setProperty(new BooleanProperty(USE_SEPARATOR, b));
    }

    public boolean getUseSeparator() {
        return getPropertyAsBoolean(USE_SEPARATOR, true);
    }

    public void setParallel(boolean parallel) {
        setProperty(new BooleanProperty(PARALLEL, parallel));
    }

    public boolean isParallel() {
        return getPropertyAsBoolean(PARALLEL, false);
    }

    public void setMaxParallel(int maxParallel) {
        setProperty(new IntegerProperty(MAX_PARALLEL, Math.max(1, maxParallel)));
    }

    public void setMaxParallel(String maxParallel) {
        setProperty(new StringProperty(MAX_PARALLEL, maxParallel));
    }

    public int getMaxParallel() {
        try {
            return Math.max(1, getPropertyAsInt(MAX_PARALLEL, DEFAULT_MAX_PARALLEL));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_PARALLEL;
        }
    }

    public String getMaxParallelString() {
        return getPropertyAsString(MAX_PARALLEL, Integer.toString(DEFAULT_MAX_PARALLEL));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDone() {
        if (loopCount >= getEndIndex()) {
            return true;
        }
        JMeterContext context = getThreadContext();
        StringBuilder builder = new StringBuilder(
                getInputVal().length()+getSeparator().length()+3);
        String inputVariable =
                builder.append(getInputVal())
                .append(getSeparator())
                .append(Integer.toString(loopCount+1)).toString();
        final JMeterVariables variables = context.getVariables();
        final Object currentVariable = variables.getObject(inputVariable);
        if (currentVariable != null) {
            variables.putObject(getReturnVal(), currentVariable);
            if (log.isDebugEnabled()) {
                log.debug("{} : Found in vars:{}, isDone:{}",
                        getName(), inputVariable, false);

            }
            return false;
        }
        return super.isDone();
    }

    /**
     * Tests that JMeterVariables contain inputVal_<count>, if not we can stop iterating
     */
    private boolean endOfArguments() {
        JMeterContext context = getThreadContext();
        String inputVariable = getInputVal() + getSeparator() + (loopCount + 1);
        if (context.getVariables().getObject(inputVariable) != null) {
            if(log.isDebugEnabled()) {
                log.debug("{} : Found in vars:{}, not end of Arguments",
                        getName(), inputVariable);
            }
            return false;
        }
        if(log.isDebugEnabled()) {
            log.debug("{} : Did not find in vars:{}, End of Arguments reached",
                    getName(), inputVariable);
        }
        return true;
    }

    // Prevent entry if nothing to do
    @Override
    public Sampler next() {
        if (isParallel()) {
            return nextParallel();
        }
        updateIterationIndex(getName(), loopCount);
        try {
            if (breakLoop || emptyList()) {
                resetBreakLoop();
                reInitialize();
                resetLoopCount();
                return null;
            }
            return super.next();
        } finally {
            updateIterationIndex(getName(), loopCount);
        }
    }

    private Sampler nextParallel() {
        updateIterationIndex(getName(), loopCount);
        try {
            if (breakLoop) {
                resetBreakLoop();
                reInitialize();
                resetLoopCount();
                return null;
            }
            if (parallelSamplerReturned) {
                parallelSamplerReturned = false;
                reInitialize();
                resetLoopCount();
                return null;
            }
            ParallelControllerSampler parallelSampler = createParallelSampler();
            if (parallelSampler.getBranchCount() == 0) {
                reInitialize();
                resetLoopCount();
                return null;
            }
            parallelSamplerReturned = true;
            return parallelSampler;
        } finally {
            updateIterationIndex(getName(), loopCount);
        }
    }

    private ParallelControllerSampler createParallelSampler() {
        JMeterVariables variables = getThreadContext().getVariables();
        String input = getInputVal();
        String separator = getSeparator();
        String output = getReturnVal();
        int startIndex = getStartIndex();
        int branchCount = countInputItems(variables, input, separator, startIndex, getEndIndex());
        return new ParallelControllerSampler(
                this,
                getName(),
                getMaxParallel(),
                branchCount,
                branchIndex -> createParallelBranch(
                        output,
                        variables.getObject(input + separator + (startIndex + branchIndex + 1))));
    }

    private static int countInputItems(JMeterVariables variables, String input, String separator,
            int startIndex, int endIndex) {
        int itemCount = 0;
        for (int index = startIndex + 1; index <= endIndex; index++) {
            if (variables.getObject(input + separator + index) == null) {
                break;
            }
            itemCount++;
        }
        return itemCount;
    }

    private ParallelControllerSampler.ParallelBranch createParallelBranch(String output, Object value) {
        IdentityHashMap<TransactionController, TransactionController> sourceTransactionControllers =
                new IdentityHashMap<>();
        ForEachParallelBranch branch = new ForEachParallelBranch(output, value);
        branch.setName(getName());
        for (TestElement child : getSubControllers()) {
            ParallelController.addParallelChild(branch, child, sourceTransactionControllers);
        }
        branch.initialize();
        return new ParallelControllerSampler.ParallelBranch(branch, sourceTransactionControllers);
    }

    /**
     * Check if there are any matching entries
     *
     * @return whether any entries in the list
     */
    private boolean emptyList() {
        JMeterContext context = getThreadContext();

        StringBuilder builder = new StringBuilder(
                getInputVal().length()+getSeparator().length()+3);
        String inputVariable =
                builder.append(getInputVal())
                .append(getSeparator())
                .append(Integer.toString(loopCount+1)).toString();
        if (context.getVariables().getObject(inputVariable) != null) {
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("{} No entries found - null first entry: {}",
                    getName(), inputVariable);
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Sampler nextIsNull() throws NextIsNullException {
        reInitialize();
        // Conditions to reset the loop count
        if (breakLoop
                || endOfArguments() // no more variables to iterate
                || loopCount >= getEndIndex() // we reached end index
                ) {
            resetBreakLoop();
            resetLoopCount();
            return null;
        }
        return next();
    }

    protected void incrementLoopCount() {
        loopCount++;
    }

    protected void resetLoopCount() {
        loopCount = getStartIndex();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int getIterCount() {
        return loopCount + 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void reInitialize() {
        setFirst(true);
        resetCurrent();
        incrementLoopCount();
        recoverRunningVersion();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void triggerEndOfLoop() {
        super.triggerEndOfLoop();
        resetLoopCount();
    }

    /**
     * Reset loopCount to Start index
     * @see org.apache.jmeter.control.GenericController#initialize()
     */
    @Override
    public void initialize() {
        super.initialize();
        loopCount = getStartIndex();
        parallelSamplerReturned = false;
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
        parallelSamplerReturned = false;
        setFirst(true);
        resetCurrent();
        resetLoopCount();
        recoverRunningVersion();
    }

    @Override
    public void iterationStart(LoopIterationEvent iterEvent) {
        reInitialize();
        resetLoopCount();
        parallelSamplerReturned = false;
    }

    private static final class ForEachParallelBranch extends GenericController implements ParallelContextModifier {
        private static final long serialVersionUID = 1L;

        private final String output;
        private final Object value;

        private ForEachParallelBranch(String output, Object value) {
            this.output = output;
            this.value = value;
        }

        @Override
        public void prepareParallelContext(JMeterContext workerContext) {
            workerContext.setVariables(new ForEachParallelVariables(workerContext.getVariables(), output, value));
        }
    }

    private static final class ForEachParallelVariables extends JMeterVariables {
        private final JMeterVariables parent;
        private final String output;
        private Object value;

        private ForEachParallelVariables(JMeterVariables parent, String output, Object value) {
            this.parent = parent;
            this.output = output;
            this.value = value;
        }

        @Override
        public void put(String key, String value) {
            if (output.equals(key)) {
                this.value = value;
            } else {
                parent.put(key, value);
            }
        }

        @Override
        public void putObject(String key, Object value) {
            if (output.equals(key)) {
                this.value = value;
            } else {
                parent.putObject(key, value);
            }
        }

        @Override
        public void putAll(Map<String, ?> vars) {
            for (Map.Entry<String, ?> entry : vars.entrySet()) {
                putObject(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public void putAll(JMeterVariables vars) {
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                putObject(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public String get(String key) {
            if (output.equals(key)) {
                return value == null ? null : value.toString();
            }
            return parent.get(key);
        }

        @Override
        public Object getObject(String key) {
            if (output.equals(key)) {
                return value;
            }
            return parent.getObject(key);
        }

        @Override
        public Object remove(String key) {
            if (output.equals(key)) {
                Object previous = value;
                value = null;
                return previous;
            }
            return parent.remove(key);
        }

        @Override
        public void clear() {
            value = null;
            parent.clear();
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : parent.entrySet()) {
                merged.put(entry.getKey(), entry.getValue());
            }
            merged.put(output, value);
            return Collections.unmodifiableMap(merged).entrySet();
        }

        @Override
        public int getIteration() {
            return parent.getIteration();
        }

        @Override
        public void incIteration() {
            parent.incIteration();
        }

        @Override
        public boolean isSameUserOnNextIteration() {
            return parent.isSameUserOnNextIteration();
        }
    }
}
