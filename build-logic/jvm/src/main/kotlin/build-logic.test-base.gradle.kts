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
import com.github.vlsi.gradle.properties.dsl.props
import java.util.TimeZone
import org.apache.jmeter.buildtools.testing.TestEnvironmentInputs
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("java-library")
    id("build-logic.build-params")
}

dependencies {
    findProject(":src:testkit")?.let {
        testImplementation(testFixtures(it))
    }
}

// Projects register the environment-controlled workloads they own here.
val projectTestEnvironment = extensions.create<TestEnvironmentInputs>("testEnvironmentInputs")

tasks.configureEach<Test> {
    val testEnvironment = extensions.create<TestEnvironmentInputs>("testEnvironmentInputs")
    testEnvironment.flags.convention(projectTestEnvironment.flags)
    testEnvironment.paths.convention(projectTestEnvironment.paths)
    inputs.property("environmentFlags", testEnvironment.flags)
    inputs.property("environmentPaths", testEnvironment.paths)
    val externalTestsEnabled = testEnvironment.flags.zip(testEnvironment.paths) { flags, paths ->
        flags.values.any { it == "true" } || paths.values.any { it.isNotEmpty() }
    }
    // The same URL/path can refer to a changed endpoint or fixture on the next invocation.
    outputs.doNotCacheIf("External tests must execute against current endpoints/fixtures") {
        externalTestsEnabled.get()
    }
    outputs.upToDateWhen { !externalTestsEnabled.get() }

    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
    buildParameters.testJdk?.let {
        javaLauncher.convention(javaToolchains.launcherFor(it))
    }
    providers.gradleProperty("testLanguage").orNull?.let { systemProperty("user.language", it) }
    providers.gradleProperty("testCountry").orNull?.let { systemProperty("user.country", it) }
    // These suites exercise native processes, clocks, and platform-specific GUI behavior.
    // Gradle does not automatically include the host OS or environment in Test cache keys.
    inputs.property("testOperatingSystem", providers.systemProperty("os.name"))
    inputs.property("testOperatingSystemVersion", providers.systemProperty("os.version"))
    inputs.property("testArchitecture", providers.systemProperty("os.arch"))
    inputs.property("testTimeZone", providers.environmentVariable("TZ").orElse(TimeZone.getDefault().id))
    inputs.property("testJavaRuntimeVersion", javaLauncher.map { it.metadata.javaRuntimeVersion })
    inputs.property("testJavaVendor", javaLauncher.map { it.metadata.vendor })
    inputs.property("testJvmVersion", javaLauncher.map { it.metadata.jvmVersion })
    // Pass the property to tests
    fun passProperty(name: String, default: String? = null) {
        val value = providers.systemProperty(name).orNull ?: default
        value?.let { systemProperty(name, it) }
    }
    providers.systemPropertiesPrefixedBy("jmeter.properties.").get().forEach { (name, value) ->
        systemProperty(name.removePrefix("jmeter.properties."), value)
    }
    props.string("testExtraJvmArgs").trim().takeIf { it.isNotBlank() }?.let {
        jvmArgs(it.split(" ::: "))
    }
    props.string("testDisableCaching").trim().takeIf { it.isNotBlank() }?.let {
        outputs.doNotCacheIf(it) {
            true
        }
    }
    passProperty("java.awt.headless")
    passProperty("skip.test_TestDNSCacheManager.testWithCustomResolverAnd1Server")
    // Enable testing ByteBuddy with EA Java versions
    passProperty("net.bytebuddy.experimental", "true")
}
