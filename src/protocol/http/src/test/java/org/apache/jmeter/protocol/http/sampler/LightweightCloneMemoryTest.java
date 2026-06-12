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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.TreeCloner;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

/**
 * Measures the retained heap of per-thread test tree clones - the memory that
 * stays referenced for the lifetime of each virtual user - comparing lightweight
 * (shared property map) clones against full deep clones.
 */
public class LightweightCloneMemoryTest {

    private static final int SAMPLERS = 50;
    private static final int THREADS = 100;

    private static ListedHashTree buildTree() {
        ListedHashTree tree = new ListedHashTree();
        LoopController loop = new LoopController();
        loop.setLoops(10);
        HashTree loopSubtree = tree.add(loop);

        HeaderManager headerManager = new HeaderManager();
        headerManager.add(new Header("Accept", "application/json"));
        headerManager.add(new Header("Authorization", "Bearer ${token}"));
        loopSubtree.add(headerManager);

        CookieManager cookieManager = new CookieManager();
        loopSubtree.add(cookieManager);

        for (int i = 0; i < SAMPLERS; i++) {
            HTTPSamplerProxy sampler = new HTTPSamplerProxy();
            sampler.setName("Request " + i);
            sampler.setDomain("example.com");
            sampler.setPort(443);
            sampler.setProtocol("https");
            sampler.setMethod("GET");
            sampler.setPath("/api/v1/items/${itemId}/details");
            sampler.setFollowRedirects(true);
            sampler.setUseKeepAlive(true);
            for (int j = 0; j < 5; j++) {
                sampler.addArgument("param" + j, "value" + j);
            }
            loopSubtree.add(sampler);
        }
        return tree;
    }

    private static ListedHashTree cloneTreeLightweight(ListedHashTree tree) {
        TreeCloner cloner = new TreeCloner(true);
        tree.traverse(cloner);
        return cloner.getClonedTree();
    }

    private static ListedHashTree cloneTreeDeep(ListedHashTree tree) {
        // Mirrors TreeCloner's work (clone + tree assembly), but always deep clones
        ListedHashTree newTree = new ListedHashTree();
        for (Object root : tree.getArray()) {
            HashTree subTree = newTree.add(((TestElement) root).clone());
            for (Object child : tree.getTree(root).getArray()) {
                subTree.add(((TestElement) child).clone());
            }
        }
        return newTree;
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        long previous = Long.MAX_VALUE;
        // Iterate GC until the reading stabilizes
        for (int i = 0; i < 10; i++) {
            System.gc();
            Thread.sleep(50);
            long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            if (previous - used < 16 * 1024) {
                return used;
            }
            previous = used;
        }
        return previous;
    }

    private static long retainedBytesPerClone(ListedHashTree tree, boolean lightweight) throws InterruptedException {
        List<ListedHashTree> hold = new ArrayList<>(THREADS);
        long before = usedHeapAfterGc();
        for (int i = 0; i < THREADS; i++) {
            hold.add(lightweight ? cloneTreeLightweight(tree) : cloneTreeDeep(tree));
        }
        long after = usedHeapAfterGc();
        long perClone = (after - before) / THREADS;
        if (hold.size() != THREADS) { // keep `hold` reachable until after measurement
            throw new IllegalStateException();
        }
        return perClone;
    }

    @Test
    public void lightweightCloneRetainsFarLessThanDeepClone() throws InterruptedException {
        ListedHashTree tree = buildTree();

        // Warm up both paths (class loading, source snapshot creation)
        cloneTreeLightweight(tree);
        cloneTreeDeep(tree);

        long lightweightBytes = retainedBytesPerClone(tree, true);
        long deepBytes = retainedBytesPerClone(tree, false);

        System.out.printf("Retained heap per thread tree (%d HTTP samplers with variables, %d threads):%n",
                SAMPLERS, THREADS);
        System.out.printf("  deep clone:        %,d bytes%n", deepBytes);
        System.out.printf("  lightweight clone: %,d bytes (%.0f%% of deep)%n",
                lightweightBytes, 100.0 * lightweightBytes / deepBytes);

        assertTrue(lightweightBytes < deepBytes * 0.7,
                () -> String.format("expected lightweight clone (%,d bytes retained) to stay well below "
                        + "deep clone (%,d bytes retained)", lightweightBytes, deepBytes));
    }
}
