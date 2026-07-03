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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentRegexSupportTest {
    private val redirectBody =
        """<p>Found. Redirecting to <a href="/u/login?state=hKFo2SBTTTJ3OGo3Tzli">/u/login?state=hKFo2SBTTTJ3OGo3Tzli</a></p>"""

    @Test
    fun `quoted escaping is rejected because ORO does not support it`() {
        val problem = AgentRegexSupport.oroProblem("""\Qstate\E\s*=\s*([^&"'\s<>]+)""")
        assertNotNull(problem)
        assertTrue(problem!!.contains("\\Q"))
    }

    @Test
    fun `plain query parameter regex is accepted and matches the redirect body`() {
        val regex = """state=([^&"'\s<>]+)"""
        assertNull(AgentRegexSupport.oroProblem(regex))
        assertTrue(AgentRegexSupport.oroMatches(regex, redirectBody))
    }

    @Test
    fun `quoted escaping never matches even when syntactically tolerated`() {
        assertFalse(AgentRegexSupport.oroMatches("""\Qstate\E=([^&]+)""", redirectBody))
    }

    @Test
    fun `oroEscape escapes metacharacters with single backslashes`() {
        assertEquals("""a\.b\[c\]\$""", AgentRegexSupport.oroEscape("a.b[c]$"))
        assertEquals("plain_field", AgentRegexSupport.oroEscape("plain_field"))
        assertFalse(AgentRegexSupport.oroEscape("state.x").contains("\\Q"))
    }

    @Test
    fun `escaped literal round trips through the ORO engine`() {
        val literal = "price[0].total?"
        val regex = AgentRegexSupport.oroEscape(literal) + "=([0-9]+)"
        assertNull(AgentRegexSupport.oroProblem(regex))
        assertTrue(AgentRegexSupport.oroMatches(regex, "before price[0].total?=42 after"))
    }
}
