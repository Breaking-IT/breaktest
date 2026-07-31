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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentRegexCaptureTest {

    /** The getapigconfigs response shape from the FedEx repair runs. */
    private val apigResponse =
        """{"clientID":"l7xxab12cd34ef56","clientSecret":"s3cr3t","env":"prod"}"""

    @Test
    fun `matching is not the same as capturing the intended value`() {
        val generic = """"([^"]+)""""
        // This is what the planner used to emit, and why ${'$'}{client_id} resolved to
        // the string "clientID": it matches, but captures the key.
        assertTrue(AgentRegexSupport.oroMatches(generic, apigResponse))
        assertEquals("clientID", AgentRegexSupport.oroFirstCapture(generic, apigResponse))
        assertNotEquals("l7xxab12cd34ef56", AgentRegexSupport.oroFirstCapture(generic, apigResponse))
    }

    @Test
    fun `an anchored pattern captures the value`() {
        val anchored = """"clientID"\s*:\s*"([^"]+)""""
        assertEquals("l7xxab12cd34ef56", AgentRegexSupport.oroFirstCapture(anchored, apigResponse))
    }

    @Test
    fun `no capture is reported for a non-matching or group-less pattern`() {
        assertNull(AgentRegexSupport.oroFirstCapture("""notpresent"([^"]+)"""", apigResponse))
        assertNull(AgentRegexSupport.oroFirstCapture("""clientID""", apigResponse))
        assertNull(AgentRegexSupport.oroFirstCapture("""([""", apigResponse))
    }

    @Test
    fun `capture is reported for headers and form shapes too`() {
        val headers = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=abc123; Path=/\r\n\r\n{}"
        assertEquals("abc123", AgentRegexSupport.oroFirstCapture("""Set-Cookie: sid=([^;]+)""", headers))
    }
}
