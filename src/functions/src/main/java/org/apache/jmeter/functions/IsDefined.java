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

/** Implements {@code __isDefined}. */
@AutoService(Function.class)
public class IsDefined extends AbstractNativeFunction {
    public IsDefined() {
        super("__isDefined", 1, 1, -1, "evalvar_name_param");
    }

    @Override
    protected String evaluate() {
        return getVariables() != null && getVariables().getObject(argument(0)) != null ? "1" : "0";
    }
}
