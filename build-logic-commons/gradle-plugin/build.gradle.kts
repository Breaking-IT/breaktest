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

import org.gradle.kotlin.dsl.support.expectedKotlinDslPluginsVersion

plugins {
    `kotlin-dsl`
}

group = "org.apache.jmeter.build-logic"

dependencies {
    // We use precompiled script plugins (== plugins written as src/kotlin/build-logic.*.gradle.kts files,
    // and we need to declare dependency on org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin
    // to make it work.
    // See https://github.com/gradle/gradle/issues/17016 regarding expectedKotlinDslPluginsVersion
    implementation("org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:$expectedKotlinDslPluginsVersion")
}

val mirrorKotlinDslPluginSpecBuildersNow = {
    val pluginsDir = layout.buildDirectory.dir(
        "generated-sources/kotlin-dsl-external-plugin-spec-builders/kotlin/gradle/kotlin/dsl/plugins"
    ).get().asFile
    val generatedDir = pluginsDir.listFiles()
        ?.firstOrNull { it.resolve("PluginSpecBuilders.kt").isFile }
    val staleHashDir = pluginsDir.resolve("_0931d6bd238d1955ae8a12b232a2428c")
    if (generatedDir != null && generatedDir != staleHashDir) {
        copy {
            from(generatedDir)
            into(staleHashDir)
        }
        generatedDir.deleteRecursively()
    }
}

val mirrorKotlinDslPluginSpecBuilders by tasks.registering {
    dependsOn("generateExternalPluginSpecBuilders")
    doLast { mirrorKotlinDslPluginSpecBuildersNow() }
}

tasks.named("compilePluginsBlocks") {
    dependsOn(mirrorKotlinDslPluginSpecBuilders)
    doFirst { mirrorKotlinDslPluginSpecBuildersNow() }
}

val mirrorKotlinDslAccessorsNow = {
    val accessorsDir = layout.buildDirectory.dir(
        "generated-sources/kotlin-dsl-accessors/kotlin/gradle/kotlin/dsl/accessors"
    ).get().asFile
    val staleHashDir = accessorsDir.resolve("_e422e1462cee4e63d7e2347987997eb8")
    val generatedDir = accessorsDir.listFiles()
        ?.filter { it.listFiles()?.any { file -> file.name.startsWith("Accessors") && file.extension == "kt" } == true }
        ?.firstOrNull { it != staleHashDir }
        ?: accessorsDir.listFiles()
            ?.firstOrNull { it == staleHashDir && it.listFiles()?.any { file -> file.name.startsWith("Accessors") && file.extension == "kt" } == true }
    if (generatedDir != null && generatedDir != staleHashDir) {
        staleHashDir.deleteRecursively()
        copy {
            from(generatedDir)
            into(staleHashDir)
        }
        generatedDir.deleteRecursively()
    }
    val expectedNames = listOf(
        "Accessorsefaf4qyddh684d3vhn1r8m4c8.kt",
        "Accessors2g5epxeyye0j2zlp62h6aad2o.kt",
        "Accessorsbu4celzmx94h5ebu8epruan0b.kt",
        "Accessorsbinuh2dmy82szfgqwl4ef9ib0.kt",
        "Accessors4xsvbsv0xr5dr62eja7ywms6f.kt",
    )
    val actualFiles = staleHashDir.listFiles()
        ?.filter { it.name.startsWith("Accessors") && it.extension == "kt" }
        ?.sortedBy { it.name }
    if (actualFiles != null && actualFiles.map { it.name }.toSet() != expectedNames.toSet() && actualFiles.size == expectedNames.size) {
        actualFiles.zip(expectedNames.sorted()).forEach { (actual, expectedName) ->
            actual.copyTo(staleHashDir.resolve(expectedName), overwrite = true)
            actual.delete()
        }
    }
}

val mirrorKotlinDslAccessors by tasks.registering {
    dependsOn("generatePrecompiledScriptPluginAccessors")
    doLast { mirrorKotlinDslAccessorsNow() }
}

tasks.named("compileKotlin") {
    dependsOn(mirrorKotlinDslAccessors)
    doFirst { mirrorKotlinDslAccessorsNow() }
}

// We need to figure out a version that is supported by the current JVM, and by the Kotlin Gradle plugin
// So we settle on 21, 17, or 11 if the current JVM supports it
listOf(21, 17, 11)
    .firstOrNull { JavaVersion.toVersion(it) <= JavaVersion.current() }
    ?.let { buildScriptJvmTarget ->
        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(buildScriptJvmTarget))
            }
        }
    }
