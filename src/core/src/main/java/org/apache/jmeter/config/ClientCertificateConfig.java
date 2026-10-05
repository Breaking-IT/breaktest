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

import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.util.JsseSSLManager;
import org.apache.jmeter.util.SSLManager;

/** Scoped client identity. Values are evaluated on the virtual user's thread. */
public class ClientCertificateConfig extends ConfigTestElement implements TestStateListener {
    private static final long serialVersionUID = 1L;
    public static final String MODE = "ClientCertificate.mode";
    public static final String STORE = "ClientCertificate.store";
    public static final String TYPE = "ClientCertificate.type";
    public static final String PASSWORD = "ClientCertificate.password";
    public static final String ALIAS = "ClientCertificate.alias";
    public static final String INHERIT = "inherit";
    public static final String CERTIFICATE = "certificate";
    public static final String NONE = "none";

    public String getMode() {
        return getPropertyAsString(MODE, INHERIT);
    }

    public boolean isInherit() {
        return INHERIT.equals(getMode());
    }

    @Override
    public void testStarted() {
        ((JsseSSLManager) SSLManager.getInstance()).clearScopedKeyStores();
    }

    @Override
    public void testStarted(String host) {
        testStarted();
    }

    @Override
    public void testEnded() {
        ((JsseSSLManager) SSLManager.getInstance()).clearScopedKeyStores();
    }

    @Override
    public void testEnded(String host) {
        testEnded();
    }
}
