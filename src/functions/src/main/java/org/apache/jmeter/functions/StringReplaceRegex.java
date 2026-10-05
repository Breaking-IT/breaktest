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

/** Implements {@code __strReplaceRegex}. */
@AutoService(Function.class)
public class StringReplaceRegex extends AbstractNativeFunction {
    public StringReplaceRegex() {
        super("__strReplaceRegex", 3, 4, 3, "native_function_text", "native_function_regex", "native_function_replacement", "function_name_paropt");
    }

    @Override
    protected String evaluate() {
        return argument(0).replaceAll(argument(1), argument(2));
    }
}
