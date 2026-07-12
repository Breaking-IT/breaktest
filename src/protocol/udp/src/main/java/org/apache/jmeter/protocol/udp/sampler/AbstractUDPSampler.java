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

package org.apache.jmeter.protocol.udp.sampler;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.util.Arrays;
import java.util.Properties;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.util.JMeterUtils;

abstract class AbstractUDPSampler extends AbstractSampler implements Interruptible, ThreadListener {

    static final String TIMEOUT = "timeout"; // $NON-NLS-1$
    static final String ENCODE_CLASS = "encodeclass"; // $NON-NLS-1$
    static final String SOCKET_ID = "socket_id"; // $NON-NLS-1$

    static final int MAX_IPV4_PAYLOAD_SIZE = 65_507;
    static final int MAX_IPV6_PAYLOAD_SIZE = 65_527;
    static final String RECEIVE_BUFFER_SIZE_PROPERTY = "udp.receive_buffer_size"; // $NON-NLS-1$

    private transient UDPTrafficCodec codec;
    private transient RuntimeException codecFailure;
    private transient byte[] receiveBuffer;
    private transient DatagramPacket receivePacket;

    public String getTimeout() {
        return getPropertyAsString(TIMEOUT, "1000");
    }

    public void setTimeout(String timeout) {
        setProperty(TIMEOUT, timeout);
    }

    int getTimeoutAsInt() {
        int timeout = Integer.parseInt(getTimeout());
        if (timeout < 0) {
            throw new IllegalArgumentException("UDP response timeout must be zero or greater");
        }
        return timeout;
    }

    public String getEncoderClass() {
        return getPropertyAsString(ENCODE_CLASS, UTF8StringUDPTrafficCodec.class.getName());
    }

    public void setEncoderClass(String encoderClass) {
        setProperty(ENCODE_CLASS, encoderClass);
        codec = null;
        codecFailure = null;
    }

    public String getSocketID() {
        return getPropertyAsString(SOCKET_ID);
    }

    public void setSocketID(String socketId) {
        setProperty(SOCKET_ID, socketId);
    }

    final UDPTrafficCodec getCodec() {
        if (codecFailure != null) {
            throw codecFailure;
        }
        if (codec == null) {
            initializeCodec();
        }
        return codec;
    }

    final long getSocketScope() {
        return Thread.currentThread().threadId();
    }

    final UDPSocketManager.SocketKey getSocketKey() {
        return new UDPSocketManager.SocketKey(getSocketScope(), getSocketID());
    }

    final boolean hasSharedSocket() {
        return !getSocketID().isBlank();
    }

    @Override
    public void threadStarted() {
        initializeCodec();
        UDPSocketManager.registerThread(getSocketScope());
    }

    @Override
    public void threadFinished() {
        closeOwnedSocket();
        UDPSocketManager.unregisterThread(getSocketScope());
    }

    abstract void closeOwnedSocket();

    final byte[] receiveDatagram(DatagramSocket socket, SampleResult result) throws IOException {
        int protocolMaximum = socket.getInetAddress() instanceof Inet4Address
                ? MAX_IPV4_PAYLOAD_SIZE
                : MAX_IPV6_PAYLOAD_SIZE;
        int configuredLimit = getUdpProperty(RECEIVE_BUFFER_SIZE_PROPERTY, protocolMaximum);
        int receiveLimit = Math.max(1, Math.min(protocolMaximum, configuredLimit));
        int probeCapacity = receiveLimit < protocolMaximum ? receiveLimit + 1 : receiveLimit;
        if (receiveBuffer == null || receiveBuffer.length != probeCapacity) {
            receiveBuffer = new byte[probeCapacity];
            receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        } else {
            receivePacket.setData(receiveBuffer, 0, receiveBuffer.length);
        }

        socket.receive(receivePacket);
        result.latencyEnd();
        if (receivePacket.getLength() > receiveLimit) {
            throw new UDPDatagramTooLargeException(
                    "UDP response exceeds the configured receive limit of " + receiveLimit + " bytes");
        }
        return Arrays.copyOfRange(
                receivePacket.getData(),
                receivePacket.getOffset(),
                receivePacket.getOffset() + receivePacket.getLength());
    }

    static int maximumPayloadSize(DatagramSocket socket) {
        return socket.getInetAddress() instanceof Inet4Address
                ? MAX_IPV4_PAYLOAD_SIZE
                : MAX_IPV6_PAYLOAD_SIZE;
    }

    static int getUdpProperty(String name, int defaultValue) {
        Properties properties = JMeterUtils.getJMeterProperties();
        if (properties == null) {
            return Integer.getInteger(name, defaultValue);
        }
        return JMeterUtils.getPropDefault(name, defaultValue);
    }

    private void initializeCodec() {
        try {
            String className = getEncoderClass();
            if (className.isBlank()) {
                codec = new RawUDPTrafficCodec();
                return;
            }
            Class<?> codecClass = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
            Object instance = codecClass.getDeclaredConstructor().newInstance();
            if (!(instance instanceof UDPTrafficCodec udpTrafficCodec)) {
                throw new IllegalArgumentException(className + " does not implement " + UDPTrafficCodec.class.getName());
            }
            codec = udpTrafficCodec;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            codecFailure = new IllegalArgumentException("Unable to initialize UDP data codec '"
                    + getEncoderClass() + '\'', ex);
        }
    }
}
