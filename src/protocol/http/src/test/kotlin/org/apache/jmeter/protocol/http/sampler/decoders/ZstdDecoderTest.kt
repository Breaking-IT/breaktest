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

package org.apache.jmeter.protocol.http.sampler.decoders

import io.airlift.compress.zstd.ZstdOutputStream
import org.apache.jmeter.samplers.ResponseDecoderRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class ZstdDecoderTest {
    private val decoder = ZstdDecoder()

    @Test
    fun testGetEncodings() {
        assertEquals(listOf("zstd"), decoder.encodings, "encodings")
    }

    @Test
    fun testGetPriority() {
        assertEquals(0, decoder.priority, "Default priority should be 0")
    }

    @Test
    fun testRegisteredByServiceLoader() {
        assertTrue(ResponseDecoderRegistry.hasDecoder("zstd"), "zstd decoder should be registered")
    }

    @Test
    fun testDecodeZstdData() {
        val original = "Hello World from zstd".toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream().use { output ->
            ZstdOutputStream(output).use { it.write(original) }
            output.toByteArray()
        }

        val decoded = decoder.decode(compressed)

        assertEquals("Hello World from zstd", decoded.toString(Charsets.UTF_8), "Decoded text should match original")
    }

    @Test
    fun testDecodeInvalidData() {
        val invalidData = "This is not zstd compressed data".toByteArray(Charsets.UTF_8)

        assertThrows(IOException::class.java) {
            decoder.decode(invalidData)
        }
    }
}
