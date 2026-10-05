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
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import javax.swing.SwingUtilities;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.assertions.gui.AssertionGui;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigElement;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.control.gui.LogicControllerGui;
import org.apache.jmeter.control.gui.TransactionControllerGui;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.functions.InvalidVariableException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.control.RecordingController;
import org.apache.jmeter.protocol.http.gui.AuthPanel;
import org.apache.jmeter.protocol.http.har.FindPredefinedCorrelationsAction;
import org.apache.jmeter.protocol.http.har.HarConverter;
import org.apache.jmeter.protocol.http.har.HarEntry;
import org.apache.jmeter.protocol.http.har.HarImportOptions;
import org.apache.jmeter.protocol.http.proxy.gui.RecorderWizard;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerFactory;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.recording.RecordedExchangeStore;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.JmxArchiveEntryStore;
import org.apache.jmeter.scenario.SharedProfile;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testelement.NonTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.Visualizer;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.exec.KeyToolUtils;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.jorphan.util.StringUtilities;
import org.apache.oro.text.MalformedCachePatternException;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.Perl5Compiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Class handles storing of generated samples etc. */
public class ProxyControl extends GenericController implements NonTestElement {

    private static final Logger log = LoggerFactory.getLogger(ProxyControl.class);

    private static final long serialVersionUID = 240L;

    private static final String ASSERTION_GUI = AssertionGui.class.getName();
    private static final String TRANSACTION_CONTROLLER_GUI = TransactionControllerGui.class.getName();
    private static final String LOGIC_CONTROLLER_GUI = LogicControllerGui.class.getName();
    private static final String AUTH_PANEL = AuthPanel.class.getName();
    private static final String AUTH_MANAGER = AuthManager.class.getName();

    public static final int DEFAULT_PORT = 8888;
    // and as a string
    public static final String DEFAULT_PORT_S = Integer.toString(DEFAULT_PORT);

    //+ JMX file attributes
    private static final String PORT = "ProxyControlGui.port"; // $NON-NLS-1$
    private static final String DOMAINS = "ProxyControlGui.domains"; // $NON-NLS-1$
    private static final String EXCLUDE_LIST = "ProxyControlGui.exclude_list"; // $NON-NLS-1$
    private static final String INCLUDE_LIST = "ProxyControlGui.include_list"; // $NON-NLS-1$
    private static final String STORE_RECORDED_EXCHANGES = "ProxyControlGui.store_recorded_exchanges"; // $NON-NLS-1$
    private static final String IGNORE_HTTP_ERRORS = "ProxyControl.ignore_http_errors";

    private static final String ADD_ASSERTIONS = "ProxyControlGui.add_assertion"; // $NON-NLS-1$
    private static final String GROUPING_MODE = "ProxyControlGui.grouping_mode"; // $NON-NLS-1$
    private static final String SAMPLER_TYPE_NAME = "ProxyControlGui.sampler_type_name"; // $NON-NLS-1$
    private static final String SAMPLER_REDIRECT_AUTOMATICALLY = "ProxyControlGui.sampler_redirect_automatically"; // $NON-NLS-1$
    private static final String SAMPLER_FOLLOW_REDIRECTS = "ProxyControlGui.sampler_follow_redirects"; // $NON-NLS-1$
    private static final String USE_KEEPALIVE = "ProxyControlGui.use_keepalive"; // $NON-NLS-1$
    private static final String DETECT_GRAPHQL_REQUEST = "ProxyControlGui.detect_graphql_request"; // $NON-NLS-1$
    private static final String SAMPLER_DOWNLOAD_IMAGES = "ProxyControlGui.sampler_download_images"; // $NON-NLS-1$
    private static final String HTTP_SAMPLER_NAMING_MODE = "ProxyControlGui.proxy_http_sampler_naming_mode"; // $NON-NLS-1$
    private static final String HTTP_SAMPLER_FORMAT = "ProxyControlGui.proxy_http_sampler_format"; // $NON-NLS-1$
    private static final String PREFIX_HTTP_SAMPLER_NAME = "ProxyControlGui.proxy_prefix_http_sampler_name"; // $NON-NLS-1$
    private static final String PROXY_PAUSE_HTTP_SAMPLER = "ProxyControlGui.proxy_pause_http_sampler"; // $NON-NLS-1$
    private static final String DEFAULT_ENCODING_PROPERTY = "ProxyControlGui.default_encoding"; // $NON-NLS-1$
    private static final String REGEX_MATCH = "ProxyControlGui.regex_match"; // $NON-NLS-1$
    private static final String CONTENT_TYPE_EXCLUDE = "ProxyControlGui.content_type_exclude"; // $NON-NLS-1$
    private static final String CONTENT_TYPE_INCLUDE = "ProxyControlGui.content_type_include"; // $NON-NLS-1$
    private static final String NOTIFY_CHILD_SAMPLER_LISTENERS_FILTERED = "ProxyControlGui.notify_child_sl_filtered"; // $NON-NLS-1$

    private static final String BEARER_AUTH = "Bearer"; // $NON-NLS-1$
    private static final String BASIC_AUTH = "Basic"; // $NON-NLS-1$
    private static final String DIGEST_AUTH = "Digest"; // $NON-NLS-1$

    //- JMX file attributes

    // Must agree with the order of entries in the drop-down
    // created in ProxyControlGui.createGroupingPanel()
    private static final int GROUPING_ADD_SEPARATORS = 1;
    private static final int GROUPING_IN_SIMPLE_CONTROLLERS = 2;
    private static final int GROUPING_STORE_FIRST_ONLY = 3;
    private static final int GROUPING_IN_TRANSACTION_CONTROLLERS = 4;

    // Legacy numeric sampler type values from old JMX workbench files.
    private static final Set<String> LEGACY_NUMERIC_SAMPLER_TYPES = Set.of("0", "1", "2");

    // for ssl connection
    private static final String KEYSTORE_TYPE =
            JMeterUtils.getPropDefault("proxy.cert.type", "JKS"); // $NON-NLS-1$ $NON-NLS-2$

    // Proxy configuration SSL
    private static final String CERT_DIRECTORY =
            JMeterUtils.getPropDefault("proxy.cert.directory", JMeterUtils.getJMeterBinDir()); // $NON-NLS-1$

    private static final String CERT_FILE_DEFAULT = "proxyserver.jks";// $NON-NLS-1$

    private static final String CERT_FILE =
            JMeterUtils.getPropDefault("proxy.cert.file", CERT_FILE_DEFAULT); // $NON-NLS-1$

    private static final File CERT_PATH = new File(CERT_DIRECTORY, CERT_FILE);

    private static final String CERT_PATH_ABS = CERT_PATH.getAbsolutePath();

    private static final String DEFAULT_PASSWORD = "password"; // $NON-NLS-1$ NOSONAR only default password, if user has not defined one

    /** Keys for user preferences */
    private static final String USER_PASSWORD_KEY = "proxy_cert_password"; // NOSONAR not a hardcoded password

    // Note: Windows user preferences are stored relative to: HKEY_CURRENT_USER\Software\JavaSoft\Prefs
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(ProxyControl.class);

    private static final boolean USE_DYNAMIC_KEYS = JMeterUtils.getPropDefault("proxy.cert.dynamic_keys", true); // $NON-NLS-1$

    // The alias to be used if dynamic host names are not possible
    static final String JMETER_SERVER_ALIAS = ":jmeter:"; // $NON-NLS-1$

    public static final int CERT_VALIDITY = JMeterUtils.getPropDefault("proxy.cert.validity", 7); // $NON-NLS-1$

    // If this is defined, it is assumed to be the alias of a user-supplied certificate; overrides dynamic mode
    static final String CERT_ALIAS = JMeterUtils.getProperty("proxy.cert.alias"); // $NON-NLS-1$

    private static final String DEFAULT_SAMPLER_FORMAT = JMeterUtils.getPropDefault("proxy.sampler_format",
            "#{counter,number,000} - #{path} (#{name})");

    public enum KeystoreMode {
        USER_KEYSTORE,   // user-provided keystore
        JMETER_KEYSTORE, // keystore generated by JMeter; single entry
        DYNAMIC_KEYSTORE,// keystore generated by JMeter; dynamic entries
        NONE             // cannot use keystore
    }

    static final KeystoreMode KEYSTORE_MODE;

    static {
        if (CERT_ALIAS != null) {
            KEYSTORE_MODE = KeystoreMode.USER_KEYSTORE;
            log.info(
                    "HTTP(S) Test Script Recorder will use the keystore '{}' with the alias: '{}'",
                    CERT_PATH_ABS, CERT_ALIAS);
        } else {
            if (!KeyToolUtils.haveKeytool()) {
                KEYSTORE_MODE = KeystoreMode.NONE;
            } else if (USE_DYNAMIC_KEYS) {
                KEYSTORE_MODE = KeystoreMode.DYNAMIC_KEYSTORE;
                log.info(
                        "HTTP(S) Test Script Recorder SSL Proxy will use keys that support embedded 3rd party resources in file {}",
                        CERT_PATH_ABS);
            } else {
                KEYSTORE_MODE = KeystoreMode.JMETER_KEYSTORE;
                log.warn(
                        "HTTP(S) Test Script Recorder SSL Proxy will use keys that may not work for embedded resources in file {}",
                        CERT_PATH_ABS);
            }
        }
    }

