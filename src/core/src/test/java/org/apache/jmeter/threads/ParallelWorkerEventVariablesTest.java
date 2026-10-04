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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ParallelWorkerEventVariablesTest {
    @Test
    void capturesStayLocalAndNestedWorkersKeepTheTriggeringMessage() {
        JMeterVariables parent = new JMeterVariables();
        parent.put("page", "main");
        var first = new ParallelWorkerVariables(parent);
        var other = new ParallelWorkerVariables(parent);
        first.setEventVariables(Map.of("page", "one", "message", "ready"));
        var nested = new ParallelWorkerVariables(first);
        first.put("page", "edited");
        first.put("ordinary", "shared");
        assertEquals("main", parent.get("page"));
        assertEquals("main", other.get("page"));
        assertEquals("shared", other.get("ordinary"));
        assertEquals("one", nested.get("page"));
        first.setEventVariables(Map.of("page", "two"));
        assertEquals("two", first.get("page"));
        assertEquals("one", nested.get("page"));
        assertNull(first.get("message"));
        first.remove("page");
        assertNull(first.get("page"));
        assertEquals("main", parent.get("page"));
        assertEquals("main", other.get("page"));
    }
}
