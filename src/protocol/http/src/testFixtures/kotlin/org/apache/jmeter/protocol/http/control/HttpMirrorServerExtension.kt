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

package org.apache.jmeter.protocol.http.control

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.concurrent.TimeUnit

/**
 * JUnit 5 extension to start and stop [HttpMirrorServer] for testing.
 * Typical usage:
 *
 * ```java
 * @RegisterExtension
 * HttpMirrorServerExtension httpMirrorServer = new HttpMirrorServerExtension(8181);
 * ```
 *
 * See https://junit.org/junit5/docs/current/user-guide/#extensions-registration-programmatic
 */
public class HttpMirrorServerExtension(
    val serverPort: Int
) : BeforeAllCallback, AfterAllCallback {
    private val NAMESPACE =
        ExtensionContext.Namespace.create(HttpMirrorServerExtension::class.java)

    /**
     * Utility method to handle starting the [HttpMirrorServer] for testing.
     *
     * @param port
     * port on which the mirror should be started
     * @return newly created http mirror server
     * @throws Exception
     * if something fails
     */
    @Throws(InterruptedException::class)
    fun startHttpMirror(): HttpMirrorServer {
        val server = HttpMirrorServer(serverPort)
        server.start()
        if (!server.awaitStartup(10, TimeUnit.SECONDS)) {
            server.stopServer()
            server.interrupt()
            server.join(5000)
            throw IllegalStateException("Could not start mirror server on port: $serverPort", server.exception)
        }
        return server
    }

    private fun getStore(context: ExtensionContext): ExtensionContext.Store {
        return context.getStore(NAMESPACE)
    }

    private fun getServer(context: ExtensionContext): HttpMirrorServer? {
        return getStore(context).get("server", HttpMirrorServer::class.java)
    }

    override fun beforeAll(context: ExtensionContext) {
        val server = startHttpMirror()
        getStore(context).put("server", server)
    }

    override fun afterAll(context: ExtensionContext) {
        getServer(context)?.let {
            it.stopServer()
            it.interrupt()
            it.join(5000)
            check(!it.isAlive) { "Mirror server did not stop on port: $serverPort" }
        }
    }
}
