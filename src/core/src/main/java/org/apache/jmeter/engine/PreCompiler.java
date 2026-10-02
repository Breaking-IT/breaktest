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

package org.apache.jmeter.engine;

import java.util.Map;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.functions.InvalidVariableException;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.HashTreeTraverser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to replace function and variable references in the test tree.
 *
 */
public class PreCompiler implements HashTreeTraverser {
    private static final Logger log = LoggerFactory.getLogger(PreCompiler.class);

    private final ValueReplacer replacer;

    public PreCompiler() {
        replacer = new ValueReplacer();
    }

    /** {@inheritDoc} */
    @Override
    public void addNode(Object node, HashTree subTree) {
        if(node instanceof TestElement testElement) {
            try {
                replacer.replaceValues(testElement);
            } catch (InvalidVariableException e) {
                log.error("invalid variables in node {}", testElement.getName(), e);
            }
        }

        if (node instanceof TestPlan testPlan) {
            JMeterVariables vars = createVars(testPlan);
            JMeterContextService.getContext().setVariables(vars);
        }

        if (node instanceof Arguments arguments) {
            Map<String, String> args = createArgumentsMap(arguments);
            JMeterContextService.getContext().getVariables().putAll(args);
        }
    }

    /**
     * Create Map of Arguments
     * @param arguments {@link Arguments}
     * @return {@link Map}
     */
    private Map<String, String> createArgumentsMap(Arguments arguments) {
        arguments.setRunningVersion(true);
        Map<String, String> args = arguments.getArgumentsAsMap();
        replacer.addVariables(args);
        return args;
    }

    /**
     * Create variables for testPlan
     * @param testPlan {@link JMeterVariables}
     * @return {@link JMeterVariables}
     */
    private JMeterVariables createVars(TestPlan testPlan) {
        testPlan.prepareForPreCompile(); //A hack to make user-defined variables in the testplan element more dynamic
        Map<String, String> args = testPlan.getUserDefinedVariables();
        replacer.setUserDefinedVariables(args);
        JMeterVariables vars = new JMeterVariables();
        vars.putAll(args);
        return vars;
    }

    /** {@inheritDoc} */
    @Override
    public void subtractNode() {
    }

    /** {@inheritDoc} */
    @Override
    public void processPath() {
    }

}
