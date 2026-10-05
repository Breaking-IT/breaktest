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

package org.apache.jmeter.protocol.http.proxy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.parser.HTMLParseException;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.ConversionUtils;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.JOrphanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread to handle a client connection. Gets each request from the client and
 * passes it on to the server, then sends the response back to the client.
 * Information about the request and response is stored so it can be used in a
 * JMeter test plan.
 */
public class Proxy extends Thread {
    // Mime-types of resources, that are not HTML and not binary that should be skipped on form parsing in JSoup
    private static final List<String> NOT_HTML_TEXT_TYPES = Arrays.asList("application/javascript", "application/json", "text/javascript");

    private static final Logger log = LoggerFactory.getLogger(Proxy.class);


    private static final String[] HEADERS_TO_REMOVE;

    // Allow list of headers to be overridden
    private static final String PROXY_HEADERS_REMOVE = "proxy.headers.remove"; // $NON-NLS-1$

    private static final String PROXY_HEADERS_REMOVE_DEFAULT = "If-Modified-Since,If-None-Match,Host"; // $NON-NLS-1$

    private static final String PROXY_HEADERS_REMOVE_SEPARATOR = ","; // $NON-NLS-1$

    private static final String KEYMANAGERFACTORY =
        JMeterUtils.getPropDefault("proxy.cert.factory", "SunX509"); // $NON-NLS-1$ $NON-NLS-2$

    private static final String SSLCONTEXT_PROTOCOL =
        JMeterUtils.getPropDefault("proxy.ssl.protocol", "TLS"); // $NON-NLS-1$ $NON-NLS-2$

    private static final String[] SOCKET_PROTOCOL_ARRAY =
            JMeterUtils.getArrayPropDefault("https.socket.protocols", null); // $NON-NLS-1$

    private static final String[] SUPPORTED_CIPHER_ARRAY =
            JMeterUtils.getArrayPropDefault("https.cipherSuites", null); // $NON-NLS-1$

    // HashMap to save ssl connection between Jmeter proxy and browser
    private static final HashMap<String, SSLSocketFactory> HOST2SSL_SOCK_FAC = new HashMap<>();

    private static final SamplerCreatorFactory SAMPLERFACTORY = new SamplerCreatorFactory();

    static {
        String removeList = JMeterUtils.getPropDefault(PROXY_HEADERS_REMOVE,PROXY_HEADERS_REMOVE_DEFAULT);
        HEADERS_TO_REMOVE = JOrphanUtils.split(removeList,PROXY_HEADERS_REMOVE_SEPARATOR);
        log.info("Proxy will remove the headers: {}", removeList);
    }

    /** Socket to client. */
    private Socket clientSocket = null;

    /** Target to receive the generated sampler. */
    private ProxyControl target;


    /** Reference to Daemon's Map of url string to page character encoding of that page */
    private Map<String, String> pageEncodings;
    /** Reference to Daemon's Map of url string to character encoding for the form */
    private Map<String, String> formEncodings;

    private String port; // For identifying log messages

    private KeyStore keyStore; // keystore for SSL keys; fixed at config except for dynamic host key generation

    private String keyPassword;

    /**
     * Default constructor - used by newInstance call in Daemon
     */
    public Proxy() {
        port = "";
    }

    /**
     * Configure the Proxy.
     * Intended to be called directly after construction.
     * Should not be called after it has been passed to a new thread,
     * otherwise the variables may not be published correctly.
     *
     * @param clientSocket
     *            the socket connection to the client
     * @param target
     *            the ProxyControl which will receive the generated sampler
     * @param pageEncodings
     *            reference to the Map of Deamon, with mappings from page urls to encoding used
     * @param formEncodings
     *            reference to the Map of Deamon, with mappings from form action urls to encoding used
     */
    void configure(Socket clientSocket, ProxyControl target, Map<String, String> pageEncodings, Map<String, String> formEncodings) {
        this.target = target;
        transport.setRecordingSettings(() -> RecordingRequestSettings.capture(target));
        this.clientSocket = clientSocket;
        this.pageEncodings = pageEncodings;
        this.formEncodings = formEncodings;
        this.port = "["+ clientSocket.getPort() + "] ";
        this.keyStore = target.getKeyStore();
        this.keyPassword = target.getKeyPassword();
    }

