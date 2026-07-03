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
import org.apache.jmeter.testelement.property.StringProperty
import org.apache.jmeter.treebuilder.dsl.testTree
import org.apache.jmeter.treebuilder.oneRequest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentDynamicValueAnalyzerTest : JMeterTestCase() {
    @Test
    fun `long hex path segment is flagged as high-confidence hex id`() {
        val hexId = "052667f52d1aef11129d17517615ff9abdfa68d16430ef08b3d1a0307f4e02e074462f340bf8a8b0896018e2d7744e92"
        val sampler = ScriptRepairSampler("POST /bestelling/verwerken").apply {
            setProperty(StringProperty("HTTPSampler.path", "/bestelling/verwerken/$hexId"))
        }
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +sampler
                }
            }
        }

        val candidates = AgentDynamicValueAnalyzer().analyze(tree, 100)

        val hexCandidate = candidates.firstOrNull { it.literal == hexId }
        assertTrue(hexCandidate != null, "expected the 96-char hex path segment to be flagged")
        assertTrue(hexCandidate!!.kind == "hex-id", "expected kind hex-id, got ${hexCandidate.kind}")
        assertTrue(hexCandidate.priority >= 90, "expected high priority, got ${hexCandidate.priority}")
    }

    @Test
    fun `opaque non-hex path segment is flagged as high-confidence path id`() {
        val opaque = "hKFo2SBTTTJ3OGo3Tzlib3NmMHZEYWpkR1ZvVjdvU0F0UzdrOKFur3VuaXZlcnNhbC1sb2dpbg"
        val sampler = ScriptRepairSampler("GET /session").apply {
            setProperty(StringProperty("HTTPSampler.path", "/session/$opaque/resume"))
        }
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +sampler
                }
            }
        }

        val candidates = AgentDynamicValueAnalyzer().analyze(tree, 100)

        val pathCandidate = candidates.firstOrNull { it.literal == opaque }
        assertTrue(pathCandidate != null, "expected the opaque path segment to be flagged")
        assertTrue(
            pathCandidate!!.kind == "path-opaque-id",
            "expected kind path-opaque-id, got ${pathCandidate.kind}",
        )
        assertTrue(pathCandidate.priority >= 90, "expected high priority, got ${pathCandidate.priority}")
    }

    @Test
    fun `opaque token outside a path stays lower confidence`() {
        val opaque = "hKFo2SBTTTJ3OGo3Tzlib3NmMHZEYWpkR1ZvVjdvU0F0UzdrOKFur3VuaXZlcnNhbC1sb2dpbg"
        val sampler = ScriptRepairSampler(
            "POST /api/order",
            requestBody = "sessionBlob=$opaque",
        )
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +sampler
                }
            }
        }

        val candidates = AgentDynamicValueAnalyzer().analyze(tree, 100)

        val candidate = candidates.firstOrNull { it.literal == opaque }
        assertTrue(candidate != null, "expected the opaque body value to be flagged")
        assertTrue(candidate!!.kind == "opaque-token", "expected kind opaque-token, got ${candidate.kind}")
    }
}
