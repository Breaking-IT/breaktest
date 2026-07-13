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

package org.apache.jmeter.protocol.udp.control.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.swing.JTextField;

import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.protocol.udp.sampler.HexStringUDPTrafficCodec;
import org.apache.jmeter.protocol.udp.sampler.RawUDPTrafficCodec;
import org.apache.jmeter.protocol.udp.sampler.UTF8StringUDPTrafficCodec;
import org.junit.jupiter.api.Test;

class UDPDataCodecPanelTest extends JMeterTestCase {

    private final UDPDataCodecPanel panel = new UDPDataCodecPanel();

    @Test
    void defaultsToFriendlyUtf8TextFormat() {
        assertEquals(UTF8StringUDPTrafficCodec.class.getName(), panel.getCodecClass());
    }

    @Test
    void mapsBuiltInCodecClassesToPayloadFormats() {
        panel.setCodecClass(HexStringUDPTrafficCodec.class.getName());
        assertEquals(HexStringUDPTrafficCodec.class.getName(), panel.getCodecClass());

        panel.setCodecClass(RawUDPTrafficCodec.class.getName());
        assertEquals(RawUDPTrafficCodec.class.getName(), panel.getCodecClass());
    }

    @Test
    void preservesCustomCodecClass() {
        panel.setCodecClass("example.protocol.CustomCodec");

        assertEquals("example.protocol.CustomCodec", panel.getCodecClass());
    }

    @Test
    void rejectsBlankCustomCodecClass() {
        panel.setCodecClass("example.protocol.CustomCodec");
        JTextField customClass = assertInstanceOf(JTextField.class, panel.getComponent(4));
        customClass.setText("   ");

        IllegalStateException failure = assertThrows(IllegalStateException.class, panel::getCodecClass);

        assertEquals("Enter a custom UDP codec classname.", failure.getMessage());
    }

    @Test
    void mapsLegacyBlankCodecToSingleByteFormat() {
        panel.setCodecClass("");

        assertEquals(RawUDPTrafficCodec.class.getName(), panel.getCodecClass());
    }
}
