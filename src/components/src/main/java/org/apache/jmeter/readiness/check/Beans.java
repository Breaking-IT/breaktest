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

package org.apache.jmeter.readiness.check;

import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testelement.TestElement;

/**
 * Test beans keep their configuration in properties and only copy it to their fields when prepared to run. The
 * checks read prepared copies, never preparing the edited element itself.
 */
public final class Beans {
    private Beans() {
    }

    /**
     * @param element an element
     * @return a prepared copy for a test bean, the element itself otherwise
     */
    @SuppressWarnings("unchecked")
    public static <T extends TestElement> T prepared(T element) {
        if (!(element instanceof TestBean)) {
            return element;
        }
        T copy = (T) element.clone();
        try {
            TestBeanHelper.prepare(copy);
        } catch (RuntimeException e) {
            return element;
        }
        return copy;
    }
}
