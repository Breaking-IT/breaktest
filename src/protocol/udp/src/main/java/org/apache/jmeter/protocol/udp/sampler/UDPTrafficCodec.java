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

package org.apache.jmeter.protocol.udp.sampler;

/**
 * Converts the text stored in a test plan to UDP datagram bytes and converts
 * received datagram bytes to sample response data.
 */
public interface UDPTrafficCodec {

    byte[] encode(String data);

    byte[] decode(byte[] data);

    /**
     * Returns the character encoding of the bytes produced by {@link #decode(byte[])}, or
     * {@code null} when the decoded response has no character representation.
     */
    default String responseEncoding() {
        return null;
    }
}
