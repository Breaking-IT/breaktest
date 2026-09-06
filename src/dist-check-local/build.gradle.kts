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

// These fixtures only use in-process samplers and read-only input files. Each owns
// its testName.{csv,xml,jtl,log,err} outputs. Keep this group serial internally,
// while allowing it to overlap the protocol and JMS groups in separate projects.
val batchTests = listOf(
    "BatchTestLocal",
    "Bug62239",
    "Bug52968",
    "Bug50898",
    "Bug56243",
    // ModuleController must support a target node with the same name.
    "Bug55375",
    "Bug56811",
    "BUG_62847",
    "TestResultStatusAction"
).map { name ->
    tasks.register<BatchTest>("batch" + name.replaceFirstChar { it.titlecaseChar() }) {
        testName.set(name)
        dependsOn(":src:dist-check:copyExtraTestLibs")
        classpath(loggingClasspath)
        classpath(rootProject.fileTree("lib/opt") { include("*.jar") })
        // None of these fixtures uses the UDP shutdown listener.
        jmeterArgument("jmeterengine.nongui.port", "0")
    }
}

tasks.check {
    dependsOn(batchTests)
}
