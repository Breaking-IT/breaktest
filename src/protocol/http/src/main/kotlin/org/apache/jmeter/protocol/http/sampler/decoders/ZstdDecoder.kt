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

import com.google.auto.service.AutoService
import io.airlift.compress.MalformedInputException
import io.airlift.compress.zstd.ZstdInputStream
import org.apache.jmeter.samplers.ResponseDecoder
import org.apiguardian.api.API
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Decoder for Zstandard compressed response data.
 * Handles "zstd" content encoding.
 *
 * @since 1.0.0
 */
@AutoService(ResponseDecoder::class)
@API(status = API.Status.INTERNAL, since = "1.0.0")
public class ZstdDecoder : ResponseDecoder {
    override val encodings: List<String>
        get() = listOf("zstd")

    override fun decodeStream(input: InputStream): InputStream {
        return object : FilterInputStream(ZstdInputStream(input)) {
            override fun read(): Int =
                try {
                    super.read()
                } catch (e: MalformedInputException) {
                    throw IOException(e)
                }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                try {
                    super.read(b, off, len)
                } catch (e: MalformedInputException) {
                    throw IOException(e)
                }
        }
    }
}
