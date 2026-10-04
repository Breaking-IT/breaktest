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

package org.apache.jmeter.protocol.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.Test;

class SseMatchControllerTest {
    @Test
    void matchesDataAndOptionalEventNameAndSavesCompleteData() {
        SseMatchController controller = new SseMatchController();
        controller.setMatchValue("hello");
        SampleResult result = new SampleResult();
        result.setDataType(SampleResult.TEXT);
        result.setResponseData("hello", "UTF-8");
        result.setResponseMessage("ready");
        assertTrue(controller.matcher().match(result));
        controller.setEventName("another-event");
        assertFalse(controller.matcher().match(result));
        controller.setEventName("ready");
        assertTrue(controller.matcher().match(result));
        result.setResponseData("hello world", "UTF-8");
        assertFalse(controller.matcher().match(result));
        controller.setMatchMode(SseMatchController.REGEX);
        assertTrue(controller.matcher().match(result));
        controller.setSaveMessageVariable("data");
        JMeterVariables variables = new JMeterVariables();
        controller.matcher().saveMessage(result, variables);
        assertEquals("hello world", variables.get("data"));
        controller.setSaveMessageVariable(JMeterThread.PACKAGE_OBJECT);
        assertThrows(IllegalArgumentException.class, controller::matcher);
        controller.setSaveMessageVariable("");
        controller.setMatchValue("[");
        assertThrows(java.util.regex.PatternSyntaxException.class, controller::matcher);
    }
}
