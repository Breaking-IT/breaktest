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

package org.apache.jmeter.samplers.decoders

import org.apache.jorphan.io.DirectAccessByteArrayOutputStream
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class DeflateDecoderTest {
    private val decoder = DeflateDecoder()

    @Test
    fun testGetEncodings() {
        assertEquals(listOf("deflate"), decoder.encodings, "encodings")
    }

    @Test
    fun testGetPriority() {
        assertEquals(0, decoder.priority, "Default priority should be 0")
    }

    @Test
    fun testDecodeDeflateWithZlibWrapper() {
        val originalText = "Deflate UTF-8: café, 日本語, 🌍."
        val originalData = originalText.toByteArray(Charsets.UTF_8)

        // Compress with ZLIB wrapper (default)
        val compressed = compressDeflate(originalData, nowrap = false)

        // Decode
        val decoded = decoder.decode(compressed)

        assertArrayEquals(originalData, decoded, "Decoded data should match original (ZLIB wrapper)")
    }

    @Test
    fun testDecodeDeflateRaw() {
        val originalText = "Testing raw deflate without ZLIB wrapper."
        val originalData = originalText.toByteArray(Charsets.UTF_8)

        // Compress with NO_WRAP (raw deflate)
        val compressed = compressDeflate(originalData, nowrap = true)

        // Decode - should fallback to raw deflate
        val decoded = decoder.decode(compressed)

        assertArrayEquals(originalData, decoded, "Decoded data should match original (raw deflate)")
    }

    @Test
    fun testDecodeEmptyData() {
        val emptyCompressed = compressDeflate(ByteArray(0), nowrap = false)
        val decoded = decoder.decode(emptyCompressed)

        assertEquals(0, decoded.size, "Empty data should decode to empty array")
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 16383, 16384, 16385, 65536, 262144, 1048576])
    fun testBulkReadBoundariesWithBothWrappers(size: Int) {
        val original = ByteArray(size) { (it * 31).toByte() }
        for (nowrap in listOf(false, true)) {
            assertArrayEquals(original, decoder.decode(compressDeflate(original, nowrap)))
        }
    }

    @Test
    fun testMalformedAndTrailingDataMatchOriginalAlgorithm() {
        for (nowrap in listOf(false, true)) {
            val compressed = compressDeflate(ByteArray(1024) { it.toByte() }, nowrap)
            for (length in compressed.indices) {
                assertEquivalent(compressed.copyOf(length))
            }
            // Exercise header/body corruption and, for zlib, its Adler-32 trailer.
            for (index in listOf(0, 2, compressed.size / 2, compressed.lastIndex)) {
                val corrupt = compressed.copyOf()
                corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
                assertEquivalent(corrupt)
            }
            assertEquivalent(compressed + byteArrayOf(1, 2, 3))
            assertEquivalent(compressed + compressed)
        }
    }

    @Test
    fun testConcurrentDecodesOwnTheirOutput() {
        val original = ByteArray(65536) { it.toByte() }
        val pool = Executors.newFixedThreadPool(4)
        try {
            for (nowrap in listOf(false, true)) {
                val compressed = compressDeflate(original, nowrap)
                val savedInput = compressed.copyOf()
                val outputs = pool.invokeAll(List(16) { Callable { decoder.decode(compressed) } }).map { it.get() }
                outputs.forEach { assertArrayEquals(original, it) }
                outputs.drop(1).forEach { assertNotSame(outputs.first(), it) }
                outputs.first().fill(0)
                outputs.drop(1).forEach { assertArrayEquals(original, it) }
                assertArrayEquals(original, decoder.decode(compressed))
                assertArrayEquals(savedInput, compressed)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun testStreamClosesInputAfterSuccessAndFailure() {
        val original = "Streaming deflate".toByteArray()
        val compressed = compressDeflate(original, nowrap = false)
        for (data in listOf(compressed, compressed.copyOf(compressed.size - 1))) {
            var closed = false
            val input = object : ByteArrayInputStream(data) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
            if (data === compressed) {
                decoder.decodeStream(input).use { assertArrayEquals(original, it.readAllBytes()) }
            } else {
                assertThrows(IOException::class.java) { decoder.decodeStream(input).use { it.readAllBytes() } }
            }
            assertTrue(closed)
        }
    }

    private fun assertEquivalent(input: ByteArray) {
        val expected = runCatching { originalDecode(input) }
        if (expected.isSuccess) {
            assertArrayEquals(expected.getOrThrow(), decoder.decode(input))
        } else {
            val actual = assertThrows(IOException::class.java) { decoder.decode(input) }
            assertEquals(expected.exceptionOrNull()!!.javaClass, actual.javaClass)
            assertEquals(expected.exceptionOrNull()!!.message, actual.message)
        }
    }

    // Original buffering and fallback behavior; explicitly release the oracle's inflaters.
    private fun originalDecode(input: ByteArray): ByteArray {
        fun inflate(nowrap: Boolean): ByteArray {
            val inflater = Inflater(nowrap)
            try {
                val out = DirectAccessByteArrayOutputStream()
                InflaterInputStream(ByteArrayInputStream(input), inflater).use { it.transferTo(out) }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }
        return try {
            inflate(false)
        } catch (e: IOException) {
            inflate(true)
        }
    }

    /**
     * Helper method to compress data with deflate
     * @param data the data to compress
     * @param nowrap if true, uses raw deflate (no ZLIB wrapper)
     */
    private fun compressDeflate(data: ByteArray, nowrap: Boolean): ByteArray {
        val baos = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, nowrap)
        try {
            DeflaterOutputStream(baos, deflater).use { it.write(data) }
        } finally {
            deflater.end()
        }
        return baos.toByteArray()
    }
}