    /**
     * Whether to use the redirect disabling feature (can be switched off if it does not work)
     */
    private static final boolean USE_REDIRECT_DISABLING =
            JMeterUtils.getPropDefault("proxy.redirect.disabling", true); // $NON-NLS-1$

    // Although this field is mutable, it is only accessed within the synchronized method deliverSampler()
    private static String LAST_REDIRECT = null;

    private static final boolean USE_JAVA_REGEX = !JMeterUtils.getPropDefault(
            "jmeter.regex.engine", "oro").equalsIgnoreCase("oro");

    private transient Daemon server;

    private transient KeyStore keyStore;

    private volatile boolean notifyChildSamplerListenersOfFilteredSamples = true;

    private final Set<Class<?>> addableInterfaces = new HashSet<>(
            Arrays.asList(Visualizer.class, ConfigElement.class,
                    Assertion.class, Timer.class, PreProcessor.class,
                    PostProcessor.class, SampleListener.class));

    /**
     * Tree node where the samples should be stored.
     * This property is not persistent.
     */
    private volatile JMeterTreeNode target;
    private transient volatile JMeterTreeNode recordingTarget;

    private String storePassword;

    private String keyPassword;

    private JMeterTreeModel nonGuiTreeModel;

    private final Queue<RecordedSampler> sampleQueue = new ConcurrentLinkedQueue<>();

    // accessed from Swing-Thread, only

    private transient volatile ExecutorService captureWorker;
    private transient volatile RecordingDiagnostics diagnostics = new RecordingDiagnostics();

    public RecordingDiagnostics getRecordingDiagnostics() {
        return diagnostics;
    }

    public ProxyControl() {
        setPort(DEFAULT_PORT);
        setExcludeList(new HashSet<>());
        setIncludeList(new HashSet<>());
        // Preserve the legacy property position for JMX round-trips; headers are now unconditional.
        setProperty("ProxyControlGui.capture_http_headers", true);
    }

    /**
     * Set a {@link JMeterTreeModel} to be used by the ProxyControl, when used
     * in a non-GUI environment, where the {@link JMeterTreeModel} can't be
     * acquired through {@link GuiPackage#getTreeModel()}
     *
     * @param treeModel the {@link JMeterTreeModel} to be used, or {@code null} when
     *                  the GUI model should be used
     */
    public void setNonGuiTreeModel(JMeterTreeModel treeModel) {
        this.nonGuiTreeModel = treeModel;
    }

    public void setPort(int port) {
        this.setProperty(new IntegerProperty(PORT, port));
    }

    public void setPort(String port) {
        setProperty(PORT, port);
    }

    public void setSslDomains(String domains) {
        setProperty(DOMAINS, domains, "");
    }

    public String getSslDomains() {
        return getPropertyAsString(DOMAINS, "");
    }

    public void setStoreRecordedExchanges(boolean store) {
        setProperty(new BooleanProperty(STORE_RECORDED_EXCHANGES, store));
    }

    public void setGroupingMode(int grouping) {
        setProperty(new IntegerProperty(GROUPING_MODE, grouping));
    }

    public void setAssertions(boolean b) {
        setProperty(new BooleanProperty(ADD_ASSERTIONS, b));
    }

    public void setSamplerTypeName(String samplerTypeName) {
        setProperty(new StringProperty(SAMPLER_TYPE_NAME, samplerTypeName));
    }

    public void setSamplerRedirectAutomatically(boolean b) {
        setProperty(new BooleanProperty(SAMPLER_REDIRECT_AUTOMATICALLY, b));
    }

    public void setSamplerFollowRedirects(boolean b) {
        setProperty(new BooleanProperty(SAMPLER_FOLLOW_REDIRECTS, b));
    }

    public void setUseKeepAlive(boolean b) {
        setProperty(new BooleanProperty(USE_KEEPALIVE, b));
    }

    public void setDetectGraphQLRequest(boolean b) {
        setProperty(new BooleanProperty(DETECT_GRAPHQL_REQUEST, b));
    }

    public void setSamplerDownloadImages(boolean b) {
        setProperty(new BooleanProperty(SAMPLER_DOWNLOAD_IMAGES, b));
    }

    public void setHTTPSampleNamingMode(int httpNamingMode) {
        setProperty(new IntegerProperty(HTTP_SAMPLER_NAMING_MODE, httpNamingMode));
    }

    public String getDefaultEncoding() {
        return getPropertyAsString(DEFAULT_ENCODING_PROPERTY, StandardCharsets.UTF_8.name());
    }

    public void setDefaultEncoding(String defaultEncoding) {
        setProperty(DEFAULT_ENCODING_PROPERTY, defaultEncoding);
    }

    public void setPrefixHTTPSampleName(String prefixHTTPSampleName) {
        setProperty(PREFIX_HTTP_SAMPLER_NAME, prefixHTTPSampleName);
    }

    public void setProxyPauseHTTPSample(String proxyPauseHTTPSample) {
        setProperty(PROXY_PAUSE_HTTP_SAMPLER, proxyPauseHTTPSample);
    }

    public void setNotifyChildSamplerListenerOfFilteredSamplers(boolean b) {
        notifyChildSamplerListenersOfFilteredSamples = b;
        setProperty(new BooleanProperty(NOTIFY_CHILD_SAMPLER_LISTENERS_FILTERED, b));
    }

    public void setIncludeList(Collection<String> list) {
        if (list.size() >= 2) {
            // Deduplicate if there is more than one element in the list
            list = list.stream().distinct().collect(Collectors.toList());
        }
        setProperty(new CollectionProperty(INCLUDE_LIST, list));
    }

    public void setExcludeList(Collection<String> list) {
        if (list.size() >= 2) {
            // Deduplicate if there is more than one element in the list
            list = list.stream().distinct().collect(Collectors.toList());
        }
        setProperty(new CollectionProperty(EXCLUDE_LIST, list));
    }

    public void setRegexMatch(boolean b) {
        setProperty(new BooleanProperty(REGEX_MATCH, b));
    }

    public void setContentTypeExclude(String contentTypeExclude) {
        setProperty(new StringProperty(CONTENT_TYPE_EXCLUDE, contentTypeExclude));
    }

    public void setContentTypeInclude(String contentTypeInclude) {
        setProperty(new StringProperty(CONTENT_TYPE_INCLUDE, contentTypeInclude));
    }

    public boolean getAssertions() {
        return false; // Retain the legacy property for JMX round trips only.
    }

    public int getGroupingMode() {
        return getPropertyAsInt(GROUPING_MODE, GROUPING_IN_TRANSACTION_CONTROLLERS);
    }

    public int getPort() {
        return getPropertyAsInt(PORT);
    }

    public String getPortString() {
        return getPropertyAsString(PORT);
    }

    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    public boolean getStoreRecordedExchanges() {
        return getPropertyAsBoolean(STORE_RECORDED_EXCHANGES, true);
    }

    public String getSamplerTypeName() {
        String type = getPropertyAsString(SAMPLER_TYPE_NAME);
        if (LEGACY_NUMERIC_SAMPLER_TYPES.contains(type)) {
            type = HTTPSamplerFactory.IMPL_HTTP_CLIENT5;
        }
        return type;
    }

    public boolean getSamplerRedirectAutomatically() {
        return getPropertyAsBoolean(SAMPLER_REDIRECT_AUTOMATICALLY, false);
    }

    public boolean getSamplerFollowRedirects() {
        return getPropertyAsBoolean(SAMPLER_FOLLOW_REDIRECTS, false);
    }

    public boolean getUseKeepalive() {
        return getPropertyAsBoolean(USE_KEEPALIVE, true);
    }

    public boolean getDetectGraphQLRequest() {
        return getPropertyAsBoolean(DETECT_GRAPHQL_REQUEST, true);
    }

    public boolean getSamplerDownloadImages() {
        return getPropertyAsBoolean(SAMPLER_DOWNLOAD_IMAGES, false);
    }

    public int getHTTPSampleNamingMode() {
        return getPropertyAsInt(HTTP_SAMPLER_NAMING_MODE);
    }

    public String getPrefixHTTPSampleName() {
        return getPropertyAsString(PREFIX_HTTP_SAMPLER_NAME);
    }

    public String getProxyPauseHTTPSample() {
        return getPropertyAsString(PROXY_PAUSE_HTTP_SAMPLER);
    }

    public boolean getNotifyChildSamplerListenerOfFilteredSamplers() {
        return getPropertyAsBoolean(NOTIFY_CHILD_SAMPLER_LISTENERS_FILTERED, true);
    }

    public boolean getRegexMatch() {
        return false; // Recorder variable substitution always uses literal values.
    }

    public String getContentTypeExclude() {
        return getPropertyAsString(CONTENT_TYPE_EXCLUDE);
    }

    public String getContentTypeInclude() {
        return getPropertyAsString(CONTENT_TYPE_INCLUDE);
    }

    public void setHttpSampleNameFormat(String text) {
        setProperty(HTTP_SAMPLER_FORMAT, text, DEFAULT_SAMPLER_FORMAT);
    }

    public String getHttpSampleNameFormat() {
        return getPropertyAsString(HTTP_SAMPLER_FORMAT, DEFAULT_SAMPLER_FORMAT);
    }

    /**
     * @return the {@link JMeterTreeModel} used when run in non-GUI mode, or {@code null} when run in GUI mode
     */
    public JMeterTreeModel getNonGuiTreeModel() {
        return nonGuiTreeModel;
    }

