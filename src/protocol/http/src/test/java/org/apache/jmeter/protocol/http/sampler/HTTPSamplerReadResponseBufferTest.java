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

package org.apache.jmeter.protocol.http.sampler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

/**
 * Tests response-body buffer sizing in
 * {@link HTTPSamplerBase#readResponse(SampleResult, java.io.InputStream, long, String)}:
 * a known Content-Length within {@code httpsampler.max_preallocate_size} is allocated
 * exactly, unknown or oversized lengths fall back to the growable default buffer.
 */
public class HTTPSamplerReadResponseBufferTest {

    private static final int FALLBACK = HTTPSamplerBase.storedBodyInitialBufferSize(0, false);

    @Test
    public void knownLengthIsPreallocatedExactly() {
        assertEquals(100, HTTPSamplerBase.storedBodyInitialBufferSize(100, false));
        assertEquals(100_000, HTTPSamplerBase.storedBodyInitialBufferSize(100_000, false),
                "lengths above the old 65KiB cap must be preallocated exactly");
        assertEquals(16 * 1024 * 1024, HTTPSamplerBase.storedBodyInitialBufferSize(16 * 1024 * 1024, false),
                "the default preallocation limit itself is still preallocated");
    }

    @Test
    public void unknownLengthUsesFallbackBuffer() {
        assertEquals(FALLBACK, HTTPSamplerBase.storedBodyInitialBufferSize(0, false));
        assertEquals(FALLBACK, HTTPSamplerBase.storedBodyInitialBufferSize(-1, false));
    }

    @Test
    public void untrustedHugeLengthUsesFallbackBuffer() {
        assertEquals(FALLBACK, HTTPSamplerBase.storedBodyInitialBufferSize(16 * 1024 * 1024 + 1L, false),
                "a Content-Length above the preallocation limit must not be allocated up front");
        assertEquals(FALLBACK, HTTPSamplerBase.storedBodyInitialBufferSize(Long.MAX_VALUE, false),
                "a bogus huge Content-Length must not overflow or allocate");
    }

    private static byte[] body(int size) {
        byte[] body = new byte[size];
        for (int i = 0; i < size; i++) {
            body[i] = (byte) i;
        }
        return body;
    }

    private static byte[] readResponse(byte[] body, long declaredLength) throws IOException {
        HTTPSamplerBase sampler = new HTTPNullSampler();
        SampleResult result = new SampleResult();
        result.sampleStart();
        sampler.readResponse(result, new ByteArrayInputStream(body), declaredLength, null);
        return result.getResponseData();
    }

    @Test
    public void largeBodyWithKnownLengthIsStoredCompletely() throws IOException {
        byte[] body = body(200_000); // above the 65KiB growable default, below the preallocation limit
        assertArrayEquals(body, readResponse(body, body.length));
    }

    @Test
    public void bodyWithUnknownLengthIsStoredCompletely() throws IOException {
        byte[] body = body(200_000);
        assertArrayEquals(body, readResponse(body, 0));
    }

    @Test
    public void bodyShorterThanDeclaredLengthIsStoredCompletely() throws IOException {
        // A lying server: preallocation is too large, but content must be exact
        byte[] body = body(100_000);
        assertArrayEquals(body, readResponse(body, 300_000));
    }
}
