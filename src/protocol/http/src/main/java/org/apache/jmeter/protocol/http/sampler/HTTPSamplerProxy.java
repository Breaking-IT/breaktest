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

import java.net.URL;
import java.util.Objects;

import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.samplers.Interruptible;

/**
 * Proxy class that dispatches to the appropriate HTTP sampler.
 * <p>
 * This class is stored in the test plan, and holds all the configuration settings.
 * The actual implementation is created at run-time, and is passed a reference to this class
 * so it can get access to all the settings stored by HTTPSamplerProxy.
 */
public class HTTPSamplerProxy extends HTTPSamplerBase implements Interruptible, org.apache.jmeter.samplers.ChildControllerSampler {

    private static final long serialVersionUID = 1L;

    public static final String SSE_ENABLED = "HTTPSampler.sseEnabled";
    public static final String SSE_SESSION = "HTTPSampler.sseSession";
    public static final String SSE_COUNT = "HTTPSampler.sseCountIncoming";
    public static final String SSE_EXISTING_ACTION = "HTTPSampler.sseExistingSessionAction";
    public static final String SSE_NAME_MODE = "HTTPSampler.sseSampleNameMode";
    public static final String SSE_SAMPLE_NAME = "HTTPSampler.sseSampleName";
    public static final String SSE_MAX = "HTTPSampler.sseMaxEventCharacters";
    private transient java.util.List<org.apache.jmeter.protocol.sse.SseMatchController> sseMatches = new java.util.ArrayList<>();
    private transient org.apache.jmeter.protocol.sse.SseSession sseReader;
    private transient volatile org.apache.jmeter.protocol.sse.SseSession activeSse;

    public boolean isSseEnabled() { return getPropertyAsBoolean(SSE_ENABLED, false); }
    public void setSseEnabled(boolean value) { setProperty(SSE_ENABLED, value, false); }
    public String getSseSessionName() { return getPropertyAsString(SSE_SESSION, "sse"); }
    public void setSseSessionName(String value) { setProperty(SSE_SESSION, value, "sse"); }
    public boolean getSseCountIncoming() { return getPropertyAsBoolean(SSE_COUNT, true); }
    public String getSseExistingSessionAction() {
        return getPropertyAsString(SSE_EXISTING_ACTION, org.apache.jmeter.protocol.sse.SseSampler.RECONNECT);
    }
    public void setSseExistingSessionAction(String value) {
        setProperty(SSE_EXISTING_ACTION, value, org.apache.jmeter.protocol.sse.SseSampler.RECONNECT);
    }
    public String getSseIncomingSampleName(String eventName) {
        String name = getPropertyAsString(SSE_SAMPLE_NAME);
        if (org.apache.jmeter.protocol.sse.SseSampler.FIXED_NAME.equals(
                getPropertyAsString(SSE_NAME_MODE, org.apache.jmeter.protocol.sse.SseSampler.EVENT_NAME))) {
            return name.isEmpty() ? getName() : name;
        }
        return (name.isEmpty() ? getName() + " / " : name) + eventName;
    }
    public int getSseMaxEventCharacters() { return getPropertyAsInt(SSE_MAX, 1048576); }
    public Object getSseTransportKey() { return sseReader == null ? null : sseReader.getTransportKey(); }
    public void setSseReader(org.apache.jmeter.protocol.sse.SseSession reader) { sseReader = reader; }

