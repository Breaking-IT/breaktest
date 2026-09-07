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

package org.apache.jmeter.protocol.http

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.apache.jmeter.engine.StandardJMeterEngine
import org.apache.jmeter.junit.JMeterTestCase
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerFactory
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy
import org.apache.jmeter.test.samplers.CollectSamplesListener
import org.apache.jmeter.testelement.TestPlan
import org.apache.jmeter.threads.openmodel.OpenModelThreadGroup
import org.apache.jmeter.treebuilder.dsl.testTree
import org.apache.jorphan.test.JMeterSerialTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HttpRequestInterruptTest : JMeterTestCase(), JMeterSerialTest {
    @ParameterizedTest
    @Timeout(20, unit = TimeUnit.SECONDS)
    @ValueSource(strings = [HTTPSamplerFactory.IMPL_HTTP_CLIENT5])
    fun `http request interrupts`(httpImplementation: String) {
        val server = WireMockServer(wireMockConfig().dynamicPort())
        val received = CountDownLatch(1)
        val listener = CollectSamplesListener()
        val engine = StandardJMeterEngine()
        server.addMockServiceRequestListener { _, _ -> received.countDown() }
        server.start()
        try {
            server.stubFor(
                get("/delayed").willReturn(aResponse().withFixedDelay(60_000).withStatus(200))
            )
            val tree = testTree {
                TestPlan::class {
                    +listener
                    OpenModelThreadGroup::class {
                        // One predictable arrival; stop explicitly after the server receives it.
                        scheduleString = "rate(1 / sec) even_arrivals(1 s) pause(1 min)"
                        HTTPSamplerProxy::class {
                            implementation = httpImplementation
                            method = "GET"
                            protocol = "http"
                            domain = "localhost"
                            port = server.port()
                            path = "/delayed"
                        }
                    }
                }
            }
            engine.configure(tree)
            engine.runTest()
            assertTrue(received.await(10, TimeUnit.SECONDS), "The delayed request must reach the server")
            assertTrue(listener.events.isEmpty(), "The delayed response must still be in flight")
            engine.stopTest(true)
            engine.awaitTermination(Duration.ofSeconds(5))

            val events = listener.events
            assertEquals(1, events.size, "One interrupted request expected")
            val result = events.single().result
            assertFalse(result.isSuccessful)
            assertFalse(result.isResponseCodeOK)
            assertTrue(result.responseCode.contains("Interrupted")) {
                "Expected cancellation of the in-flight request, got ${result.responseCode}: ${result.responseMessage}"
            }
        } finally {
            try {
                engine.stopTest(true)
                engine.awaitTermination(Duration.ofSeconds(5))
            } finally {
                server.stop()
            }
        }
    }
}
