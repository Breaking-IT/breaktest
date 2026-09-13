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

import com.github.vlsi.gradle.dsl.configureEach

plugins {
    `java-library`
    id("build-logic.test-base")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

plugins.withId("java-test-fixtures") {
    dependencies {
        "testFixturesImplementation"("org.junit.jupiter:junit-jupiter")
    }
}

tasks.configureEach<Test> {
    useJUnitPlatform()
    // Pass the property to tests
    fun passProperty(name: String, default: String? = null) {
        val value = providers.systemProperty(name).orNull ?: default
        value?.let { systemProperty(name, it) }
    }
    passProperty("junit.jupiter.execution.parallel.enabled", "true")
    providers.gradleProperty("testParallelism").orNull?.let {
        require(it.toInt() > 0) { "testParallelism must be positive" }
        // CI overlaps projects; do not multiply each worker by the runner CPU count.
        systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
        systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", it)
        systemProperty("junit.jupiter.execution.parallel.config.fixed.max-pool-size", it)
    }
    passProperty("junit.jupiter.execution.timeout.threaddump.enabled", "true")
    passProperty("junit.jupiter.execution.timeout.default", "2 m")
}
