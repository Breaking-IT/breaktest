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

import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.StringUtilities;

/**
 * Factory to return the appropriate HTTPSampler for use with classes that need
 * an HTTPSampler; also creates the implementations for use with HTTPSamplerProxy.
 *
 */
public final class HTTPSamplerFactory {

    // Legacy sampler names accepted when loading old test plans and properties.
    static final String LEGACY_HTTP_SAMPLER_JAVA = "HTTPSampler"; //$NON-NLS-1$

    /** Use Apache HTTPClient HTTP implementation */
    public static final String HTTP_SAMPLER_APACHE = "HTTPSampler2"; //$NON-NLS-1$

    //+ JMX implementation attribute values (also displayed in GUI) - do not change
    public static final String IMPL_HTTP_CLIENT5 = "HttpClient5";  // $NON-NLS-1$

    public static final String IMPL_HTTP_CLIENT4 = "HttpClient4";  // $NON-NLS-1$

    public static final String IMPL_HTTP_CLIENT3_1 = "HttpClient3.1"; // $NON-NLS-1$

    static final String LEGACY_IMPL_JAVA = "Java"; // $NON-NLS-1$
    //- JMX

    public static final String DEFAULT_CLASSNAME =
        JMeterUtils.getPropDefault("jmeter.httpsampler", ""); //$NON-NLS-1$ //$NON-NLS-2$

    private HTTPSamplerFactory() {
        // Not intended to be instantiated
    }

    /**
     * Create a new instance of the default sampler
     *
     * @return instance of default sampler
     */
    public static HTTPSamplerBase newInstance() {
        return newInstance(DEFAULT_CLASSNAME);
    }

    /**
     * Create a new instance of the required sampler type.
     * Legacy sampler names are accepted when loading old test plans, however all
     * HTTP requests now use the HttpClient 5 implementation.
     *
     * @param alias legacy sampler name or IMPL_HTTP_CLIENT5
     * @return the appropriate sampler
     */
    public static HTTPSamplerBase newInstance(String alias) {
        if (StringUtilities.isBlank(alias)) {
            return new HTTPSamplerProxy();
        }
        if (isKnownImplementation(alias)) {
            return new HTTPSamplerProxy(IMPL_HTTP_CLIENT5);
        }
        throw new IllegalArgumentException("Unknown sampler type: '" + alias+"'");
    }

    public static String[] getImplementations(){
        return new String[]{IMPL_HTTP_CLIENT5};
    }

    public static HTTPAbstractImpl getImplementation(String impl, HTTPSamplerBase base){
        if (HTTPSamplerBase.PROTOCOL_FILE.equals(base.getProtocol())) {
            return new HTTPFileImpl(base);
        }
        if (!StringUtilities.isBlank(impl) && !isKnownImplementation(impl)) {
            throw new IllegalArgumentException("Unknown implementation type: '"+impl+"'");
        }
        if (!base.isHttp11Protocol()) {
            return new HTTPHC5H2Impl(base);
        }
        return new HTTPHC5Impl(base);
    }

    public static String normalizeImplementation(String impl) {
        return StringUtilities.isBlank(impl) ? "" : IMPL_HTTP_CLIENT5;
    }

    private static boolean isKnownImplementation(String impl) {
        return IMPL_HTTP_CLIENT5.equals(impl)
                || IMPL_HTTP_CLIENT4.equals(impl)
                || HTTP_SAMPLER_APACHE.equals(impl)
                || IMPL_HTTP_CLIENT3_1.equals(impl)
                || LEGACY_HTTP_SAMPLER_JAVA.equals(impl)
                || LEGACY_IMPL_JAVA.equals(impl);
    }

}
