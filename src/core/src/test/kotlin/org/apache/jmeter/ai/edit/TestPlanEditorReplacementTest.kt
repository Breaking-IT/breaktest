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

package org.apache.jmeter.ai.edit

import org.apache.jmeter.ai.ScriptRepairSampler
import org.apache.jmeter.config.Argument
import org.apache.jmeter.config.Arguments
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.testelement.property.TestElementProperty
import org.apache.jmeter.treebuilder.dsl.testTree
import org.apache.jmeter.treebuilder.oneRequest
import org.apache.jorphan.collections.ListedHashTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestPlanEditorReplacementTest {
    @Test
    fun `replace literal reaches nested argument values`() {
        val sampler = ScriptRepairSampler("Login")
        val args = Arguments().apply {
            addArgument("state", "OLD_STATE")
        }
        sampler.setProperty(TestElementProperty("HTTPsampler.Arguments", args))

        val replacements = TestPlanEditor().replaceLiteral(
            sampler,
            org.apache.jorphan.collections.ListedHashTree(),
            "OLD_STATE",
            "\${auth0_login_state}",
        )

        val argument = args.arguments.get(0).objectValue as Argument
        assertEquals(1, replacements)
        assertEquals("\${auth0_login_state}", argument.value)
    }

    @Test
    fun `replace literal in tree updates all matching sampler properties`() {
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +ScriptRepairSampler("First", requestBody = "state=OLD_STATE")
                    +ScriptRepairSampler("Second", requestHeaders = "Referer: /resume?state=OLD_STATE")
                }
            }
        }

        val replacements = TestPlanEditor().replaceLiteralInTree(
            tree,
            "OLD_STATE",
            "\${auth0_login_state}",
        )

        assertEquals(2, replacements)
    }

    @Test
    fun `replace literal does not update sampler names by default`() {
        val sampler = ScriptRepairSampler(
            "GET /api/tickets/b4763075-14fe-4db9-3cd5-08d8c1d8d470",
            requestBody = "/api/tickets/b4763075-14fe-4db9-3cd5-08d8c1d8d470",
        )
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +sampler
                }
            }
        }

        val replacements = TestPlanEditor().replaceLiteralInTree(
            tree,
            "b4763075-14fe-4db9-3cd5-08d8c1d8d470",
            "\${checkout_page_id}",
        )

        assertEquals(1, replacements)
        assertEquals("GET /api/tickets/b4763075-14fe-4db9-3cd5-08d8c1d8d470", sampler.name)
        assertEquals("/api/tickets/\${checkout_page_id}", sampler.getPropertyAsString("ScriptRepairSampler.requestBody"))
    }

    @Test
    fun `replace literal can update names when explicitly requested`() {
        val sampler = ScriptRepairSampler(
            "GET /api/tickets/b4763075-14fe-4db9-3cd5-08d8c1d8d470",
            requestBody = "/api/tickets/b4763075-14fe-4db9-3cd5-08d8c1d8d470",
        )
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +sampler
                }
            }
        }

        val replacements = TestPlanEditor().replaceLiteralInTree(
            tree,
            "b4763075-14fe-4db9-3cd5-08d8c1d8d470",
            "\${checkout_page_id}",
            includeNames = true,
        )

        assertEquals(2, replacements)
        assertEquals("GET /api/tickets/\${checkout_page_id}", sampler.name)
    }

    @Test
    fun `replace literal can leave user defined variable values unchanged`() {
        val testPlan = TestPlan("Test Plan").apply {
            addParameter("username", "recorded-user")
        }
        val sampler = ScriptRepairSampler(
            "Login",
            requestBody = "username=recorded-user&password=secret",
        )
        val tree = ListedHashTree().apply {
            add(testPlan).add(sampler)
        }

        val replacements = TestPlanEditor().replaceLiteralInTree(
            tree,
            "recorded-user",
            "\${username}",
            excludeUserDefinedVariables = true,
        )

        assertEquals(1, replacements)
        assertEquals("recorded-user", testPlan.arguments.getArgument(0).value)
        assertEquals("username=\${username}&password=secret", sampler.getPropertyAsString("ScriptRepairSampler.requestBody"))
    }

    @Test
    fun `replace literal in names leaves request data unchanged`() {
        val sampler = ScriptRepairSampler(
            "GET /api/tickets/b4763075-14fe-4db9-3cd5-08d8c1d8d470",
            requestBody = "/api/tickets/\${checkout_page_id}",
        )
        val tree = testTree {
            TestPlan::class {
                oneRequest {
                    +sampler
                }
            }
        }

        val replacements = TestPlanEditor().replaceLiteralInNamesInTree(
            tree,
            "b4763075-14fe-4db9-3cd5-08d8c1d8d470",
            "{checkout_page_id}",
        )

        assertEquals(1, replacements)
        assertEquals("GET /api/tickets/{checkout_page_id}", sampler.name)
        assertEquals("/api/tickets/\${checkout_page_id}", sampler.getPropertyAsString("ScriptRepairSampler.requestBody"))
    }

    @Test
    fun `regex extractor normalizes response header use field`() {
        assertEquals("true", TestPlanEditor().normalizeExtractorUseField("headers"))
        assertEquals("false", TestPlanEditor().normalizeExtractorUseField("body"))
        assertEquals("request_headers", TestPlanEditor().normalizeExtractorUseField("requestHeaders"))
    }
}
