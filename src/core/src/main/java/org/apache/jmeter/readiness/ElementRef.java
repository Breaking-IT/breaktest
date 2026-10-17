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

package org.apache.jmeter.readiness;

import java.util.List;
import java.util.Objects;

/**
 * Identifies a test plan element in a readiness report without holding on to it.
 *
 * @param sourceId stable identity of the element for this GUI session (see
 *     {@link org.apache.jmeter.threads.ValidationCheckpoints#sourceIdOf(org.apache.jmeter.testelement.TestElement)}),
 *     never its name
 * @param name display name, for presentation only
 * @param type simple class name of the element, for presentation and to check tree paths
 * @param treePath child indexes from the tree root to the element when the report was built, used to navigate after
 *     the element itself was replaced (for instance by undo)
 */
public record ElementRef(String sourceId, String name, String type, List<Integer> treePath) {
    public ElementRef {
        Objects.requireNonNull(sourceId, "sourceId");
        name = name == null ? "" : name;
        type = type == null ? "" : type;
        treePath = treePath == null ? List.of() : List.copyOf(treePath);
    }

    @Override
    public String toString() {
        return name.isEmpty() ? type : name;
    }
}