    /**
     * Main processing method for the Proxy object
     */
    private final HttpProxyTransport transport = new HttpProxyTransport();
    private volatile boolean stopRequested;
    private boolean requestStarted;

    @Override
    public void run() {
        boolean http2 = false;
        String destination = "Unknown destination (request headers incomplete)";
        String method = "Unknown";
        try {
            JMeterContextService.getContext().setRecording(true);
            java.io.InputStream input = clientSocket.getInputStream();
            String tunnelAuthority = null;
            HttpProxyTransport.Head head = HttpProxyTransport.readHead(input, this::requestSettings);
            if (head != null && "CONNECT".equals(head.method())) {
                tunnelAuthority = head.target();
                destination = "https://" + tunnelAuthority;
                method = "CONNECT (TLS handshake)";
                java.net.URI endpoint = java.net.URI.create("https://" + tunnelAuthority);
                clientSocket.getOutputStream().write(
                        "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                clientSocket.getOutputStream().flush();
                clientSocket = startSSL(clientSocket, endpoint.toURL());
                requestStarted = false;
                input = new BufferedInputStream(clientSocket.getInputStream());
                if ("h2".equals(((SSLSocket) clientSocket).getApplicationProtocol())) {
                    http2 = true;
                    transport.relayHttp2(clientSocket, input, capture -> target.submitCapture(() -> record(capture)),
                            () -> RecordingRequestSettings.capture(target), target.getRecordingDiagnostics());
                    return;
                }
                method = "Unknown (request headers incomplete)";
                head = HttpProxyTransport.readHead(input, this::requestSettings);
            } else {
                input = new BufferedInputStream(input);
            }
            while (head != null) {
                destination = head.url(tunnelAuthority).toString();
                method = head.method();
                HttpProxyTransport.Capture capture = transport.forward(head, head.url(tunnelAuthority), clientSocket, input);
                if (capture.upgraded()) {
                    requestStarted = false;
                    try {
                        transport.tunnel(clientSocket, input, capture);
                    } finally {
                        target.submitCapture(() -> record(capture));
                    }
                    break;
                }
                try {
                    target.submitCapture(() -> record(capture));
                } catch (RuntimeException e) {
                    target.getRecordingDiagnostics().processingError("Unable to enqueue captured request: " + e);
                    log.error("Unable to add captured request {} to the test plan", capture.getUrlAsString(), e);
                }
                requestStarted = false;
                if (!capture.keepAlive()) {
                    break;
                }
                destination = tunnelAuthority == null ? "Unknown destination (request headers incomplete)" : "https://" + tunnelAuthority;
                method = "Unknown (request headers incomplete)";
                head = HttpProxyTransport.readHead(input, this::requestSettings);
            }
        } catch (Exception e) {
            if (!http2 && requestStarted) {
                target.getRecordingDiagnostics().uncapturedFailure(destination, method, (stopRequested ? "Recorder stopped" : "Recording connection ended")
                        + " before the request could be captured: " + e);
            }
            log.debug("{} Recording connection closed: {}", port, e.toString());
        } finally {
            stopRecording();
            JMeterContextService.getContext().setRecording(false);
        }
    }

    private RecordingRequestSettings requestSettings() {
        requestStarted = true;
        return RecordingRequestSettings.capture(target);
    }

    /** Interrupt persistent connections, streaming responses and upgraded tunnels on recorder stop. */
    void stopRecording() {
        stopRequested = true;
        transport.stop();
        try {
            clientSocket.close();
        } catch (IOException e) {
            log.debug("{} Closing recorder client", port, e);
        }
    }

    private void record(HttpProxyTransport.Capture result) {
        RecordingRequestSettings settings = result.request().settings;
        HttpRequestHdr request = new HttpRequestHdr(settings.prefix(), settings.samplerType(), settings.namingMode(), settings.format());
        request.setDetectGraphQLRequest(settings.graphQL());
        HTTPSamplerBase sampler;
        List<TestElement> children = new ArrayList<>();
        try {
            request.parseCaptured(result.request(), result.getURL(), result.requestBody());
            SamplerCreator creator = SAMPLERFACTORY.getSamplerCreator(request, pageEncodings, formEncodings);
            sampler = creator.createAndPopulateSampler(request, pageEncodings, formEncodings);
            creator.postProcessSampler(sampler, result);
            children.addAll(creator.createChildren(sampler, result));
            if (result.sse == null) {
                String pageEncoding = addPageEncoding(result);
                addFormEncodings(result, pageEncoding);
            }
        } catch (Exception e) {
            // A replay conversion problem must never prevent the browser receiving its response.
            target.getRecordingDiagnostics().processingError("Replay conversion failed: " + e);
            log.warn("Unable to convert recorded request {}", result.getUrlAsString(), e);
            sampler = fallbackSampler(result, e);
        }
        if (result.sse != null) {
            var nativeSse = new org.apache.jmeter.protocol.sse.SseSampler();
            var properties = sampler.propertyIterator();
            while (properties.hasNext()) {
                nativeSse.setProperty(properties.next().clone());
            }
            nativeSse.setProperty(TestElement.TEST_CLASS, org.apache.jmeter.protocol.sse.SseSampler.class.getName());
            nativeSse.setProperty(TestElement.GUI_CLASS, org.apache.jmeter.protocol.sse.SseSamplerGui.class.getName());
            nativeSse.setSseSessionName("proxy-sse-" + java.util.UUID.randomUUID());
            nativeSse.setResponseTimeout(org.apache.jmeter.protocol.sse.SseSampler.DEFAULT_RESPONSE_TIMEOUT);
            sampler = nativeSse;
        }
        if ("HTTP/2".equals(result.getProtocolVersion())) {
            sampler.setHttpProtocol(HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2);
        }
        HeaderManager headers = request.getHeaderManager();
        headers.removeHeaderNamed(HTTPConstants.HEADER_COOKIE);
        for (String header : HEADERS_TO_REMOVE) {
            headers.removeHeaderNamed(header);
        }
        children.add(headers);
        target.deliverSampler(sampler, children.toArray(new TestElement[0]), result);
    }

    static HTTPSamplerBase fallbackSampler(HttpProxyTransport.Capture result, Exception e) {
        HTTPSamplerBase sampler = new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy();
        sampler.setProperty(TestElement.GUI_CLASS, org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui.class.getName());
        sampler.setProtocol(result.getURL().getProtocol());
        sampler.setDomain(result.getURL().getHost());
        sampler.setPort(result.getURL().getPort() < 0 ? HTTPSamplerBase.UNSPECIFIED_PORT : result.getURL().getPort());
        sampler.setPath(result.getURL().getFile());
        sampler.setMethod(result.getHTTPMethod());
        sampler.setName(result.getHTTPMethod() + " " + result.getURL().getFile());
        sampler.setEnabled(false);
        sampler.setComment("Replay conversion failed: " + e);
        return sampler;
    }

    /**
     * Set the counter for all registered {@link SamplerCreatorFactory}s
     *
     * @param value to be initialized
     */
    public static void setCounter(int value) {
        SAMPLERFACTORY.setCounter(value);
    }

    /**
     * Get SSL connection from hashmap, creating it if necessary.
     *
     * @param host
     * @return a ssl socket factory, or null if keystore could not be opened/processed
     */
    private SSLSocketFactory getSSLSocketFactory(String host) {
        if (keyStore == null) {
            log.error("{} No keystore available, cannot record SSL", port);
            return null;
        }
        final String hashAlias;
        final String keyAlias;
        switch (ProxyControl.KEYSTORE_MODE) {
            case DYNAMIC_KEYSTORE -> {
                try {
                    keyStore = target.getKeyStore(); // pick up any recent changes from other threads
                    String alias = getDomainMatch(keyStore, host);
                    if (alias == null) {
                        hashAlias = host;
                        keyAlias = host;
                        keyStore = target.updateKeyStore(port, keyAlias);
                    } else if (alias.equals(host)) { // the host has a key already
                        hashAlias = host;
                        keyAlias = host;
                    } else { // the host matches a domain; use its key
                        hashAlias = alias;
                        keyAlias = alias;
                    }
                } catch (IOException | GeneralSecurityException e) {
                    log.error("{} Problem with keystore", port, e);
                    return null;
                }
            }
            case JMETER_KEYSTORE -> hashAlias = keyAlias = ProxyControl.JMETER_SERVER_ALIAS;
            case USER_KEYSTORE -> hashAlias = keyAlias = ProxyControl.CERT_ALIAS;
            default -> throw new IllegalStateException("Impossible case: " + ProxyControl.KEYSTORE_MODE);
        }
        synchronized (HOST2SSL_SOCK_FAC) {
            final SSLSocketFactory sslSocketFactory = HOST2SSL_SOCK_FAC.get(hashAlias);
            if (sslSocketFactory != null) {
                log.debug("{} Good, already in map, host={} using alias {}", port, host, hashAlias);
                return sslSocketFactory;
            }
            try {
                SSLContext sslcontext = SSLContext.getInstance(SSLCONTEXT_PROTOCOL);
                sslcontext.init(getWrappedKeyManagers(keyAlias), null, null);
                SSLSocketFactory sslFactory = sslcontext.getSocketFactory();
                HOST2SSL_SOCK_FAC.put(hashAlias, sslFactory);
                log.info("{} KeyStore for SSL loaded OK and put host '{}' in map with key ({})", port, host, hashAlias);
                return sslFactory;
            } catch (GeneralSecurityException e) {
                log.error("{} Problem with SSL certificate", port, e);
            } catch (IOException e) {
                log.error("{} Problem with keystore", port, e);
            }
            return null;
        }
    }

    /**
     * Get matching alias for a host from keyStore that may contain domain aliases.
     * Assumes domains must have at least 2 parts (apache.org);
     * does not check if TLD requires more (google.co.uk).
     * Note that DNS wildcards only apply to a single level, i.e.
     * podling.incubator.apache.org matches *.incubator.apache.org
     * but does not match *.apache.org
     *
     * @param keyStore the KeyStore to search
     * @param host the hostname to match
     * @return the keystore entry or {@code null} if no match found
     * @throws KeyStoreException
     */
    private static String getDomainMatch(KeyStore keyStore, String host) throws KeyStoreException {
        if (keyStore.containsAlias(host)) {
            return host;
        }
        String[] parts = host.split("\\."); // get the component parts
        // Assume domains must have at least 2 parts, e.g. apache.org
        // Replace the first part with "*"
        StringBuilder sb = new StringBuilder("*"); // $NON-NLS-1$
        for(int j = 1; j < parts.length ; j++) { // Skip the first part
            sb.append('.');
            sb.append(parts[j]);
        }
        String alias = sb.toString();
        if (keyStore.containsAlias(alias)) {
            return alias;
        }
        return null;
    }

    /**
     * Return the key managers, wrapped to return a specific alias
     */
    private KeyManager[] getWrappedKeyManagers(final String keyAlias)
            throws GeneralSecurityException, IOException {
        if (!keyStore.containsAlias(keyAlias)) {
            throw new IOException("Keystore does not contain alias " + keyAlias);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KEYMANAGERFACTORY);
        kmf.init(keyStore, keyPassword.toCharArray());
        final KeyManager[] keyManagers = kmf.getKeyManagers();
        // Check if alias is suitable here, rather than waiting for connection to fail
        final int keyManagerCount = keyManagers.length;
        final KeyManager[] wrappedKeyManagers = new KeyManager[keyManagerCount];
        for (int i =0; i < keyManagerCount; i++) {
            wrappedKeyManagers[i] = new ServerAliasKeyManager(keyManagers[i], keyAlias);
        }
        return wrappedKeyManagers;
    }

    /**
     * Negotiate a SSL connection.
     *
     * @param sock socket in
     * @param endpoint
     * @return a new client socket over ssl
     * @throws IOException if negotiation failed
     */
    private Socket startSSL(Socket sock, URL endpoint) throws IOException {
        String host = endpoint.getHost();
        SSLSocketFactory sslFactory = getSSLSocketFactory(host);
        SSLSocket secureSocket;
        if (sslFactory != null) {
            try {
                secureSocket = (SSLSocket) sslFactory.createSocket(sock,
                        sock.getInetAddress().getHostName(), sock.getPort(), true);
                secureSocket.setUseClientMode(false);
                javax.net.ssl.SSLParameters parameters = secureSocket.getSSLParameters();
                parameters.setApplicationProtocols(new String[]{"h2", "http/1.1"});
                secureSocket.setSSLParameters(parameters);
                secureSocket.setHandshakeApplicationProtocolSelector((socket, offered) -> {
                    try {
                        String selected = transport.negotiate(endpoint, offered);
                        return selected.isEmpty() ? null : selected;
                    } catch (IOException e) {
                        // Allow an HTTP/1.1 request to arrive so an upstream connection failure
                        // can be retained as a disabled sampler with its diagnostic.
                        log.debug("Unable to negotiate upstream TLS for {}", endpoint, e);
                        transport.close();
                        return offered.contains("http/1.1") ? "http/1.1" : null;
                    }
                });
                if (SUPPORTED_CIPHER_ARRAY != null) {
                    secureSocket.setEnabledCipherSuites(SUPPORTED_CIPHER_ARRAY);
                }
                if (SOCKET_PROTOCOL_ARRAY != null) {
                    secureSocket.setEnabledProtocols(SOCKET_PROTOCOL_ARRAY);
                }
                if (log.isDebugEnabled()){
                    log.debug("{} SSL transaction ok with cipher: {}", port, secureSocket.getSession().getCipherSuite());
                }
                secureSocket.startHandshake();
                return secureSocket;
            } catch (IOException e) {
                log.error("{} Error in SSL socket negotiation: ", port, e);
                throw e;
            }
        } else {
            log.warn("{} Unable to negotiate SSL transaction, no keystore?", port);
            throw new IOException("Unable to negotiate SSL transaction, no keystore?");
        }
    }

    private String addPageEncoding(SampleResult result) {
        String pageEncoding = null;
        try {
            pageEncoding = ConversionUtils.getEncodingFromContentType(result.getContentType());
        } catch(IllegalCharsetNameException ex) {
            log.warn("Unsupported charset detected in contentType:'{}', will continue processing with default charset",
                    result.getContentType(), ex);
        }
        if (pageEncoding != null) {
            String urlWithoutQuery = getUrlWithoutQuery(result.getURL());
            pageEncodings.put(urlWithoutQuery, pageEncoding);
        }
        return pageEncoding;
    }

    /**
     * Add the form encodings for all forms in the sample result
     *
     * @param result the sample result to check
     * @param pageEncoding the encoding used for the sample result page
     */
    private void addFormEncodings(SampleResult result, String pageEncoding) {
        FormCharSetFinder finder = new FormCharSetFinder();
        if (SampleResult.isBinaryType(result.getContentType())) {
            if (log.isDebugEnabled()) {
                log.debug("Will not guess encoding of URL: {} as it's binary", result.getUrlAsString());
            }
            return; // no point parsing anything else, e.g. GIF ...
        }
        if (isNotHtmlType(result.getContentType())) {
            if (log.isDebugEnabled()) {
                log.debug("Will not guess encoding of URL: {} as it's not HTML", result.getUrlAsString());
            }
            return; // None HTML types have been crashing JSoup parser, so return here early
        }
        try {
            finder.addFormActionsAndCharSet(result.getResponseDataAsString(), formEncodings, pageEncoding);
        }
        catch (HTMLParseException parseException) {
            if (log.isDebugEnabled()) {
                log.debug("{} Unable to parse response, could not find any form character set encodings for url:{}", port, result.getUrlAsString());
            }
        }
    }

    private static boolean isNotHtmlType(String contentType) {
        for (String mimeType: NOT_HTML_TEXT_TYPES) {
            if (contentType.startsWith(mimeType)) {
                return true;
            }
        }
        return false;
    }

    private static String getUrlWithoutQuery(URL url) {
        String fullUrl = url.toString();
        String urlWithoutQuery = fullUrl;
        String query = url.getQuery();
        if(query != null) {
            // Get rid of the query and the ?
            urlWithoutQuery = urlWithoutQuery.substring(0, urlWithoutQuery.length() - query.length() - 1);
        }
        return urlWithoutQuery;
    }
}
