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

package org.apache.jmeter.protocol.websocket.sampler;

import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.ThreadListener;

public abstract class AbstractWebSocketSampler extends AbstractSampler implements TestBean, ThreadListener, Interruptible {
    private static final long serialVersionUID = 1L;
    private transient volatile CompletableFuture<?> pending;
    private transient volatile WebSocketSession active;
    private transient volatile boolean interrupted;

    public String getSessionName() {
        return getPropertyAsString("sessionName", "default");
    }

    public void setSessionName(String value) {
        setProperty("sessionName", value);
    }

    public int getTimeout() {
        return getPropertyAsInt("timeout", 10000);
    }

    public void setTimeout(int value) {
        setProperty("timeout", value);
    }

    @Override
    public final SampleResult sample(Entry entry) {
        interrupted = false;
        SampleResult result = new SampleResult();
        result.setSampleLabel(getName());
        result.setDataType(SampleResult.TEXT);
        result.sampleStart();
        try {
            if (getSessionName().isBlank() || getTimeout() <= 0) {
                throw new IllegalArgumentException("Session name is required and timeout must be positive");
            }
            result.setSuccessful(true);
            result.setResponseCodeOK();
            result.setResponseMessageOK();
            execute(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure(result, e);
        } catch (Exception e) {
            failure(result, e);
        } finally {
            pending = null;
            active = null;
            result.sampleEnd();
        }
        return result;
    }

    private void failure(SampleResult result, Exception error) {
        WebSocketSession session = active;
        if (session != null) {
            session.dispose();
        }
        result.setSuccessful(false);
        result.setResponseCode("WS_ERROR");
        Throwable cause = error;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof WebSocketHandshakeException handshake) {
            var response = handshake.getResponse();
            result.setResponseCode(Integer.toString(response.statusCode()));
            result.setResponseMessage("WebSocket handshake rejected: HTTP " + response.statusCode());
            StringBuilder headers = new StringBuilder();
            response.headers().map().forEach((name, values) ->
                    values.forEach(value -> headers.append(name).append(": ").append(value).append("\n")));
            result.setResponseHeaders(headers.toString());
            Object body = response.body();
            if (body instanceof byte[] bytes) {
                result.setResponseData(bytes);
            } else if (body instanceof String text) {
                result.setResponseData(text, "UTF-8");
            }
        } else {
            result.setResponseMessage(cause.toString());
        }
    }

    protected abstract void execute(SampleResult result) throws Exception;

    protected final void active(WebSocketSession session) {
        active = session;
        if (interrupted && session != null) {
            session.dispose();
        }
    }

    protected final <T> T await(CompletableFuture<T> future) throws Exception {
        return await(future, getTimeout(), TimeUnit.MILLISECONDS);
    }

    protected final <T> T await(CompletableFuture<T> future, long timeout, TimeUnit unit) throws Exception {
        pending = future;
        if (interrupted) {
            future.cancel(true);
        }
        try {
            return future.get(timeout, unit);
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
            pending = null;
        }
    }

    @Override
    public boolean interrupt() {
        interrupted = true;
        WebSocketSession session = active;
        CompletableFuture<?> future = pending;
        if (session != null) {
            session.dispose();
        }
        return future != null && future.cancel(true);
    }

    @Override
    public void threadStarted() {
    }

    @Override
    public void threadFinished() {
        WebSocketSessions.cleanup();
    }
}
