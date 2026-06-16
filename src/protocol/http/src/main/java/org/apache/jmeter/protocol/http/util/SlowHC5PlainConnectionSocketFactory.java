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

package org.apache.jmeter.protocol.http.util;

import java.io.IOException;
import java.net.Socket;

import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.jmeter.util.SlowSocket;

/**
 * Apache HttpClient 5 protocol factory to generate slow sockets.
 */
@SuppressWarnings("deprecation")
public class SlowHC5PlainConnectionSocketFactory extends PlainConnectionSocketFactory {
    private final int charactersPerSecond;

    public SlowHC5PlainConnectionSocketFactory(final int cps) {
        this.charactersPerSecond = cps;
    }

    @Override
    public Socket createSocket(final HttpContext context) throws IOException {
        return new SlowSocket(charactersPerSecond);
    }
}
