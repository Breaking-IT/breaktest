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


import com.google.auto.service.AutoService;

/** Implements {@code __env}. */
@AutoService(Function.class)
public class Environment extends AbstractNativeFunction {
    public Environment() {
        super("__env", 1, 3, 1, "native_function_environment", "function_name_paropt", "native_function_default");
    }

    @Override
    protected String evaluate() {
        String name = argument(0);
        String value = System.getenv(name);
        return value != null ? value : parameterCount() == 3 ? argument(2) : name;
    }
}
