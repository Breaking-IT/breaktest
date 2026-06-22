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
import java.util.regex.Pattern;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.property.FunctionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;

/**
 * A single structured condition row for {@link IfController}.
 */
public class IfControllerCondition extends AbstractTestElement implements Serializable {
    private static final long serialVersionUID = 242L;

    public static final String OPERAND1 = "IfController.condition.operand1"; // $NON-NLS-1$

    public static final String OPERATOR = "IfController.condition.operator"; // $NON-NLS-1$

    public static final String OPERAND2 = "IfController.condition.operand2"; // $NON-NLS-1$

    private transient String cachedRegex;

    private transient Pattern cachedPattern;

    public IfControllerCondition() {
        this("", IfController.Operator.EQUALS.getId(), ""); // $NON-NLS-1$ // $NON-NLS-2$
    }

    public IfControllerCondition(String operand1, String operator, String operand2) {
        setOperand1(operand1);
        setOperator(operator);
        setOperand2(operand2);
    }

    public String getOperand1() {
        return getPropertyAsString(OPERAND1);
    }

    public String getRawOperand1() {
        JMeterProperty property = getProperty(OPERAND1);
        if (property instanceof FunctionProperty && property.getObjectValue() instanceof CompoundVariable variable) {
            return variable.getRawParameters();
        }
        return property.getStringValue();
    }

    public void setOperand1(String operand1) {
        setProperty(OPERAND1, operand1);
    }

    public String getOperator() {
        return getPropertyAsString(OPERATOR, IfController.Operator.EQUALS.getId());
    }

    public void setOperator(String operator) {
        setProperty(OPERATOR, operator, IfController.Operator.EQUALS.getId());
    }

    public String getOperand2() {
        return getPropertyAsString(OPERAND2);
    }

    public void setOperand2(String operand2) {
        setProperty(OPERAND2, operand2);
    }

    public boolean matchesRegex(String value) {
        String regex = getOperand2();
        if (!regex.equals(cachedRegex)) {
            cachedRegex = regex;
            cachedPattern = Pattern.compile(regex);
        }
        return cachedPattern.matcher(value).matches();
    }

    public boolean isBlank() {
        return getOperand1().isBlank() && getOperand2().isBlank();
    }
}
