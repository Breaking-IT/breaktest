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

package org.apache.jmeter.protocol.http.config.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.config.gui.AbstractConfigGui;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.JBooleanPropertyEditor;
import org.apache.jmeter.gui.JEnumPropertyEditor;
import org.apache.jmeter.gui.JTextComponentBinding;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.util.HorizontalPanel;
import org.apache.jmeter.gui.util.InfoButton;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.protocol.http.control.gui.ProxyDestinationPanel;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBaseSchema;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JEditableCheckBox;
import org.apache.jorphan.gui.JFactory;
import org.apache.jorphan.util.StringUtilities;

import net.miginfocom.swing.MigLayout;

/**
 * GUI for Http Request defaults
 */
@GUIMenuSortOrder(1)
@TestElementMetadata(labelResource = "url_config_title")
public class HttpDefaultsGui extends AbstractConfigGui {

    private static final long serialVersionUID = 242L;

    private UrlConfigGui urlConfigGui;
    private final JBooleanPropertyEditor retrieveEmbeddedResources = new JBooleanPropertyEditor(
            HTTPSamplerBaseSchema.INSTANCE.getRetrieveEmbeddedResources(),
            "web_testing_retrieve_images",
            JMeterUtils::getResString);
    private final JBooleanPropertyEditor concurrentDwn = new JBooleanPropertyEditor(
            HTTPSamplerBaseSchema.INSTANCE.getConcurrentDownload(),
            "web_testing_concurrent_download",
            JMeterUtils::getResString);
    private JTextField concurrentPool;
    private final JEnumPropertyEditor<HTTPSamplerBase.ResponseProcessingMode> responseProcessingMode =
            new JEnumPropertyEditor<>(
                    HTTPSamplerBaseSchema.INSTANCE.getResponseProcessingMode(),
                    "response_processing_mode",
                    HTTPSamplerBase.ResponseProcessingMode.class,
                    JMeterUtils::getResString,
                    "response_processing_not_set"
            );
    private final InfoButton responseProcessingInfo = new InfoButton(
            JMeterUtils.getResString("response_processing_info_title"),
            JMeterUtils.getResString("response_processing_info"));
    private JTextField embeddedAllowRE; // regular expression used to match against embedded resource URLs to allow
    private JTextField embeddedExcludeRE; // regular expression used to match against embedded resource URLs to discard
    private JTextField sourceIpAddr;
    private final JComboBox<String> sourceIpType = new JComboBox<>(HTTPSamplerBase.getSourceTypeList());
    private final ProxyDestinationPanel proxyDestinations = new ProxyDestinationPanel();
    private JTextField proxyScheme;
    private JTextField proxyHost;
    private JTextField proxyPort;
    private JTextField proxyUser;
    private JPasswordField proxyPass;
    private final JComboBox<String> httpProtocol = new JComboBox<>(HTTPSamplerBase.getHttpProtocolList());
    private final JCheckBox http3Upgrade = new JCheckBox(JMeterUtils.getResString("http_defaults_http3_upgrade"), true);
    private final JCheckBox http2Coalescing = new JCheckBox(
            JMeterUtils.getResString("http_defaults_http2_coalescing"), true);
    private JMeterProperty legacyHttp2Coalescing;
    private boolean saveHttp2Coalescing = true;
    private JMeterProperty legacyHttp3Upgrade;
    private boolean saveHttp3Upgrade = true;
    private JTabbedPane tabbedPane;
    private final JComboBox<String> keepAlive = new JComboBox<>(new String[] {
            JMeterUtils.getResString("http_defaults_keepalive_unset"),
            JMeterUtils.getResString("http_defaults_keepalive_enabled"),
            JMeterUtils.getResString("http_defaults_keepalive_disabled")
    });
    private JTextField maxConnectionsPerHost;
    private JTextField connectionAvailabilityTimeout;
    private boolean configuring;
    private JTextField connectTimeOut;
    private JTextField responseTimeOut;
    private JMeterProperty legacyHttpProtocol;

