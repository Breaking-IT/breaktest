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

package org.apache.jmeter.protocol.sse;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.samplers.ResponseDecoderRegistry;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.SamplePackage;

/** Keeps the normal HTTP transport alive while returning its opening result to the script. */
public final class SseSession implements AutoCloseable {
    private final HTTPSamplerProxy transport;
    private final Object transportKey;
    private final Runnable borrowHttpClientCache;
    private final CompletableFuture<HTTPSampleResult> opening = new CompletableFuture<>();
    private final Consumer<SampleResult> publish;
    private final SseMessageHandlers handlers;
    private volatile boolean closed;
    private volatile boolean receiving;
    private volatile Thread reader;

    public SseSession(HTTPSamplerProxy sampler, List<SseMatchController> matches) {
        var owner = JMeterContextService.getContext().getThread();
        transportKey = owner == null ? Thread.currentThread() : owner;
        borrowHttpClientCache = org.apache.jmeter.protocol.http.sampler.HTTPHC5Impl.borrowClientCacheForSse();
        transport = (HTTPSamplerProxy) sampler.clone();
        transport.setSseReader(this);
        // The stream must neither use cached responses nor fetch embedded resources.
        transport.removeProperty(transport.getSchema().getCacheManager());
        transport.setImageParser(false);
        if (sampler.getCookieManager() != null) {
            transport.setCookieManager(sampler.getCookieManager());
        }
        publish = publisher();
        handlers = new SseMessageHandlers(matches, sampler.getSseSessionName(), publish);
    }

