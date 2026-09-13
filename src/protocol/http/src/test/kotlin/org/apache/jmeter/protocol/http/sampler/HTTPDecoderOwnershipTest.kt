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

package org.apache.jmeter.protocol.http.sampler

import io.airlift.compress.zstd.ZstdOutputStream
import org.apache.jmeter.protocol.http.sampler.decoders.BrotliDecoder
import org.apache.jmeter.protocol.http.sampler.decoders.ZstdDecoder
import org.apache.jmeter.samplers.ResponseDecoder
import org.apache.jmeter.samplers.ResponseDecoderRegistry
import org.apache.jmeter.samplers.SampleResult
import org.apache.jmeter.samplers.decoders.DeflateDecoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.DeflaterOutputStream

class HTTPDecoderOwnershipTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun checksumClosesDecoderAfterSuccessAndFailure(truncated: Boolean) {
        val original = "café 日本語 🌍 ".repeat(2048).toByteArray(Charsets.UTF_8)
        val deflate = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write(original) }
        }.toByteArray()
        val brotli = Base64.getDecoder().decode("G/+nAARqcqTH+vvtjQo4TjiU6pbbqvqwcsuZSj8B+KkGAA==")
        val zstd = ByteArrayOutputStream().apply {
            ZstdOutputStream(this).use { it.write(original) }
        }.toByteArray()
        for ((decoder, compressed) in listOf(DeflateDecoder() to deflate, BrotliDecoder() to brotli, ZstdDecoder() to zstd)) {
            var decoderClosed = false
            var inputClosed = false
            val encoding = "test-decoder-ownership-${UUID.randomUUID()}"
            ResponseDecoderRegistry.registerDecoder(object : ResponseDecoder {
                override val encodings = listOf(encoding)
                override fun decodeStream(input: InputStream): InputStream =
                    object : FilterInputStream(decoder.decodeStream(input)) {
                        override fun close() {
                            decoderClosed = true
                            super.close()
                        }
                    }
            })
            val data = if (truncated) compressed.copyOf(compressed.size - 1) else compressed
            val input = object : ByteArrayInputStream(data) {
                override fun close() {
                    inputClosed = true
                    super.close()
                }
            }
            val sampler = HTTPNullSampler()
            sampler.setResponseProcessingMode(HTTPSamplerBase.ResponseProcessingMode.CHECKSUM_DECODED_MD5)
            val result = SampleResult().apply { sampleStart() }
            if (truncated) {
                assertThrows(IOException::class.java) {
                    sampler.readResponse(result, input, data.size.toLong(), encoding)
                }
            } else {
                sampler.readResponse(result, input, data.size.toLong(), encoding)
                val expected = MessageDigest.getInstance("MD5").digest(original)
                    .joinToString("") { "%02x".format(it) }
                assertEquals(expected, result.responseData.toString(Charsets.US_ASCII))
            }
            assertTrue(decoderClosed, "The decoder wrapper must close, not only the HTTP input")
            assertTrue(inputClosed)
        }
    }
}
