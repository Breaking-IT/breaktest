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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TestElementMetadata(labelResource = "udp_sample_title")
public class UDPSampler extends AbstractUDPSampler {

    private static final Logger log = LoggerFactory.getLogger(UDPSampler.class);

    public static final String HOSTNAME = "hostname"; // $NON-NLS-1$
    public static final String PORT = "port"; // $NON-NLS-1$
    public static final String DATA = "data"; // $NON-NLS-1$
    public static final String WAIT_RESPONSE = "waitresponse"; // $NON-NLS-1$
    public static final String CLOSE_SOCKET = "closechannel"; // $NON-NLS-1$
    public static final String BIND_ADDRESS = "bind_address"; // $NON-NLS-1$
    public static final String BIND_PORT = "bind_port"; // $NON-NLS-1$
    public static final String ALLOW_UNEXPECTED_DISCONNECT = "allow_unexpected_disconnect"; // $NON-NLS-1$

    private static final String FRAGMENTATION_WARNING_SIZE_PROPERTY =
            "udp.fragmentation_warning_size"; // $NON-NLS-1$

    private transient volatile UDPSocketManager.SocketHandle ownedHandle;
    private transient volatile UDPSocketManager.SocketHandle activeHandle;
    private transient volatile UDPSocketManager.SocketKey activeSocketKey;
    private transient boolean fragmentationWarningLogged;

    public String getHostName() {
        return getPropertyAsString(HOSTNAME);
    }

    public void setHostName(String hostName) {
        setProperty(HOSTNAME, hostName);
    }

    public String getPort() {
        return getPropertyAsString(PORT);
    }

    public void setPort(String port) {
        setProperty(PORT, port);
    }

    public String getRequestData() {
        return getPropertyAsString(DATA);
    }

    public void setRequestData(String requestData) {
        setProperty(DATA, requestData);
    }

    public boolean isWaitResponse() {
        return getPropertyAsBoolean(WAIT_RESPONSE, true);
    }

    public void setWaitResponse(boolean waitResponse) {
        setProperty(WAIT_RESPONSE, waitResponse);
    }

    public boolean isCloseChannel() {
        return getPropertyAsBoolean(CLOSE_SOCKET);
    }

    public void setCloseChannel(boolean closeSocket) {
        setProperty(CLOSE_SOCKET, closeSocket);
    }

    public String getBindAddress() {
        return getPropertyAsString(BIND_ADDRESS);
    }

    public void setBindAddress(String bindAddress) {
        setProperty(BIND_ADDRESS, bindAddress);
    }

    public String getBindPort() {
        return getPropertyAsString(BIND_PORT);
    }

    public void setBindPort(String bindPort) {
        setProperty(BIND_PORT, bindPort);
    }

    public boolean isAllowUnexpectedDisconnect() {
        return getPropertyAsBoolean(ALLOW_UNEXPECTED_DISCONNECT);
    }

    public void setAllowUnexpectedDisconnect(boolean allowUnexpectedDisconnect) {
        setProperty(ALLOW_UNEXPECTED_DISCONNECT, allowUnexpectedDisconnect);
    }

    @Override
    public SampleResult sample(Entry entry) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(getName());
        result.setSamplerData(getRequestData());
        result.setDataType(SampleResult.TEXT);
        result.setContentType("application/octet-stream"); // $NON-NLS-1$
        result.sampleStart();

        try {
            UDPSocketManager.SocketHandle handle = getSocketHandle();
            activeSocketKey = hasSharedSocket() ? getSocketKey() : null;
            activeHandle = handle;
            ReentrantLock lock = handle.lock();
            lock.lockInterruptibly();
            try {
                DatagramSocket socket = handle.socket();
                socket.setSoTimeout(getTimeoutAsInt());
                result.connectEnd();

                byte[] request = getCodec().encode(getRequestData());
                validateRequestSize(socket, request.length);
                socket.send(new DatagramPacket(request, request.length));
                result.setSentBytes(request.length);

                byte[] response = isWaitResponse() ? receiveDatagram(socket, result) : new byte[0];
                result.setResponseData(getCodec().decode(response));
                result.setBodySize((long) response.length);
                result.setSuccessful(true);
                result.setResponseCodeOK();
                result.setResponseMessageOK();
            } finally {
                lock.unlock();
            }
        } catch (UDPDatagramTooLargeException ex) {
            fail(result, "413", ex.getMessage(), ex);
        } catch (SocketTimeoutException ex) {
            fail(result, "408", "UDP response timed out", ex);
        } catch (PortUnreachableException ex) {
            handleDisconnect(result, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(result, "500", "UDP request interrupted", ex);
        } catch (Exception ex) {
            fail(result, "500", ex.getMessage(), ex);
        } finally {
            activeHandle = null;
            activeSocketKey = null;
            if (isCloseChannel() && !hasSharedSocket()) {
                closeOwnedSocket();
            }
            if (result.getEndTime() == 0) {
                result.sampleEnd();
            }
        }
        return result;
    }

