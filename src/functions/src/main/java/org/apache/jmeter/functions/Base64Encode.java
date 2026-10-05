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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.auto.service.AutoService;

/** Implements {@code __base64Encode}. */
@AutoService(Function.class)
public class Base64Encode extends AbstractNativeFunction {
    public Base64Encode() {
        super("__base64Encode", 1, 2, 1, "native_function_text", "function_name_paropt");
    }

    @Override
    protected String evaluate() {
        return Base64.getEncoder().encodeToString(argument(0).getBytes(StandardCharsets.UTF_8));
    }
}
