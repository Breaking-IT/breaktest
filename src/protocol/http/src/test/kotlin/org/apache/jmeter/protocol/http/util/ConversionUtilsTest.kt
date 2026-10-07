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

package org.apache.jmeter.protocol.http.util

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class ConversionUtilsTest {
    companion object {
        @JvmStatic
        fun partiallyEncodedUrls() = listOf(
            arguments(
                "/api?@a1=%27%2FLists%2F\${fileName}%27&SortDir=Desc",
                "/api?@a1=%27%2FLists%2F$%7BfileName%7D%27&SortDir=Desc"
            ),
            arguments("/a%2fb c?q=%26%3D+hello world#f%23 x", "/a%2fb%20c?q=%26%3D+hello%20world#f%23%20x"),
            arguments("/a%252F b?q=%2527", "/a%252F%20b?q=%2527"),
            arguments("/a% b?q=%2&bad=%GG&ok=%20", "/a%25%20b?q=%252&bad=%25GG&ok=%20"),
            arguments("/already%2Fencoded?q=%27value%27", "/already%2Fencoded?q=%27value%27"),
        )

        @JvmStatic
        fun percentEncodeValues() =
            listOf(
                arguments("hello", "hello"),
                arguments("%", "%"),
                arguments("\"", "%22"),
                arguments(" ", " "),
                arguments("\r", "%0D"),
                arguments("\n", "%0A"),
                arguments("\r\r\n\n", "%0D%0D%0A%0A"),
                arguments("😃", "😃"),
                arguments("comment ça va?", "comment ça va?"),
                arguments("quoted \"content\"", "quoted %22content%22"),
            )

        @JvmStatic
        fun htmlEntityValues() =
            listOf(
                arguments("Hello, 😃, world", "Hello, 😃, world", StandardCharsets.UTF_8),
                arguments("Hello, 😃, world", "Hello, &#128515, world", StandardCharsets.ISO_8859_1),
                arguments("丈, 😃, and नि", "丈, 😃, and नि", StandardCharsets.UTF_8),
                arguments("丈, 😃, and नि", "&#19976, &#128515, and &#2344&#2367", StandardCharsets.ISO_8859_1),
            )
    }

    @ParameterizedTest
    @MethodSource("partiallyEncodedUrls")
    fun urlComponentsPreserveEscapes(input: String, expected: String) {
        assertEquals("https://example.test$expected", ConversionUtils.toUrl("https", "example.test", input).toString())
        assertEquals("https://example.test:8443$expected", ConversionUtils.toUrl("https", "example.test", 8443, input).toString())
    }

    @ParameterizedTest
    @MethodSource("partiallyEncodedUrls")
    fun relativeUrlsPreserveEscapes(input: String, expected: String) {
        val base = ConversionUtils.toUrl("https://example.test/base/")
        assertEquals("https://example.test$expected", ConversionUtils.toUrl(base, input).toString())
        assertEquals("https://example.test/base$expected", ConversionUtils.toUrl(base, input.removePrefix("/")).toString())
    }

    @ParameterizedTest
    @MethodSource("partiallyEncodedUrls")
    fun postSamplerPreservesEscapes(input: String, expected: String) {
        val sampler = HTTPSamplerProxy()
        sampler.protocol = "https"
        sampler.domain = "example.test"
        sampler.method = "POST"
        sampler.path = input
        assertEquals("https://example.test$expected", sampler.url.toString())
    }

    @ParameterizedTest
    @MethodSource("percentEncodeValues")
    fun percentEncode(input: String, output: String) {
        assertEquals(output, ConversionUtils.percentEncode(input)) {
            "ConversionUtils.percentEncode($input)"
        }
    }

    @ParameterizedTest
    @MethodSource("htmlEntityValues")
    fun htmlEntities(input: String, output: String, charset: Charset) {
        assertEquals(output, ConversionUtils.encodeWithEntities(input, charset))
    }
}