    private UDPSocketManager.SocketHandle getSocketHandle() throws Exception {
        String host = getHostName().trim();
        String portValue = getPort().trim();
        String bindAddress = getBindAddress().trim();
        String bindPortValue = getBindPort().trim();

        if (hasSharedSocket() && host.isEmpty() && portValue.isEmpty()) {
            UDPSocketManager.SocketHandle existing = UDPSocketManager.get(getSocketKey());
            if (existing == null) {
                throw new IllegalStateException("No open UDP socket found for Socket ID '" + getSocketID()
                        + "'; hostname/IP and destination port are required to create it");
            }
            if (!bindAddress.isEmpty() || !bindPortValue.isEmpty()) {
                throw new IllegalArgumentException(
                        "UDP local bind settings cannot be changed when reusing an existing socket without an endpoint");
            }
            return existing;
        }

        if (host.isEmpty()) {
            throw new IllegalArgumentException("UDP hostname/IP is empty");
        }
        int port = parsePort(portValue, "UDP destination port", false);
        int bindPort = parsePort(bindPortValue, "UDP local bind port", true);
        String configuration = host + ':' + port + '|' + bindAddress + ':' + bindPort;

        if (hasSharedSocket()) {
            return UDPSocketManager.getOrCreate(getSocketKey(), configuration,
                    () -> createSocket(host, port, bindAddress, bindPort));
        }
        if (ownedHandle == null || ownedHandle.socket().isClosed() || !ownedHandle.matches(configuration)) {
            closeOwnedSocket();
            ownedHandle = new UDPSocketManager.SocketHandle(
                    createSocket(host, port, bindAddress, bindPort), configuration);
        }
        return ownedHandle;
    }

    private static DatagramSocket createSocket(String host, int port, String bindAddress, int bindPort)
            throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        try {
            InetSocketAddress localAddress = bindAddress.isEmpty()
                    ? new InetSocketAddress(bindPort)
                    : new InetSocketAddress(InetAddress.getByName(bindAddress), bindPort);
            socket.bind(localAddress);
            socket.connect(InetAddress.getByName(host), port);
            return socket;
        } catch (Exception ex) {
            socket.close();
            throw ex;
        }
    }

    private static int parsePort(String value, String label, boolean allowEmpty) {
        if (value == null || value.isBlank()) {
            if (allowEmpty) {
                return 0;
            }
            throw new IllegalArgumentException(label + " is empty");
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port < (allowEmpty ? 0 : 1) || port > 65_535) {
                throw new IllegalArgumentException(label + " must be between "
                        + (allowEmpty ? "0" : "1") + " and 65535");
            }
            return port;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " is not a number: " + value, ex);
        }
    }

    private void validateRequestSize(DatagramSocket socket, int requestSize) throws UDPDatagramTooLargeException {
        int maximumPayloadSize = maximumPayloadSize(socket);
        if (requestSize > maximumPayloadSize) {
            throw new UDPDatagramTooLargeException("UDP request is " + requestSize
                    + " bytes, exceeding the maximum payload of " + maximumPayloadSize + " bytes");
        }

        int defaultWarningSize = socket.getInetAddress() instanceof Inet4Address ? 1_472 : 1_452;
        int warningSize = getUdpProperty(FRAGMENTATION_WARNING_SIZE_PROPERTY, defaultWarningSize);
        if (warningSize > 0 && requestSize > warningSize && !fragmentationWarningLogged) {
            log.warn("UDP request payload is {} bytes and may be fragmented along the network path; "
                    + "consider application-level chunking when the target protocol supports it", requestSize);
            fragmentationWarningLogged = true;
        }
    }

    private void handleDisconnect(SampleResult result, PortUnreachableException ex) {
        boolean allowed = isAllowUnexpectedDisconnect();
        result.setSuccessful(allowed);
        result.setResponseCode(allowed ? "200" : "500");
        result.setResponseMessage("UDP destination is unreachable");
        result.setResponseData("DISCONNECTED", null); // $NON-NLS-1$
        closeActiveHandle();
        if (!allowed) {
            log.warn("UDP destination is unreachable", ex);
        }
    }

    private static void fail(SampleResult result, String code, String message, Exception ex) {
        result.setSuccessful(false);
        result.setResponseCode(code);
        result.setResponseMessage(message == null ? ex.toString() : message);
        result.setResponseData(ex.toString(), null);
    }

    private void closeActiveHandle() {
        UDPSocketManager.SocketHandle handle = activeHandle;
        if (handle == null) {
            return;
        }
        if (hasSharedSocket()) {
            UDPSocketManager.SocketKey socketKey = activeSocketKey;
            if (socketKey != null) {
                UDPSocketManager.remove(socketKey, handle);
            } else {
                handle.socket().close();
            }
        } else {
            handle.socket().close();
            if (ownedHandle == handle) {
                ownedHandle = null;
            }
        }
    }

    @Override
    void closeOwnedSocket() {
        if (ownedHandle != null) {
            ownedHandle.socket().close();
            ownedHandle = null;
        }
    }

    @Override
    public boolean interrupt() {
        UDPSocketManager.SocketHandle handle = activeHandle;
        if (handle == null) {
            return false;
        }
        closeActiveHandle();
        return true;
    }
}
