/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.jmeter.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Utility class to define the BreakTest version string.
 * Note: @...@-like macroses in this file should be replaced by a Gradle task.
 * The version itself comes from {@code version.properties}, which the build generates next to this class.
 */
public final class JMeterVersion {
    private JMeterVersion() {
    }

    static String getVERSION() {
        return VersionHolder.VERSION;
    }

    private static final class VersionHolder {
        static final String VERSION = loadVersion();

        private static String loadVersion() {
            try (InputStream in = JMeterVersion.class.getResourceAsStream("version.properties")) {
                if (in == null) {
                    throw new IllegalStateException("version.properties is missing next to " + JMeterVersion.class);
                }
                Properties properties = new Properties();
                properties.load(in);
                return properties.getProperty("version");
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read version.properties", e);
            }
        }
    }

    public static String getCopyRight() {
        return "Copyright (c) 2024-@YEAR@ Breaking IT and BreakTest contributors."
                + " Portions Copyright (c) 1998-2024 The Apache Software Foundation";
    }
}
