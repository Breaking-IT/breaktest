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

package org.apache.jmeter.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.gui.action.validation.TreeClonerForValidation;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.save.ArchiveFiles;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.ValidationCheckpoints;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CsvValidationResumeTest extends JMeterTestCase {
    private static final List<String> ACCOUNTS = new CopyOnWriteArrayList<>();
    @TempDir
    Path directory;

    @AfterEach
    void cleanup() throws Exception {
        ValidationCheckpoints.clear();
        FileServer.getFileServer().closeFiles();
        JMeterContextService.setValidationRun(false);
        ArchiveFiles.activate(null);
    }

    public static class AccountSampler extends AbstractSampler {
        @Override
        public SampleResult sample(Entry entry) {
            ACCOUNTS.add(JMeterContextService.getContext().getVariables().get("account"));
            SampleResult result = new SampleResult();
            result.setSuccessful(true);
            return result;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"shareMode.all", "shareMode.group", "shareMode.thread", "custom"})
    @Timeout(15)
    void resumesArchivedAccountWithoutRunningEarlierSampler(String sharing) throws Exception {
        ValidationCheckpoints.clear();
        ACCOUNTS.clear();
        TestPlan plan = new TestPlan();
        ArchiveFiles.put(plan, "accounts.csv", "account\nAlice\nBob\n".getBytes(StandardCharsets.UTF_8), false);
        CSVDataSet csv = new CSVDataSet();
        csv.setName("accounts");
        csv.setProperty("TestElement.gui_class", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        csv.setProperty("filename", "accounts.csv");
        csv.setProperty("delimiter", ",");
        csv.setProperty("shareMode", sharing);
        csv.setProperty("recycle", false);
        csv.setUseCsvFromArchive(true);
        ThreadGroup group = new ThreadGroup();
        LoopController loop = new LoopController();
        loop.setLoops(1);
        group.setSamplerController(loop);
        group.setNumThreads(1);
        ListedHashTree source = new ListedHashTree();
        var flow = source.add(plan).add(group);
        flow.add(csv);
        flow.add(new AccountSampler());
        AccountSampler target = new AccountSampler();
        target.setName("target");
        flow.add(target);
        ListedHashTree initial = cloneTree(source);
        ValidationCheckpoints.rememberStructure(initial);
        run(initial);
        assertEquals(List.of("Alice", "Alice"), ACCOUNTS);
        var checkpoint = ValidationCheckpoints.get(target);
        for (int i = 0; i < 2; i++) {
            ListedHashTree resumed = cloneTree(source);
            ValidationCheckpoints.prepareResume(resumed, checkpoint);
            ACCOUNTS.clear();
            run(resumed);
            assertEquals(List.of("Alice"), ACCOUNTS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoresQuotedMultilineCursorAndRandomOrder(boolean random) throws Exception {
        JMeterContextService.setValidationRun(true);
        FileServer server = FileServer.getFileServer();
        Path csv = directory.resolve("accounts.csv");
        Files.writeString(csv, "account,description\r\nAlice,\"line1\nline2\"\r\nBob,b\r\nCarol,c\r\n");
        server.reserveFile(csv.toString(), "UTF-8", "original", true, random);
        server.getParsedLine("original", false, true, ',');
        Object state = server.captureReadPosition("original");
        String[] next = server.getParsedLine("original", false, true, ',');
        String[] last = server.getParsedLine("original", false, true, ',');
        server.closeFiles();
        server.reserveFile(csv.toString(), "UTF-8", "resumed", true, random);
        for (int i = 0; i < 2; i++) {
            server.restoreReadPosition("resumed", state);
            assertArrayEquals(next, server.getParsedLine("resumed", false, true, ','));
            assertArrayEquals(last, server.getParsedLine("resumed", false, true, ','));
            assertArrayEquals(new String[0], server.getParsedLine("resumed", false, true, ','));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoresPlainRowsIncludingRecycledPosition(boolean random) throws Exception {
        JMeterContextService.setValidationRun(true);
        FileServer server = FileServer.getFileServer();
        Path csv = directory.resolve("plain.csv");
        Files.writeString(csv, "name\nAlice\nBob\n");
        server.reserveFile(csv.toString(), "UTF-8", "plain", true, random);
        server.readLine("plain", true, true);
        server.readLine("plain", true, true);
        server.readLine("plain", true, true);
        Object saved = server.captureReadPosition("plain");
        String next = server.readLine("plain", false, true);
        server.restoreReadPosition("plain", saved);
        assertEquals(next, server.readLine("plain", false, true));
        Files.writeString(csv, "changed contents\n");
        assertThrows(IllegalStateException.class, () -> server.restoreReadPosition("plain", saved));
    }

    private static ListedHashTree cloneTree(ListedHashTree source) {
        TreeClonerForValidation cloner = new TreeClonerForValidation();
        source.traverse(cloner);
        return cloner.getClonedTree();
    }

    private static void run(ListedHashTree tree) throws Exception {
        JMeterContextService.setValidationRun(true);
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(tree);
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(4));
    }
}
