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

import org.apache.jmeter.samplers.ResponseDecoder
import org.apache.jmeter.samplers.ResponseDecoderRegistry
import org.brotli.dec.BrotliInputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Basic tests for BrotliDecoder.
 * Full integration tests for brotli decompression are covered by HTTP sampler tests.
 */
class BrotliDecoderTest {
    private val decoder = BrotliDecoder()
    private val original = object : ResponseDecoder {
        override val encodings = listOf("br")
        override fun decodeStream(input: InputStream): InputStream = BrotliInputStream(input)
    }
    private val utf8 = "café 日本語 🌍 ".repeat(2048).toByteArray(Charsets.UTF_8)
    // brotli 1.2.0 -q 6 -w 22, applied to the UTF-8 bytes above.
    private val utf8Compressed = Base64.getDecoder().decode("G/+nAARqcqTH+vvtjQo4TjiU6pbbqvqwcsuZSj8B+KkGAA==")

    @Test
    fun testGetEncodings() {
        assertEquals(listOf("br"), decoder.encodings, "encodings")
    }

    @Test
    fun testGetPriority() {
        assertEquals(0, decoder.priority, "Default priority should be 0")
    }

    @Test
    fun testDecodeBrotliData() {
        // Pre-compressed "Hello World" with Brotli
        // Generated using: printf 'Hello World' | brotli | base64
        val compressed = Base64.getDecoder().decode("DwWASGVsbG8gV29ybGQD")

        val decoded = decoder.decode(compressed)

        assertEquals("Hello World", decoded.toString(Charsets.UTF_8), "Decoded text should match original")
    }

    @Test
    fun testDecodeInvalidData() {
        val invalidData = "This is not brotli compressed data".toByteArray(Charsets.UTF_8)

        assertThrows(IOException::class.java) {
            decoder.decode(invalidData)
        }
    }

    @Test
    fun testRegisteredByServiceLoader() {
        assertTrue(ResponseDecoderRegistry.hasDecoder("br"))
        assertArrayEquals(utf8, ResponseDecoderRegistry.decode("BR", utf8Compressed))
    }

    @ParameterizedTest
    @CsvSource(
        "0, Ow==",
        "1, CwCAQQM=",
        "16383, G/4/ACSC4rFAcG8AAA==",
        "16384, G/8/ACSC4rFAcm8AAA==",
        "16385, GwBAACSC4rFAdG8AAA==",
        "65536, G///ACSC4rFAcu8BAA==",
        "1048576, W///D0AiKB4LJPf+AQ=="
    )
    fun testBulkReadBoundaries(size: Int, encoded: String) {
        // brotli 1.2.0 -q 6 -w 22, applied to size repetitions of ASCII 'A'.
        val compressed = Base64.getDecoder().decode(encoded)
        assertArrayEquals(ByteArray(size) { 'A'.code.toByte() }, decoder.decode(compressed))
    }

    @Test
    fun testMalformedAndTrailingDataMatchOriginalAlgorithm() {
        for (length in utf8Compressed.indices) {
            assertEquivalent(utf8Compressed.copyOf(length))
        }
        for (index in utf8Compressed.indices) {
            val corrupt = utf8Compressed.copyOf()
            corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            assertEquivalent(corrupt)
        }
        assertEquivalent(utf8Compressed + byteArrayOf(1, 2, 3))
        assertEquivalent(utf8Compressed + utf8Compressed)
    }

    @Test
    fun testConcurrentDecodesOwnTheirOutput() {
        val savedInput = utf8Compressed.copyOf()
        val pool = Executors.newFixedThreadPool(4)
        val outputs = try {
            pool.invokeAll(List(16) { Callable { decoder.decode(utf8Compressed) } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        outputs.forEach { assertArrayEquals(utf8, it) }
        outputs.drop(1).forEach { assertNotSame(outputs.first(), it) }
        outputs.first().fill(0)
        outputs.drop(1).forEach { assertArrayEquals(utf8, it) }
        assertArrayEquals(utf8, decoder.decode(utf8Compressed))
        assertArrayEquals(savedInput, utf8Compressed)
    }

    @Test
    fun testStreamSupportsSingleByteAndBulkReadsAndClosesInput() {
        var closed = false
        val input = object : ByteArrayInputStream(utf8Compressed) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val output = ByteArrayOutputStream()
        decoder.decodeStream(input).use {
            repeat(8) { _ -> output.write(it.read()) }
            output.write(it.readNBytes(5))
            it.transferTo(output)
        }
        assertArrayEquals(utf8, output.toByteArray())
        assertTrue(closed)
    }

    private fun assertEquivalent(input: ByteArray) {
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
