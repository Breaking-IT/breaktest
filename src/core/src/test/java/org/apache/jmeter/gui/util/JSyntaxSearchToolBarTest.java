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

package org.apache.jmeter.gui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JSyntaxSearchToolBarTest {

    @Test
    void createsLineAndCharacterHighlightsForDiff() {
        JSyntaxSearchToolBar.DiffView diff = JSyntaxSearchToolBar.createDiffView(
                "GET https://example.invalid/a HTTP/1.1\nAccept: application/json\n", // $NON-NLS-1$
                "GET https://example.invalid/b HTTP/1.1\nAccept: application/json\nX-Test: replayed\n"); // $NON-NLS-1$

        assertTrue(diff.text().contains("- GET https://example.invalid/a HTTP/1.1")); // $NON-NLS-1$
        assertTrue(diff.text().contains("+ GET https://example.invalid/b HTTP/1.1")); // $NON-NLS-1$
        assertTrue(diff.text().contains("  Accept: application/json")); // $NON-NLS-1$
        assertTrue(diff.text().contains("+ X-Test: replayed")); // $NON-NLS-1$
        assertEquals(3, diff.lineHighlights().size());
        assertEquals(4, diff.lineHighlights().get(0).line());
        assertTrue(diff.textHighlights().stream()
                .anyMatch(highlight -> diff.text().substring(highlight.start(), highlight.end()).equals("a"))); // $NON-NLS-1$
        assertTrue(diff.textHighlights().stream()
                .anyMatch(highlight -> diff.text().substring(highlight.start(), highlight.end()).equals("b"))); // $NON-NLS-1$
    }
}
