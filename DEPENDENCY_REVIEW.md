<!--
Copyright 2024-2026 BreakTest contributors

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy of the
License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
-->

# Dependency review — 2026-10-16

This review compares the third-party and testing BOM pins with Maven Central
release metadata. The runtime and test upgrades below are implemented in this change.
This is a maintenance review, not a vulnerability assessment.
Build plugins, frontend assets and the complete transitive dependency graph are
outside this review.

## Groovy upgrade

The Groovy BOM moves from 5.0.4 to 6.0.0, aligning all bundled Groovy modules.
[Maven Central](https://repo.maven.apache.org/maven2/org/apache/groovy/groovy-bom/maven-metadata.xml)
publishes 6.0.0 as a final release. Its Java 17 minimum fits BreakTest's Java 21
baseline. Existing JSR223 engine, direct-evaluation, controller and function tests
are especially relevant because BreakTest accesses Groovy's compiled-script internals.

This is a major scripting-language upgrade: existing user scripts should be
replayed before production load runs. The
[Groovy 6 migration notes](https://groovy-lang.org/releasenotes/groovy-6.0.html#_breaking_changes)
describe changed map membership, loop-variable capture, stricter generic syntax,
and JSON/XML parsing limits. Quoted interpolation in Groovy SQL now fails instead
of warning. `@Grab`'s Ivy backend and the classic non-invokedynamic runtime have
moved into optional modules; scripts or plugins using those features need the
corresponding modules and dependencies. The default JSR223 execution path uses
invokedynamic.

## Runtime upgrades

The regression coverage for these updates is listed below. Published newer
versions alone do not establish a security issue or guarantee compatibility.
Each source links to the artifact's Maven Central version history.

| Library | Previous → updated | Reason and validation |
| --- | --- | --- |
| [HttpClient 5](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/client5/httpclient5/maven-metadata.xml) | 5.6.1 → 5.6.5 | Patch update on the active HTTP execution path; exercise redirects, cookies, proxy/TLS, HTTP/2 and HTTP/3 and inspect resolved HttpCore versions. |
| [Log4j](https://repo.maven.apache.org/maven2/org/apache/logging/log4j/log4j-core/maven-metadata.xml) | 2.26.0 → 2.26.1 | Update API, core and SLF4J binding together; smoke-test packaged logging. |
| [Jackson BOM](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/jackson-bom/maven-metadata.xml) | 2.21.2 → 2.22.3 | Stay on Jackson 2; exercise JSON extraction, HAR import and agent serialization. |
| [DBCP](https://repo.maven.apache.org/maven2/org/apache/commons/commons-dbcp2/maven-metadata.xml) / [Pool](https://repo.maven.apache.org/maven2/org/apache/commons/commons-pool2/maven-metadata.xml) | 2.9.0 → 2.14.0 / 2.12.1 → 2.13.1 | DBCP is substantially behind; update together and run JDBC tests for initialization, reuse, eviction and shutdown. |
| [Bouncy Castle](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/maven-metadata.xml) | 1.84 → 1.86 | Keep all three artifacts aligned; validate recorder certificates and mail signing. |
| [jsoup](https://repo.maven.apache.org/maven2/org/jsoup/jsoup/maven-metadata.xml) | 1.21.2 → 1.23.2 | Exercise CSS extraction and malformed HTML parsing. |
| [Woodstox](https://repo.maven.apache.org/maven2/com/fasterxml/woodstox/woodstox-core/maven-metadata.xml) | 7.1.1 → 7.3.0 | Exercise XML parsing and response formatting. |
| [Commons IO](https://repo.maven.apache.org/maven2/commons-io/commons-io/maven-metadata.xml), [Lang3](https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/maven-metadata.xml), [Text](https://repo.maven.apache.org/maven2/org/apache/commons/commons-text/maven-metadata.xml), [Codec](https://repo.maven.apache.org/maven2/commons-codec/commons-codec/maven-metadata.xml) | 2.21.0 → 2.22.0; 3.19.0 → 3.21.0; 1.14.0 → 1.15.0; 1.20.0 → 1.23.0 | Routine maintenance candidates; run core/functions and protocol tests. |
| [FlatLaf](https://repo.maven.apache.org/maven2/com/formdev/flatlaf/maven-metadata.xml) / [MigLayout](https://repo.maven.apache.org/maven2/com/miglayout/miglayout-swing/maven-metadata.xml) | 3.6 → 3.7.2 / 11.4.3 → 11.5.1 | Update companion artifacts together; visually test editors, dialogs, themes and scaling. |

## Additional upgrades

The following updates are also included.

| Library family | Previous → updated |
| --- | --- |
| kotlinx-coroutines | 1.10.2 → 1.11.0 |
| ASM | 9.9 → 9.11 |
| Caffeine | 3.2.3 → 3.3.1 |
| Error Prone annotations | 2.44.0 → 2.50.0 (optional Error Prone compiler tooling remains unchanged) |
| ph-commons / ph-css | 12.1.5 → 12.5.1 / 8.1.1 → 8.2.3 |
| Commons Logging / Net / Collections4 / JEXL | 1.3.6 → 1.4.0 / 3.12.0 → 3.13.0 / 4.5.0 → 4.6.0 / 3.5.0 → 3.7.0 |
| dnsjava | 3.6.4 → 3.6.5 (license metadata and bundled notice updated to BSD-3-Clause) |
| FreeMarker / JAXB txw2 | 2.3.34 → 2.3.35 / 4.0.6 → 4.0.9 |
| JetBrains annotations | 26.0.2-1 → 26.1.0 |
| Neo4j driver / SnakeYAML Engine | 6.0.2 → 6.3.0 / 3.0.1 → 3.2 |
| Apache RAT | 0.17 → 0.18 |
| JUnit / Spring test BOM | 6.0.1 → 6.1.3 / 7.0.0 → 7.0.9 |
| WireMock / MockK | 3.13.1 → 3.13.2 / 1.14.9 → 1.14.11 |
| Byte Buddy / EqualsVerifier / Objenesis | 1.17.8 → 1.18.15 / 4.3 → 4.5.2 / 3.4 → 3.6 |
| ActiveMQ / MINA | 6.2.6 → 6.3.2 / 2.2.8 → 2.2.9 |

## Compatibility changes

- Tika 4 document extraction uses a managed `TikaInputStream`. MIME detection
  uses Tika's default detectors without loading parser services, replacing the
  obsolete Tika 3 XML configuration that ignored parser initialization problems.
  Only tika-core is bundled; a user-installed `tika-app` (or parser modules) must
  be upgraded to 4.x. An incompatible Tika 3 `tika-app` is reported as missing
  instead of failing the document view.
- DBCP settings use the Duration-based API, preserving their millisecond units
  and negative timeout semantics. The diagnostic username getter uses its new name.
- dnsjava's current published license is BSD-3-Clause. The override and bundled
  copyright/license text now match the 3.6.5 artifact.

## Major upgrades and intentional holds

- RSyntaxTextArea 3.6.2 → 4.0.1, JSVG 1.4.0 → 2.2.0, JSONPath 2.10.0 →
  3.0.0, Saxon 12.9 → 13.1, and Tika 3.2.3 → 4.1.0 are included.
- Jetty moves from 11.0.26 to 12.1.14. WireMock uses `wiremock-jetty12`
  3.13.2, with both the Jetty core and EE10 BOMs aligned to 12.1.14.
  Mixing EE10 12.0 with core 12.1 caused a `NoSuchMethodError` at server startup,
  so Renovate updates both BOMs in one group.
- SLF4J API and the JCL bridge are aligned on 2.0.20, matching the existing
  Log4j SLF4J 2 provider. The distribution already resolved SLF4J 2 transitively;
  the stale 1.7 constraints and Renovate upper bound are removed.
- Lets-Plot stays on Batik 4.8.0 / Kotlin JVM 4.11.2: newer Lets-Plot Kotlin
  releases require kotlin-stdlib 2.3, above the Kotlin 2.2.21 compiler that
  builds BreakTest and the Kotlin versions plugin consumers may use.
- xml-apis stays on 1.4.01 with its existing Renovate upper bound.
  Historical 2.0.2 metadata is not a suitable replacement for the legacy XML stack.
- Commons Lang 2, Commons Collections 3 and HttpClient 4 are deliberately retained
  for compatibility. Newer major families are not drop-in replacements.
- Ignore historical date-like Commons Collections versions and apparent
  downgrades for `javax.mail:mail` and `unbescape`. JDOM 2 requires a migration,
  not a numeric version bump on a legacy API.

## Verification

The release JAR inventory and dependency signature/checksum metadata are updated;
strict verification remains enabled. The unit suites report 7,397 passed and 104
skipped tests. Distribution batch coverage includes JDBC, JMS, HTTPS and HTML
parsing. The repository's external-service/flaky tests retain their existing skips.

Validation commands:

```sh
./gradlew test autostyleCheck :src:dist:verifyReleaseDependencies -PupdateExpectedJars
./gradlew check autostyleCheck :src:licenses:renderLicenseForBinary
```

DBCP 2.14 declares Jakarta Transactions only for its managed (JTA) connections,
which BreakTest does not use, so that dependency is excluded from the distribution.
JAXB txw2's EDL notice is classified as BSD-3-Clause.
