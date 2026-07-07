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

package org.apache.jmeter.save;

import org.apache.jorphan.collections.HashTree;

/**
 * Hook applied to every test plan tree right after it is deserialized from JMX,
 * before it reaches the GUI tree model or the engine. Implementations are discovered
 * through {@link java.util.ServiceLoader} and may migrate legacy tree shapes in place
 * (for example folding a config element that used to live as a sampler child into a
 * native sampler property).
 *
 * Implementations must be stateless and are applied in class-name order.
 */
public interface LoadedTreePostProcessor {

    /**
     * Transform the freshly loaded tree in place.
     *
     * @param loadedTree the deserialized test plan tree, never {@code null}
     */
    void process(HashTree loadedTree);
}
