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

import java.nio.file.Path;
import java.util.List;

import org.apache.jmeter.JMeter;
import org.junit.jupiter.api.Test;

class RestartTest {
    @Test
    void replacesOriginalTestPlanWithCurrentlyLoadedPlan() {
        Path currentPlan = Path.of("plans", "currently-open.jmx").toAbsolutePath().normalize();
        List<String> restartCommand = List.of(
                "/path/to/java", "-cp", "breaktest.jar", "org.apache.jmeter.NewDriver",
                "-t", "/path/to/original.jmx", "-Jcustom.property=value");

        assertEquals(List.of(
                "/path/to/java", "-cp", "breaktest.jar", "org.apache.jmeter.NewDriver",
                "-J" + JMeter.REOPEN_TEST_PLAN_PROPERTY + "=" + currentPlan,
                "-Jcustom.property=value"),
                Restart.withTestPlan(restartCommand, currentPlan.toString()));
    }

    @Test
    void removesLongAndAttachedTestPlanOptions() {
        Path currentPlan = Path.of("plans", "currently-open.jmx").toAbsolutePath().normalize();
        List<String> restartCommand = List.of(
                "/path/to/java", "--testfile=/path/to/original.jmx", "-t/path/to/another.jmx");

        assertEquals(List.of("/path/to/java", "-J" + JMeter.REOPEN_TEST_PLAN_PROPERTY + "=" + currentPlan),
                Restart.withTestPlan(restartCommand, currentPlan.toString()));
    }

    @Test
    void replacesReopenPropertyInheritedFromAPreviousRestart() {
        Path currentPlan = Path.of("plans", "currently-open.jmx").toAbsolutePath().normalize();
        List<String> restartCommand = List.of(
                "/path/to/java", "-jar", "/path/to/breaktest.jar",
                "-J" + JMeter.REOPEN_TEST_PLAN_PROPERTY + "=/path/to/previously-reopened.jmx",
                "-Jcustom.property=value");

        assertEquals(List.of(
                "/path/to/java", "-jar", "/path/to/breaktest.jar",
                "-J" + JMeter.REOPEN_TEST_PLAN_PROPERTY + "=" + currentPlan,
                "-Jcustom.property=value"),
                Restart.withTestPlan(restartCommand, currentPlan.toString()));
    }

    @Test
    void placesReopenPropertyAfterJarForPackagedLaunches() {
        Path currentPlan = Path.of("plans", "currently-open.jmx").toAbsolutePath().normalize();
        List<String> restartCommand = List.of(
                "/path/to/java", "-Xmx2g", "-jar", "/path/to/breaktest.jar", "-Jcustom.property=value");

        assertEquals(List.of(
                "/path/to/java", "-Xmx2g", "-jar", "/path/to/breaktest.jar",
                "-J" + JMeter.REOPEN_TEST_PLAN_PROPERTY + "=" + currentPlan,
                "-Jcustom.property=value"),
                Restart.withTestPlan(restartCommand, currentPlan.toString()));
    }
}
