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
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.apache.jmeter.gui.action.Copy;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.sse.SseMatchController;
import org.apache.jmeter.util.JMeterTreeNodeTransferable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HTTPSamplerClipboardTest extends JMeterTestCase {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void canCopyHttpSamplerAgainAfterClipboardRoundTrip(boolean sseEnabled) throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("Copied request");
        sampler.setPath("/events");
        sampler.setSseEnabled(sseEnabled);
        JMeterTreeNode original = new JMeterTreeNode(sampler, null);
        JMeterTreeNode pasted = clipboardRoundTrip(Copy.cloneTreeNode(original));

        JMeterTreeNode copiedAgain = Copy.cloneTreeNode(pasted);
        HTTPSamplerProxy copy = (HTTPSamplerProxy) copiedAgain.getTestElement();
        assertNotSame(pasted.getTestElement(), copy);
        assertEquals("Copied request", copy.getName());
        assertEquals("/events", copy.getPath());
        assertEquals(sseEnabled, copy.isSseEnabled());
    }

    @Test
    void canAttachSseControllersAfterClipboardRoundTrip() throws Exception {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setSseEnabled(true);
        sampler.addChildController(new SseMatchController());
        HTTPSamplerProxy pasted = (HTTPSamplerProxy) clipboardRoundTrip(
                new JMeterTreeNode(sampler, null)).getTestElement();

        // Runtime child-controller bindings are rebuilt when the pasted tree is compiled.
        pasted.addChildController(new SseMatchController());
        HTTPSamplerProxy copy = (HTTPSamplerProxy) pasted.clone();
        copy.addChildController(new SseMatchController());
    }

    private static JMeterTreeNode clipboardRoundTrip(JMeterTreeNode node) throws Exception {
        JMeterTreeNodeTransferable transferable = new JMeterTreeNodeTransferable();
        transferable.setTransferData(new JMeterTreeNode[]{node});
        return JMeterTreeNodeTransferable.readTransferData(transferable)[0];
    }
}
