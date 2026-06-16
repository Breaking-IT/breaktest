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

package org.apache.jmeter.protocol.http.sampler.hc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.apache.hc.client5.http.socket.LayeredConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.jmeter.util.HttpSSLProtocolSocketFactory;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.util.JsseSSLManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lazy HTTPS socket factory for Apache HttpClient 5.
 */
@SuppressWarnings("deprecation")
public final class LazyLayeredConnectionSocketFactoryHC5 implements LayeredConnectionSocketFactory {
    private static final Logger LOG = LoggerFactory.getLogger(LazyLayeredConnectionSocketFactoryHC5.class);
    private static final String[] SOCKET_PROTOCOL_ARRAY =
            JMeterUtils.getArrayPropDefault("https.socket.protocols", null); // $NON-NLS-1$
    private static final String[] SOCKET_CIPHER_ARRAY =
            JMeterUtils.getArrayPropDefault("https.socket.ciphers", null); // $NON-NLS-1$
    private static final String[] CIPHER_SUITE_ARRAY =
            JMeterUtils.getArrayPropDefault("https.cipherSuites", SOCKET_CIPHER_ARRAY); // $NON-NLS-1$

    private static class AdapteeHolder {
        private static final LayeredConnectionSocketFactory ADAPTEE = checkAndInit();

        private static LayeredConnectionSocketFactory checkAndInit() {
            LOG.info("Setting up HTTPS TrustAll Socket Factory for HC5");
            return new SSLConnectionSocketFactory(
                    new HttpSSLProtocolSocketFactory(JsseSSLManager.CPS),
                    SOCKET_PROTOCOL_ARRAY,
                    CIPHER_SUITE_ARRAY,
                    NoopHostnameVerifier.INSTANCE);
        }
    }

    @Override
    public Socket createSocket(HttpContext context) throws IOException {
        return AdapteeHolder.ADAPTEE.createSocket(context);
    }

    @Override
    public Socket connectSocket(TimeValue connectTimeout, Socket socket, HttpHost host,
            InetSocketAddress remoteAddress, InetSocketAddress localAddress, HttpContext context) throws IOException {
        return AdapteeHolder.ADAPTEE.connectSocket(connectTimeout, socket, host, remoteAddress, localAddress, context);
    }

    @Override
    public Socket createLayeredSocket(Socket socket, String target, int port, HttpContext context) throws IOException {
        return AdapteeHolder.ADAPTEE.createLayeredSocket(socket, target, port, context);
    }
}
