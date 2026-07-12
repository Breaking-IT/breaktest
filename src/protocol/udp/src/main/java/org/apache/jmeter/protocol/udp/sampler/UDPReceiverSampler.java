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

import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;

@TestElementMetadata(labelResource = "udp_receiver_title")
public class UDPReceiverSampler extends AbstractUDPSampler {

    public static final String FAIL_ON_TIMEOUT = "fail_on_timeout"; // $NON-NLS-1$

    private transient volatile UDPSocketManager.SocketHandle activeHandle;
    private transient volatile UDPSocketManager.SocketKey activeSocketKey;

    public boolean isFailOnTimeout() {
        return getPropertyAsBoolean(FAIL_ON_TIMEOUT);
    }

    public void setFailOnTimeout(boolean failOnTimeout) {
        setProperty(FAIL_ON_TIMEOUT, failOnTimeout);
    }

    @Override
    public SampleResult sample(Entry entry) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(getName());
        result.setDataType(SampleResult.TEXT);
        result.setContentType("application/octet-stream"); // $NON-NLS-1$
        result.sampleStart();

        try {
            if (getSocketID().isBlank()) {
                throw new IllegalArgumentException("UDP Receiver requires a Socket ID");
            }
            UDPSocketManager.SocketKey socketKey = getSocketKey();
            UDPSocketManager.SocketHandle handle = UDPSocketManager.get(socketKey);
            if (handle == null) {
                throw new IllegalStateException("No open UDP socket found for Socket ID '" + getSocketID() + '\'');
            }
            activeSocketKey = socketKey;
            activeHandle = handle;
            ReentrantLock lock = handle.lock();
            lock.lockInterruptibly();
            try {
                byte[] response = receive(handle.socket(), result);
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
            boolean fail = isFailOnTimeout();
            result.setSuccessful(!fail);
            result.setResponseCode(fail ? "408" : "204");
            result.setResponseMessage("No UDP data received within the timeout");
            result.setResponseData(new byte[0]);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(result, "500", "UDP receive interrupted", ex);
        } catch (Exception ex) {
            fail(result, "500", ex.getMessage(), ex);
        } finally {
            activeHandle = null;
            activeSocketKey = null;
            if (result.getEndTime() == 0) {
                result.sampleEnd();
            }
        }
        return result;
    }

    private byte[] receive(DatagramSocket socket, SampleResult result) throws Exception {
        socket.setSoTimeout(getTimeoutAsInt());
        result.connectEnd();
        return receiveDatagram(socket, result);
    }

    private static void fail(SampleResult result, String code, String message, Exception ex) {
        result.setSuccessful(false);
        result.setResponseCode(code);
        result.setResponseMessage(message == null ? ex.toString() : message);
        result.setResponseData(ex.toString(), null);
    }

    @Override
    void closeOwnedSocket() {
        // Receiver samplers borrow a shared socket owned by a UDP Request.
    }

    @Override
    public boolean interrupt() {
        UDPSocketManager.SocketHandle handle = activeHandle;
        if (handle == null) {
            return false;
        }
        UDPSocketManager.SocketKey socketKey = activeSocketKey;
        if (socketKey != null) {
            UDPSocketManager.remove(socketKey, handle);
        } else {
            handle.socket().close();
        }
        return true;
    }
}