    public HTTPSampleResult open() throws Exception {
        var owner = JMeterContextService.getContext();
        var context = JMeterContextService.createContext();
        context.setVariables(owner.getVariables());
        context.setThread(owner.getThread());
        context.setThreadNum(owner.getThreadNum());
        context.setThreadGroup(owner.getThreadGroup());
        context.setEngine(owner.getEngine());
        context.setSamplingStarted(owner.isSamplingStarted());
        handlers.start();
        reader = Thread.ofVirtual().name("SSE " + transport.getSseSessionName()).unstarted(() -> {
            JMeterContextService.replaceContext(context);
            borrowHttpClientCache.run();
            try {
                if (closed) {
                    return;
                }
                HTTPSampleResult result = (HTTPSampleResult) transport.sample();
                if (result == null) {
                    throw new IOException("HTTP response did not open an SSE stream");
                }
                if (!opening.isDone() && result.isSuccessful() && !"204".equals(result.getResponseCode())) {
                    result.setSuccessful(false);
                    result.setResponseMessage("SSE requires HTTP 200 and Content-Type: text/event-stream; received "
                            + result.getResponseCode() + " " + result.getContentType());
                }
                if (!opening.isDone()) {
                    handlers.close();
                }
                if (!opening.complete(result) && !closed && !result.isSuccessful()) {
                    publish.accept(result);
                }
            } catch (Exception failure) {
                if (opening.completeExceptionally(failure)) {
                    handlers.close();
                }
            } finally {
                receiving = false;
                opening.completeExceptionally(new IOException("SSE transport stopped before receiving headers"));
                transport.sseReaderFinished();
                JMeterContextService.getContext().clear();
            }
        });
        reader.start();
        try {
            return opening.get();
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    public Object getTransportKey() {
        return transportKey;
    }

    public static boolean isEventStream(HTTPSampleResult result) {
        return "200".equals(result.getResponseCode())
                && "text/event-stream".equalsIgnoreCase(result.getContentType().split(";", 2)[0].trim());
    }

    /** Called by the regular HTTP response reader after the response headers arrive. */
    public void read(HTTPSampleResult result, InputStream stream, String encoding) throws IOException {
        if (closed) {
            throw new IOException("SSE session closed");
        }
        result.latencyEnd();
        HTTPSampleResult connected = new HTTPSampleResult(result);
        connected.sampleEnd();
        connected.setSuccessful(true);
        connected.setResponseMessage("SSE stream opened");
        receiving = true;
        opening.complete(connected);
        try (InputStream decoded = ResponseDecoderRegistry.decodeStream(encoding, stream)) {
            SseParser.read(decoded, transport.getSseMaxEventCharacters(), event -> {
                if (closed) {
                    return;
                }
                SampleResult message = new SampleResult();
                message.setSampleLabel(transport.getName() + " / " + event.eventName());
                message.sampleStart();
                message.setDataType(SampleResult.TEXT);
                message.setResponseData(event.data(), "UTF-8");
                message.setResponseHeaders("event: " + event.eventName() + "\nid: " + event.eventId() + "\n");
                message.setResponseCode("200");
                message.setResponseMessage(event.eventName());
                message.setSuccessful(true);
                message.sampleEnd();
                handlers.accept(message);
                if (transport.getSseCountIncoming()) {
                    SampleResult reported = new SampleResult(message);
                    reported.setSampleLabel(transport.getSseIncomingSampleName(event.eventName()));
                    publish.accept(reported);
                }
            });
        } finally {
            receiving = false;
        }
        // Leave queued handlers alive until explicit close or virtual-user cleanup.
    }

    public boolean isOpen() {
        return !closed && receiving;
    }

    @Override
    public void close() {
        closed = true;
        handlers.close();
        transport.interrupt();
        Thread active = reader;
        if (active != null) {
            active.interrupt();
        }
        opening.completeExceptionally(new IOException("SSE session closed"));
    }

    static SampleResult copyMessage(SampleResult original) {
        SampleResult copy = new SampleResult(original);
        copy.setResponseData(original.getResponseData().clone());
        return copy;
    }

    private Consumer<SampleResult> publisher() {
        JMeterContext context = JMeterContextService.getContext();
        SamplePackage pack = (SamplePackage) context.getVariables().getObject(JMeterThread.PACKAGE_OBJECT);
        List<SampleListener> listeners = pack == null ? List.of() : pack.getSampleListeners().stream()
                .map(listener -> listener instanceof NoThreadClone ? listener
                        : (SampleListener) ((TestElement) listener).clone()).toList();
        SseSessions registry = SseSessions.current();
        JMeterContext callbackContext = JMeterContextService.createContext();
        callbackContext.setVariables(context.getVariables());
        callbackContext.setThread(context.getThread());
        callbackContext.setThreadNum(context.getThreadNum());
        callbackContext.setThreadGroup(context.getThreadGroup());
        callbackContext.setEngine(context.getEngine());
        callbackContext.setSamplingStarted(context.isSamplingStarted());
        AbstractThreadGroup threadGroup = context.getThreadGroup();
        String group = threadGroup == null ? "" : threadGroup.getName();
        List<SampleResult.TestElementPathEntry> sourcePath = pack == null ? List.of() : pack.getSourceTestElementPath();
        String threadName = context.getThread() == null ? Thread.currentThread().getName() : context.getThread().getThreadName();
        ListenerNotifier notifier = new ListenerNotifier();
        return incoming -> {
            if (closed || callbackContext.getThread() != null && !callbackContext.getThread().isRunning()) {
                return;
            }
            SampleResult result = copyMessage(incoming);
            result.setThreadName(threadName);
            result.setAllThreads(JMeterContextService.getNumberOfThreads());
            result.setGroupThreads(threadGroup == null ? 0 : threadGroup.getNumberOfThreads());
            result.setSourceTestElementPath(sourcePath);
            SampleEvent event = new SampleEvent(result, group, context.getVariables());
            registry.notifyListeners(() -> {
                if (closed) {
                    return;
                }
                JMeterContext previous = JMeterContextService.getContext();
                callbackContext.setPreviousResult(result);
                JMeterContextService.replaceContext(callbackContext);
                try {
                    notifier.notifyListeners(event, listeners);
                } finally {
                    JMeterContextService.replaceContext(previous);
                }
            });
        };
    }

}
