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
import org.apache.jorphan.io.DirectAccessByteArrayOutputStream
import org.apiguardian.api.API
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

// Limit speculative allocation from an unverified trailer; decoded output can grow beyond this.
private const val MAX_INITIAL_SIZE = 1024L * 1024

/**
 * Decoder for gzip compressed response data.
 * Handles both "gzip" and "x-gzip" content encodings.
 *
 * @since 6.0.0
 */
@API(status = API.Status.INTERNAL, since = "6.0.0")
public class GzipDecoder : ResponseDecoder {
    override val encodings: List<String>
        get() = listOf("gzip", "x-gzip")

    override fun decode(compressed: ByteArray): ByteArray {
        // ISIZE is only a hint: concatenated members and trailing bytes can make
        // it differ from the decoded length. Bound allocation, then read to EOF
        // as usual so GZIPInputStream still validates each decoded member's CRC/ISIZE.
        var hint = 0L
        if (compressed.size >= 18) { // Fixed header (10) plus trailer (8); not a validity check.
            for (i in 0 until 4) {
                hint = hint or ((compressed[compressed.size - 4 + i].toLong() and 0xff) shl (8 * i))
            }
        }
        val initialSize = if (hint in 1L..MAX_INITIAL_SIZE) hint.toInt() else 32
        val out = DirectAccessByteArrayOutputStream(initialSize)
        decodeStream(ByteArrayInputStream(compressed)).use {
            it.transferTo(out)
        }
        return out.toByteArray()
    }

    override fun decodeStream(input: InputStream): InputStream {
        return GZIPInputStream(input)
    }
}
