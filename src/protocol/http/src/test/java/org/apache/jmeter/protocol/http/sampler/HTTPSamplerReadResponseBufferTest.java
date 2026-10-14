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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

/**
 * Tests response-body buffer sizing in
 * {@link HTTPSamplerBase#readResponse(SampleResult, java.io.InputStream, long, String)}:
 * a known Content-Length within {@code httpsampler.max_preallocate_size} is allocated
 * exactly, unknown or oversized lengths start from the first nonempty read,
 * capped by the configured growable default buffer.
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

    private static final int MAX_BUFFER = 65 * 1024;
    private static final int PREALLOC = 16 * 1024 * 1024;

    @Test
    public void truncationCapsKnownLengthBuffer() {
        // Content-Length 1MiB but truncation at 64KiB -> only 64KiB is stored, so preallocate that
        assertEquals(64 * 1024,
                HTTPSamplerBase.storedBodyInitialBufferSize(1024 * 1024, false, 64 * 1024, MAX_BUFFER, PREALLOC));
    }

    @Test
    public void chunkedResponseTruncationCapsFallback() {
        // Unknown length (chunked) with truncation below the growable default -> limit-sized fallback
        assertEquals(32 * 1024,
                HTTPSamplerBase.storedBodyInitialBufferSize(0, false, 32 * 1024, MAX_BUFFER, PREALLOC),
                "chunked + truncation caps the fallback before first-read sizing");
        assertEquals(0,
                HTTPSamplerBase.storedBodyInitialBufferSize(-1, false, 32 * 1024, MAX_BUFFER, PREALLOC) - 32 * 1024);
    }

    @Test
    public void chunkedResponseWithoutTruncationUsesGrowableBuffer() {
        assertEquals(MAX_BUFFER,
                HTTPSamplerBase.storedBodyInitialBufferSize(0, false, 0, MAX_BUFFER, PREALLOC),
                "no truncation leaves the base fallback unchanged before first-read sizing");
    }

    @Test
    public void recordingDisablesTruncationCap() {
        // While recording, truncation is bypassed, so a chunked response uses the growable buffer
        assertEquals(MAX_BUFFER,
                HTTPSamplerBase.storedBodyInitialBufferSize(0, true, 32 * 1024, MAX_BUFFER, PREALLOC));
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

    @Test
    public void firstReadBoundsUnknownAndUntrustedLengthAllocation() {
        assertEquals(123, HTTPSamplerBase.storedBodyInitialBufferSize(
                -1, false, 0, MAX_BUFFER, PREALLOC, 123, false));
        assertEquals(7, HTTPSamplerBase.storedBodyInitialBufferSize(
                Long.MAX_VALUE, false, 0, MAX_BUFFER, PREALLOC, 7, false));
        assertEquals(32, HTTPSamplerBase.storedBodyInitialBufferSize(
                0, false, 32, MAX_BUFFER, PREALLOC, 100, false));
        assertEquals(100, HTTPSamplerBase.storedBodyInitialBufferSize(
                0, true, 32, MAX_BUFFER, PREALLOC, 100, false), "recording bypasses truncation");
        assertEquals(16, HTTPSamplerBase.storedBodyInitialBufferSize(
                -1, false, 0, 16, PREALLOC, 100, false), "configured initial buffer cap is retained");
    }

    @Test
    public void fullFirstReadKeepsFallbackForZeroCopyTruncation() {
        assertEquals(32 * 1024, HTTPSamplerBase.storedBodyInitialBufferSize(
                -1, false, 32 * 1024, MAX_BUFFER, PREALLOC, 8192, true),
                "a large chunked body reaches the truncation limit, so allocate it exactly");
        assertEquals(MAX_BUFFER, HTTPSamplerBase.storedBodyInitialBufferSize(
                -1, false, 0, MAX_BUFFER, PREALLOC, 8192, true));
        assertEquals(MAX_BUFFER, HTTPSamplerBase.storedBodyInitialBufferSize(
                Long.MAX_VALUE, false, 0, MAX_BUFFER, PREALLOC, 8192, true));
    }

    @Test
    public void firstReadDoesNotReduceKnownLengthPreallocation() {
        assertEquals(100_000, HTTPSamplerBase.storedBodyInitialBufferSize(
                100_000, false, 0, MAX_BUFFER, PREALLOC, 7, false));
        assertEquals(32, HTTPSamplerBase.storedBodyInitialBufferSize(
                100_000, false, 32, MAX_BUFFER, PREALLOC, 7, false));
        assertEquals(100_000, HTTPSamplerBase.storedBodyInitialBufferSize(
                100_000, true, 32, MAX_BUFFER, PREALLOC, 7, false));
    }

    @Test
    public void emptySmallAndIncorrectlyDeclaredBodiesRemainExact() throws IOException {
        for (long length : new long[]{-1, 0, 1, 100_000, Long.MAX_VALUE}) {
            assertArrayEquals(new byte[0], readResponse(new byte[0], length));
            assertArrayEquals(body(1), readResponse(body(1), length));
            assertArrayEquals(body(123), readResponse(body(123), length));
        }
        assertArrayEquals(body(20_000), readResponse(body(20_000), 7),
                "bodies longer than the declaration must grow without losing bytes");
    }

    @Test
    public void unknownLengthHandlesShortAndZeroReads() throws IOException {
        byte[] original = body(20_000);
        InputStream input = new ByteArrayInputStream(original) {
            private boolean zeroRead = true;

            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                if (zeroRead) {
                    zeroRead = false;
                    return 0;
                }
                zeroRead = true;
                return super.read(bytes, offset, Math.min(length, 7));
            }
        };
        SampleResult result = new SampleResult();
        result.sampleStart();
        new HTTPNullSampler().readResponse(result, input, -1, null);
        assertArrayEquals(original, result.getResponseData());
    }

    @Test
    public void laterResponsesCannotOverwriteEarlierStoredBodies() throws IOException {
        byte[] first = readResponse(body(7), -1);
        byte[] second = readResponse(body(20_000), -1);
        second[0] = 42;
        assertArrayEquals(body(7), first);
    }

    @Test
    public void compressedUnknownLengthBodyRetainsEncodingAndDecodesExactly() throws IOException {
        byte[] original = body(20_000);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(original);
        }
        SampleResult result = new SampleResult();
        result.sampleStart();
        new HTTPNullSampler().readResponse(result, new ByteArrayInputStream(compressed.toByteArray()), -1, "gzip");
        assertTrue(result.isResponseDataCompressed());
        assertArrayEquals(original, result.getResponseData());
    }

    @Test
    public void inputFailuresPropagateBeforeAndAfterTheFirstRead() {
        IOException failure = new IOException("body read failed");
        for (boolean emitFirstByte : new boolean[]{false, true}) {
            InputStream input = new InputStream() {
                private boolean emitted;

                @Override
                public int read() throws IOException {
                    throw failure;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (emitFirstByte && !emitted) {
                        emitted = true;
                        bytes[offset] = 1;
                        return 1;
                    }
                    throw failure;
                }
            };
            SampleResult result = new SampleResult();
            result.sampleStart();
            assertSame(failure, assertThrows(IOException.class,
                    () -> new HTTPNullSampler().readResponse(result, input, -1, null)));
        }
    }
}