    public HttpDefaultsGui() {
        super();
        init();
        HTTPSamplerBaseSchema schema = HTTPSamplerBaseSchema.INSTANCE;
        bindingGroup.addAll(
                Arrays.asList(
                        retrieveEmbeddedResources,
                        concurrentDwn,
                        new JTextComponentBinding(concurrentPool, schema.getConcurrentDownloadPoolSize()),
                        responseProcessingMode,
                        new JTextComponentBinding(embeddedAllowRE, schema.getEmbeddedUrlAllowRegex()),
                        new JTextComponentBinding(embeddedExcludeRE, schema.getEmbeddedUrlExcludeRegex()),
                        new JTextComponentBinding(sourceIpAddr, schema.getIpSource()),
                        // TODO: sourceIpType
                        new JTextComponentBinding(proxyScheme, schema.getProxy().getScheme()),
                        new JTextComponentBinding(proxyHost, schema.getProxy().getHost()),
                        new JTextComponentBinding(proxyPort, schema.getProxy().getPort()),
                        new JTextComponentBinding(proxyUser, schema.getProxy().getUsername()),
                        new JTextComponentBinding(proxyPass, schema.getProxy().getPassword()),
                        proxyDestinations,
                        new JTextComponentBinding(maxConnectionsPerHost, schema.getMaxConnectionsPerHost()),
                        new JTextComponentBinding(connectionAvailabilityTimeout, schema.getConnectionAvailabilityTimeout()),
                        new JTextComponentBinding(connectTimeOut, schema.getConnectTimeout()),
                        new JTextComponentBinding(responseTimeOut, schema.getResponseTimeout())
                )
        );
    }

    @Override
    public String getLabelResource() {
        return "url_config_title"; // $NON-NLS-1$
    }

    /**
     * @see org.apache.jmeter.gui.JMeterGUIComponent#createTestElement()
     */
    @Override
    public TestElement createTestElement() {
        ConfigTestElement config = new ConfigTestElement();
        modifyTestElement(config);
        return config;
    }

