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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.gui.DummySamplerGui;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.StatisticalSampleResult;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.FunctionProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DummySamplerTest extends JMeterTestCase {
    @TempDir
    Path directory;

    @Test
    void defaultsProduceNativeHttpResult() {
        DummySampler sampler = new DummySampler();
        sampler.setName("Synthetic HTTP");
        HTTPSampleResult result = assertInstanceOf(HTTPSampleResult.class, sampler.sample(null));
        assertTrue(result.isSuccessful());
        assertEquals("Synthetic HTTP", result.getSampleLabel());
        assertEquals("200", result.getResponseCode());
        assertEquals("GET", result.getHTTPMethod());
        assertEquals(0, result.getTime());
    }

    @Test
    void preservesConfiguredHttpDataTimingsAndSizesWithoutNetwork() {
        DummySampler sampler = new DummySampler();
        set(sampler, SUCCESSFUL, "false");
        set(sampler, RESPONSE_CODE, "503");
        set(sampler, RESPONSE_MESSAGE, "Unavailable");
        set(sampler, RESPONSE_TIME, "4321");
        set(sampler, LATENCY, "321");
        set(sampler, CONNECT_TIME, "21");
        set(sampler, IDLE_TIME, "11");
        set(sampler, URL, "https://never-contact.invalid/test");
        set(sampler, REQUEST_DATA, "extra request data");
        set(sampler, REQUEST_HEADERS, "X-Request: one");
        set(sampler, RESPONSE_HEADERS, "X-Response: two");
        set(sampler, RESPONSE_DATA, "héllo");
        set(sampler, CONTENT_TYPE, "application/json");
        set(sampler, SENT_BYTES, "123");
        set(sampler, BODY_SIZE, "456");
        set(sampler, HEADERS_SIZE, "78");
        set(sampler, LOCAL_ENDPOINT, "127.0.0.1:1234");
        set(sampler, DESTINATION_ENDPOINT, "192.0.2.1:443");
        set(sampler, PROTOCOL_VERSION, "HTTP/2");
        set(sampler, TLS_VERSION, "TLSv1.3");
        set(sampler, HTTP_METHOD, "POST");
        set(sampler, QUERY_STRING, "{\"hello\":true}");
        set(sampler, COOKIES, "session=dummy");
        set(sampler, REDIRECT_LOCATION, "/next");
        HTTPSampleResult result = assertInstanceOf(HTTPSampleResult.class, sampler.sample(null));
        assertFalse(result.isSuccessful());
        assertEquals("503", result.getResponseCode());
        assertEquals("Unavailable", result.getResponseMessage());
        assertEquals(4321, result.getTime());
        assertEquals(321, result.getLatency());
        assertEquals(21, result.getConnectTime());
        assertEquals(11, result.getIdleTime());
        assertEquals("https://never-contact.invalid/test", result.getURL().toString());
        assertTrue(result.getSamplerData().contains("extra request data"));
        assertEquals("X-Request: one", result.getRequestHeaders());
        assertEquals("X-Response: two", result.getResponseHeaders());
        assertEquals("héllo", result.getResponseDataAsString());
        assertEquals("UTF-8", result.getDataEncodingNoDefault());
        assertEquals("application/json", result.getContentType());
        assertEquals(123, result.getSentBytes());
        assertEquals(534, result.getBytesAsLong());
        assertEquals("127.0.0.1:1234", result.getLocalEndpoint());
        assertEquals("192.0.2.1:443", result.getDestinationEndpoint());
        assertEquals("HTTP/2", result.getProtocolVersion());
        assertEquals("TLSv1.3", result.getTlsVersion());
        assertEquals("POST", result.getHTTPMethod());
        assertEquals("{\"hello\":true}", result.getQueryString());
        assertEquals("session=dummy", result.getCookies());
        assertEquals("/next", result.getRedirectLocation());
    }

    @Test
    void statisticalTimeAndCountsAreNotLost() {
        DummySampler sampler = new DummySampler();
        sampler.setProperty(DummySampler.RESULT_TYPE, "STATISTICAL");
        set(sampler, RESPONSE_TIME, "250");
        set(sampler, SAMPLE_COUNT, "10");
        set(sampler, ERROR_COUNT, "3");
        StatisticalSampleResult result = assertInstanceOf(StatisticalSampleResult.class, sampler.sample(null));
        assertEquals(250, result.getTime());
        assertEquals(250, result.getEndTime() - result.getStartTime());
        assertEquals(10, result.getSampleCount());
        assertEquals(3, result.getErrorCount());
    }

    @Test
    void standardResultIgnoresHiddenStatisticsAndSupportsBinary() {
        DummySampler sampler = new DummySampler();
        sampler.setProperty(DummySampler.RESULT_TYPE, "STANDARD");
        set(sampler, SAMPLE_COUNT, "invalid hidden field");
        set(sampler, DATA_TYPE, "bin");
        set(sampler, BASE64, "true");
        set(sampler, RESPONSE_DATA, "AP+A");
        SampleResult result = sampler.sample(null);
        assertEquals(SampleResult.class, result.getClass());
        assertTrue(result.isSuccessful());
        assertArrayEquals(new byte[] {0, (byte) 255, (byte) 128}, result.getResponseData());
        assertEquals(3, result.getBytesAsLong());
    }

    @Test
    void timestampModesAreVerifiedInFreshJvms() throws Exception {
        // SampleResult captures the timestamp setting in a static final field.
        var entries = new java.util.LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    entries.add(Path.of(url.toURI()).toString());
                }
            }
        }
        for (String mode : new String[] {"true", "false"}) {
            var process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", String.join(java.io.File.pathSeparator, entries),
                    TimestampProbe.class.getName(), mode).inheritIO().start();
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Timestamp probe timed out");
                assertEquals(0, process.exitValue(), "Timestamp mode: " + mode);
            } finally {
                process.destroyForcibly();
            }
        }
    }

    public static class TimestampProbe {
        public static void main(String[] args) {
            org.apache.jmeter.util.JMeterUtils.loadJMeterProperties("../../../bin/jmeter.properties");
            org.apache.jmeter.util.JMeterUtils.setProperty("sampleresult.timestamp.start", args[0]);
            assertEquals(Boolean.parseBoolean(args[0]), new SampleResult().isStampedAtStart());
            DummySamplerTest test = new DummySamplerTest();
            test.simulatedIntervalsStartWhenInvokedForEveryResultType();
            test.explicitTimestampIsPreserved();
        }
    }

    @Test
    void simulatedIntervalsStartWhenInvokedForEveryResultType() {
        for (DummySampler.ResultType type : DummySampler.ResultType.values()) {
            DummySampler sampler = new DummySampler();
            sampler.setProperty(DummySampler.RESULT_TYPE, type.name());
            set(sampler, RESPONSE_TIME, "40");
            set(sampler, SIMULATE_TIME, "true");
            long before = System.currentTimeMillis();
            SampleResult result = sampler.sample(null);
            long after = System.currentTimeMillis();
            assertTrue(result.isSuccessful());
            assertTrue(result.getStartTime() >= before, type.name());
            assertTrue(result.getEndTime() <= after, type.name());
            assertEquals(40, result.getTime());
            assertEquals(40, result.getEndTime() - result.getStartTime());
        }
    }

    @Test
    void httpRequestDetailsRemainVisibleWithoutUrl() {
        DummySampler sampler = new DummySampler();
        set(sampler, HTTP_METHOD, "POST");
        set(sampler, QUERY_STRING, "body=value");
        set(sampler, COOKIES, "session=test");
        set(sampler, REQUEST_DATA, "extra details");
        HTTPSampleResult result = assertInstanceOf(HTTPSampleResult.class, sampler.sample(null));
        assertNull(result.getURL());
        assertEquals("body=value", result.getQueryString());
        assertEquals("session=test", result.getCookies());
        assertTrue(result.getSamplerData().contains("POST data:\nbody=value"));
        assertTrue(result.getSamplerData().contains("Cookie Data:\nsession=test"));
        assertTrue(result.getSamplerData().endsWith("extra details"));
    }

    @Test
    void resultTypesAcceptCaseVariantsButRejectUnknownTypes() {
        DummySampler sampler = new DummySampler();
        sampler.setProperty(DummySampler.RESULT_TYPE, "http");
        assertInstanceOf(HTTPSampleResult.class, sampler.sample(null));
        sampler.setProperty(DummySampler.RESULT_TYPE, "Statistical");
        assertInstanceOf(StatisticalSampleResult.class, sampler.sample(null));
        sampler.setProperty(DummySampler.RESULT_TYPE, "future-type");
        assertEquals("DUMMY_ERROR", sampler.sample(null).getResponseCode());
    }

    @Test
    void explicitTimestampIsPreserved() {
        DummySampler sampler = new DummySampler();
        set(sampler, TIMESTAMP, "1700000000000");
        set(sampler, RESPONSE_TIME, "123");
        SampleResult result = sampler.sample(null);
        assertEquals(1700000000000L, result.getTimeStamp());
        assertEquals(123, result.getTime());
    }

    @Test
    void invalidInputProducesFailedResult() {
        for (DummySamplerField field : new DummySamplerField[] {RESPONSE_TIME, HEADERS_SIZE, SUCCESSFUL,
                ENCODING, URL, DATA_TYPE}) {
            DummySampler sampler = new DummySampler();
            set(sampler, field, "invalid");
            SampleResult result = sampler.sample(null);
            assertFalse(result.isSuccessful(), field.name());
            assertEquals("DUMMY_ERROR", result.getResponseCode());
        }
        DummySampler sampler = new DummySampler();
        set(sampler, RESPONSE_TIME, "-1");
        assertFalse(sampler.sample(null).isSuccessful());
        sampler.setProperty(DummySampler.RESULT_TYPE, "STATISTICAL");
        set(sampler, RESPONSE_TIME, "0");
        set(sampler, ERROR_COUNT, "2");
        assertFalse(sampler.sample(null).isSuccessful());
    }

    @Test
    void evaluatesVariablesAtSamplingTime() throws Exception {
        DummySampler sampler = new DummySampler();
        JMeterVariables variables = new JMeterVariables();
        variables.put("duration", "37");
        variables.put("sampleSuccessful", "false");
        JMeterContextService.getContext().setVariables(variables);
        JMeterContextService.getContext().setSamplingStarted(true);
        sampler.setProperty(new FunctionProperty(RESPONSE_TIME.propertyName(), new CompoundVariable("${duration}")));
        sampler.setProperty(new FunctionProperty(SUCCESSFUL.propertyName(), new CompoundVariable("${sampleSuccessful}")));
        sampler.setRunningVersion(true);
        try {
            SampleResult result = sampler.sample(null);
            assertEquals(37, result.getTime());
            assertFalse(result.isSuccessful());
            assertEquals("200", result.getResponseCode());
        } finally {
            JMeterContextService.getContext().clear();
        }
    }

    @Test
    void cachedUrlDoesNotFreezeVariablesOrShareResponseBytes() throws Exception {
        DummySampler sampler = new DummySampler();
        JMeterVariables variables = new JMeterVariables();
        JMeterContextService.getContext().setVariables(variables);
        JMeterContextService.getContext().setSamplingStarted(true);
        sampler.setProperty(new FunctionProperty(URL.propertyName(), new CompoundVariable("${url}")));
        sampler.setProperty(new FunctionProperty(RESPONSE_DATA.propertyName(), new CompoundVariable("${body}")));
        sampler.setRunningVersion(true);
        try {
            for (String path : new String[] {"first", "first", "second"}) {
                variables.incIteration();
                variables.put("url", "https://example.invalid/" + path);
                variables.put("body", path);
                SampleResult result = sampler.sample(null);
                assertEquals("https://example.invalid/" + path, result.getURL().toString());
                assertEquals(path, result.getResponseDataAsString());
                result.getResponseData()[0] = 0;
                assertEquals(path, sampler.sample(null).getResponseDataAsString());
            }
            variables.incIteration();
            variables.put("url", "invalid");
            assertEquals("DUMMY_ERROR", sampler.sample(null).getResponseCode());
            variables.incIteration();
            variables.put("url", "");
            variables.put("body", "");
            SampleResult empty = sampler.sample(null);
            assertTrue(empty.isSuccessful());
            assertEquals("", empty.getResponseDataAsString());
            assertNull(empty.getURL());
        } finally {
            JMeterContextService.getContext().clear();
        }
    }

    @Test
    void savedPlanRetainsEveryProperty() throws Exception {
        DummySampler sampler = new DummySampler();
        sampler.setName("Saved dummy");
        sampler.setProperty(TestElement.GUI_CLASS, DummySamplerGui.class.getName());
        sampler.setProperty(TestElement.TEST_CLASS, DummySampler.class.getName());
        sampler.setProperty(DummySampler.RESULT_TYPE, "STANDARD");
        for (DummySamplerField field : DummySamplerField.values()) {
            set(sampler, field, "${" + field.name() + "}\n<content>&");
        }
        ListedHashTree tree = new ListedHashTree();
        tree.add(sampler);
        Path file = directory.resolve("dummy.jmx");
        SaveService.saveTreeToFile(tree, file);
        DummySampler loaded = (DummySampler) SaveService.loadTree(file.toFile()).getArray()[0];
        assertEquals(sampler.getName(), loaded.getName());
        assertEquals(sampler.getResultType(), loaded.getResultType());
        for (DummySamplerField field : DummySamplerField.values()) {
            assertEquals(sampler.value(field), loaded.value(field));
        }
    }

    @Test
    void simulationSleepsAndCanBeInterrupted() throws Exception {
        DummySampler sampler = new DummySampler();
        set(sampler, SIMULATE_TIME, "true");
        set(sampler, RESPONSE_TIME, "30");
        long start = System.nanoTime();
        assertEquals(30, sampler.sample(null).getTime());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 25);
        assertFalse(sampler.interrupt());
        set(sampler, RESPONSE_TIME, "60000");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> sampler.sample(null));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean interrupted = false;
            while (!(interrupted = sampler.interrupt()) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(interrupted);
            assertEquals("DUMMY_ERROR", future.get(5, TimeUnit.SECONDS).getResponseCode());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void set(DummySampler sampler, DummySamplerField field, String value) {
        sampler.setProperty(field.propertyName(), value);
    }
}
