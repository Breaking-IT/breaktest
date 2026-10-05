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

package org.apache.jmeter.protocol.http.proxy;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Identify the endpoint of a failed operation without changing successful traffic. */
final class RecordingIo {
    private RecordingIo() {
    }

    static InputStream input(InputStream input, String peer) {
        return new FilterInputStream(input) {
            @Override
            public int read() throws IOException {
                try {
                    return in.read();
                } catch (IOException e) {
                    throw new IOException(peer + " read failed: " + e, e);
                }
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                try {
                    return in.read(bytes, offset, length);
                } catch (IOException e) {
                    throw new IOException(peer + " read failed: " + e, e);
                }
            }
        };
    }

    static OutputStream output(OutputStream output, String peer) {
        return new FilterOutputStream(output) {
            @Override
            public void write(int value) throws IOException {
                try {
                    out.write(value);
                } catch (IOException e) {
                    throw new IOException(peer + " write failed: " + e, e);
                }
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                try {
                    out.write(bytes, offset, length);
                } catch (IOException e) {
                    throw new IOException(peer + " write failed: " + e, e);
                }
            }

            @Override
            public void flush() throws IOException {
                try {
                    out.flush();
                } catch (IOException e) {
                    throw new IOException(peer + " flush failed: " + e, e);
                }
            }
        };
    }
}
