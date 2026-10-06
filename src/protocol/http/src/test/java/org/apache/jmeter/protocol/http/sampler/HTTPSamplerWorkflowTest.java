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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.gui.action.Copy;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.sse.SseSampler;
import org.apache.jmeter.protocol.sse.SseSamplerGui;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterTreeNodeTransferable;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.sun.net.httpserver.HttpServer;

@Timeout(20)
class HTTPSamplerWorkflowTest extends JMeterTestCase {
    @TempDir
    Path tempDir;

    enum Transfer { DUPLICATE, CLIPBOARD, LEGACY_CLIPBOARD, SAVE_RELOAD }
    enum Kind { HTTP, SSE_FLAG, SSE_SAMPLER }

    static Stream<Arguments> workflows() {
        return Stream.of(Kind.values()).flatMap(kind ->
                Stream.of(Transfer.values()).map(transfer -> Arguments.of(kind, transfer)));
    }

    @ParameterizedTest
    @MethodSource("workflows")
    void repeatedTransfersPreserveConfigurationAndAllowIndependentEditing(Kind kind, Transfer transfer) throws Exception {
        HTTPSamplerProxy original = configuredSampler(kind);
        HTTPSamplerProxy current = original;
        for (int i = 0; i < 3; i++) {
            current = transfer(current, transfer);
            assertEquals(original.getClass(), current.getClass());
            assertEquals(kind != Kind.HTTP, current.isSseEnabled());
            assertEquals("Request", current.getName());
            assertEquals("example.test", current.getDomain());
            assertEquals("/submit", current.getPath());
            assertEquals("POST", current.getMethod());
            assertEquals("payload", current.getArguments().getArgument(0).getValue());
            assertTrue(current.getPostBodyRaw());
            assertEquals("3000", current.getPropertyAsString(HTTPSamplerBase.RESPONSE_TIMEOUT));
            assertEquals("session", current.getSseSessionName());
            assertEquals("original", current.getNativeHeaderList().get(0).getValue());
            assertEquals("second", current.getNativeHeaderList().get(1).getValue());
            assertNotSame(original.getNativeHeaderList().get(0), current.getNativeHeaderList().get(0));
        }
        current.getNativeHeaderList().get(0).setValue("changed");
        current.getArguments().getArgument(0).setValue("changed body");
        assertEquals("original", original.getNativeHeaderList().get(0).getValue());
        assertEquals("payload", original.getArguments().getArgument(0).getValue());
    }

    @ParameterizedTest
    @EnumSource(Transfer.class)
    void ordinaryHttpStillExecutesAfterTransfer(Transfer transfer) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submit", exchange -> {
            String request = exchange.getRequestMethod() + "|" + exchange.getRequestHeaders().getFirst("X-Test")
                    + "|" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = request.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        HTTPSamplerProxy sampler = null;
        HTTPSamplerProxy original = configuredSampler(Kind.HTTP);
        try {
            original.setDomain("127.0.0.1");
            original.setPort(server.getAddress().getPort());
            // Exercise transfer of a sampler that has already initialized its HTTP transport.
            var firstResult = original.sample();
            assertTrue(firstResult.isSuccessful(), firstResult::getResponseMessage);
            assertEquals("POST|original|payload", firstResult.getResponseDataAsString());
            sampler = transfer(transfer(original, transfer), transfer);
            assertFalse(sampler.isSseEnabled());
            var result = sampler.sample();
            assertTrue(result.isSuccessful(), result::getResponseMessage);
            assertEquals("200", result.getResponseCode());
            assertEquals("POST|original|payload", result.getResponseDataAsString());
        } finally {
            if (sampler != null) {
                sampler.threadFinished();
            }
            original.threadFinished();
            server.stop(0);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Transfer.class, names = {"CLIPBOARD", "LEGACY_CLIPBOARD"})
    void copyingParentWithMixedHttpAndSseChildrenPreservesTheWholeSubtree(Transfer transfer) throws Exception {
        JMeterTreeNode root = new JMeterTreeNode(new GenericController(), null);
        for (Kind kind : Kind.values()) {
            root.add(new JMeterTreeNode(configuredSampler(kind), null));
        }
        JMeterTreeNode current = root;
        for (int i = 0; i < 3; i++) {
            current = Copy.cloneTreeNodes(new JMeterTreeNode[]{clipboard(current, transfer)})[0];
            assertEquals(3, current.getChildCount());
            for (int child = 0; child < 3; child++) {
                HTTPSamplerProxy copied = (HTTPSamplerProxy) ((JMeterTreeNode) current.getChildAt(child)).getTestElement();
                assertEquals(child != 0, copied.isSseEnabled());
                assertEquals("payload", copied.getArguments().getArgument(0).getValue());
            }
        }
    }

    private static HTTPSamplerProxy configuredSampler(Kind kind) {
        HTTPSamplerProxy sampler = kind == Kind.SSE_SAMPLER ? new SseSampler() : new HTTPSamplerProxy();
        if (kind == Kind.SSE_FLAG) {
            sampler.setSseEnabled(true);
        }
        // Leave the SSE property absent for HTTP, as it is in pre-SSE plans.
        sampler.setProperty(TestElement.GUI_CLASS,
                kind == Kind.SSE_SAMPLER ? SseSamplerGui.class.getName() : HttpTestSampleGui.class.getName());
        sampler.setName("Request");
        sampler.setDomain("example.test");
        sampler.setProtocol("http");
        sampler.setPath("/submit");
        sampler.setMethod("POST");
        sampler.setConnectTimeout("3000");
        sampler.setResponseTimeout("3000");
        sampler.setSseSessionName("session");
        sampler.setPostBodyRaw(true);
        sampler.addNonEncodedArgument("", "payload", "");
        sampler.setNativeHeaders(List.of(new Header("X-Test", "original"), new Header("X-Other", "second")));
        return sampler;
    }

    private HTTPSamplerProxy transfer(HTTPSamplerProxy sampler, Transfer transfer) throws Exception {
        JMeterTreeNode node = new JMeterTreeNode(sampler, null);
        if (transfer == Transfer.SAVE_RELOAD) {
            ListedHashTree tree = new ListedHashTree();
            tree.add(sampler);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            SaveService.saveTree(tree, output);
            Path file = tempDir.resolve("request.jmx");
            Files.write(file, output.toByteArray());
            node = new JMeterTreeNode((HTTPSamplerProxy) SaveService.loadTree(file.toFile()).getArray()[0], null);
        } else if (transfer != Transfer.DUPLICATE) {
            node = clipboard(node, transfer);
        }
        // Both Copy and Duplicate use this recursive cloning path.
        return (HTTPSamplerProxy) Copy.cloneTreeNodes(new JMeterTreeNode[]{node})[0].getTestElement();
    }

    private static JMeterTreeNode clipboard(JMeterTreeNode node, Transfer transfer) throws Exception {
        JMeterTreeNodeTransferable data = new JMeterTreeNodeTransferable();
        data.setTransferData(new JMeterTreeNode[]{node});
        if (transfer == Transfer.LEGACY_CLIPBOARD) {
            return ((JMeterTreeNode[]) data.getTransferData(
                    JMeterTreeNodeTransferable.JMETER_TREE_NODE_ARRAY_DATA_FLAVOR))[0];
        }
        return JMeterTreeNodeTransferable.readTransferData(data)[0];
    }
}
