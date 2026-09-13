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

import org.apache.jmeter.samplers.ResponseDecoder
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
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class GzipDecoderTest {
    private val decoder = GzipDecoder()
    // Exercise the original ResponseDecoder default method as a parity oracle on this JVM.
    private val baseline = object : ResponseDecoder {
        override val encodings = listOf("gzip")
        override fun decodeStream(input: InputStream): InputStream = GZIPInputStream(input)
    }

    @Test
    fun testGetEncodings() {
        assertEquals(listOf("gzip", "x-gzip"), decoder.encodings, "encodings")
    }

    @Test
    fun testGetPriority() {
        assertEquals(0, decoder.priority, "Default priority should be 0")
    }

    @Test
    fun testDecodeGzipData() {
        val originalText = "Hello, World! Gzip UTF-8: café, 日本語, 🌍."
        val originalData = originalText.toByteArray(Charsets.UTF_8)

        // Compress data with gzip
        val compressed = compressGzip(originalData)

        // Decode
        val decoded = decoder.decode(compressed)

        assertArrayEquals(originalData, decoded, "Decoded data should match original")
        assertEquals(originalText, decoded.toString(Charsets.UTF_8), "Decoded text should match original")
    }

    @Test
    fun testDecodeEmptyData() {
        val emptyCompressed = compressGzip(ByteArray(0))
        val decoded = decoder.decode(emptyCompressed)

        assertEquals(0, decoded.size, "Empty data should decode to empty array")
    }

    @Test
    fun testDecodeInvalidData() {
        val invalidData = "This is not gzip compressed data".toByteArray(Charsets.UTF_8)

        assertThrows(IOException::class.java) {
            decoder.decode(invalidData)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 31, 32, 33, 1024, 1048575, 1048576, 1048577, 2097152])
    fun testDecodeAcrossAllocationCap(size: Int) {
        val original = ByteArray(size) { (it * 31).toByte() }
        assertArrayEquals(original, decoder.decode(compressGzip(original)))
    }

    @ParameterizedTest
    @ValueSource(longs = [0, 1, 31, 32, 1024, 1048575, 1048576, 1048577, 2147483648, 4294967295])
    fun testTrailingDataIsOnlyAnAllocationHint(hint: Long) {
        // Non-gzip trailing data is accepted by GZIPInputStream. Its last four bytes
        // can understate or overstate the output size, or exceed the signed Int range.
        val trailing = byteArrayOf(0, 0) + ByteArray(4) { (hint ushr (8 * it)).toByte() }
        for (size in listOf(1, 65536)) {
            val original = ByteArray(size) { it.toByte() }
            val compressed = compressGzip(original) + trailing
            assertArrayEquals(baseline.decode(compressed), decoder.decode(compressed))
            assertArrayEquals(original, decoder.decode(compressed))
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [1, 1048576, 4294967295])
    fun testInvalidHeaderWithAllocationHint(hint: Long) {
        val invalid = ByteArray(14) + ByteArray(4) { (hint ushr (8 * it)).toByte() }
        val expected = assertThrows(IOException::class.java) { baseline.decode(invalid) }
        val actual = assertThrows(IOException::class.java) { decoder.decode(invalid) }
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 1048576])
    fun testConcatenatedMembersGrowBeyondLastMemberHint(lastSize: Int) {
        val first = ByteArray(65536) { it.toByte() }
        val last = ByteArray(lastSize) { (it * 7).toByte() }
        assertArrayEquals(first + last, decoder.decode(compressGzip(first) + compressGzip(last)))
    }

    @Test
    fun testCorruptTrailersAreValidatedIncludingLaterMembers() {
        val valid = compressGzip(ByteArray(1024) { it.toByte() })
        for (offset in listOf(8, 4)) { // CRC32 and ISIZE respectively
            val corrupt = valid.copyOf()
            corrupt[corrupt.size - offset] = (corrupt[corrupt.size - offset].toInt() xor 1).toByte()
            for (input in listOf(corrupt, valid + corrupt)) {
                val expected = assertThrows(IOException::class.java) { baseline.decode(input) }
                val actual = assertThrows(IOException::class.java) { decoder.decode(input) }
                assertEquals(expected.javaClass, actual.javaClass)
                assertEquals(expected.message, actual.message)
            }
        }
    }

    @Test
    fun testTruncationAndPartialFollowingMembersMatchDefaultDecoder() {
        val valid = compressGzip("A gzip member with a trailer".toByteArray())
        for (length in 0 until valid.size) {
            val truncated = valid.copyOf(length)
            for (input in listOf(truncated, valid + truncated)) {
                val expected = runCatching { baseline.decode(input) }
                if (expected.isSuccess) {
                    assertArrayEquals(expected.getOrThrow(), decoder.decode(input), "length=$length")
                } else {
                    val actual = assertThrows(IOException::class.java) { decoder.decode(input) }
                    assertEquals(expected.exceptionOrNull()!!.javaClass, actual.javaClass, "length=$length")
                    assertEquals(expected.exceptionOrNull()!!.message, actual.message, "length=$length")
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1024, 1048577])
    fun testConcurrentDecodesOwnTheirOutput(size: Int) {
        val original = ByteArray(size) { it.toByte() }
        val compressed = compressGzip(original)
        val savedInput = compressed.copyOf()
        val pool = Executors.newFixedThreadPool(4)
        val outputs = try {
            pool.invokeAll(List(32) { Callable { decoder.decode(compressed) } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        outputs.forEach { assertArrayEquals(original, it) }
        for (output in outputs.drop(1)) {
            assertNotSame(outputs.first(), output)
        }
        outputs.first().fill(0)
        outputs.drop(1).forEach { assertArrayEquals(original, it) }
        assertArrayEquals(original, decoder.decode(compressed))
        assertArrayEquals(savedInput, compressed)
    }

    @Test
    fun testDecodeStreamClosesUnderlyingInput() {
        var closed = false
        val input = object : ByteArrayInputStream(compressGzip(byteArrayOf(1, 2, 3))) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        decoder.decodeStream(input).use { assertArrayEquals(byteArrayOf(1, 2, 3), it.readAllBytes()) }
        assertTrue(closed)
    }

    /**
     * Helper method to compress data with gzip
     */
    private fun compressGzip(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzipOut ->
            gzipOut.write(data)
        }
        return baos.toByteArray()
    }
}
