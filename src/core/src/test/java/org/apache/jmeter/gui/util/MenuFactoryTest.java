/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
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
 */

package org.apache.jmeter.gui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class MenuFactoryTest {

    @Test
    void configElementsUseTheSameSpecificIconsAsTreeNodes() {
        assertEquals(MenuFactory.ModernMenuIcon.Kind.CSV_DATA_SET,
                iconKind("org.apache.jmeter.testbeans.gui.TestBeanGUI CSV Data Set Config"));
        assertEquals(MenuFactory.ModernMenuIcon.Kind.USER_DEFINED_VARIABLES,
                iconKind("org.apache.jmeter.config.gui.ArgumentsPanel User Defined Variables"));
        assertEquals(MenuFactory.ModernMenuIcon.Kind.HTTP_HEADERS,
                iconKind("org.apache.jmeter.protocol.http.gui.HeaderPanel HTTP Header Manager"));
    }

    @Test
    void controllerAddMenuKeepsOriginalCategoryOrder() {
        assertEquals(List.of(
                MenuFactory.SAMPLERS,
                MenuFactory.CONTROLLERS,
                MenuFactory.ASSERTIONS,
                MenuFactory.TIMERS,
                MenuFactory.PRE_PROCESSORS,
                MenuFactory.POST_PROCESSORS,
                MenuFactory.CONFIG_ELEMENTS,
                MenuFactory.LISTENERS), MenuFactory.AddMenuOrder.CONTROLLER);
    }

    @Test
    void samplerAddMenuKeepsPreAndPostProcessorsTogether() {
        assertEquals(List.of(
                MenuFactory.ASSERTIONS,
                MenuFactory.TIMERS,
                MenuFactory.PRE_PROCESSORS,
                MenuFactory.POST_PROCESSORS,
                MenuFactory.CONFIG_ELEMENTS,
                MenuFactory.LISTENERS), MenuFactory.AddMenuOrder.DEFAULT);
    }

    private static MenuFactory.ModernMenuIcon.Kind iconKind(String descriptor) {
        return MenuFactory.ModernMenuIcon.kindFor(MenuFactory.CONFIG_ELEMENTS, descriptor);
    }

}
