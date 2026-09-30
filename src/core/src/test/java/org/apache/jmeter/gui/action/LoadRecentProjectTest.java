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

package org.apache.jmeter.gui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LoadRecentProjectTest extends JMeterTestCase {
    @ParameterizedTest
    @CsvSource({",9", "0,1", "-5,1", "101,100", "2147483647,100", "invalid,9", "25,25", "1,1", "100,100"})
    void configuredHistorySizeIsBounded(String configured, int expected) {
        Object previous = JMeterUtils.getJMeterProperties().remove("recent.files.max");
        try {
            if (configured != null) {
                JMeterUtils.setProperty("recent.files.max", configured);
            }
            assertEquals(expected, LoadRecentProject.getMaxRecentFiles());
        } finally {
            if (previous == null) {
                JMeterUtils.getJMeterProperties().remove("recent.files.max");
            } else {
                JMeterUtils.getJMeterProperties().put("recent.files.max", previous);
            }
        }
    }
}
