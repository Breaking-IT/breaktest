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

import java.util.concurrent.ThreadLocalRandom;

import com.google.auto.service.AutoService;

/** Implements {@code __chooseRandom}. */
@AutoService(Function.class)
public class ChooseRandom extends AbstractNativeFunction {
    public ChooseRandom() {
        super("__chooseRandom", 2, Integer.MAX_VALUE, -2, "native_function_choices", "native_function_result");
    }

    @Override
    protected String evaluate() {
        return argument(ThreadLocalRandom.current().nextInt(parameterCount() - 1));
    }
}
