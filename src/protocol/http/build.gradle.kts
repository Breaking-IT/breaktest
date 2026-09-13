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

plugins {
    id("java-test-fixtures")
    id("build-logic.jvm-published-library")
}

// Keep direct `test --tests ...` invocations compatible; CI opts into the split.
val splitHttp3LiveTests = providers.gradleProperty("splitHttp3LiveTests").map { it.toBoolean() }.orElse(false)
val liveHttp3Methods = listOf(
    "org.apache.jmeter.protocol.http.sampler.TestHTTPJavaHttp3Impl.http3SamplesLiveEndpointOnSupportedRuntime",
    "org.apache.jmeter.protocol.http.sampler.TestHTTPJavaHttp3Impl.preferredModeUpgradesToHttp3AfterAltSvcDiscovery"
)
val httpTestEnvironment = listOf(
    "BREAKTEST_HTTP2_LIVE", "BREAKTEST_HTTP_LIVE", "BREAKTEST_HTTP3_LIVE",
    "BREAKTEST_HTTP3_CERT_LIVE", "BREAKTEST_HC5_LIFECYCLE_BENCHMARK",
    "BREAKTEST_HTTP3_FIXTURE", "BREAKTEST_HTTP3_SELF_SIGNED_URL", "BREAKTEST_UPLOAD_HAR"
).associateWith { providers.environmentVariable(it).orElse("") }

tasks.withType<Test>().configureEach {
    httpTestEnvironment.forEach { (name, value) -> inputs.property(name, value) }
    // An unchanged URL/path does not mean unchanged endpoint or fixture contents.
    val externalTestsEnabled = provider {
        httpTestEnvironment.any { (variable, value) ->
            if (variable == "BREAKTEST_HTTP3_LIVE" && name == "test" && splitHttp3LiveTests.get()) {
                false
            } else if (variable.endsWith("_LIVE") || variable.endsWith("_BENCHMARK")) {
                value.get() == "true"
            } else {
                value.get().isNotEmpty()
            }
        }
    }
    outputs.doNotCacheIf("External HTTP tests must execute against current endpoints/fixtures") {
        externalTestsEnabled.get()
    }
    outputs.upToDateWhen { !externalTestsEnabled.get() }
}

tasks.test {
    if (splitHttp3LiveTests.get()) {
        liveHttp3Methods.forEach { filter.excludeTestsMatching(it) }
    }
}

val http3LiveTest by tasks.registering(Test::class) {
    description = "Runs the opt-in live HTTP/3 checks separately from the reusable HTTP suite"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    liveHttp3Methods.forEach { filter.includeTestsMatching(it) }
    onlyIf { httpTestEnvironment.getValue("BREAKTEST_HTTP3_LIVE").get() == "true" }
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
