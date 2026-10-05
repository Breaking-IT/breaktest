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

package org.apache.jmeter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.swing.SwingUtilities;

import org.apache.jmeter.config.gui.ClientCertificateConfigGui;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.junit.jupiter.api.Test;

class ClientCertificateConfigGuiTest extends JMeterTestCase {
    @Test
    void editorPreservesVariableExpressionsAndAllModesInJmx() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ClientCertificateConfigGui gui = new ClientCertificateConfigGui();
            for (String mode : List.of(ClientCertificateConfig.INHERIT, ClientCertificateConfig.CERTIFICATE,
                    ClientCertificateConfig.NONE)) {
                ClientCertificateConfig config = new ClientCertificateConfig();
                config.setProperty(ClientCertificateConfig.MODE, mode);
                config.setProperty(ClientCertificateConfig.STORE, "${certFile}");
                config.setProperty(ClientCertificateConfig.TYPE, "PKCS12");
                config.setProperty(ClientCertificateConfig.PASSWORD, "${certPassword}");
                config.setProperty(ClientCertificateConfig.ALIAS, "${clientCertAlias}");
                gui.configure(config);
                TestElement saved = gui.createTestElement();
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    SaveService.saveElement(saved, output);
                    var restored = (ClientCertificateConfig) SaveService.loadElement(
                            new ByteArrayInputStream(output.toByteArray()));
                    assertEquals(mode, restored.getMode());
                    for (String field : List.of(ClientCertificateConfig.STORE, ClientCertificateConfig.TYPE,
                            ClientCertificateConfig.PASSWORD, ClientCertificateConfig.ALIAS)) {
                        assertEquals(config.getPropertyAsString(field), restored.getPropertyAsString(field));
                    }
                    assertEquals(ClientCertificateConfigGui.class.getName(),
                            restored.getPropertyAsString(TestElement.GUI_CLASS));
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
            }
            gui.clearGui();
            ClientCertificateConfig reset = (ClientCertificateConfig) gui.createTestElement();
            assertTrue(reset.isInherit());
            assertEquals("", reset.getPropertyAsString(ClientCertificateConfig.PASSWORD));
        });
    }
}
