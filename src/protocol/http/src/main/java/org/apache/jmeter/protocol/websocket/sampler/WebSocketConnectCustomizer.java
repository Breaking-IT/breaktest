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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.gui.HeaderTablePanel;
import org.apache.jmeter.testbeans.gui.GenericTestBeanCustomizer;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.util.JMeterUtils;

/** Session settings and the same header table used by HTTP Request. */
public class WebSocketConnectCustomizer extends GenericTestBeanCustomizer {
    private static final long serialVersionUID = 1L;
    private final HeaderTablePanel headers = new HeaderTablePanel(false);
    private transient Map<String, Object> properties;

    public WebSocketConnectCustomizer() {
        super(new WebSocketConnectSamplerBeanInfo());
        GridBagLayout generatedLayout = (GridBagLayout) getLayout();
        JPanel session = new JPanel(new GridBagLayout());
        for (Component component : getComponents()) {
            session.add(component, generatedLayout.getConstraints(component));
        }
        removeAll();
        ResourceBundle resources = ResourceBundle.getBundle(
                WebSocketConnectSampler.class.getName() + "Resources", JMeterUtils.getLocale());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(resources.getString("session.displayName"), session);
        tabs.addTab(resources.getString("headers.displayName"), headers);
        setLayout(new BorderLayout());
        add(tabs, BorderLayout.CENTER);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setObject(Object object) {
        super.setObject(object);
        properties = (Map<String, Object>) object;
        List<Header> values = new ArrayList<>();
        if (properties.get("headers") instanceof Collection<?> collection) {
            for (Object item : collection) {
                Object value = item instanceof JMeterProperty property ? property.getObjectValue() : item;
                if (value instanceof Header header) {
                    values.add(header);
                }
            }
        }
        headers.setHeaders(values);
    }

    @Override
    protected void saveGuiFields() {
        super.saveGuiFields();
        properties.put("headers", headers.getHeaders());
    }
}
