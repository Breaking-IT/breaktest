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

import org.apache.jmeter.buildtools.testing.TestEnvironmentInputs

plugins {
    id("java-test-fixtures")
    id("build-logic.jvm-published-library")
}

// Keep direct `test --tests ...` invocations compatible; CI opts into the split.
val splitHttp3LiveTests = providers.gradleProperty("splitHttp3LiveTests").map { it.toBoolean() }.orElse(false)
val liveHttp3 = providers.environmentVariable("BREAKTEST_HTTP3_LIVE").orElse("")
extensions.configure<TestEnvironmentInputs> {
    listOf(
        "BREAKTEST_HTTP2_LIVE", "BREAKTEST_HTTP_LIVE", "BREAKTEST_HTTP3_LIVE",
        "BREAKTEST_HTTP3_CERT_LIVE", "BREAKTEST_HC5_LIFECYCLE_BENCHMARK"
    ).forEach { flags.put(it, providers.environmentVariable(it).orElse("")) }
    listOf(
        "BREAKTEST_HTTP3_FIXTURE", "BREAKTEST_HTTP3_SELF_SIGNED_URL", "BREAKTEST_UPLOAD_HAR"
    ).forEach { paths.put(it, providers.environmentVariable(it).orElse("")) }
}

tasks.test {
    if (splitHttp3LiveTests.get()) {
        useJUnitPlatform { excludeTags("live-http3") }
        extensions.configure<TestEnvironmentInputs> {
            // The excluded workload runs in http3LiveTest with the real flag value.
            flags.put("BREAKTEST_HTTP3_LIVE", "")
        }
    }
}

val http3LiveTest by tasks.registering(Test::class) {
    description = "Runs the opt-in live HTTP/3 checks separately from the reusable HTTP suite"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("live-http3") }
    onlyIf { liveHttp3.get() == "true" }
    // Avoid overlapping global/network-sensitive HTTP suites within this project.
    mustRunAfter(tasks.test)
}

tasks.check {
    if (splitHttp3LiveTests.get()) {
        dependsOn(http3LiveTest)
    }
}

dependencies {
    api(projects.src.core)

    api(projects.src.components) {
        because("we need SearchTextExtension")
    }

    api("com.thoughtworks.xstream:xstream") {
        because("HTTPResultConverter uses XStream in public API")
        exclude("io.github.x-stream", "mxparser")
    }

    compileOnly("javax.activation:javax.activation-api") {
        because("ParseCurlCommandAction uses new MimetypesFileTypeMap()")
    }

    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.unbescape:unbescape")
    implementation("org.jodd:jodd-lagarto") {
        exclude("ch.qos.logback")
        exclude("commons-logging")
        exclude("org.apache.logging.log4j")
    }
    implementation("org.jodd:jodd-log") {
        because("jodd-lagarto 5 still uses custom jodd-log so we configure it to use slf4j")
        exclude("ch.qos.logback")
        exclude("commons-logging")
        exclude("org.apache.logging.log4j")
    }
    implementation("org.jsoup:jsoup")
    implementation("oro:oro")
    implementation("commons-net:commons-net")
    implementation("com.helger.commons:ph-commons") {
        // We don't really need to use/distribute jsr305
        exclude("com.google.code.findbugs", "jsr305")
    }
    implementation("com.helger:ph-css") {
        // We don't really need to use/distribute jsr305
        exclude("com.google.code.findbugs", "jsr305")
    }
    implementation("dnsjava:dnsjava")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("org.apache.httpcomponents.core5:httpcore5-h2")
    implementation("com.miglayout:miglayout-swing")
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.brotli:dec") {
        because("BrotliDecoder for HTTP response decompression")
    }
    implementation("io.airlift:aircompressor") {
        because("ZstdDecoder for HTTP response decompression")
    }
    testImplementation(testFixtures(projects.src.core))
    testImplementation(testFixtures(projects.src.testkitWiremock))
    testImplementation("org.wiremock:wiremock")
    // For some reason JMeter bundles just tika-core and tika-parsers without transitive
    // dependencies. So we exclude those
    implementation("org.apache.tika:tika-core") {
        isTransitive = false
    }
    runtimeOnly("org.apache.tika:tika-parsers") {
        isTransitive = false
    }
}
