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

import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.BASE64;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.BODY_SIZE;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.CONNECT_TIME;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.CONTENT_TYPE;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.COOKIES;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.DATA_TYPE;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.DESTINATION_ENDPOINT;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.ENCODING;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.ERROR_COUNT;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.HEADERS_SIZE;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.HTTP_METHOD;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.IDLE_TIME;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.LATENCY;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.LOCAL_ENDPOINT;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.PROTOCOL_VERSION;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.QUERY_STRING;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.REDIRECT_LOCATION;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.REQUEST_DATA;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.REQUEST_HEADERS;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.RESPONSE_CODE;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.RESPONSE_DATA;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.RESPONSE_HEADERS;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.RESPONSE_MESSAGE;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.RESPONSE_TIME;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.SAMPLE_COUNT;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.SENT_BYTES;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.SIMULATE_TIME;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.SUCCESSFUL;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.TIMESTAMP;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.TLS_VERSION;
import static org.apache.jmeter.protocol.http.sampler.DummySamplerField.URL;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Locale;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.StatisticalSampleResult;

/** Produces configurable native results without sending a request. */
public class DummySampler extends AbstractSampler implements Interruptible {
    private static final long serialVersionUID = 1L;
    public static final String RESULT_TYPE = "DummySampler.result_type";

    public enum ResultType { HTTP, STANDARD, STATISTICAL }

    private transient volatile Thread sleepingThread;
    // Samplers are cloned per worker. Cache only the last parsed URL, never evaluated properties.
    private transient ParsedUrl parsedUrl;

    private record ParsedUrl(String text, java.net.URL url) { }

    public ResultType getResultType() {
        String type = getPropertyAsString(RESULT_TYPE, ResultType.HTTP.name());
        try {
            return ResultType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            return ResultType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        }
    }

    public String value(DummySamplerField field) {
        return getPropertyAsString(field.propertyName(), field.defaultValue());
    }

    @Override
    public SampleResult sample(Entry entry) {
        long started = System.currentTimeMillis();
        try {
            ResultType type = getResultType();
            long elapsed = number(RESPONSE_TIME);
            String timestamp = value(TIMESTAMP).trim();
            long stamp = timestamp.isEmpty() ? started : number(TIMESTAMP, timestamp);
            SampleResult result = createResult(type, stamp, elapsed, timestamp.isEmpty());
            result.setSampleLabel(getName());
            result.setSuccessful(flag(SUCCESSFUL));
            result.setResponseCode(value(RESPONSE_CODE));
            result.setResponseMessage(value(RESPONSE_MESSAGE));
            result.setLatency(number(LATENCY));
            result.setConnectTime(number(CONNECT_TIME));
            result.setIdleTime(number(IDLE_TIME));
            result.setSamplerData(value(REQUEST_DATA));
            result.setRequestHeaders(value(REQUEST_HEADERS));
            result.setResponseHeaders(value(RESPONSE_HEADERS));
            result.setContentType(value(CONTENT_TYPE));
            Charset encoding = Charset.forName(value(ENCODING));
            result.setDataEncoding(encoding.name());
            String dataType = value(DATA_TYPE);
            if (!SampleResult.TEXT.equals(dataType) && !SampleResult.BINARY.equals(dataType)) {
                throw new IllegalArgumentException("Data type must be text or bin");
            }
            result.setDataType(dataType);
            boolean base64 = flag(BASE64);
            String response = value(RESPONSE_DATA);
            // New results already have an empty response; avoid allocating another empty array.
            if (!response.isEmpty()) {
                result.setResponseData(base64 ? Base64.getDecoder().decode(response) : response.getBytes(encoding));
            }
            result.setSentBytes(number(SENT_BYTES));
            result.setBodySize(number(BODY_SIZE));
            result.setHeadersSize(integer(HEADERS_SIZE));
            result.setLocalEndpoint(value(LOCAL_ENDPOINT));
            result.setDestinationEndpoint(value(DESTINATION_ENDPOINT));
            result.setProtocolVersion(value(PROTOCOL_VERSION));
            result.setTlsVersion(value(TLS_VERSION));
            String url = value(URL);
            if (!url.isEmpty()) {
                result.setURL(parseUrl(url));
            }
            if (result instanceof HTTPSampleResult http) {
                http.setHTTPMethod(value(HTTP_METHOD));
                http.setQueryString(value(QUERY_STRING));
                http.setCookies(value(COOKIES));
                http.setRedirectLocation(value(REDIRECT_LOCATION));
            }
            if (result instanceof StatisticalSampleResult) {
                int samples = integer(SAMPLE_COUNT);
                int errors = integer(ERROR_COUNT);
                if (samples == 0 || errors > samples) {
                    throw new IllegalArgumentException("Sample count must be positive and error count must not exceed it");
                }
                result.setSampleCount(samples);
                result.setErrorCount(errors);
            }
            if (flag(SIMULATE_TIME) && elapsed > 0) {
                sleepingThread = Thread.currentThread();
                try {
                    Thread.sleep(elapsed);
                } finally {
                    sleepingThread = null;
                }
            }
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure(started, "Dummy sampler interrupted");
        } catch (IllegalArgumentException | ArithmeticException | MalformedURLException ex) {
            return failure(started, "Invalid dummy sampler configuration: " + ex.getMessage());
        }
    }

    private java.net.URL parseUrl(String text) throws MalformedURLException {
        ParsedUrl cached = parsedUrl;
        if (cached == null || !cached.text().equals(text)) {
            cached = new ParsedUrl(text, URI.create(text).toURL());
            parsedUrl = cached;
        }
        return cached.url();
    }

    private static SampleResult createResult(ResultType type, long stamp, long elapsed, boolean automaticTimestamp) {
        SampleResult result = type == ResultType.HTTP ? new HTTPSampleResult() : new SampleResult();
        // An automatic timestamp anchors the interval at invocation, regardless of timestamp mode.
        result.setStampAndTime(automaticTimestamp && !result.isStampedAtStart()
                ? Math.addExact(stamp, elapsed) : stamp, elapsed);
        if (type == ResultType.STATISTICAL) {
            StatisticalSampleResult statistical = new StatisticalSampleResult();
            statistical.setSampleCount(0);
            statistical.add(result);
            return statistical;
        }
        return result;
    }

    private SampleResult failure(long started, String message) {
        SampleResult result = createResult(ResultType.STANDARD, started,
                Math.max(0, System.currentTimeMillis() - started), true);
        result.setSampleLabel(getName());
        result.setSuccessful(false);
        result.setResponseCode("DUMMY_ERROR");
        result.setResponseMessage(message);
        result.setDataType(SampleResult.TEXT);
        result.setResponseData(message, "UTF-8");
        return result;
    }

    private long number(DummySamplerField field) {
        return number(field, value(field).trim());
    }

    private static long number(DummySamplerField field, String value) {
        try {
            long number = Long.parseLong(value);
            if (number >= 0) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // Report the property instead of silently converting invalid input to zero.
        }
        throw new IllegalArgumentException(field.name() + " must be a non-negative integer: " + value);
    }

    private int integer(DummySamplerField field) {
        long number = number(field);
        if (number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field.name() + " must not exceed " + Integer.MAX_VALUE);
        }
        return (int) number;
    }

    private boolean flag(DummySamplerField field) {
        String value = value(field).trim();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(field.name() + " must be true or false: " + value);
        }
        return Boolean.parseBoolean(value);
    }

    @Override
    public boolean interrupt() {
        Thread thread = sleepingThread;
        if (thread == null) {
            return false;
        }
        thread.interrupt();
        return true;
    }
}
