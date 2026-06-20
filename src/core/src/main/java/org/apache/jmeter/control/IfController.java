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

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleScriptContext;

import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.testelement.schema.PropertiesAccessor;
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

    private static final ThreadLocal<ScriptEngine> GROOVY_ENGINE = ThreadLocal
            .withInitial(() -> getInstance().getEngineByName(GROOVY_ENGINE_NAME));

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
            result = isUseExpression() ?
                    evaluateExpression(getCondition())
                    :
                    evaluateCondition(getCondition());
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

    @Override
    public void threadStarted() {}

    @Override
    public void threadFinished() {
       GROOVY_ENGINE.remove();
    }
}