    @Override
    public boolean acceptsChildController(org.apache.jmeter.control.Controller controller) {
        return isSseEnabled() && controller instanceof org.apache.jmeter.protocol.sse.SseMatchController;
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void addChildController(org.apache.jmeter.control.Controller controller) {
        if (!isSseEnabled() || !(controller instanceof org.apache.jmeter.protocol.sse.SseMatchController match)) {
            throw new IllegalArgumentException("SSE Connect only accepts SSE Match controllers");
        }
        if (sseMatches.stream().noneMatch(existing -> existing == match)) {
            sseMatches.add(match);
        }
    }

    @Override
    public java.util.List<org.apache.jmeter.control.Controller> createDefaultChildControllers() {
        return java.util.List.of();
    }

    @Override
    public Object clone() {
        HTTPSamplerProxy copy = (HTTPSamplerProxy) super.clone();
        copy.impl = null;
        copy.sseReader = null;
        copy.activeSse = null;
        copy.sseMatches = new java.util.ArrayList<>(sseMatches);
        return copy;
    }

    @Override
    public void readResponse(org.apache.jmeter.samplers.SampleResult result, java.io.InputStream input,
            long length, String encoding) throws java.io.IOException {
        if (sseReader != null && result instanceof HTTPSampleResult http && org.apache.jmeter.protocol.sse.SseSession.isEventStream(http)) {
            sseReader.read(http, input, encoding);
        } else {
            super.readResponse(result, input, length, encoding);
        }
    }

    /** Releases reader-local resources without closing the virtual user's HTTP/2 pool. */
    public void sseReaderFinished() {
        if (impl != null) {
            impl.streamFinished();
        }
    }

    private transient HTTPAbstractImpl impl;
    private transient String implHttpProtocol;
    private transient String implConfiguredImplementation;

    public HTTPSamplerProxy(){
        super();
    }

    /**
     * Convenience method used to initialise the implementation.
     *
     * @param impl the implementation to use.
     */
    public HTTPSamplerProxy(String impl){
        super();
        setImplementation(impl);
    }

    /** {@inheritDoc} */
    @Override
    protected HTTPSampleResult sample(URL u, String method, boolean areFollowingRedirect, int depth) {
        if (isSseEnabled() && sseReader == null) {
            org.apache.jmeter.protocol.sse.SseSession session = null;
            org.apache.jmeter.protocol.sse.SseSessions sessions = null;
            String sessionName = getSseSessionName();
            boolean opened = false;
            try {
                if (!"http".equalsIgnoreCase(u.getProtocol()) && !"https".equalsIgnoreCase(u.getProtocol())) {
                    throw new IllegalArgumentException("SSE requires an HTTP or HTTPS URL");
                }
                if (getSseSessionName().isBlank() || getSseMaxEventCharacters() <= 0) {
                    throw new IllegalArgumentException("SSE needs a session name and a positive maximum event size");
                }
                session = new org.apache.jmeter.protocol.sse.SseSession(this, sseMatches);
                sessions = org.apache.jmeter.protocol.sse.SseSessions.current();
                if (!sessions.connect(sessionName, session, getSseExistingSessionAction())) {
                    session.close();
                    HTTPSampleResult result = new HTTPSampleResult();
                    result.sampleStart();
                    result.setSampleLabel(getName());
                    result.setSamplerData("Reused SSE session: " + getSseSessionName());
                    result.setResponseCodeOK();
                    result.setResponseMessage("Existing SSE stream reused; no HTTP request performed");
                    result.setSuccessful(true);
                    result.sampleEnd();
                    return result;
                }
                activeSse = session;
                HTTPSampleResult result = session.open();
                opened = result.isSuccessful() && org.apache.jmeter.protocol.sse.SseSession.isEventStream(result);
                return result;
            } catch (Exception failure) {
                if (session != null) {
                    session.close();
                }
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                HTTPSampleResult result = new HTTPSampleResult();
                result.sampleStart();
                result.sampleEnd();
                return errorResult(failure, result);
            } finally {
                if (!opened && session != null && sessions != null) {
                    sessions.remove(sessionName, session);
                }
            }
        }
        // When Retrieve Embedded resources + Concurrent Pool is used
        // as the instance of Proxy is cloned, we end up with impl being null
        // testIterationStart will not be executed but it's not a problem for 51380 as it's download of resources
        // so SSL context is to be reused
        String httpProtocol = getHttpProtocol();
        String implementation = getImplementation();
        if (impl != null && (!Objects.equals(implHttpProtocol, httpProtocol)
                || !Objects.equals(implConfiguredImplementation, implementation))) {
            // Variables and defaults can change between iterations. A cached HTTP/1.1
            // implementation cannot honor a later HTTP/2 selection (or vice versa).
            impl.threadFinished();
            impl = null;
        }
        if (impl == null) { // Not called from multiple threads, so this is OK
            try {
                impl = HTTPSamplerFactory.getImplementation(implementation, this);
                implHttpProtocol = httpProtocol;
                implConfiguredImplementation = implementation;
            } catch (Exception ex) {
                return errorResult(ex, new HTTPSampleResult());
            }
        }
        return impl.sample(u, method, areFollowingRedirect, depth);
    }

    // N.B. It's not possible to forward threadStarted() to the implementation class.
    // This is because Config items are not processed until later, and HTTPDefaults may define the implementation

    @Override
    public void threadFinished(){
        if (activeSse != null) {
            activeSse.close();
            activeSse = null;
        }
        if (impl != null){
            impl.threadFinished(); // Forward to sampler
        }
    }

    @Override
    public boolean interrupt() {
        if (activeSse != null) {
            activeSse.close();
            return true;
        }
        if (impl != null) {
            return impl.interrupt(); // Forward to sampler
        }
        return false;
    }

    @Override
    public void testIterationStart(LoopIterationEvent event) {
        if (impl != null) {
            impl.notifyFirstSampleAfterLoopRestart();
        }
    }
}