    public void addConfigElement(ConfigElement config) {
        // NOOP
    }

    public void startProxy() throws IOException {
        if (hasPendingRecording()) {
            throw new IOException("Review the previous recording before starting another one.");
        }
        try {
            initKeyStore();
        } catch (GeneralSecurityException e) {
            log.error("Could not initialise key store", e);
            throw new IOException("Could not create keystore", e);
        } catch (IOException e) { // make sure we log the error
            log.error("Could not initialise key store", e);
            throw e;
        }
        recordingTarget = findTargetControllerNode();
        diagnostics = new RecordingDiagnostics();
        captureWorker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("proxy-recording-worker").factory());
        notifyTestListenersOfStart();
        try {
            server = new Daemon(getPort(), this);
            server.start();
            if (GuiPackage.getInstance() != null) {
                GuiPackage.getInstance().register(server);
            }
        } catch (IOException e) {
            log.error("Could not create HTTP(S) Test Script Recorder Proxy daemon", e);
            captureWorker.close();
            captureWorker = null;
            notifyTestListenersOfEnd();
            throw e;
        }
    }

    public void addExcludedPattern(String pattern) {
        getExcludePatterns().addItem(pattern);
    }

    public CollectionProperty getExcludePatterns() {
        return (CollectionProperty) getProperty(EXCLUDE_LIST);
    }

    public void addIncludedPattern(String pattern) {
        getIncludePatterns().addItem(pattern);
    }

    public CollectionProperty getIncludePatterns() {
        return (CollectionProperty) getProperty(INCLUDE_LIST);
    }

    public void clearExcludedPatterns() {
        getExcludePatterns().clear();
    }

    public void clearIncludedPatterns() {
        getIncludePatterns().clear();
    }

    /** @return the target controller node */
    public JMeterTreeNode getTarget() {
        return target;
    }

    /**
     * Sets the target node where samples generated by the proxy are to be stored.
     *
     * @param target target node to store generated samples
     */
    public void setTarget(JMeterTreeNode target) {
        this.target = target;
        if (server != null) {
            recordingTarget = findTargetControllerNode();
        }
    }

    JMeterTreeNode captureTarget() {
        return server == null ? findTargetControllerNode() : recordingTarget;
    }

    public void setAddPreflightSuffix(boolean enabled) {
        setProperty("ProxyControlGui.add_preflight_suffix", enabled, true);
    }

    public boolean getAddPreflightSuffix() {
        return getPropertyAsBoolean("ProxyControlGui.add_preflight_suffix", true);
    }

    public void setIgnoreHttpErrors(boolean ignore) {
        setProperty(IGNORE_HTTP_ERRORS, ignore, false);
    }

    public boolean getIgnoreHttpErrors() {
        return getPropertyAsBoolean(IGNORE_HTTP_ERRORS, false);
    }

    /**
     * Receives the recorded sampler from the proxy server for placing in the
     * test tree; this is skipped if the sampler is null (e.g. for recording SSL errors)
     * Always sends the result to any registered sample listeners.
     *
     * @param sampler      the sampler, may be null
     * @param testElements the test elements to be added (e.g. header manager) under the Sampler
     * @param result       the sample result, not null
     */
    public synchronized void deliverSampler(final HTTPSamplerBase sampler, final TestElement[] testElements, final SampleResult result) {
        RecordingRequestSettings settings = result instanceof HttpProxyTransport.Capture capture && capture.request().settings != null
                ? capture.request().settings : RecordingRequestSettings.capture(this);
        boolean notifySampleListeners = true;
        if (sampler != null) {
            if (settings.preflightSuffix() && "OPTIONS".equals(sampler.getMethod())) {
                sampler.setName(sampler.getName() + "_preflight");
            }
            if (USE_REDIRECT_DISABLING
                    && (settings.autoRedirects() || settings.followRedirects())
                    && result instanceof HTTPSampleResult httpSampleResult) {
                final String urlAsString = httpSampleResult.getUrlAsString();
                if (urlAsString.equals(LAST_REDIRECT)) { // the url matches the last redirect
                    sampler.setEnabled(false);
                    sampler.setComment("Detected a redirect from the previous sample");
                } else { // this is not the result of a redirect
                    LAST_REDIRECT = null; // so break the chain
                }
                if (httpSampleResult.isRedirect()) { // Save Location so resulting sample can be disabled
                    if (LAST_REDIRECT == null) {
                        sampler.setComment("Detected the start of a redirect chain");
                    }
                    LAST_REDIRECT = httpSampleResult.getRedirectLocation();
                } else {
                    LAST_REDIRECT = null;
                }
            }
            boolean transportFailure = !result.getResponseCode().matches("[1-5][0-9]{2}")
                    || result instanceof HttpProxyTransport.Capture capture && !capture.transportError().isEmpty();
            diagnostics.captured(transportFailure || sampler.getComment().startsWith("Replay conversion failed:"));
            if (transportFailure) {
                sampler.setEnabled(false);
                String diagnostic = result instanceof HttpProxyTransport.Capture capture
                        ? capture.transportError() : result.getResponseMessage();
                diagnostics.incomplete(result.getUrlAsString() + " — " + diagnostic);
                sampler.setComment(sampler.getComment() + "\nRecording transport failed: " + diagnostic);
            }
            if (transportFailure || sampler.getComment().startsWith("Replay conversion failed:")
                    || filterContentType(result) && filterUrl(sampler)) {
                JMeterTreeNode myTarget = settings.target();
                @SuppressWarnings("unchecked") // OK, because find only returns correct element types
                Collection<ConfigTestElement> defaultConfigurations = (Collection<ConfigTestElement>) findApplicableElements(
                        myTarget, ConfigTestElement.class, false);
                @SuppressWarnings("unchecked") // OK, because find only returns correct element types
                Collection<Arguments> userDefinedVariables = (Collection<Arguments>) findApplicableElements(
                        myTarget, Arguments.class, true);

                removeValuesFromSampler(sampler, defaultConfigurations);
                replaceValues(sampler, testElements, userDefinedVariables);
                sampler.setAutoRedirects(settings.autoRedirects());
                sampler.setFollowRedirects(settings.followRedirects());
                sampler.setUseKeepAlive(settings.keepAlive());
                sampler.setImageParser(settings.images());
                Authorization authorization = createAuthorization(testElements, result);
                TestElement[] childElements = foldHeaderManagers(sampler, testElements);
                if (settings.ignoreErrors() && result.getResponseCode().matches("[45][0-9]{2}")) {
                    ResponseAssertion assertion = new ResponseAssertion();
                    assertion.setProperty(TestElement.GUI_CLASS, AssertionGui.class.getName());
                    assertion.setName("Ignore HTTP-" + result.getResponseCode());
                    assertion.setComment("Recorded HTTP-" + result.getResponseCode() + " response, ignoring it");
                    assertion.setTestFieldResponseCode();
                    assertion.setToSubstringType();
                    assertion.setAssumeSuccess(true);
                    childElements = Arrays.copyOf(childElements, childElements.length + 1);
                    childElements[childElements.length - 1] = assertion;
                }
                if (settings.storeExchanges()) {
                    storeRecordedExchange(sampler, result);
                }
                RecordedSampler recorded = new RecordedSampler(sampler, childElements, myTarget, settings.prefix(),
                        settings.grouping(), result, transportFailure, authorization);
                recorded.transactionGapMillis = settings.transactionGapMillis();
                sampleQueue.add(recorded);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "Sample excluded based on url or content-type: {} - {}",
                            result.getUrlAsString(), result.getContentType());
                }
                diagnostics.filtered();
                notifySampleListeners = notifyChildSamplerListenersOfFilteredSamples;
                result.setSampleLabel("[" + result.getSampleLabel() + "]");
            }
        }
        if (notifySampleListeners) {
            // SampleEvent is not passed JMeterVariables, because they don't make sense for Proxy Recording
            notifySampleListeners(new SampleEvent(result, "WorkBench"));
        } else {
            log.debug(
                    "Sample not delivered to Child Sampler Listener based on url or content-type: {} - {}",
                    result.getUrlAsString(), result.getContentType());
        }
    }

    private void storeRecordedExchange(HTTPSamplerBase sampler, SampleResult result) {
        try {
            RecordedExchangeStore.Archive recording = result instanceof HttpProxyTransport.Capture capture
                    ? RecordedExchangeStore.fromProxy(result, capture.requestWire(), capture.responseWire(),
                            capture.requestBody(), capture.transportError(),
                            capture.webSocket == null ? List.of() : capture.webSocket.messages(),
                            capture.webSocket == null ? null : capture.webSocket.wire(true),
                            capture.webSocket == null ? null : capture.webSocket.wire(false),
                            capture.sse == null ? null : capture.sse.events())
                    : RecordedExchangeStore.fromProxy(result);
            JmxArchiveEntryStore.registerBundle(
                    recording.manifestEntryName(), recording.checksum(), recording.entries());
            // Keep the source on the sampler so grouping and target changes cannot break its link.
            sampler.setProperty(RecordedExchangeStore.MANIFEST_PROPERTY, recording.manifestEntryName());
            sampler.setProperty(RecordedExchangeStore.CHECKSUM_PROPERTY, recording.checksum());
            sampler.setProperty(RecordedExchangeStore.EXCHANGE_ID_PROPERTY, recording.exchangeIds().get(0));
        } catch (IOException e) {
            log.error("Unable to store the recorded request and response for {}", sampler.getName(), e);
            diagnostics.processingError("Unable to store recorded request and response: " + e);
        }
    }

    /**
     * Recorded browser headers become native sampler headers instead of a child Header Manager
     * element. Also drops the runtime {@code HTTPSampler.header_manager} property that was set
     * on the sampler to execute the live recording sample, so it is not persisted.
     * Runs after {@link #replaceValues} and {@link #createAuthorization}, so variable
     * replacement has been applied and the Authorization header has been extracted.
     *
     * @param sampler      the recorded sampler
     * @param testElements elements captured alongside the sampler
     * @return the elements that should still be added as tree children of the sampler
     */
    private static TestElement[] foldHeaderManagers(HTTPSamplerBase sampler, TestElement[] testElements) {
        sampler.removeProperty(HTTPSamplerBase.HEADER_MANAGER);
        List<TestElement> remaining = new ArrayList<>();
        for (TestElement testElement : testElements) {
            if (testElement instanceof HeaderManager headerManager) {
                sampler.addNativeHeadersIfAbsent(headerManager);
            } else {
                remaining.add(testElement);
            }
        }
        return remaining.toArray(new TestElement[0]);
    }

    /**
     * Detect Header manager in subConfigs,
     * Find(if any) Authorization header
     * Construct Authentication object
     * Removes Authorization if present
     *
     * @param testElements {@link TestElement}[]
     * @param result       {@link HTTPSampleResult}
     * @return {@link Authorization}
     */
    private static Authorization createAuthorization(final TestElement[] testElements, SampleResult result) {
        Header authHeader;
        Authorization authorization = null;
        // Iterate over subconfig elements searching for HeaderManager
        for (TestElement te : testElements) {
            if (te instanceof HeaderManager headerManager) {
                @SuppressWarnings("unchecked") // headers should only contain the correct classes
                List<TestElementProperty> headers = (ArrayList<TestElementProperty>) headerManager.getHeaders().getObjectValue();
                for (Iterator<?> iterator = headers.iterator(); iterator.hasNext();) {
                    TestElementProperty tep = (TestElementProperty) iterator
                            .next();
                    if (tep.getName().equals(HTTPConstants.HEADER_AUTHORIZATION)) {
                        //Construct Authorization object from HEADER_AUTHORIZATION
                        authHeader = (Header) tep.getObjectValue();
                        String headerValue = authHeader.getValue().trim();
                        String[] authHeaderContent = headerValue.split(" ");//$NON-NLS-1$
                        String authType;
                        String authCredentialsBase64;
                        if(authHeaderContent.length>=2) {
                            authType = authHeaderContent[0];
                            // if HEADER_AUTHORIZATION contains "Basic"
                            // then set Mechanism.BASIC_DIGEST, otherwise Mechanism.KERBEROS
                            Mechanism mechanism;
                            switch (authType) {
                                case BEARER_AUTH -> {
                                    // This one will need to be correlated manually by user
                                    return null;
                                }
                                case DIGEST_AUTH -> mechanism = Mechanism.DIGEST;
                                case BASIC_AUTH -> mechanism = Mechanism.BASIC;
                                default -> mechanism = Mechanism.KERBEROS;
                            }
                            authCredentialsBase64 = authHeaderContent[1];
                            authorization=new Authorization();
                            authorization.setURL(computeAuthUrl(result.getUrlAsString()));
                            authorization.setMechanism(mechanism);
                            if(BASIC_AUTH.equals(authType)) {
                                String authCred = new String(Base64.getDecoder().decode(authCredentialsBase64), StandardCharsets.UTF_8);
                                String[] loginPassword = authCred.split(":"); //$NON-NLS-1$
                                if(loginPassword.length == 2) {
                                    authorization.setUser(loginPassword[0]);
                                    authorization.setPass(loginPassword[1]);
                                } else {
                                    log.error("Error parsing BASIC Auth authorization header:'{}', decoded value:'{}'",
                                            authCredentialsBase64, authCred);
                                    // we keep initial header
                                    return null;
                                }
                            } else {
                                // Digest or Kerberos
                                authorization.setUser("${AUTH_LOGIN}");//$NON-NLS-1$
                                authorization.setPass("${AUTH_PASSWORD}");//$NON-NLS-1$
                            }
                        }
                        // remove HEADER_AUTHORIZATION from HeaderManager
                        // because it's useless after creating Authorization object
                        iterator.remove();
                        break;
                    }
                }
            }
        }
        return authorization;
    }

    private static String computeAuthUrl(String url) {
        int index = url.lastIndexOf('/');
        if (index >=0) {
            return url.substring(0, index+1);
        }
        return url;
    }

    /** Enqueue conversion and archive work; forwarding threads never execute it. */
    void submitCapture(Runnable capture) {
        ExecutorService worker = captureWorker;
        if (worker == null) {
            throw new IllegalStateException("Recorder processing worker is not running");
        }
        worker.execute(() -> {
            var context = org.apache.jmeter.threads.JMeterContextService.getContext();
            context.setRecording(true);
            try {
                capture.run();
            } catch (RuntimeException e) {
                diagnostics.processingError("Unable to process recorded exchange: " + e);
                log.error("Unable to process recorded exchange", e);
            } finally {
                context.setRecording(false);
            }
        });
    }

    public boolean hasPendingRecording() {
        return !sampleQueue.isEmpty() || diagnostics.pending();
    }

    /** Reopen a postponed review. Must run on the Swing event thread. */
    public void reviewRecording() {
        if (server != null) {
            return;
        }
        if (!hasPendingRecording()) {
            return;
        }
        List<RecordedSampler> samples = new ArrayList<>(sampleQueue);
        samples.sort(Comparator.comparingDouble((RecordedSampler sample) -> sample.entry.getStartMs()).thenComparingLong(RecordedSampler::sequence));
        RecordingTransactions.assign(samples);
        if (nonGuiTreeModel != null || GuiPackage.getInstance() == null) {
            // Non-GUI callers have no selection dialog; retain failed captures disabled for inspection.
            applyRecording(samples, RecorderSettings.options(this), getGroupingMode(), false);
            sampleQueue.removeAll(samples);
            diagnostics.reviewed();
            return;
        }
        RecorderWizard wizard = new RecorderWizard(GuiPackage.getInstance().getMainFrame(), this, samples);
        wizard.setVisible(true);
        RecorderWizard.Result selection = wizard.getResult();
        if (selection == null) {
            return; // Review later keeps every pending capture available.
        }
        List<RecordedSampler> included = selectRecording(samples, selection.hosts(), selection.failed());
        Set<JMeterTreeNode> previousRequests = new HashSet<>(FindPredefinedCorrelationsAction.correlationRequests(getJmeterTreeModel()));
        applyRecording(included, selection.options(), selection.grouping(), true);
        RecordingTreeExpansion.expand(included);
        if (selection.processCorrelations()) {
            FindPredefinedCorrelationsAction.reviewRecording(GuiPackage.getInstance(),
                    FindPredefinedCorrelationsAction.correlationRequests(getJmeterTreeModel()).stream()
                            .filter(node -> !previousRequests.contains(node)).toList());
        }
        sampleQueue.removeAll(samples);
        diagnostics.reviewed();
    }

    public static List<RecordedSampler> selectRecording(List<RecordedSampler> samples, Set<String> hosts,
            Set<RecordedSampler> failed) {
        return samples.stream().filter(sample -> hosts.contains(HarConverter.hostnameOf(sample.entry.getUrl())))
                .filter(sample -> !sample.failed || failed.contains(sample))
                .sorted(Comparator.comparingDouble((RecordedSampler sample) -> sample.entry.getStartMs())
                        .thenComparingLong(RecordedSampler::sequence)).toList();
    }

    void applyRecording(List<RecordedSampler> samples, HarImportOptions options, int grouping, boolean enableSelectedFailures) {
        RecordingTransactions.assign(samples);
        RecordingSessionNames sessionNames = new RecordingSessionNames(getJmeterTreeModel());
        for (RecordedSampler sample : samples) {
            if (sample.sampler instanceof org.apache.jmeter.protocol.sse.SseSampler sse) {
                sse.setSseSessionName(sessionNames.next("sse-"));
            }
        }
        if (enableSelectedFailures) {
            samples.stream().filter(RecordedSampler::failed).forEach(sample -> sample.sampler.setEnabled(true));
        }
        if (grouping != GROUPING_IN_TRANSACTION_CONTROLLERS && grouping != 0
                && samples.stream().noneMatch(sample -> sample.entry.isWebSocket())) {
            // Preserve the explicit legacy separator/simple-controller/first-only choices.
            Queue<RecordedSampler> pending = new ConcurrentLinkedQueue<>(sampleQueue);
            sampleQueue.clear();
            sampleQueue.addAll(samples);
            putSamplesIntoModel();
            sampleQueue.addAll(pending);
            return;
        }
        Map<JMeterTreeNode, Map<HarEntry, HashTree>> targets = new LinkedHashMap<>();
        for (RecordedSampler info : samples) {
            if (info.authorization != null) {
                setAuthorization(info.authorization, info.target);
            }
            if (enableSelectedFailures && info.failed) {
                info.sampler.setEnabled(true);
            }
            HashTree tree = new ListedHashTree();
            HashTree children = tree.add(info.sampler);
            for (TestElement child : info.testElements) {
                if (isAddableTestElement(child)) {
                    children.add(child);
                }
            }
            targets.computeIfAbsent(info.target, ignored -> new LinkedHashMap<>()).put(info.entry, tree);
        }
        for (var target : targets.entrySet()) {
            HashTree tree = new HarConverter(List.of(), options, "Proxy recording", "")
                    .layoutRecorded(target.getValue(), grouping);
            sessionNames.renameWebSockets(tree);
            if (getAssertions()) {
                for (Object element : tree.list()) {
                    HashTree first = firstRecordedSampler(tree.getTree(element));
                    if (element instanceof HTTPSamplerBase) {
                        first = tree.getTree(element);
                    }
                    if (first != null) {
                        ResponseAssertion assertion = new ResponseAssertion();
                        assertion.setProperty(TestElement.GUI_CLASS, ASSERTION_GUI);
                        assertion.setName(JMeterUtils.getResString("assertion_title"));
                        assertion.setTestFieldResponseData();
                        first.add(assertion);
                    }
                }
            }
            try {
                getJmeterTreeModel().addSubTree(tree, target.getKey());
            } catch (IllegalUserActionException e) {
                throw new IllegalStateException("Unable to insert recorded scenario", e);
            }
        }
    }

    private static HashTree firstRecordedSampler(HashTree tree) {
        for (Object element : tree.list()) {
            if (element instanceof HTTPSamplerBase) {
                return tree.getTree(element);
            }
            HashTree nested = firstRecordedSampler(tree.getTree(element));
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    public void stopProxy() {
        if (server != null) {
            server.stopServer();
            if (GuiPackage.getInstance() != null) {
                GuiPackage.getInstance().unregister(server);
            }
            try {
                server.join(1000); // wait for server to stop
                server.awaitConnections();
            } catch (InterruptedException e) {
                //NOOP
                Thread.currentThread().interrupt();
            }
            ExecutorService worker = captureWorker;
            if (worker != null) {
                worker.close();
                captureWorker = null;
            }
            notifyTestListenersOfEnd();
            server = null;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            reviewRecording();
        } else {
            try {
                SwingUtilities.invokeAndWait(this::reviewRecording);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (InvocationTargetException e) {
                log.error("Unable to flush recorded samples", e.getCause());
            }
        }
    }

    @SuppressWarnings("JavaUtilDate")
    public String[] getCertificateDetails() {
        if (isDynamicMode()) {
            try {
                X509Certificate caCert = (X509Certificate) keyStore.getCertificate(KeyToolUtils.getRootCAalias());
                if (caCert == null) {
                    return new String[]{"Could not find certificate"};
                }
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                md.update(caCert.getEncoded());
                return new String[]
                        {
                        caCert.getSubjectX500Principal().toString(),
                        "Fingerprint(SHA1): " + JOrphanUtils.baToHexString(md.digest(), ' '),
                        "Created: "+ caCert.getNotBefore().toString()
                        };
            } catch (GeneralSecurityException e) {
                log.error("Problem reading root CA from keystore", e);
                return new String[] { "Problem with root certificate", e.getMessage() };
            }
        }
        return new String[0]; // should not happen
    }

    // Package protected to allow test case access
    boolean filterUrl(HTTPSamplerBase sampler) {
        String domain = sampler.getDomain();
        if (StringUtilities.isEmpty(domain)) {
            return false;
        }

        String url = generateMatchUrl(sampler);
        CollectionProperty includePatterns = getIncludePatterns();
        if (!includePatterns.isEmpty() && !matchesPatterns(url, includePatterns)) {
            return false;
        }

        CollectionProperty excludePatterns = getExcludePatterns();
        if (!excludePatterns.isEmpty() && matchesPatterns(url, excludePatterns)) {
            return false;
        }

        return true;
    }

    // Package protected to allow test case access

    /**
     * Filter the response based on the content type.
     * If no include nor exclude filter is specified, the result will be included
     *
     * @param result the sample result to check
     * @return <code>true</code> means result will be kept
     */
    boolean filterContentType(SampleResult result) {
        String includeExp = getContentTypeInclude();
        String excludeExp = getContentTypeExclude();

        // If no expressions are specified, we let the sample pass
        if (StringUtilities.isEmpty(includeExp) &&
                StringUtilities.isEmpty(excludeExp)) {
            return true;
        }

        // Check that we have a content type
        String sampleContentType = result.getContentType();
        if (StringUtilities.isEmpty(sampleContentType)) {
            if (log.isDebugEnabled()) {
                log.debug("No Content-type found for : {}", result.getUrlAsString());
            }

            return true;
        }

        if (log.isDebugEnabled()) {
            log.debug("Content-type to filter : {}", sampleContentType);
        }

        // Check if the include pattern is matched
        boolean matched = testPattern(includeExp, sampleContentType, true);
        if(!matched) {
            return false;
        }

        // Check if the exclude pattern is matched
        matched = testPattern(excludeExp, sampleContentType, false);
        if(!matched) {
            return false;
        }

        return true;
    }

    /**
     * Returns true if matching pattern was different from expectedToMatch
     *
     * @param expression        Expression to match
     * @param sampleContentType content to check
     * @return boolean true if Matching expression
     */
    private static boolean testPattern(String expression, String sampleContentType, boolean expectedToMatch) {
        if (StringUtilities.isEmpty(expression)) {
            return true;
        }
        if(log.isDebugEnabled()) {
            log.debug(
                    "Testing Expression : {} on sampleContentType: {}, expected to match: {}",
                    expression, sampleContentType, expectedToMatch);
        }

        try {
            boolean contains;
            if (USE_JAVA_REGEX) {
                contains = isContainedWithJavaRegex(expression, sampleContentType);
            } else {
                contains = isContainedWithOroRegex(expression, sampleContentType);
            }
            if (contains != expectedToMatch) {
                return false;
            }
        } catch (PatternSyntaxException | MalformedCachePatternException e) {
            log.warn("Skipped invalid content pattern: {}", expression, e);
        }
        return true;
    }

    private static boolean isContainedWithJavaRegex(String expression, String sampleContentType) {
        java.util.regex.Pattern pattern = JMeterUtils.compilePattern(expression);
        return pattern.matcher(sampleContentType).find();
    }

    private static boolean isContainedWithOroRegex(String expression, String sampleContentType) {
        Pattern pattern = JMeterUtils.getPatternCache().getPattern(expression,
                Perl5Compiler.READ_ONLY_MASK | Perl5Compiler.SINGLELINE_MASK);
        return JMeterUtils.getMatcher().contains(sampleContentType, pattern);
    }

    /**
     * Find if there is any AuthManager in JMeterTreeModel
     * If there is no one, create and add it to tree
     * Add authorization object to AuthManager
     *
     * @param authorization {@link Authorization}
     * @param target        {@link JMeterTreeNode}
     */
    private void setAuthorization(Authorization authorization, JMeterTreeNode target) {
        JMeterTreeModel jmeterTreeModel = getJmeterTreeModel();
        List<JMeterTreeNode> authManagerNodes = jmeterTreeModel.getNodesOfType(AuthManager.class);
        if (authManagerNodes.isEmpty()) {
            try {
                log.debug("Creating HTTP Authentication manager for authorization: {}", authorization);
                AuthManager authManager = newAuthorizationManager(authorization);
                jmeterTreeModel.addComponent(authManager, target);
            } catch (IllegalUserActionException e) {
                log.error("Failed to add Authorization Manager to target node: {}", target.getName(), e);
            }
        } else {
            AuthManager authManager = (AuthManager) authManagerNodes.get(0).getTestElement();
            authManager.addAuth(authorization);
        }
    }

    private JMeterTreeModel getJmeterTreeModel() {
        if (this.nonGuiTreeModel == null) {
            return GuiPackage.getInstance().getTreeModel();
        }
        return this.nonGuiTreeModel;
    }

    /**
     * Helper method to add a Response Assertion
     * Called from AWT Event thread
     */
    private static void addAssertion(JMeterTreeModel model, JMeterTreeNode node) throws IllegalUserActionException {
        ResponseAssertion ra = new ResponseAssertion();
        ra.setProperty(TestElement.GUI_CLASS, ASSERTION_GUI);
        ra.setName(JMeterUtils.getResString("assertion_title")); // $NON-NLS-1$
        ra.setTestFieldResponseData();
        model.addComponent(ra, node);
    }

    /** Construct a new AuthManager with the provided authorization */
    private static AuthManager newAuthorizationManager(Authorization authorization) {
        AuthManager authManager = new AuthManager();
        authManager.setProperty(TestElement.GUI_CLASS, AUTH_PANEL);
        authManager.setProperty(TestElement.TEST_CLASS, AUTH_MANAGER);
        authManager.setName("HTTP Authorization Manager");
        authManager.addAuth(authorization);
        return authManager;
    }

    /**
     * Helper method to add a Divider
     * Called from Application Thread that needs to update GUI (JMeterTreeModel)
     */
    private static void addDivider(final JMeterTreeModel model, final JMeterTreeNode node) {
        final GenericController sc = new GenericController();
        sc.setProperty(TestElement.GUI_CLASS, LOGIC_CONTROLLER_GUI);
        sc.setName("-------------------"); // $NON-NLS-1$
        safelyAddComponent(model, node, sc);
    }

    private static void safelyAddComponent(
            final JMeterTreeModel model,
            final JMeterTreeNode node,
            final GenericController controller) {
        JMeterUtils.runSafe(true, () -> {
            try {
                model.addComponent(controller, node);
            } catch (IllegalUserActionException e) {
                log.error("Program error", e);
                throw new Error(e);
            }
        });
    }

    /**
     * Helper method to add a Simple Controller to contain the samplers.
     * Called from Application Thread that needs to update GUI (JMeterTreeModel)
     *
     * @param model Test component tree model
     * @param node  Node in the tree where we will add the Controller
     * @param name  A name for the Controller
     */
    private static void addSimpleController(final JMeterTreeModel model, final JMeterTreeNode node, String name) {
        final GenericController sc = new GenericController();
        sc.setProperty(TestElement.GUI_CLASS, LOGIC_CONTROLLER_GUI);
        sc.setName(name);
        safelyAddComponent(model, node, sc);
    }

    /**
     * Helper method to add a Transaction Controller to contain the samplers.
     * Called from Application Thread that needs to update GUI (JMeterTreeModel)
     *
     * @param model Test component tree model
     * @param node  Node in the tree where we will add the Controller
     * @param name  A name for the Controller
     */
    private static void addTransactionController(final JMeterTreeModel model, final JMeterTreeNode node, String name) {
        final TransactionController sc = new TransactionController();
        sc.setIncludeTimers(false);
        sc.setProperty(TestElement.GUI_CLASS, TRANSACTION_CONTROLLER_GUI);
        sc.setName(name);
        safelyAddComponent(model, node, sc);
    }

    /**
     * Helper method to replicate any timers found within the Proxy Controller
     * into the provided sampler, while replacing any occurrences of string _T_
     * in the timer's configuration with the provided deltaT.
     * Called from AWT Event thread
     *
     * @param model  Test component tree model
     * @param node   Sampler node in where we will add the timers
     * @param deltaT Time interval from the previous request
     */
    @SuppressWarnings("JdkObsolete")
    private void addTimers(JMeterTreeModel model, JMeterTreeNode node, long deltaT) {
        TestPlan variables = new TestPlan();
        variables.addParameter("T", Long.toString(deltaT)); // $NON-NLS-1$
        ValueReplacer replacer = new ValueReplacer(variables);
        JMeterTreeNode mySelf = model.getNodeOf(this);
        if(mySelf != null) {
            Enumeration<?> children = mySelf.children();
            while (children.hasMoreElements()) {
                JMeterTreeNode templateNode = (JMeterTreeNode)children.nextElement();
                if (templateNode.isEnabled()) {
                    TestElement template = templateNode.getTestElement();
                    if (template instanceof Timer) {
                        TestElement timer = (TestElement) template.clone();
                        try {
                            timer.setComment("Recorded:"+Long.toString(deltaT)+"ms");
                            replacer.undoReverseReplace(timer);
                            model.addComponent(timer, node);
                        } catch (InvalidVariableException
                                | IllegalUserActionException e) {
                            // Not 100% sure, but I believe this can't happen, so
                            // I'll log and throw an error:
                            log.error("Program error adding timers", e);
                            throw new Error(e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds the first enabled node of a given type in the tree.
     *
     * @param type class of the node to be found
     * @return the first node of the given type in the test component tree, or
     * <code>null</code> if none was found.
     */
    private JMeterTreeNode findFirstNodeOfType(Class<?> type) {
        JMeterTreeModel treeModel = getJmeterTreeModel();
        List<JMeterTreeNode> nodes = treeModel.getNodesOfType(type);
        for (JMeterTreeNode node : nodes) {
            if (node.isEnabled()) {
                return node;
            }
        }
        return null;
    }

    /**
     * Finds the controller where samplers have to be stored, that is:
     * <ul>
     * <li>The controller specified by the <code>target</code> property.
     * <li>If none was specified, the first RecordingController in the tree.
     * <li>If none is found, the first AbstractThreadGroup in the tree.
     * </ul>
     *
     * @return the tree node for the controller where the proxy must store the
     *         generated samplers.
     */
    public JMeterTreeNode findTargetControllerNode() {
        JMeterTreeNode myTarget = getTarget();
        if (myTarget != null) {
            return myTarget;
        }
        myTarget = findFirstNodeOfType(RecordingController.class);
        if (myTarget != null) {
            return myTarget;
        }
        myTarget = findFirstNodeOfType(AbstractThreadGroup.class);
        if (myTarget != null) {
            return myTarget;
        }
        log.error("Program error: test script recording target not found.");
        return null;
    }

    /**
     * Finds all configuration objects of the given class applicable to the
     * recorded samplers, that is:
     * <ul>
     * <li>All such elements directly within the HTTP(S) Test Script Recorder (these have
     * the highest priority).
     * <li>All such elements directly within the target controller (higher
     * priority) or directly within any containing controller (lower priority),
     * including the Test Plan itself (lowest priority).
     * </ul>
     *
     * @param myTarget  tree node for the recording target controller.
     * @param myClass   Class of the elements to be found.
     * @param ascending true if returned elements should be ordered in ascending
     *                  priority, false if they should be in descending priority.
     * @return a collection of applicable objects of the given class.
     */
    // TODO - could be converted to generic class?
    @SuppressWarnings("JdkObsolete")
    private Collection<?> findApplicableElements(JMeterTreeNode myTarget, Class<? extends TestElement> myClass, boolean ascending) {
        JMeterTreeModel treeModel = getJmeterTreeModel();
        Deque<TestElement> elements = new ArrayDeque<>();

        // Look for elements directly within the HTTP proxy:
        JMeterTreeNode node = treeModel.getNodeOf(this);
        if (node != null) {
            Enumeration<?> kids = node.children();
            while (kids.hasMoreElements()) {
                JMeterTreeNode subNode = (JMeterTreeNode) kids.nextElement();
                if (subNode.isEnabled()) {
                    TestElement element = (TestElement) subNode.getUserObject();
                    if (myClass.isInstance(element)) {
                        if (ascending) {
                            elements.addFirst(element);
                        } else {
                            elements.add(element);
                        }
                    }
                }
            }
        }
        // Look for arguments elements in the target controller or higher up:
        for (JMeterTreeNode controller = myTarget;
             controller != null;
             controller = (JMeterTreeNode) controller.getParent()) {
            List<JMeterTreeNode> kids = new ArrayList<>();
            controller.children().asIterator().forEachRemaining(kid -> kids.add((JMeterTreeNode) kid));
            if (controller.getUserObject() instanceof TestPlan) {
                // The Shared Profile holds the test plan level configuration of a plan with sections
                for (JMeterTreeNode shared : treeModel.getNodesOfType(SharedProfile.class)) {
                    if (shared.isEnabled()) {
                        shared.children().asIterator().forEachRemaining(kid -> kids.add((JMeterTreeNode) kid));
                    }
                }
            }
            for (JMeterTreeNode subNode : kids) {
                if (subNode.isEnabled()) {
                    TestElement element = (TestElement) subNode.getUserObject();
                    if (myClass.isInstance(element)) {
                        log.debug("Applicable: {}", element.getName());
                        if (ascending) {
                            elements.addFirst(element);
                        } else {
                            elements.add(element);
                        }
                    }

                    // Special case for the TestPlan's Arguments sub-element:
                    if (element instanceof TestPlan tp) {
                        Arguments args = tp.getArguments();
                        if (myClass.isInstance(args)) {
                            if (ascending) {
                                elements.addFirst(args);
                            } else {
                                elements.add(args);
                            }
                        }
                    }
                }
            }
        }

        return elements;
    }

    private void putSamplesIntoModel() {
        // return early, as JMeterTreeModel might not been initialized yet
        if (sampleQueue.isEmpty()) {
            return;
        }
        final JMeterTreeModel treeModel = getJmeterTreeModel();
        Map<JMeterTreeNode, String> transactions = new LinkedHashMap<>();
        Map<JMeterTreeNode, Double> previousEnds = new LinkedHashMap<>();
        while (!sampleQueue.isEmpty()) {
            RecordedSampler info = sampleQueue.poll();
            if (info.authorization != null) {
                setAuthorization(info.authorization, info.target);
            }
            try {
                log.info("Add sample {} into controller {}", info.sampler.getName(), info.prefix);
                try {
                    Double previousEnd = previousEnds.get(info.target);
                    long deltaT = previousEnd == null ? 0 : (long) Math.max(0, info.entry.getStartMs() - previousEnd);
                    previousEnds.merge(info.target, info.entry.getEndMs(), Math::max);
                    boolean firstInBatch = !Objects.equals(transactions.put(info.target, info.entry.getTransactionId()),
                            info.entry.getTransactionId());
                    prepareTree(treeModel, firstInBatch, info);

                    if (info.groupingMode == GROUPING_STORE_FIRST_ONLY) {
                        if (!firstInBatch && info.sampler.isEnabled()) {
                            continue;
                        }

                        // If we're not storing subsequent samplers, we'll need the
                        // first sampler to do all the work...:
                        info.sampler.setFollowRedirects(true);
                        info.sampler.setImageParser(true);
                    }

                    final JMeterTreeNode targetNode = getTargetNode(info.target, info.groupingMode);
                    final JMeterTreeNode newNode = treeModel.addComponent(info.sampler, targetNode);
                    if (firstInBatch) {
                        if (getAssertions()) {
                            addAssertion(treeModel, newNode);
                        }
                        addTimers(treeModel, newNode, deltaT);
                    }
                    addTestElements(treeModel, info.testElements, newNode);
                } catch (IllegalUserActionException ex) {
                    log.error("Error placing sampler", ex);
                    JMeterUtils.reportErrorToUser(ex.getMessage());
                }
            } catch (Exception ex) {
                log.error("Error placing sampler", ex);
                JMeterUtils.reportErrorToUser(ex.getMessage());
            }
        }
    }

    private void addTestElements(final JMeterTreeModel treeModel, TestElement[] testElements,
            final JMeterTreeNode newNode) throws IllegalUserActionException {
        if (testElements == null) {
            return;
        }
        for (TestElement testElement : testElements) {
            if (isAddableTestElement(testElement)) {
                treeModel.addComponent(testElement, newNode);
            }
        }
    }

    private static void prepareTree(final JMeterTreeModel treeModel, boolean firstInBatch, RecordedSampler info) {
        JMeterTreeNode myTarget = info.target;
        int cachedGroupingMode = info.groupingMode;
        if (firstInBatch) {
            String controllerName = info.entry.getTransactionName();
            if (!myTarget.isLeaf() && cachedGroupingMode == GROUPING_ADD_SEPARATORS) {
                addDivider(treeModel, myTarget);
            }
            if (cachedGroupingMode == GROUPING_IN_SIMPLE_CONTROLLERS) {
                addSimpleController(treeModel, myTarget, controllerName);
            }
            if (cachedGroupingMode == GROUPING_IN_TRANSACTION_CONTROLLERS) {
                addTransactionController(treeModel, myTarget, controllerName);
            }
        }
    }

    private static JMeterTreeNode getTargetNode(JMeterTreeNode origTarget, int cachedGroupingMode) {
        if (cachedGroupingMode == GROUPING_IN_SIMPLE_CONTROLLERS ||
                cachedGroupingMode == GROUPING_IN_TRANSACTION_CONTROLLERS) {
            // Find the last controller in the target to store the
            // sampler there:
            for (int i = origTarget.getChildCount() - 1; i >= 0; i--) {
                JMeterTreeNode currentNode = (JMeterTreeNode) origTarget.getChildAt(i);
                if (currentNode.getTestElement() instanceof GenericController) {
                    return currentNode;
                }
            }
        }
        return origTarget;
    }

    /**
     * @param testElement {@link TestElement} to add
     * @return true if testElement can be added to Sampler
     */
    private boolean isAddableTestElement(
            TestElement testElement) {
        if (hasCorrectInterface(testElement, addableInterfaces)) {
            if (testElement.getProperty(TestElement.GUI_CLASS) != null) {
                return true;
            } else {
                log.error(
                        "Cannot add element that lacks the {} property as testElement: {}",
                        TestElement.GUI_CLASS, testElement);
                return false;
            }
        } else {
            return false;
        }
    }

    private static boolean hasCorrectInterface(Object obj, Set<Class<?>> klasses) {
        for (Class<?> klass: klasses) {
            if (klass != null && klass.isInstance(obj)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove from the sampler all values which match the one provided by the
     * first configuration in the given collection which provides a value for
     * that property.
     *
     * @param sampler        Sampler to remove values from.
     * @param configurations ConfigTestElements in descending priority.
     */
    private static void removeValuesFromSampler(HTTPSamplerBase sampler, Collection<? extends ConfigTestElement> configurations) {
        PropertyIterator props = sampler.propertyIterator();
        while (props.hasNext()) {
            JMeterProperty prop = props.next();
            String name = prop.getName();
            String value = prop.getStringValue();

            // There's a few properties which are excluded from this processing:
            if (name.equals(TestElement.ENABLED)
                    || name.equals(TestElement.GUI_CLASS)
                    || name.equals(TestElement.NAME)
                    || name.equals(TestElement.TEST_CLASS)) {
                continue; // go on with next property.
            }

            for (ConfigTestElement config : configurations) {
                String configValue = config.getPropertyAsString(name);

                if (StringUtilities.isNotEmpty(configValue)) {
                    if (configValue.equals(value)) {
                        sampler.setProperty(name, ""); // $NON-NLS-1$
                    }
                    // Property was found in a config element. Whether or not
                    // it matched the value in the sampler, we're done with
                    // this property -- don't look at lower-priority configs
                    break;
                }
            }
        }
    }

    private static String generateMatchUrl(HTTPSamplerBase sampler) {
        StringBuilder buf = new StringBuilder(sampler.getDomain());
        buf.append(':'); // $NON-NLS-1$
        buf.append(sampler.getPort());
        buf.append(sampler.getPath());
        if (!sampler.getQueryString().isEmpty()) {
            buf.append('?'); // $NON-NLS-1$
            buf.append(sampler.getQueryString());
        }
        return buf.toString();
    }

    private static boolean matchesPatterns(String url, CollectionProperty patterns) {
        if (USE_JAVA_REGEX) {
            return matchesPatternsWithJavaRegex(url, patterns);
        }
        return matchesPatternsWithOroRegex(url, patterns);
    }

    private static boolean matchesPatternsWithJavaRegex(String url, CollectionProperty patterns) {
        for (JMeterProperty jMeterProperty : patterns) {
            String item = jMeterProperty.getStringValue();
            try {
                java.util.regex.Pattern pattern = JMeterUtils.compilePattern(item);
                if (pattern.matcher(url).matches()) {
                    return true;
                }
            } catch (PatternSyntaxException e) {
                log.warn("Skipped invalid pattern: {}", item, e);
            }
        }
        return false;
    }

    private static boolean matchesPatternsWithOroRegex(String url, CollectionProperty patterns) {
        for (JMeterProperty jMeterProperty : patterns) {
            String item = jMeterProperty.getStringValue();
            try {
                Pattern pattern = JMeterUtils.getPatternCache().getPattern(
                        item, Perl5Compiler.READ_ONLY_MASK | Perl5Compiler.SINGLELINE_MASK);
                if (JMeterUtils.getMatcher().matches(url, pattern)) {
                    return true;
                }
            } catch (MalformedCachePatternException e) {
                log.warn("Skipped invalid pattern: {}", item, e);
            }
        }
        return false;
    }

    /**
     * Scan all test elements passed in for values matching the value of any of
     * the variables in any of the variable-holding elements in the collection.
     *
     * @param sampler   A TestElement to replace values on
     * @param configs   More TestElements to replace values on
     * @param variables Collection of Arguments to use to do the replacement, ordered
     *                  by ascending priority.
     */
    private static void replaceValues(TestElement sampler, TestElement[] configs, Collection<? extends Arguments> variables) {
        // Build the replacer from all the variables in the collection:
        ValueReplacer replacer = new ValueReplacer();
        for (Arguments variable : variables) {
            final Map<String, String> map = variable.getArgumentsAsMap();
            // Drop any empty values (Bug 45199)
            map.values().removeIf(""::equals);
            replacer.addVariables(map);
        }

        try {
            replacer.reverseReplace(sampler, false);
            for (TestElement config : configs) {
                if (config != null) {
                    replacer.reverseReplace(config, false);
                }
            }
        } catch (InvalidVariableException e) {
            log.warn("Invalid variables included for replacement into recorded sample", e);
        }
    }

    /**
     * This will notify sample listeners directly within the Proxy of the
     * sampling that just occurred -- so that we have a means to record the
     * server's responses as we go.
     *
     * @param event sampling event to be delivered
     */
    @SuppressWarnings("JdkObsolete")
    private void notifySampleListeners(SampleEvent event) {
        JMeterTreeModel treeModel = getJmeterTreeModel();
        JMeterTreeNode myNode = treeModel.getNodeOf(this);
        if(myNode != null) {
            Enumeration<?> kids = myNode.children();
            while (kids.hasMoreElements()) {
                JMeterTreeNode subNode = (JMeterTreeNode)kids.nextElement();
                if (subNode.isEnabled()) {
                    TestElement testElement = subNode.getTestElement();
                    if (testElement instanceof SampleListener sampleListener) {
                        sampleListener.sampleOccurred(event);
                    }
                }
            }
        }
    }

    /**
     * This will notify test listeners directly within the Proxy that the 'test'
     * (here meaning the proxy recording) has started.
     */
    @SuppressWarnings("JdkObsolete")
    private void notifyTestListenersOfStart() {
        JMeterTreeModel treeModel = getJmeterTreeModel();
        JMeterTreeNode myNode = treeModel.getNodeOf(this);
        if(myNode != null) {
            Enumeration<?> kids = myNode.children();
            while (kids.hasMoreElements()) {
                JMeterTreeNode subNode = (JMeterTreeNode)kids.nextElement();
                if (subNode.isEnabled()) {
                    TestElement testElement = subNode.getTestElement();
                    if (testElement instanceof TestStateListener testStateListener) {
                        TestBeanHelper.prepare(testElement);
                        testStateListener.testStarted();
                    }
                }
            }
        }
    }

    /**
     * This will notify test listeners directly within the Proxy that the 'test'
     * (here meaning the proxy recording) has ended.
     */
    @SuppressWarnings("JdkObsolete")
    private void notifyTestListenersOfEnd() {
        JMeterTreeModel treeModel = getJmeterTreeModel();
        JMeterTreeNode myNode = treeModel.getNodeOf(this);
        if (myNode != null) {
            Enumeration<?> kids = myNode.children();
            while (kids.hasMoreElements()) {
                JMeterTreeNode subNode = (JMeterTreeNode) kids.nextElement();
                if (subNode.isEnabled()) {
                    TestElement testElement = subNode.getTestElement();
                    if (testElement instanceof TestStateListener testStateListener) { // TL - TE
                        testStateListener.testEnded();
                    }
                }
            }
        }
    }

    @Override
    public boolean canRemove() {
        return null == server;
    }

    private void initKeyStore() throws IOException, GeneralSecurityException {
        switch (KEYSTORE_MODE) {
            case DYNAMIC_KEYSTORE -> {
                storePassword = getPassword();
                keyPassword = getPassword();
                initDynamicKeyStore();
            }
            case JMETER_KEYSTORE -> {
                storePassword = getPassword();
                keyPassword = getPassword();
                initJMeterKeyStore();
            }
            case USER_KEYSTORE -> {
                storePassword = JMeterUtils.getPropDefault("proxy.cert.keystorepass", DEFAULT_PASSWORD); // $NON-NLS-1$
                keyPassword = JMeterUtils.getPropDefault("proxy.cert.keypassword", DEFAULT_PASSWORD); // $NON-NLS-1$
                log.info("HTTP(S) Test Script Recorder will use the keystore '{}' with the alias: '{}'", CERT_PATH_ABS, CERT_ALIAS);
                initUserKeyStore();
            }
            case NONE -> throw new IOException("Cannot find keytool application and no keystore was provided");
        }
    }

    /**
     * Initialise the user-provided keystore
     */
    @SuppressWarnings("JavaUtilDate")
    private void initUserKeyStore() {
        try {
            keyStore = getKeyStore(storePassword.toCharArray());
            X509Certificate caCert = (X509Certificate) keyStore.getCertificate(CERT_ALIAS);
            if (caCert == null) {
                log.error("Could not find key with alias {}", CERT_ALIAS);
                keyStore = null;
            } else {
                caCert.checkValidity(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)));
            }
        } catch (Exception e) {
            keyStore = null;
            log.error(
                    "Could not open keystore or certificate is not valid {} {}",
                    CERT_PATH_ABS, e.getMessage(), e);
        }
    }

    /**
     * Initialise the dynamic domain keystore
     */
    @SuppressWarnings("JavaUtilDate")
    private void initDynamicKeyStore() throws IOException, GeneralSecurityException {
        if (storePassword  != null) { // Assume we have already created the store
            try {
                keyStore = getKeyStore(storePassword.toCharArray());
                for(String alias : KeyToolUtils.getCAaliases()) {
                    X509Certificate  caCert = (X509Certificate) keyStore.getCertificate(alias);
                    if (caCert == null) {
                        keyStore = null; // no CA key - probably the wrong store type.
                        break; // cannot continue
                    } else {
                        caCert.checkValidity(new Date(System.currentTimeMillis()+TimeUnit.DAYS.toMillis(1)));
                        log.info("Valid alias found for {}", alias);
                    }
                }
            } catch (IOException e) { // store is faulty, we need to recreate it
                keyStore = null; // if cert is not valid, flag up to recreate it
                if (e.getCause() instanceof UnrecoverableKeyException) {
                    log.warn(
                            "Could not read key store {}; cause: {}, a new one will be created, ensure you install it in browser",
                            e.getMessage(), e.getCause().getMessage(), e);
                } else {
                    log.warn(
                            "Could not open/read key store {}, a new one will be created, ensure you install it in browser",
                            e.getMessage(), e); // message includes the file name
                }
            } catch (CertificateExpiredException e) {
                keyStore = null; // if cert is not valid, flag up to recreate it
                log.warn(
                        "Existing ROOT Certificate has expired, a new one will be created, ensure you install it in browser, message: {}",
                        e.getMessage(), e);
            } catch (CertificateNotYetValidException e) {
                keyStore = null; // if cert is not valid, flag up to recreate it
                log.warn(
                        "Existing ROOT Certificate is not yet valid, a new one will be created, ensure you install it in browser, message: {}",
                        e.getMessage(), e);
            } catch (GeneralSecurityException e) {
                keyStore = null; // if cert is not valid, flag up to recreate it
                log.warn(
                        "Problem reading key store, a new one will be created, ensure you install it in browser, message: {}",
                        e.getMessage(), e);
            }
        }
        if (keyStore == null) { // no existing file or not valid
            storePassword = JOrphanUtils.generateRandomAlphanumericPassword(20); // Alphanum to avoid issues with command-line quoting
            keyPassword = storePassword; // we use same password for both
            setPassword(storePassword);
            log.info(
                    "Creating HTTP(S) Test Script Recorder Root CA in {}, ensure you install certificate in your Browser for recording",
                    CERT_PATH_ABS);
            KeyToolUtils.generateProxyCA(CERT_PATH, storePassword, CERT_VALIDITY);
            log.info("Created keystore in {}", CERT_PATH_ABS);
            keyStore = getKeyStore(storePassword.toCharArray()); // This should now work
        }
        final String sslDomains = getSslDomains().trim();
        if (!sslDomains.isEmpty()) {
            final String[] domains = sslDomains.split(",");
            // The subject may be either a host or a domain
            for (String subject : domains) {
                if (isValid(subject)) {
                    if (!keyStore.containsAlias(subject)) {
                        log.info("Creating entry {} in {}", subject, CERT_PATH_ABS);
                        KeyToolUtils.generateHostCert(CERT_PATH, storePassword, subject, CERT_VALIDITY);
                        keyStore = getKeyStore(storePassword.toCharArray()); // reload to pick up new aliases
                        // reloading is very quick compared with creating an entry currently
                    }
                } else {
                    log.warn("Attempt to create an invalid domain certificate: {}", subject);
                }
            }
        }
    }

    private static boolean isValid(String subject) {
        String[] parts = subject.split("\\.");
        return !parts[0].endsWith("*") // not a wildcard
                || parts.length >= 3 && !isCountryCodeSecondLevelWildcard(parts);
    }

    private static boolean isCountryCodeSecondLevelWildcard(String[] parts) {
        return parts.length == 3 && parts[1].length() <= 3 && parts[2].length() == 2;
    }

    // This should only be called for a specific host
    KeyStore updateKeyStore(String port, String host) throws IOException, GeneralSecurityException {
        synchronized (CERT_PATH) { // ensure Proxy threads cannot interfere with each other
            if (!keyStore.containsAlias(host)) {
                log.info("{} Creating entry {} in {}", port, host, CERT_PATH_ABS);
                KeyToolUtils.generateHostCert(CERT_PATH, storePassword, host, CERT_VALIDITY);
            }
            keyStore = getKeyStore(storePassword.toCharArray()); // reload after adding alias
        }
        return keyStore;
    }

    /**
     * Initialise the single key JMeter keystore (original behaviour)
     */
    @SuppressWarnings("JavaUtilDate")
    private void initJMeterKeyStore() throws IOException, GeneralSecurityException {
        if (storePassword != null) { // Assume we have already created the store
            try {
                keyStore = getKeyStore(storePassword.toCharArray());
                X509Certificate caCert = (X509Certificate) keyStore.getCertificate(JMETER_SERVER_ALIAS);
                caCert.checkValidity(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)));
            } catch (Exception e) { // store is faulty, we need to recreate it
                keyStore = null; // if cert is not valid, flag up to recreate it
                log.warn(
                        "Could not open expected file or certificate is not valid {} {}",
                        CERT_PATH_ABS, e.getMessage(), e);
            }
        }
        if (keyStore == null) { // no existing file or not valid
            storePassword = JOrphanUtils.generateRandomAlphanumericPassword(20); // Alphanum to avoid issues with command-line quoting
            keyPassword = storePassword; // we use same password for both
            setPassword(storePassword);
            log.info("Generating standard keypair in {}", CERT_PATH_ABS);
            if (!CERT_PATH.delete()) { // safer to start afresh
                log.warn(
                        "Could not delete {}, this could create issues, stop jmeter, ensure file is deleted and restart again",
                        CERT_PATH.getAbsolutePath());
            }
            KeyToolUtils.genkeypair(CERT_PATH, JMETER_SERVER_ALIAS, storePassword, CERT_VALIDITY, null, null);
            keyStore = getKeyStore(storePassword.toCharArray()); // This should now work
        }
    }

    private static KeyStore getKeyStore(char[] password) throws GeneralSecurityException, IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(CERT_PATH))) {
            log.debug("Opened Keystore file: {}", CERT_PATH_ABS);
            KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
            ks.load(in, password);
            log.debug("Loaded Keystore file: {}", CERT_PATH_ABS);
            return ks;
        }
    }

    private static String getPassword() {
        return PREFERENCES.get(USER_PASSWORD_KEY, null);
    }

    private static void setPassword(String password) {
        PREFERENCES.put(USER_PASSWORD_KEY, password);
    }

    // the keystore for use by the Proxy
    KeyStore getKeyStore() {
        return keyStore;
    }

    String getKeyPassword() {
        return keyPassword;
    }

    public static boolean isDynamicMode() {
        return KEYSTORE_MODE == KeystoreMode.DYNAMIC_KEYSTORE;
    }

}
