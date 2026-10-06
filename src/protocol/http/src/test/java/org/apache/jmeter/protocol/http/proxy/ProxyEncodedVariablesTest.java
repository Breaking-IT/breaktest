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

package org.apache.jmeter.protocol.http.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.testelement.TestElement;
import org.junit.jupiter.api.Test;

class ProxyEncodedVariablesTest extends JMeterTestCase {
    @Test
    void replacesEncodedRawFieldsAndDecodedParameters() throws Exception {
        Arguments variables = new Arguments();
        variables.addArgument("token", "abc+123/=");
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setMethod("POST");
        sampler.setPath("/submit?token=abc%2B123%2F%3D");
        HTTPArgument argument = new HTTPArgument("token", "abc+123/=");
        argument.setAlwaysEncoded(true);
        sampler.getArguments().addArgument(argument);
        HeaderManager headers = new HeaderManager();
        headers.add(new Header("X-Token", "abc%2B123%2F%3D"));
        var method = ProxyControl.class.getDeclaredMethod("replaceValues", TestElement.class,
                TestElement[].class, Collection.class);
        method.setAccessible(true);
        method.invoke(null, sampler, new TestElement[] {headers}, List.of(variables));
        assertEquals("/submit?token=${__urlencode(${token})}", sampler.getPath());
        assertEquals("${token}", sampler.getArguments().getArgument(0).getValue());
        assertTrue(((HTTPArgument) sampler.getArguments().getArgument(0)).isAlwaysEncoded());
        assertEquals("${__urlencode(${token})}", headers.get(0).getValue());

        sampler = new HTTPSamplerProxy();
        sampler.setPostBodyRaw(true);
        sampler.addNonEncodedArgument("", "token=abc%2B123%2F%3D", "");
        method.invoke(null, sampler, new TestElement[0], List.of(variables));
        assertEquals("token=${__urlencode(${token})}", sampler.getArguments().getArgument(0).getValue());
    }
}
