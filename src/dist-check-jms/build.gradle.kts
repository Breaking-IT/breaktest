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

import org.apache.jmeter.buildtools.batchtest.BatchTest

plugins {
    base
    id("build-logic.batchtest")
}

val loggingClasspath by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    loggingClasspath(project(path = ":src:dist-check", configuration = "batchLoggingClasspath"))
}

// Gradle can overlap tasks in separate projects. This suite owns port 61616,
// bin/activemq-data and JMS_TESTS.{csv,xml,jtl,log,err}; no other batch suite uses them.
// Protocol fixtures retain their serial execution and shared-fixture ordering.
val batchJmsTests = tasks.register<BatchTest>("batchJMS_TESTS") {
    testName.set("JMS_TESTS")
    dependsOn(":src:dist-check:copyExtraTestLibs")
    classpath(loggingClasspath)
    classpath(rootProject.fileTree("lib/opt") { include("*.jar") })
    // This test never uses the UDP shutdown listener; avoid its shared default port.
    jmeterArgument("jmeterengine.nongui.port", "0")
}

tasks.check {
    dependsOn(batchJmsTests)
}
