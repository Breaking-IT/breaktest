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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.DefaultComboBoxModel;
import javax.swing.SwingUtilities;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.gui.TreeNodeWrapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.proxy.gui.ProxyControlGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.scenario.SharedProfile;
import org.apache.jmeter.scenario.ThreadGroupsSection;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** The recorder in a test plan organised in sections. */
class ProxyControlSectionsTest extends JMeterTestCase {
    private final JMeterTreeModel model = new JMeterTreeModel();

    private JMeterTreeNode add(TestElement element, JMeterTreeNode parent) {
        JMeterTreeNode node = new JMeterTreeNode(element, model);
        model.insertNodeInto(node, parent, parent.getChildCount());
        return node;
    }

    private JMeterTreeNode recordMe() {
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("RecordMe");
        return add(threadGroup, model.getNodesOfType(ThreadGroupsSection.class).get(0));
    }

    @Test
    void recordingUsesTheConfigurationOfTheSharedProfile() throws Exception {
        Arguments shared = new Arguments();
        shared.addArgument("host", "example.com");
        add(shared, model.getNodesOfType(SharedProfile.class).get(0));
        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);

        Method find = ProxyControl.class.getDeclaredMethod(
                "findApplicableElements", JMeterTreeNode.class, Class.class, boolean.class);
        find.setAccessible(true);
        Collection<?> variables = (Collection<?>) find.invoke(proxy, recordMe(), Arguments.class, false);

        assertTrue(variables.contains(shared), "variables of the Shared Profile apply to recorded samplers");
    }

    @Test
    void targetControllersIncludeThreadGroupsInsideSections() throws Exception {
        recordMe();
        GuiPackage previousGui = GuiPackage.getInstance();
        List<String> targets = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                GuiPackage.initInstance(new JMeterTreeListener(model), model);
                ProxyControlGui gui = new ProxyControlGui();
                gui.configure(new ProxyControl());
                DefaultComboBoxModel<?> combo = targetModel(gui);
                for (int i = 0; i < combo.getSize(); i++) {
                    targets.add(((TreeNodeWrapper) combo.getElementAt(i)).toString());
                }
            });
        } finally {
            var field = GuiPackage.class.getDeclaredField("guiPack");
            field.setAccessible(true);
            field.set(null, previousGui);
        }
        assertTrue(targets.stream().anyMatch(target -> target.endsWith("RecordMe")), "targets: " + targets);
    }

    @Test
    void recordsIntoTheTargetThreadGroupOfASectionedPlan() throws Exception {
        JMeterTreeNode target = recordMe();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        site.start();
        ProxyControl proxy = new ProxyControl();
        proxy.setNonGuiTreeModel(model);
        proxy.setTarget(target);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        proxy.setPort(port);
        model.addComponent(proxy, (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0));
        proxy.startProxy();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), port)))
                    .build();
            URI uri = URI.create("http://localhost:" + site.getAddress().getPort() + "/login");
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            long deadline = System.currentTimeMillis() + 10_000;
            while (samplerNames(target).isEmpty() && System.currentTimeMillis() < deadline) {
                SwingUtilities.invokeAndWait(() -> { });
                Thread.sleep(100);
            }
        } finally {
            proxy.stopProxy();
            site.stop(0);
        }
        assertTrue(samplerNames(target).stream().anyMatch(name -> name.contains("/login")),
                "recorded into RecordMe: " + samplerNames(target));
    }

    private static List<String> samplerNames(JMeterTreeNode node) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) node.getChildAt(i);
            if (child.getUserObject() instanceof HTTPSamplerBase) {
                names.add(child.getName());
            }
            names.addAll(samplerNames(child));
        }
        return names;
    }

    private static DefaultComboBoxModel<?> targetModel(ProxyControlGui gui) {
        try {
            var field = ProxyControlGui.class.getDeclaredField("targetNodesModel");
            field.setAccessible(true);
            return (DefaultComboBoxModel<?>) field.get(gui);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
