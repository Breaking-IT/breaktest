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

import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder

fun RequestPatternBuilder.withRequestBody(
    httpImplementation: String,
    body: String
) = apply {
    // normalize line endings to CRLF
    val normalizedBody = body.replace("\r\n", "\n").replace("\n", "\r\n")
    val expectedBody = if (httpImplementation == HTTPSamplerFactory.IMPL_HTTP_CLIENT5) {
        Regex("(Content-Disposition: [^\r\n]+\r\n)(Content-Type: [^\r\n]+\r\n)(Content-Transfer-Encoding: [^\r\n]+\r\n)")
            .replace(normalizedBody) {
                "${it.groupValues[3]}${it.groupValues[1]}${it.groupValues[2]}"
            }
    } else {
        normalizedBody
    }
    withRequestBody(
        matching(
            expectedBody
                .replace(PostWriter.BOUNDARY, "[^ \\n\\r]{1,69}?")
        )
    )
}