    /**
     * Modifies a given TestElement to mirror the data in the gui components.
     *
     * @see org.apache.jmeter.gui.JMeterGUIComponent#modifyTestElement(TestElement)
     */
    @Override
    public void modifyTestElement(TestElement config) {
        super.modifyTestElement(config);
        urlConfigGui.modifyTestElement(config);
        enableConcurrentDwn();

        HTTPSamplerBaseSchema httpSchema = HTTPSamplerBaseSchema.INSTANCE;
        if (concurrentDwn.getValue().equals(JEditableCheckBox.Value.of(false))) {
            // Even though we remove "concurrent download pool size" if the checkbox was unchecked,
            // we do it on purpose otherwise "merging defaults to regular http sampler" would unexpectedly
            // override "concurrent download pool size" value
            // TODO: keep "concurrent download pool size" in defaults, however somehow remove it before merging config
            //   to the http sampler
            config.removeProperty(httpSchema.getConcurrentDownloadPoolSize());
        }

        // Read the editor too: expressions may not have been committed with Enter yet.
        // Explicit false must remain distinct from an unset default.
        String reuse = keepAlive.getEditor().getItem().toString().trim();
        if (reuse.isEmpty() || reuse.equals(keepAlive.getItemAt(0))) {
            config.removeProperty(HTTPSamplerBase.USE_KEEPALIVE);
        } else if (reuse.equals(keepAlive.getItemAt(1))) {
            config.setProperty(HTTPSamplerBase.USE_KEEPALIVE, true);
        } else if (reuse.equals(keepAlive.getItemAt(2))) {
            config.setProperty(HTTPSamplerBase.USE_KEEPALIVE, false);
        } else {
            config.setProperty(HTTPSamplerBase.USE_KEEPALIVE, reuse);
        }

        String text = sourceIpAddr.getText();
        if (StringUtilities.isNotEmpty(text)) {
            config.set(httpSchema.getIpSourceType(), sourceIpType.getSelectedIndex());
        } else {
            config.removeProperty(httpSchema.getIpSourceType());
        }

        config.removeProperty(httpSchema.getImplementation());
        if (saveHttp2Coalescing) {
            config.setProperty(HTTPSamplerBase.HTTP2_COALESCING, http2Coalescing.isSelected());
        } else if (legacyHttp2Coalescing != null) {
            config.setProperty(legacyHttp2Coalescing.clone());
        } else {
            config.removeProperty(HTTPSamplerBase.HTTP2_COALESCING);
        }
        if (saveHttp3Upgrade) {
            config.setProperty(HTTPSamplerBase.HTTP3_UPGRADE, http3Upgrade.isSelected());
        } else if (legacyHttp3Upgrade != null) {
            config.setProperty(legacyHttp3Upgrade.clone());
        } else {
            config.removeProperty(HTTPSamplerBase.HTTP3_UPGRADE);
        }
        String selectedProtocol = selectedHttpProtocol();
        if (StringUtilities.isBlank(selectedProtocol)) {
            config.removeProperty(httpSchema.getHttpProtocol());
        } else {
            config.set(httpSchema.getHttpProtocol(), selectedProtocol);
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        proxyDestinations.updateUi(new org.apache.jmeter.config.ConfigTestElement());
        urlConfigGui.clear();
        legacyHttpProtocol = null;
        legacyHttp2Coalescing = null;
        saveHttp2Coalescing = true;
        http2Coalescing.setSelected(true);
        legacyHttp3Upgrade = null;
        saveHttp3Upgrade = true;
        http3Upgrade.setSelected(true);
        keepAlive.setSelectedIndex(0);
        httpProtocol.setSelectedItem(HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT);
    }

    @Override
    public void configure(TestElement el) {
        configuring = true;
        try {
            super.configure(el);
            AbstractTestElement samplerBase = (AbstractTestElement) el;
            urlConfigGui.configure(el);

            HTTPSamplerBaseSchema httpSchema = HTTPSamplerBaseSchema.INSTANCE;
            legacyHttp3Upgrade = el.getPropertyOrNull(HTTPSamplerBase.HTTP3_UPGRADE);
            if (legacyHttp3Upgrade != null) {
                legacyHttp3Upgrade = legacyHttp3Upgrade.clone();
            }
            legacyHttp2Coalescing = el.getPropertyOrNull(HTTPSamplerBase.HTTP2_COALESCING);
            if (legacyHttp2Coalescing != null) {
                legacyHttp2Coalescing = legacyHttp2Coalescing.clone();
            }
            saveHttp2Coalescing = false;
            http2Coalescing.setSelected(el.getPropertyAsBoolean(HTTPSamplerBase.HTTP2_COALESCING, true));
            saveHttp3Upgrade = false;
            http3Upgrade.setSelected(el.getPropertyAsBoolean(HTTPSamplerBase.HTTP3_UPGRADE,
                    JMeterUtils.getPropDefault("httpsampler.http3.prefer_for_default", true)));
            legacyHttpProtocol = el.getPropertyOrNull(httpSchema.getHttpProtocol().getName());
            if (legacyHttpProtocol != null) {
                legacyHttpProtocol = legacyHttpProtocol.clone();
            }
            JMeterProperty reuse = el.getPropertyOrNull(HTTPSamplerBase.USE_KEEPALIVE);
            if (reuse == null) {
                keepAlive.setSelectedIndex(0);
            } else if ("true".equals(reuse.getStringValue())) {
                keepAlive.setSelectedIndex(1);
            } else if ("false".equals(reuse.getStringValue())) {
                keepAlive.setSelectedIndex(2);
            } else {
                keepAlive.setSelectedItem(reuse.getStringValue());
            }
            sourceIpType.setSelectedIndex(samplerBase.get(httpSchema.getIpSourceType()));
            httpProtocol.setSelectedItem(configuredHttpProtocol());
        } finally {
            configuring = false;
        }
    }

    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());

        // URL CONFIG
        urlConfigGui = new UrlConfigGui(false, true, false);

        JPanel connectionsPanel = new VerticalPanel();
        connectionsPanel.add(getProtocolPanel());
        connectionsPanel.add(createConnectionPoolPanel());
        connectionsPanel.add(getTimeOutPanel());
        connectionsPanel.add(createSourceAddrPanel());
        http2Coalescing.addActionListener(event -> saveHttp2Coalescing = true);
        http3Upgrade.addActionListener(event -> saveHttp3Upgrade = true);
        httpProtocol.addActionListener(event -> {
            http2Coalescing.setEnabled(StringUtilities.isBlank(selectedHttpProtocol())
                    || HTTPSamplerBase.HTTP_PROTOCOL_HTTP_2.equals(selectedHttpProtocol()));
            http3Upgrade.setEnabled(StringUtilities.isBlank(selectedHttpProtocol()));
            if (!configuring && HTTPSamplerBase.HTTP_PROTOCOL_HTTP_1_1.equals(selectedHttpProtocol())
                    && maxConnectionsPerHost.getText().isBlank()) {
                maxConnectionsPerHost.setText("6");
            }
        });

