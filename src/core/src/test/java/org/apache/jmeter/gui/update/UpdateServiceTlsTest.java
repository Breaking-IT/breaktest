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

package org.apache.jmeter.gui.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/** The global JDK property must be set at test JVM startup, before HttpClient initialization. */
@EnabledIfSystemProperty(named = "jdk.internal.httpclient.disableHostnameVerification", matches = "true")
class UpdateServiceTlsTest {
    @Test
    void updaterRejectsWrongHostnameEvenWhenGlobalVerificationIsDisabled(@TempDir Path directory)
            throws Exception {
        Path store = directory.resolve("server.p12");
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "password", "-keypass", "password",
                "-dname", "CN=wrong.example", "-ext", "SAN=dns:wrong.example", "-validity", "2")
                .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, keytool.waitFor(), output);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, "password".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "password".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keys);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        SSLContext previous = SSLContext.getDefault();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            // Trust the certificate so hostname verification is the only failing check.
            SSLContext.setDefault(context);
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://localhost:" + server.getAddress().getPort() + "/"))
                    .timeout(Duration.ofSeconds(5)).build();
            try (HttpClient permissive = HttpClient.newHttpClient();
                    HttpClient updater = UpdateService.createHttpClient()) {
                assertEquals(200, permissive.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
                SSLHandshakeException failure = assertThrows(SSLHandshakeException.class,
                        () -> updater.send(request, HttpResponse.BodyHandlers.discarding()));
                assertTrue(failure.getMessage().contains("localhost"), failure.getMessage());
            }
        } finally {
            SSLContext.setDefault(previous);
            server.stop(0);
        }
    }
}
