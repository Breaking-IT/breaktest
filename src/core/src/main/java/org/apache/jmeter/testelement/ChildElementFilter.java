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

/**
 * Implemented by test elements that veto certain element types as direct tree children.
 * Checked by the GUI when pasting, drag-and-dropping or merging nodes, in addition to the
 * regular category rules (for example an HTTP sampler rejects a child Header Manager now
 * that its headers are edited on the sampler itself).
 */
public interface ChildElementFilter {

    /**
     * @param child candidate child element
     * @return whether the given element may become a direct child of this one
     */
    boolean acceptsChildElement(TestElement child);
}
