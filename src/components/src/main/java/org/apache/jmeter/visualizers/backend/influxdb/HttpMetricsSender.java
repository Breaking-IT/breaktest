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

package org.apache.jmeter.visualizers.backend.influxdb;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;
import org.apache.jmeter.report.utils.MetricUtils;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.jorphan.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InfluxDB sender base on The Line Protocol.
 * <p>
 * The Line Protocol is a text based format for writing points to InfluxDB.
 * Syntax:
 * <pre>
 * weather,location=us-midwest temperature=82 1465839830100400200
 * |      -------------------- --------------  |
 * |               |             |             |
 * +-----------+--------+-+---------+-+---------+
 * |measurement|,tag_set| |field_set| |timestamp|
 * +-----------+--------+-+---------+-+---------+
 * </pre>
 * Each line, separated by the newline character, represents a single point in InfluxDB.
 * The Line Protocol is whitespace sensitive.
 * <p>
 * See https://docs.influxdata.com/influxdb/v1.7/write_protocols/line_protocol_tutorial/
 *
 * @since 3.2
 */
class HttpMetricsSender extends AbstractInfluxdbMetricsSender {
    private static final Logger log = LoggerFactory.getLogger(HttpMetricsSender.class);

    private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    private static final String AUTHORIZATION_HEADER_VALUE = "Token ";

    private final Object lock = new Object();

    private List<MetricTuple> metrics = new ArrayList<>();

    private SimpleHttpRequest httpRequest;
    private CloseableHttpAsyncClient httpClient;
    private URL url;
    private String token;

    private Future<SimpleHttpResponse> lastRequest;

    HttpMetricsSender() {
        super();
    }

    /**
     * The HTTP API is the primary means of writing data into InfluxDB, by
     * sending POST requests to the /write endpoint. Initiate the HttpClient
     * client with a HttpPost request from influxdb url
     *
     * @param influxdbUrl   example : http://localhost:8086/write?db=myd&rp=one_week
     * @param influxDBToken example: my-token
     * @see InfluxdbMetricsSender#setup(String, String)
     */
    @Override
    public void setup(String influxdbUrl, String influxDBToken) throws Exception {
        IOReactorConfig ioReactorConfig = IOReactorConfig
                .custom()
                .setIoThreadCount(1)
                .setSoTimeout(Timeout.ofMilliseconds(JMeterUtils.getPropDefault("backend_influxdb.socket_timeout", 3000)))
                .build();

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(JMeterUtils.getPropDefault("backend_influxdb.connection_timeout", 1000)))
                .setSocketTimeout(Timeout.ofMilliseconds(JMeterUtils.getPropDefault("backend_influxdb.socket_timeout", 3000)))
                .build();

        PoolingAsyncClientConnectionManager connManager = PoolingAsyncClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnPerRoute(2)
                .setMaxConnTotal(2)
                .build();

        httpClient = HttpAsyncClients.custom()
                .setConnectionManager(connManager)
                .setIOReactorConfig(ioReactorConfig)
                .setDefaultRequestConfig(createRequestConfig())
                .setUserAgent("ApacheJMeter" + JMeterUtils.getJMeterVersion())
                .disableCookieManagement()
                .disableConnectionState()
                .build();
        url = java.net.URI.create(influxdbUrl).toURL();
        token = influxDBToken;
        httpRequest = createRequest(url, token);
        httpClient.start();
    }

    /**
     * @param url   {@link URL} InfluxDB Url
     * @param token InfluxDB 2.0 authorization token
     * @return configured request
     * @throws URISyntaxException
     */
    private static SimpleHttpRequest createRequest(URL url, String token) throws URISyntaxException {
        SimpleHttpRequest currentHttpRequest = SimpleRequestBuilder.post(url.toURI())
                .setRequestConfig(createRequestConfig())
                .build();
        if (StringUtilities.isNotBlank(token)) {
            currentHttpRequest.setHeader(AUTHORIZATION_HEADER_NAME, AUTHORIZATION_HEADER_VALUE + token);
        }
        log.debug("Created InfluxDBMetricsSender with url: {}", url);
        return currentHttpRequest;
    }

    private static RequestConfig createRequestConfig() {
        return RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(JMeterUtils.getPropDefault("backend_influxdb.socket_timeout", 3000)))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(
                        JMeterUtils.getPropDefault("backend_influxdb.connection_request_timeout", 100)))
                .build();
    }

    @Override
    public void addMetric(String measurement, String tag, String field) {
        addMetric(measurement, tag, field, System.currentTimeMillis());
    }

    @Override
    public void addMetric(String measurement, String tag, String field, long timestamp) {
        synchronized (lock) {
            metrics.add(new MetricTuple(measurement, tag, field, timestamp));
        }
    }

    @Override
    public void writeAndSendMetrics() {
        List<MetricTuple> copyMetrics;
        synchronized (lock) {
            if (metrics.isEmpty()) {
                return;
            }
            copyMetrics = metrics;
            metrics = new ArrayList<>(copyMetrics.size());
        }
        writeAndSendMetrics(copyMetrics);
    }

    private void writeAndSendMetrics(List<MetricTuple> copyMetrics) {
        try {
            if (httpRequest == null) {
                httpRequest = createRequest(url, token);
            }
            StringBuilder sb = new StringBuilder(copyMetrics.size() * 35);
            for (MetricTuple metric : copyMetrics) {
                // Add TimeStamp in nanosecond from epoch ( default in InfluxDB )
                sb.append(metric.measurement)
                        .append(metric.tag)
                        .append(" ") //$NON-NLS-1$
                        .append(metric.field)
                        .append(" ")
                        .append(metric.timestamp)
                        .append("000000")
                        .append("\n"); //$NON-NLS-1$
            }
            String data = sb.toString();
            log.debug("Sending to influxdb:{}", data);
            httpRequest.setBody(data, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8));
            lastRequest = httpClient.execute(httpRequest, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(final SimpleHttpResponse response) {
                    int code = response.getCode();
                    // If your write request received HTTP
                    // 204 No Content: it was a success!
                    // 4xx: InfluxDB could not understand the request.
                    // 5xx: The system is overloaded or significantly impaired.
                    if (MetricUtils.isSuccessCode(code)) {
                        if (log.isDebugEnabled()) {
                            log.debug("Success, number of metrics written: {}", copyMetrics.size());
                        }
                    } else {
                        log.error("Error writing metrics to influxDB Url: {}, responseCode: {}, responseBody: {}", url, code, getBody(response));
                    }
                }

                @Override
                public void failed(final Exception ex) {
                    log.error("failed to send data to influxDB server.", ex);
                }

                @Override
                public void cancelled() {
                    log.warn("Request to influxDB server was cancelled");
                }
            });
        } catch (URISyntaxException ex) {
            log.error(ex.getMessage(), ex);
        }
    }

    /**
     * @param response HttpResponse
     * @return String entity Body if any
     */
    private static String getBody(final SimpleHttpResponse response) {
        return response == null || response.getBody() == null ? "" : response.getBodyText();
    }

    @Override
    public void destroy() {
        // Give some time to send last metrics before shutting down
        log.info("Destroying ");
        try {
            if (lastRequest != null) {
                lastRequest.get(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.error("Error waiting for last request to be send to InfluxDB", e);
            if (lastRequest != null) {
                lastRequest.cancel(true);
            }
        }
        if (httpClient != null) {
            httpClient.close(CloseMode.GRACEFUL);
        }
        JOrphanUtils.closeQuietly(httpClient);
    }

}
