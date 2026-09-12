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
import org.apache.jmeter.samplers.ResponseDecoder
import org.apache.jmeter.samplers.ResponseDecoderRegistry
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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

    private fun compress(data: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        ZstdOutputStream(this).use { it.write(data) }
    }.toByteArray()

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 8191, 8192, 8193, 16383, 16384, 16385, 131071, 131072, 131073, 1048576])
    fun testBulkReadBoundaries(size: Int) {
        val data = ByteArray(size) { (it * 31).toByte() }
        assertArrayEquals(data, decoder.decode(compress(data)))
    }

    @Test
    fun testMalformedAndConcatenatedFramesMatchOriginalAlgorithm() {
        val original = object : ResponseDecoder {
            override val encodings = listOf("zstd")
            override fun decodeStream(input: InputStream): InputStream = decoder.decodeStream(input)
        }
        val compressed = compress("café 日本語 🌍 ".repeat(2048).toByteArray(Charsets.UTF_8))
        val inputs = compressed.indices.map { compressed.copyOf(it) } +
            compressed.indices.map { index -> compressed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() } } +
            listOf(compressed + byteArrayOf(1, 2, 3), compressed + compressed)
        for (input in inputs) {
            val expected = runCatching { original.decode(input) }
            if (expected.isSuccess) {
                assertArrayEquals(expected.getOrThrow(), decoder.decode(input))
            } else {
                val actual = assertThrows(IOException::class.java) { decoder.decode(input) }
                assertEquals(expected.exceptionOrNull()!!.javaClass, actual.javaClass)
                assertEquals(expected.exceptionOrNull()!!.message, actual.message)
            }
        }
    }

    @Test
    fun testConcurrentOutputsAreIndependent() {
        val data = "café 日本語 🌍 ".repeat(4096).toByteArray(Charsets.UTF_8)
        val compressed = compress(data)
        val saved = compressed.copyOf()
        val pool = Executors.newFixedThreadPool(4)
        val outputs = try {
            pool.invokeAll(List(16) { Callable { decoder.decode(compressed) } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        outputs.forEach { assertArrayEquals(data, it) }
        outputs.drop(1).forEach { assertNotSame(outputs.first(), it) }
        outputs.first().fill(0)
        outputs.drop(1).forEach { assertArrayEquals(data, it) }
        assertArrayEquals(saved, compressed)
    }

    @Test
    fun testMixedStreamingReadsAndClosure() {
        val data = "café 日本語 🌍 ".repeat(2048).toByteArray(Charsets.UTF_8)
        var closed = false
        val input = object : ByteArrayInputStream(compress(data)) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val output = ByteArrayOutputStream()
        val stream = decoder.decodeStream(input)
        stream.use {
            repeat(7) { _ -> output.write(it.read()) }
            output.write(it.readNBytes(13))
            it.transferTo(output)
        }
        assertArrayEquals(data, output.toByteArray())
        assertTrue(closed)
        assertThrows(IOException::class.java) { stream.read() }
    }
}
