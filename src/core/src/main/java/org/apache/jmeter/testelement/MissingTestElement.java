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

package org.apache.jmeter.testelement;

import org.apache.jmeter.gui.MissingTestElementGui;

/**
 * Placeholder for a JMX element whose plugin classes are unavailable.
 */
public class MissingTestElement extends AbstractTestElement {
    private static final long serialVersionUID = 241L;

    public static final String MISSING_TEST_CLASS = "MissingTestElement.testClass"; // $NON-NLS-1$
    public static final String MISSING_GUI_CLASS = "MissingTestElement.guiClass"; // $NON-NLS-1$
    public static final String MISSING_ELEMENT = "MissingTestElement.element"; // $NON-NLS-1$
    public static final String MISSING_REASON = "MissingTestElement.reason"; // $NON-NLS-1$

    public void configureMissingElement(String elementName, String testClassName, String guiClassName, Throwable cause) {
        setProperty(MISSING_ELEMENT, elementName == null ? "" : elementName); // $NON-NLS-1$
        setProperty(MISSING_TEST_CLASS, testClassName == null ? "" : testClassName); // $NON-NLS-1$
        setProperty(MISSING_GUI_CLASS, guiClassName == null ? "" : guiClassName); // $NON-NLS-1$
        setProperty(MISSING_REASON, cause == null ? "" : cause.toString()); // $NON-NLS-1$
        setProperty(TEST_CLASS, MissingTestElement.class.getName());
        setProperty(GUI_CLASS, MissingTestElementGui.class.getName());
        setEnabled(false);
    }

    public String getMissingTestClass() {
        return getPropertyAsString(MISSING_TEST_CLASS);
    }

    public String getMissingGuiClass() {
        return getPropertyAsString(MISSING_GUI_CLASS);
    }
}
