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

import java.net.URI;

import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.jmeter.testelement.TestElement;

/** Shares HTTP proxy precedence and routing with other transports. */
public final class HttpProxyConfiguration {
    private HttpProxyConfiguration() { }

    public record Route(String host, int port, String username, String password) {
        public static final Route DIRECT = new Route("", 0, "", "");

        public boolean direct() { return host.isEmpty(); }

        @Override
        public String toString() { return "ProxyRoute[host=" + host + ", port=" + port + "]"; }
    }

    private static void copy(TestElement source, TestElement target) {
        var properties = source.propertyIterator();
        while (properties.hasNext()) {
            var property = properties.next();
            if (property.getName().startsWith("HTTPSampler.proxy")) {
                target.setProperty(property.clone());
            }
        }
    }

    public static void merge(TestElement target, TestElement defaults) {
        var settings = new HTTPSamplerProxy();
        copy(target, settings);
        settings.addTestElement(defaults);
        copy(settings, target);
    }

    public static Route resolve(TestElement element, URI destination) {
        var settings = new HTTPSamplerProxy();
        copy(element, settings);
        var context = HttpClientContext.create();
        var proxy = new HTTPHC5Impl(settings).resolveProxy(null, context);
        HttpHost candidate = proxy.enabled() ? new HttpHost(proxy.scheme(), proxy.host(), proxy.port()) : null;
        HttpHost target = new HttpHost("http", destination.getHost(), destination.getPort());
        if (HTTPHCAbstractImpl.selectProxy(candidate, target, context) == null) {
            return Route.DIRECT;
        }
        if (!"http".equalsIgnoreCase(proxy.scheme())) {
            throw new IllegalArgumentException("WebSocket connections require an HTTP proxy; HTTPS proxy endpoints are not supported");
        }
        return new Route(proxy.host(), proxy.port(), proxy.username(), proxy.password());
    }
}
