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

package org.apache.jmeter.functions;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.util.JMeterUtils;

/** Common parameter validation and result storage for native utility functions. */
abstract class AbstractNativeFunction extends AbstractFunction {
    private final String key;
    private final int minimum;
    private final int maximum;
    private final int resultIndex;
    private final List<String> descriptions;
    private CompoundVariable[] parameters = new CompoundVariable[0];

    protected AbstractNativeFunction(String key, int minimum, int maximum, int resultIndex,
            String... descriptionKeys) {
        this.key = key;
        this.minimum = minimum;
        this.maximum = maximum;
        this.resultIndex = resultIndex;
        descriptions = Arrays.stream(descriptionKeys).map(JMeterUtils::getResString).toList();
    }

    @Override
    public final String getReferenceKey() {
        return key;
    }

    @Override
    public final List<String> getArgumentDesc() {
        return descriptions;
    }

    @Override
    public final void setParameters(Collection<CompoundVariable> values) throws InvalidVariableException {
        checkParameterCount(values, minimum, maximum);
        parameters = values.toArray(new CompoundVariable[0]);
    }

    protected final int parameterCount() {
        return parameters.length;
    }

    protected final String argument(int index) {
        return parameters[index].execute();
    }

    @Override
    public final String execute(SampleResult previousResult, Sampler currentSampler) throws InvalidVariableException {
        String result;
        try {
            result = evaluate();
        } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
            throw new InvalidVariableException(key + ": invalid argument", ex);
        }
        // -1 means no result variable; -2 means the final argument is always its name.
        int index = resultIndex == -2 ? parameters.length - 1 : resultIndex;
        if (index >= 0) {
            addVariableValue(result, parameters, index);
        }
        return result;
    }

    protected abstract String evaluate() throws InvalidVariableException;
}
