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
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.control.TransactionController
import org.apache.jmeter.threads.ThreadGroup
import org.apache.jmeter.treebuilder.dsl.testTree
import org.apache.jmeter.treebuilder.oneRequest
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
}
