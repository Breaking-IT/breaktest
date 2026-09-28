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

package org.apache.jmeter.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.PreCompiler;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.scenario.ScenarioPlanMigration;
import org.apache.jmeter.scenario.ScenarioResolver;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Functions in a test plan organised in scenarios. */
class ScenarioFunctionsTest extends JMeterTestCase {
    @TempDir
    Path dir;

    /** An old plan: a thread group defines {@code seed}, a test plan level variable after it uses it. */
    private static HashTree legacyPlan(String result) {
        HashTree tree = new ListedHashTree();
        HashTree plan = tree.add(new TestPlan());
        ThreadGroup group = new ThreadGroup();
        group.setName("Workers");
        group.setNumThreads(1);
        LoopController loops = new LoopController();
        loops.setLoops(1);
        group.setSamplerController(loops);
        Arguments local = new Arguments();
        local.addArgument("seed", "2");
        plan.add(group).add(local);
        Arguments shared = new Arguments();
        shared.addArgument("result", result);
        plan.add(shared);
        return tree;
    }

    private static String compiledResult(HashTree tree) {
        ScenarioResolver.resolve(JMeter.convertSubTree(tree, false)).traverse(new PreCompiler());
        return JMeterContextService.getContext().getVariables().get("result");
    }

    @Test
    void sharedVariablesCanStillUseVariablesOfThreadGroups() {
        String result = "${__intSum(${seed},1)}";
        assertEquals("3", compiledResult(legacyPlan(result)), "The old plan");
        assertEquals("3", compiledResult(ScenarioPlanMigration.migrate(legacyPlan(result))));
    }

    @Test
    void functionsEvaluatedWhileResolvingTheScenarioAreEndedWithTheTest() throws Exception {
        Path input = dir.resolve("values.txt");
        Files.writeString(input, "first\nsecond\n");
        HashTree tree = ScenarioPlanMigration.migrate(legacyPlan("${__StringFromFile(" + input + ")}"));
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(JMeter.convertSubTree(tree, false));
        engine.runTest();
        engine.awaitTermination(Duration.ofSeconds(10));

        var registrationsField = StandardJMeterEngine.class.getDeclaredField("testList");
        registrationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<List<TestStateListener>> registrations =
                (ThreadLocal<List<TestStateListener>>) registrationsField.get(null);
        try {
            for (TestStateListener listener : registrations.get()) {
                if (listener instanceof StringFromFile) {
                    var readerField = StringFromFile.class.getDeclaredField("myBread");
                    readerField.setAccessible(true);
                    BufferedReader reader = (BufferedReader) readerField.get(listener);
                    assertThrows(IOException.class, reader::ready, "The file must be closed when the test ends");
                }
            }
        } finally {
            registrations.get().forEach(TestStateListener::testEnded);
            registrations.remove();
        }
    }
}
