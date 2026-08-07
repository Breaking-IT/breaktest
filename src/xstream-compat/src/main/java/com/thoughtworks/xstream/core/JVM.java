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

/*
 * This compatibility implementation is based on the public API of XStream's
 * com.thoughtworks.xstream.core.JVM class, distributed under the BSD license.
 * Copyright (c) 2006-2019, XStream Committers.
 * See src/licenses/licenses/xstream/LICENSE for the full license text.
 */

package com.thoughtworks.xstream.core;

import com.thoughtworks.xstream.converters.reflection.FieldDictionary;
import com.thoughtworks.xstream.converters.reflection.PureJavaReflectionProvider;
import com.thoughtworks.xstream.converters.reflection.ReflectionProvider;
import com.thoughtworks.xstream.core.util.Base64JavaUtilCodec;

/**
 * Java 21+ compatibility implementation of XStream's runtime helper.
 *
 * <p>XStream 1.4.21 probes its Unsafe reflection provider while this class is initialized, even
 * when the application explicitly selects {@link PureJavaReflectionProvider}. Recent JDKs warn
 * about that probe and will eventually reject it. BreakTest only supports Java 21 and newer, so
 * the legacy runtime detection can be replaced with standard Java APIs and the pure-Java provider.
 *
 * <p>This class intentionally has the same binary API as XStream 1.4.21's implementation and is
 * loaded before the dependency class from {@code xstream.jar}. Remove it when XStream provides an
 * equivalent upstream fix.
 */
@SuppressWarnings({"deprecation", "rawtypes"})
public class JVM implements Caching {

    private static final int JAVA_VERSION = Runtime.version().feature();
    private static final String VM_VENDOR = System.getProperty("java.vm.vendor", "");
    private static final StringCodec BASE_64_CODEC = new Base64JavaUtilCodec();

    private ReflectionProvider reflectionProvider;

    /** @deprecated XStream exposes this constructor for legacy callers. */
    @Deprecated
    public JVM() {
    }

    /** @deprecated Java 1.4 or newer is always available. */
    @Deprecated
    public static boolean is14() {
        return isVersion(4);
    }

    /** @deprecated Java 1.5 or newer is always available. */
    @Deprecated
    public static boolean is15() {
        return isVersion(5);
    }

    /** @deprecated Java 1.6 or newer is always available. */
    @Deprecated
    public static boolean is16() {
        return isVersion(6);
    }

    /** @deprecated Java 1.7 or newer is always available. */
    @Deprecated
    public static boolean is17() {
        return isVersion(7);
    }

    /** @deprecated Java 1.8 or newer is always available. */
    @Deprecated
    public static boolean is18() {
        return isVersion(8);
    }

    /** @deprecated Java 9 or newer is always available. */
    @Deprecated
    public static boolean is19() {
        return isVersion(9);
    }

    /** @deprecated Java 9 or newer is always available. */
    @Deprecated
    public static boolean is9() {
        return isVersion(9);
    }

    public static boolean isVersion(int version) {
        if (version < 1) {
            throw new IllegalArgumentException("Java version range starts with at least 1.");
        }
        return JAVA_VERSION >= version;
    }

    public static Class loadClassForName(String name) {
        return loadClassForName(name, true);
    }

    /** @deprecated Use {@link #loadClassForName(String)}. */
    @Deprecated
    public Class loadClass(String name) {
        return loadClassForName(name);
    }

    public static Class loadClassForName(String name, boolean initialize) {
        try {
            return Class.forName(name, initialize, JVM.class.getClassLoader());
        } catch (LinkageError | ClassNotFoundException e) {
            return null;
        }
    }

    /** @deprecated Use {@link #loadClassForName(String, boolean)}. */
    @Deprecated
    public Class loadClass(String name, boolean initialize) {
        return loadClassForName(name, initialize);
    }

    public static ReflectionProvider newReflectionProvider() {
        return new PureJavaReflectionProvider();
    }

    public static ReflectionProvider newReflectionProvider(FieldDictionary dictionary) {
        return new PureJavaReflectionProvider(dictionary);
    }

    public static Class getStaxInputFactory() throws ClassNotFoundException {
        return Class.forName(VM_VENDOR.contains("IBM")
                ? "com.ibm.xml.xlxp.api.stax.XMLInputFactoryImpl"
                : "com.sun.xml.internal.stream.XMLInputFactoryImpl");
    }

    public static Class getStaxOutputFactory() throws ClassNotFoundException {
        return Class.forName(VM_VENDOR.contains("IBM")
                ? "com.ibm.xml.xlxp.api.stax.XMLOutputFactoryImpl"
                : "com.sun.xml.internal.stream.XMLOutputFactoryImpl");
    }

    /** @deprecated XStream retains this method for binary compatibility. */
    @Deprecated
    public static StringCodec getBase64Codec() {
        return BASE_64_CODEC;
    }

    /** @deprecated Use {@link #newReflectionProvider()}. */
    @Deprecated
    public synchronized ReflectionProvider bestReflectionProvider() {
        if (reflectionProvider == null) {
            reflectionProvider = newReflectionProvider();
        }
        return reflectionProvider;
    }

    /** @deprecated Fields have their native declaration order on supported JDKs. */
    @Deprecated
    public static boolean reverseFieldDefinition() {
        return false;
    }

    public static boolean isAWTAvailable() {
        return loadClassForName("java.awt.Color", false) != null;
    }

    /** @deprecated Use {@link #isAWTAvailable()}. */
    @Deprecated
    public boolean supportsAWT() {
        return isAWTAvailable();
    }

    public static boolean isSwingAvailable() {
        return loadClassForName("javax.swing.LookAndFeel", false) != null;
    }

    /** @deprecated Use {@link #isSwingAvailable()}. */
    @Deprecated
    public boolean supportsSwing() {
        return isSwingAvailable();
    }

    public static boolean isSQLAvailable() {
        return loadClassForName("java.sql.Date", false) != null;
    }

    /** @deprecated Use {@link #isSQLAvailable()}. */
    @Deprecated
    public boolean supportsSQL() {
        return isSQLAvailable();
    }

    public static boolean hasOptimizedTreeSetAddAll() {
        return true;
    }

    public static boolean hasOptimizedTreeMapPutAll() {
        return true;
    }

    public static boolean canParseUTCDateFormat() {
        return true;
    }

    public static boolean canParseISO8601TimeZoneInDateFormat() {
        return true;
    }

    public static boolean canCreateDerivedObjectOutputStream() {
        return true;
    }

    @Override
    public synchronized void flushCache() {
        reflectionProvider = null;
    }

    public static void main(String[] args) {
        System.out.println("XStream JVM compatibility: Java " + JAVA_VERSION + ", pure Java reflection");
    }
}
