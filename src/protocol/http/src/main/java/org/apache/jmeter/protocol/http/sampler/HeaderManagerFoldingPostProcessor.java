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

package org.apache.jmeter.protocol.http.sampler;

import java.util.ArrayList;

import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.save.LoadedTreePostProcessor;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;

/**
 * Migrates legacy JMX shapes where an HTTP Header Manager was a direct child of an
 * HTTP sampler: the child's headers become native sampler headers
 * ({@code HTTPSampler.headers}) and the child node is dropped from the tree.
 * Header Managers at higher scopes (Test Plan, Thread Group, controllers) are untouched
 * and keep applying through the regular config merge.
 *
 * When several child managers are present, the first one in tree order wins on
 * case-insensitive name conflicts — the same precedence the innermost-first runtime
 * merge used to give them. Disabled child managers are left in place, since folding
 * them would activate their headers.
 */
@AutoService(LoadedTreePostProcessor.class)
public class HeaderManagerFoldingPostProcessor implements LoadedTreePostProcessor {

    private static final Logger log = LoggerFactory.getLogger(HeaderManagerFoldingPostProcessor.class);

    @Override
    public void process(HashTree loadedTree) {
        fold(loadedTree);
    }

    private static void fold(HashTree tree) {
        for (Object key : new ArrayList<>(tree.list())) {
            HashTree subtree = tree.getTree(key);
            if (key instanceof HTTPSamplerBase sampler) {
                foldChildManagers(sampler, subtree);
                foldLegacyProperty(sampler);
            }
            if (subtree != null) {
                fold(subtree);
            }
        }
    }

    private static void foldChildManagers(HTTPSamplerBase sampler, HashTree subtree) {
        if (subtree == null) {
            return;
        }
        for (Object child : new ArrayList<>(subtree.list())) {
            if (child instanceof HeaderManager manager && manager.isEnabled()) {
                log.info("Folding Header Manager '{}' into HTTP sampler '{}'", manager.getName(), sampler.getName());
                sampler.addNativeHeadersIfAbsent(manager);
                subtree.remove(child);
            }
        }
    }

    /**
     * Old recordings occasionally persisted the runtime {@code HTTPSampler.header_manager}
     * property inside the sampler element; fold it the same way.
     */
    private static void foldLegacyProperty(HTTPSamplerBase sampler) {
        HeaderManager manager = sampler.getHeaderManager();
        if (manager != null) {
            sampler.addNativeHeadersIfAbsent(manager);
            sampler.removeProperty(HTTPSamplerBase.HEADER_MANAGER);
        }
    }
}
