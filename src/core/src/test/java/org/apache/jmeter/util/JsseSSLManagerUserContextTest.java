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

package org.apache.jmeter.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsseSSLManagerUserContextTest {
    private static JMeterThread emptyUser() {
        LoopController loop = new LoopController();
        loop.setLoops(0);
        loop.setContinueForever(false);
        ListedHashTree tree = new ListedHashTree();
        tree.add(loop);
        ThreadGroup group = new ThreadGroup();
        JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier());
        user.setThreadGroup(group);
        user.setThreadName("tls-user");
        return user;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void iterationsRetainSessionsOnlyForTheSameUser(boolean sameUser) {
        JsseSSLManager manager = (JsseSSLManager) SSLManager.getInstance();
        var contexts = new ArrayList<SSLContext>();
        LoopController loop = new LoopController();
        loop.setLoops(2);
        loop.setContinueForever(false);
        ListedHashTree tree = new ListedHashTree();
        tree.add(loop).add(new AbstractSampler() {
            @Override
            public SampleResult sample(Entry entry) {
                try {
                    contexts.add(manager.getContext());
                } catch (GeneralSecurityException e) {
                    throw new IllegalStateException(e);
                }
                SampleResult result = new SampleResult();
                result.setSuccessful(true);
                return result;
            }
        });
        ThreadGroup group = new ThreadGroup();
        JMeterThread user = new JMeterThread(tree, group, new ListenerNotifier(), sameUser);
        user.setThreadGroup(group);
        user.setThreadName("tls-iterations");
        user.run();
        assertEquals(2, contexts.size());
        if (sameUser) {
            assertSame(contexts.get(0), contexts.get(1));
        } else {
            assertNotSame(contexts.get(0), contexts.get(1));
        }
    }

    @Test
    void concurrentWorkersShareContextButUsersAndResetsDoNot() throws Exception {
        JsseSSLManager manager = (JsseSSLManager) SSLManager.getInstance();
        JMeterThread first = emptyUser();
        JMeterThread second = emptyUser();
        var workers = Executors.newFixedThreadPool(12);
        try {
            CyclicBarrier start = new CyclicBarrier(12);
            var results = new ArrayList<Future<SSLContext>>();
            for (int i = 0; i < 12; i++) {
                JMeterThread owner = i < 6 ? first : second;
                results.add(workers.submit(() -> {
                    JMeterContextService.getContext().setThread(owner);
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        return manager.getContext();
                    } finally {
                        JMeterContextService.getContext().clear();
                    }
                }));
            }
            SSLContext firstContext = results.get(0).get(15, TimeUnit.SECONDS);
            SSLContext secondContext = results.get(6).get(15, TimeUnit.SECONDS);
            assertNotSame(firstContext, secondContext);
            assertNotSame(firstContext.getClientSessionContext(), secondContext.getClientSessionContext());
            for (int i = 0; i < results.size(); i++) {
                assertSame(i < 6 ? firstContext : secondContext, results.get(i).get(15, TimeUnit.SECONDS));
            }
            // Run the real thread lifecycle to exercise registered resource cleanup.
            first.run();
            JMeterContextService.getContext().setThread(first);
            SSLContext nextUser = manager.getContext();
            assertNotSame(firstContext, nextUser);
            manager.resetContext();
            assertNotSame(nextUser, manager.getContext());
            JMeterContextService.getContext().setThread(second);
            assertSame(secondContext, manager.getContext(), "Other users must survive cleanup and reset");
        } finally {
            workers.shutdownNow();
            JMeterContextService.getContext().setThread(first);
            manager.resetContext();
            JMeterContextService.getContext().setThread(second);
            manager.resetContext();
            JMeterContextService.getContext().clear();
        }
    }
}