        JPanel proxyPanel = new VerticalPanel();
        proxyPanel.add(getProxyServerPanel());

        JPanel advancedPanel = new VerticalPanel();
        advancedPanel.add(createEmbeddedRsrcPanel());
        advancedPanel.add(createResponseProcessingPanel());

        tabbedPane = new JTabbedPane();
        tabbedPane.add(JMeterUtils.getResString("http_defaults_tab_default"), urlConfigGui);
        tabbedPane.add(JMeterUtils.getResString("http_defaults_tab_connections"), connectionsPanel);
        tabbedPane.add(JMeterUtils.getResString("http_defaults_tab_proxy"), proxyPanel);
        tabbedPane.add(JMeterUtils.getResString("web_testing_advanced"), advancedPanel);

        JPanel emptyPanel = new JPanel();
        emptyPanel.setMaximumSize(new Dimension());

        add(makeTitlePanel(), BorderLayout.NORTH);
        add(tabbedPane, BorderLayout.CENTER);
        add(emptyPanel, BorderLayout.SOUTH);
    }

    private JPanel createConnectionPoolPanel() {
        JPanel panel = new JPanel(new MigLayout("insets 8, gap 10 4, wrap 3", "[][180!,fill][grow]"));
        panel.setBorder(BorderFactory.createTitledBorder(JMeterUtils.getResString("http1_connections_title")));
        keepAlive.setEditable(true);
        keepAlive.setToolTipText(JMeterUtils.getResString("http_defaults_keepalive_help"));
        addConnectionRow(panel, "http_defaults_keepalive", keepAlive, "http_defaults_keepalive_help");
        maxConnectionsPerHost = new JTextField(10);
        addConnectionRow(panel, "http1_max_connections_per_host", maxConnectionsPerHost, "http1_max_connections_help");
        connectionAvailabilityTimeout = new JTextField(10);
        addConnectionRow(panel, "http1_connection_availability_timeout", connectionAvailabilityTimeout,
                "http1_connection_availability_help");
        return panel;
    }

    private static void addConnectionRow(JPanel panel, String labelKey, JComponent input, String helpKey) {
        JLabel label = new JLabel(JMeterUtils.getResString(labelKey));
        label.setLabelFor(input);
        panel.add(label);
        panel.add(input, "h pref!, wrap");
        JTextArea help = new JTextArea(JMeterUtils.getResString(helpKey));
        help.setEditable(false);
        help.setFocusable(false);
        help.setOpaque(false);
        help.setBorder(BorderFactory.createEmptyBorder());
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setFont(UIManager.getFont("Label.font").deriveFont(java.awt.Font.PLAIN));
        // Wrapped text has an unbounded preferred height before the panel is laid out.
        // Reserve two full-width lines below the row without a text-field border.
        int helpHeight = 2 * help.getFontMetrics(help.getFont()).getHeight();
        panel.add(help, "span 3, growx, w 0:pref, h " + helpHeight + "!, gapbottom 4, wrap");
    }

    private JPanel getTimeOutPanel() {
        JPanel timeOut = new JPanel(new MigLayout("novisualpadding, insets 8, gap 10 8, wrap 2", "[][180!,fill]"));
        timeOut.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString("web_server_timeout_title")));
        connectTimeOut = new JTextField(10);
        responseTimeOut = new JTextField(10);
        int inputHeight = Math.max(connectTimeOut.getPreferredSize().height, responseTimeOut.getPreferredSize().height);
        String inputConstraints = "h " + inputHeight + "!, sgy timeouts";
        JLabel connectLabel = new JLabel(JMeterUtils.getResString("http_defaults_connect_timeout"));
        connectLabel.setLabelFor(connectTimeOut);
        timeOut.add(connectLabel);
        timeOut.add(connectTimeOut, inputConstraints);
        JLabel readLabel = new JLabel(JMeterUtils.getResString("http_defaults_read_timeout"));
        readLabel.setLabelFor(responseTimeOut);
        timeOut.add(readLabel);
        timeOut.add(responseTimeOut, inputConstraints);
        return timeOut;
    }

    protected JPanel createEmbeddedRsrcPanel() {
        // retrieve Embedded resources
        // add a listener to activate or not concurrent dwn.
        retrieveEmbeddedResources.addPropertyChangeListener(
                JEditableCheckBox.VALUE_PROPERTY,
                ev -> enableConcurrentDwn());
        // Download concurrent resources
        concurrentDwn.addPropertyChangeListener(
                JEditableCheckBox.VALUE_PROPERTY,
                ev -> enableConcurrentDwn());
        concurrentPool = new JTextField(2); // 2 columns size
        concurrentPool.setMinimumSize(new Dimension(10, (int) concurrentPool.getPreferredSize().getHeight()));
        concurrentPool.setMaximumSize(new Dimension(60, (int) concurrentPool.getPreferredSize().getHeight()));

        final JPanel embeddedRsrcPanel = new JPanel(new MigLayout());
        embeddedRsrcPanel.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString("web_testing_retrieve_title"))); // $NON-NLS-1$
        embeddedRsrcPanel.add(retrieveEmbeddedResources);
        embeddedRsrcPanel.add(concurrentDwn);
        embeddedRsrcPanel.add(concurrentPool, "wrap");

        // Embedded URL match regex to allow
        embeddedAllowRE = addTextFieldWithLabel(embeddedRsrcPanel, JMeterUtils.getResString("web_testing_embedded_url_pattern")); // $NON-NLS-1$

        // Embedded URL match regex to exclude
        embeddedExcludeRE = addTextFieldWithLabel(embeddedRsrcPanel, JMeterUtils.getResString("web_testing_embedded_url_exclude_pattern")); // $NON-NLS-1$

        return embeddedRsrcPanel;
    }

    private static JTextField addTextFieldWithLabel(JPanel panel, String labelText) {
        JLabel label = new JLabel(labelText); // $NON-NLS-1$
        JTextField field = new JTextField(100);
        label.setLabelFor(field);
        panel.add(label);
        panel.add(field, "span");
        return field;
    }

    @SuppressWarnings("EnumOrdinal")
    protected JPanel createSourceAddrPanel() {
        final JPanel sourceAddrPanel = new HorizontalPanel();
        sourceAddrPanel.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString("web_testing_source_ip"))); // $NON-NLS-1$

        sourceIpType.setSelectedIndex(HTTPSamplerBase.SourceType.HOSTNAME.ordinal()); //default: IP/Hostname
        sourceAddrPanel.add(sourceIpType);

        sourceIpAddr = new JTextField();
        sourceAddrPanel.add(sourceIpAddr);
        return sourceAddrPanel;
    }

    protected JPanel createResponseProcessingPanel() {
        JPanel panel = new JPanel(new MigLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString("response_processing_title"))); // $NON-NLS-1$
        panel.add(responseProcessingMode, "split 2");
        panel.add(responseProcessingInfo);
        return panel;
    }

    @Override
    public Dimension getPreferredSize() {
        return getMinimumSize();
    }

    private void enableConcurrentDwn() {
        boolean enable = !JEditableCheckBox.Value.of(false).equals(retrieveEmbeddedResources.getValue());
        concurrentDwn.setEnabled(enable);
        embeddedAllowRE.setEnabled(enable);
        embeddedExcludeRE.setEnabled(enable);
        // Allow editing the pool size if "download concurrently" checkbox is set or has expression
        concurrentPool.setEnabled(enable && !concurrentDwn.getValue().equals(JEditableCheckBox.Value.of(false)));
    }

    /**
     * Create a panel containing the HTTP wire protocol selection
     *
     * @return the panel
     */
    protected final JPanel getProtocolPanel(){
        JPanel implPanel = new VerticalPanel();
        implPanel.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString("web_server_client"))); // $NON-NLS-1$
        JPanel protocolRow = new HorizontalPanel();
        protocolRow.add(new JLabel(JMeterUtils.getResString("http_wire_protocol"))); // $NON-NLS-1$
        protocolRow.add(httpProtocol);
        implPanel.add(protocolRow);
        implPanel.add(http2Coalescing);
        implPanel.add(new JLabel(JMeterUtils.getResString("http_defaults_http2_coalescing_help")));
        implPanel.add(http3Upgrade);
        implPanel.add(new JLabel(JMeterUtils.getResString("http_defaults_http3_upgrade_help")));
        return implPanel;
    }

    private String configuredHttpProtocol() {
        return legacyHttpProtocol == null
                ? HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT
                : HTTPSamplerBase.normalizeHttpProtocol(legacyHttpProtocol.getStringValue());
    }

    private String selectedHttpProtocol() {
        Object selected = httpProtocol.getSelectedItem();
        return selected == null ? HTTPSamplerBase.HTTP_PROTOCOL_DEFAULT : selected.toString();
    }

    /**
     * Create a panel containing the proxy server details
     *
     * @return the panel
     */
    protected final JPanel getProxyServerPanel(){
        JPanel proxyServer = new HorizontalPanel();
        proxyServer.add(getProxySchemePanel(), BorderLayout.WEST);
        proxyServer.add(getProxyHostPanel(), BorderLayout.CENTER);
        proxyServer.add(getProxyPortPanel(), BorderLayout.EAST);

        JPanel proxyLogin = new HorizontalPanel();
        proxyLogin.add(getProxyUserPanel());
        proxyLogin.add(getProxyPassPanel());

        JPanel proxyServerPanel = new JPanel(new BorderLayout(0, 5));
        proxyServerPanel.setBorder(BorderFactory.createTitledBorder(
                JMeterUtils.getResString("web_proxy_server_title"))); // $NON-NLS-1$
        JPanel endpoint = new HorizontalPanel();
        endpoint.add(proxyServer);
        endpoint.add(proxyLogin);
        proxyServerPanel.add(endpoint, BorderLayout.NORTH);
        proxyDestinations.setEndpointFields(proxyScheme, proxyHost, proxyPort, proxyUser, proxyPass);
        proxyServerPanel.add(proxyDestinations, BorderLayout.CENTER);

        return proxyServerPanel;
    }

    private JPanel getProxySchemePanel() {
        JComboBox<String> schemeSelector = new JComboBox<>(new String[] {"", "http", "https"});
        schemeSelector.setEditable(true);
        proxyScheme = (JTextField) schemeSelector.getEditor().getEditorComponent();
        proxyScheme.setColumns(5);

        JLabel label = new JLabel(JMeterUtils.getResString("web_proxy_scheme")); // $NON-NLS-1$
        label.setLabelFor(proxyScheme);
        JFactory.small(label);

        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.add(label, BorderLayout.WEST);
        panel.add(schemeSelector, BorderLayout.CENTER);
        return panel;
    }

    private JPanel getProxyHostPanel() {
        proxyHost = new JTextField(10);

        JLabel label = new JLabel(JMeterUtils.getResString("web_server_domain")); // $NON-NLS-1$
        label.setLabelFor(proxyHost);
        JFactory.small(label);

        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.add(label, BorderLayout.WEST);
        panel.add(proxyHost, BorderLayout.CENTER);
        return panel;
    }

    private JPanel getProxyPortPanel() {
        proxyPort = new JTextField(10);

        JLabel label = new JLabel(JMeterUtils.getResString("web_server_port")); // $NON-NLS-1$
        label.setLabelFor(proxyPort);
        JFactory.small(label);

        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.add(label, BorderLayout.WEST);
        panel.add(proxyPort, BorderLayout.CENTER);

        return panel;
    }

    private JPanel getProxyUserPanel() {
        proxyUser = new JTextField(5);

        JLabel label = new JLabel(JMeterUtils.getResString("username")); // $NON-NLS-1$
        label.setLabelFor(proxyUser);
        JFactory.small(label);

        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.add(label, BorderLayout.WEST);
        panel.add(proxyUser, BorderLayout.CENTER);
        return panel;
    }

    private JPanel getProxyPassPanel() {
        proxyPass = new JPasswordField(5);

        JLabel label = new JLabel(JMeterUtils.getResString("password")); // $NON-NLS-1$
        label.setLabelFor(proxyPass);
        JFactory.small(label);

        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.add(label, BorderLayout.WEST);
        panel.add(proxyPass, BorderLayout.CENTER);
        return panel;
    }
}
