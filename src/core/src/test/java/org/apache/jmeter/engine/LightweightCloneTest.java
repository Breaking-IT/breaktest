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

package org.apache.jmeter.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.FunctionProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

/**
 * Tests for per-property sharing in lightweight clones created by {@link TreeCloner}.
 */
public class LightweightCloneTest extends JMeterTestCase {

    private static ConfigTestElement newElement() {
        ConfigTestElement element = new ConfigTestElement();
        element.setName("element");
        element.setProperty(new StringProperty("static", "staticValue"));
        element.setProperty(new StringProperty("otherStatic", "otherValue"));
        return element;
    }

    private static ConfigTestElement cloneViaTreeCloner(ConfigTestElement element) {
        ListedHashTree tree = new ListedHashTree();
        tree.add(element);
        TreeCloner cloner = new TreeCloner();
        tree.traverse(cloner);
        return (ConfigTestElement) cloner.getClonedTree().getArray()[0];
    }

    @Test
    public void staticPropertiesAreSharedByReference() {
        ConfigTestElement element = newElement();
        ConfigTestElement clone = cloneViaTreeCloner(element);

        assertNotSame(element, clone);
        assertTrue(clone.isPropertiesShared(), "clone should share properties");
        assertSame(element.getProperty("static"), clone.getProperty("static"));
        assertSame(element.getProperty("otherStatic"), clone.getProperty("otherStatic"));
    }

    @Test
    public void elementWithVariablePropertyIsStillLightweightCloned() {
        ConfigTestElement element = newElement();
        element.setProperty(new StringProperty("path", "/api/${userId}"));
        ConfigTestElement clone = cloneViaTreeCloner(element);

        assertTrue(clone.isPropertiesShared(),
                "a variable property must not disable lightweight cloning of the element");
        // Static properties are shared by reference
        assertSame(element.getProperty("static"), clone.getProperty("static"));
        // The variable property needs per-thread state, so it is cloned
        assertNotSame(element.getProperty("path"), clone.getProperty("path"));
        assertEquals("/api/${userId}", clone.getPropertyAsString("path"));
    }

    @Test
    public void functionPropertyIsClonedPerClone() throws Exception {
        ConfigTestElement element = newElement();
        CompoundVariable function = new CompoundVariable();
        function.setParameters("${userId}");
        element.setProperty(new FunctionProperty("path", function));
        ConfigTestElement clone = cloneViaTreeCloner(element);

        assertTrue(clone.isPropertiesShared());
        assertNotSame(element.getProperty("path"), clone.getProperty("path"),
                "FunctionProperty keeps per-thread state and must be cloned");
        // The parsed function itself is immutable during the run and stays shared
        assertSame(element.getProperty("path").getObjectValue(), clone.getProperty("path").getObjectValue());
    }

    @Test
    public void nestedTestElementsAreLightweightClonedRecursively() {
        ConfigTestElement element = newElement();
        Arguments arguments = new Arguments();
        arguments.addArgument("user", "fred");
        element.setProperty(new TestElementProperty("args", arguments));
        ConfigTestElement clone = cloneViaTreeCloner(element);

        Arguments clonedArguments = (Arguments) clone.getProperty("args").getObjectValue();
        assertNotSame(arguments, clonedArguments, "nested element instance must be per-thread");
        Argument originalArg = (Argument) ((CollectionProperty) arguments.getProperty(Arguments.ARGUMENTS))
                .get(0).getObjectValue();
        Argument clonedArg = (Argument) ((CollectionProperty) clonedArguments.getProperty(Arguments.ARGUMENTS))
                .get(0).getObjectValue();
        assertNotSame(originalArg, clonedArg, "elements nested in collections must be per-thread instances");
        assertSame(originalArg.getProperty(Argument.VALUE), clonedArg.getProperty(Argument.VALUE),
                "leaf properties of nested elements are shared");

        // Runtime mutation of a nested element stays thread-local (copy-on-write)
        clone.setRunningVersion(true);
        clonedArg.setValue("changed");
        assertEquals("changed", clonedArg.getValue());
        assertEquals("fred", originalArg.getValue(), "source element must not see the clone's modification");
    }

    @Test
    public void runtimeAdditionsToNestedCollectionsStayThreadLocal() {
        ConfigTestElement element = newElement();
        Arguments arguments = new Arguments();
        arguments.addArgument("user", "fred");
        element.setProperty(new TestElementProperty("args", arguments));
        ConfigTestElement clone = cloneViaTreeCloner(element);
        clone.setRunningVersion(true);

        Arguments clonedArguments = (Arguments) clone.getProperty("args").getObjectValue();
        clonedArguments.addArgument("session", "abc");

        assertEquals(2, clonedArguments.getArgumentCount());
        assertEquals(1, arguments.getArgumentCount(),
                "in-place addition on the clone must not leak into the source element");
    }

    @Test
    public void setPropertyDuringRunTriggersCopyOnWrite() {
        ConfigTestElement element = newElement();
        ConfigTestElement clone = cloneViaTreeCloner(element);
        clone.setRunningVersion(true);

        clone.setProperty("static", "changed");

        assertFalse(clone.isPropertiesShared(), "write should trigger copy-on-write");
        assertEquals("changed", clone.getPropertyAsString("static"));
        assertEquals("staticValue", element.getPropertyAsString("static"),
                "source element must not see the clone's modification");
        assertNotSame(element.getProperty("otherStatic"), clone.getProperty("otherStatic"),
                "after copy-on-write the clone owns all its properties");
    }

    @Test
    public void removingAbsentPropertyKeepsSharing() {
        ConfigTestElement element = newElement();
        ConfigTestElement clone = cloneViaTreeCloner(element);
        clone.setRunningVersion(true);

        clone.removeProperty("doesNotExist");

        assertTrue(clone.isPropertiesShared(),
                "removing an absent property is a no-op and must not break sharing");
        assertSame(element.getProperty("static"), clone.getProperty("static"));
    }

    @Test
    public void removingExistingPropertyTriggersCopyOnWrite() {
        ConfigTestElement element = newElement();
        ConfigTestElement clone = cloneViaTreeCloner(element);
        clone.setRunningVersion(true);

        clone.removeProperty("static");

        assertFalse(clone.isPropertiesShared());
        assertNull(clone.getPropertyOrNull("static"));
        assertEquals("staticValue", element.getPropertyAsString("static"),
                "source element must keep the removed property");
    }
}
