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

package org.apache.jmeter.ai

import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.testelement.AbstractTestElement
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.property.StringProperty
import org.apache.jmeter.testelement.property.TestElementProperty
import org.apache.jmeter.control.TransactionController
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jmeter.treebuilder.dsl.testTree
import org.apache.jmeter.treebuilder.oneRequest
import org.apache.jorphan.collections.ListedHashTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentPlanSummarizerTest : JMeterTestCase() {
    @Test
    fun `summarize prints dsl and counts samplers`() {
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler("Step 1", success = true)
                }
            }
        }

        val context = AgentPlanSummarizer().summarize(tree)

        assertEquals(3, context.elementCount)
        assertEquals(1, context.samplerCount)
        assertEquals(listOf("Step 1"), context.samplers.map { it.name })
        assertTrue(context.dsl.contains(TestPlan::class.java.name))
        assertTrue(context.dsl.contains(ThreadGroup::class.java.name))
        assertTrue(context.dsl.contains(ScriptRepairSampler::class.java.name))
    }

    @Test
    fun `summarize can cap dsl while preserving sampler inventory`() {
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler("Step 1", success = true)
                    +ScriptRepairSampler("Step 2", success = true)
                }
            }
        }

        val context = AgentPlanSummarizer().summarize(tree, dslCharacterLimit = 10)

        assertTrue(context.dsl.length > 10)
        assertTrue(context.dslTruncated)
        assertTrue(context.dslCharacterCount > context.dsl.length)
        assertEquals(2, context.samplerCount)
        assertEquals(listOf("Step 1", "Step 2"), context.samplers.map { it.name })
    }

    @Test
    fun `summarize can omit static asset samplers from model-facing inventory`() {
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler("GET /assets/app.js", success = true)
                    +ScriptRepairSampler("POST /api/order", success = true)
                }
            }
        }

        val compact = AgentPlanSummarizer().summarize(tree, includeStaticAssets = false)
        val full = AgentPlanSummarizer().summarize(tree, includeStaticAssets = true)

        assertEquals(2, compact.samplerCount)
        assertEquals(1, compact.functionalSamplerCount)
        assertEquals(1, compact.omittedStaticSamplerCount)
        assertEquals(listOf("POST /api/order"), compact.samplers.map { it.name })
        assertEquals(listOf("GET /assets/app.js", "POST /api/order"), full.samplers.map { it.name })
        assertEquals(0, full.omittedStaticSamplerCount)
        assertTrue(full.samplers.first().staticAsset)
    }

    @Test
    fun `summarize reports dynamic request value candidates`() {
        val uuid = "5d3bd642-5ced-49b6-9af7-5f945057a8ef"
        val epochMs = "1761040801000"
        val bearer = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    TransactionController::class {
                        name = "04_Transaction"
                        +ScriptRepairSampler(
                            "POST /api/order",
                            success = true,
                            requestHeaders = "Authorization: Bearer $bearer",
                            requestBody = "/api/order?id=123456&ticket=$uuid&ts=$epochMs",
                        )
                    }
                }
            }
        }

        val context = AgentPlanSummarizer().summarize(tree)

        assertEquals(
            setOf("bearer-token", "numeric-id", "uuid", "epoch-ms"),
            context.dynamicValueCandidates.map { it.kind }.toSet(),
        )
        assertTrue(context.dynamicValueCandidates.all { it.samplerName == "POST /api/order" })
        assertTrue(context.dynamicValueCandidates.any { it.literal == uuid })
        assertTrue(context.dynamicValueCandidates.any { it.literal == epochMs })
        assertTrue(context.dynamicValueCandidates.any { it.literal == bearer })
        assertTrue(context.dynamicValueCandidates.all { it.transactionName == "04_Transaction" })
    }

    @Test
    fun `summarize reports nested dynamic request value candidates`() {
        val uuid = "5d3bd642-5ced-49b6-9af7-5f945057a8ef"
        val nestedArgument = FakeRequestElement().apply {
            name = "HTTP Argument"
            setProperty(StringProperty("Argument.value", "selectedBasketItem=$uuid"))
        }
        val sampler = ScriptRepairSampler("POST /basket").apply {
            setProperty(TestElementProperty("HTTPsampler.Arguments", nestedArgument))
        }
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +sampler
                }
            }
        }

        val context = AgentPlanSummarizer().summarize(tree)

        assertTrue(
            context.dynamicValueCandidates.any {
                it.literal == uuid &&
                    it.propertyName == "Argument.value" &&
                    it.samplerName == "POST /basket"
            },
        )
    }

    @Test
    fun `summarize suppresses low signal static asset dynamic candidates`() {
        val assetHash = "app-b4763075-14fe-4db9-3cd5-08d8c1d8d470"
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler(
                        "GET /assets/$assetHash.js",
                        success = true,
                        requestBody = "/assets/$assetHash.js",
                    )
                }
            }
        }

        val context = AgentPlanSummarizer().summarize(tree)

        assertTrue(context.dynamicValueCandidates.none { it.literal.contains("b4763075") })
    }

    @Test
    fun `dynamic analyzer can include static asset requests when requested`() {
        val assetId = "b4763075-14fe-4db9-3cd5-08d8c1d8d470"
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler(
                        "GET /assets/runtime.$assetId.js",
                        success = true,
                        requestBody = "/assets/runtime.$assetId.js",
                    )
                }
            }
        }

        assertTrue(AgentDynamicValueAnalyzer().analyze(tree).none { it.literal == assetId })
        assertTrue(
            AgentDynamicValueAnalyzer(includeStaticAssetRequests = true)
                .analyze(tree)
                .any { it.literal == assetId },
        )
    }

    @Test
    fun `dynamic analyzer prioritizes functional request values before static asset noise`() {
        val assetId = "b4763075-14fe-4db9-3cd5-08d8c1d8d470"
        val transactionId = "38240dbe-3d24-4222-a967-dce8da3df796"
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler(
                        "GET /assets/runtime.$assetId.js",
                        success = true,
                        requestBody = "/assets/runtime.$assetId.js",
                    )
                    +ScriptRepairSampler(
                        "POST /api/tickets",
                        success = true,
                        requestBody = """{"transactionId":"$transactionId"}""",
                    )
                }
            }
        }

        val candidate = AgentDynamicValueAnalyzer(includeStaticAssetRequests = true)
            .analyze(tree, limit = 1)
            .single()

        assertEquals(transactionId, candidate.literal)
        assertTrue(candidate.priority > 100)
    }

    @Test
    fun `summarize still reports functional api path opaque ids`() {
        val pageId = "b4763075-14fe-4db9-3cd5-08d8c1d8d470"
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler(
                        "GET /api/tickets/$pageId",
                        success = true,
                        requestBody = "/api/tickets/$pageId",
                    )
                }
            }
        }

        val context = AgentPlanSummarizer().summarize(tree)

        assertTrue(context.dynamicValueCandidates.any { it.literal == pageId })
    }

    @Test
    fun `summarize reports fixed uuid in partially parameterized json body`() {
        val transactionId = "38240dbe-3d24-4222-a967-dce8da3df796"
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler(
                        "POST /api/tickets/b4763075-14fe-4db9-3cd5-08d8c1d8d470",
                        success = true,
                        requestBody = """
                            {"transactionId":"$transactionId","tickets":[{"productId":"${'$'}{stl_product_id}","drawId":"${'$'}{stl_draw_id}"}]}
                        """.trimIndent(),
                    )
                }
            }
        }

        val context = AgentPlanSummarizer().summarize(tree)
        val candidate = context.dynamicValueCandidates.single { it.literal == transactionId }

        assertEquals("uuid", candidate.kind)
        assertTrue(candidate.reason.contains("browser/client-generated"))
    }

    @Test
    fun `summarize reports credential and formatted date time candidates`() {
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler(
                        "POST /login",
                        success = true,
                        requestBody = "username=jane@example.test&password=Secret-123&appointment=2026-01-31 23:00:01",
                    )
                }
            }
        }

        val context = AgentPlanSummarizer().summarize(tree)

        assertTrue(
            context.dynamicValueCandidates.any {
                it.kind == "credential" && it.literal == "jane@example.test"
            },
        )
        assertTrue(
            context.dynamicValueCandidates.any {
                it.kind == "credential" && it.literal == "Secret-123"
            },
        )
        assertTrue(
            context.dynamicValueCandidates.any {
                it.kind == "date-time" && it.literal == "2026-01-31 23:00:01"
            },
        )
    }

    @Test
    fun `summarize reports draw id candidates in json bodies`() {
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler(
                        "POST /api/basket/verify",
                        success = true,
                        requestBody = """{"drawId":"20260620JUL","productId":"${'$'}{product_id}"}""",
                    )
                }
            }
        }

        val context = AgentPlanSummarizer().summarize(tree)

        assertTrue(
            context.dynamicValueCandidates.any {
                it.kind == "draw-id" && it.literal == "20260620JUL"
            },
        )
    }

    @Test
    fun `summarize reports extractor context under samplers`() {
        val testPlan = TestPlan("Test Plan")
        val threadGroup = ThreadGroup().apply { name = "Thread Group" }
        val transaction = TransactionController().apply { name = "03_Transaction" }
        val sampler = ScriptRepairSampler("POST /u/login")
        val extractor = FakeRegexExtractor().apply { name = "AI Regex Extractor - auth0_resume_state" }

        val tree = ListedHashTree()
        val testPlanTree = tree.add(testPlan)
        val threadGroupTree = testPlanTree.add(threadGroup)
        val transactionTree = threadGroupTree.add(transaction)
        val samplerTree = transactionTree.add(sampler)
        samplerTree.add(extractor)

        val context = AgentPlanSummarizer().summarize(tree)

        assertEquals(1, context.extractors.size)
        val summary = context.extractors.single()
        assertEquals("POST /u/login", summary.samplerName)
        assertEquals("03_Transaction", summary.transactionName)
        assertEquals("auth0_resume_state", summary.variableName)
        assertEquals("state=([^&]+)", summary.regex)
        assertEquals("headers", summary.useField)
        assertEquals(true, summary.failOnNoMatch)
    }

    class FakeRegexExtractor : AbstractTestElement() {
        fun getRefName(): String = "auth0_resume_state"

        fun getRegex(): String = "state=([^&]+)"

        fun getMatchNumberAsString(): String = "1"

        fun getDefaultValue(): String = "NOT_FOUND"

        fun isFailOnNoMatch(): Boolean = true

        fun useHeaders(): Boolean = true

        fun useRequestHeaders(): Boolean = false

        fun useUnescapedBody(): Boolean = false

        fun useBodyAsDocument(): Boolean = false

        fun useUrl(): Boolean = false

        fun useCode(): Boolean = false

        fun useMessage(): Boolean = false

        fun useBody(): Boolean = false
    }

    class FakeRequestElement : AbstractTestElement()
}
