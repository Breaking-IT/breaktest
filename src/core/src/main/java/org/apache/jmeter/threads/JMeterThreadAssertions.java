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

package org.apache.jmeter.threads;

import java.util.List;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testelement.AbstractScopedAssertion;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.util.JMeterError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JMeterThreadAssertions {
    private static final Logger log = LoggerFactory.getLogger(JMeterThreadAssertions.class);

    private JMeterThreadAssertions() {
    }

    static void check(List<? extends Assertion> assertions, SampleResult parent, JMeterContext threadContext) {
        for (Assertion assertion : assertions) {
            TestBeanHelper.prepare((TestElement) assertion);
            if (assertion instanceof AbstractScopedAssertion scopedAssertion) {
                String scope = scopedAssertion.fetchScope();
                if (scopedAssertion.isScopeParent(scope)
                        || scopedAssertion.isScopeAll(scope)
                        || scopedAssertion.isScopeVariable(scope)) {
                    process(parent, assertion);
                }
                if (scopedAssertion.isScopeChildren(scope)
                        || scopedAssertion.isScopeAll(scope)) {
                    recurse(parent, assertion, 3);
                }
            } else {
                process(parent, assertion);
            }
        }
        JMeterVariables variables = threadContext.getVariables();
        variables.putObject(JMeterThread.LAST_SAMPLE_OK, parent.isSuccessful());
        variables.put(JMeterThread.LAST_SAMPLE_OK, Boolean.toString(parent.isSuccessful()));
    }

    private static void recurse(SampleResult parent, Assertion assertion, int level) {
        if (level < 0) {
            return;
        }
        SampleResult[] children = parent.getSubResults();
        boolean childError = false;
        for (SampleResult childSampleResult : children) {
            process(childSampleResult, assertion);
            recurse(childSampleResult, assertion, level - 1);
            if (!childSampleResult.isSuccessful()) {
                childError = true;
            }
        }
        // If parent is OK, but child failed, add a message and flag the parent as failed
        if (childError && parent.isSuccessful()) {
            AssertionResult assertionResult = new AssertionResult(((AbstractTestElement) assertion).getName());
            assertionResult.setResultForFailure("One or more sub-samples failed");
            parent.addAssertionResult(assertionResult);
            parent.setSuccessful(false);
        }
    }

    private static void process(SampleResult result, Assertion assertion) {
        AssertionResult assertionResult;
        try {
            assertionResult = assertion.getResult(result);
        } catch (AssertionError e) {
            log.debug("Error processing Assertion.", e);
            assertionResult = new AssertionResult("Assertion failed! See log file (debug level, only).");
            assertionResult.setFailure(true);
            assertionResult.setFailureMessage(e.toString());
        } catch (JMeterError e) {
            log.error("Error processing Assertion.", e);
            assertionResult = new AssertionResult("Assertion failed! See log file.");
            assertionResult.setError(true);
            assertionResult.setFailureMessage(e.toString());
        } catch (Exception e) {
            log.error("Exception processing Assertion.", e);
            assertionResult = new AssertionResult("Assertion failed! See log file.");
            assertionResult.setError(true);
            assertionResult.setFailureMessage(e.toString());
        }
        result.setSuccessful(result.isSuccessful() && !(assertionResult.isError() || assertionResult.isFailure()));
        result.addAssertionResult(assertionResult);
    }
}
