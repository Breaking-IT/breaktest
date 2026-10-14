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

package org.apache.jmeter.modifiers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.util.ValueReplacer;
import org.apache.jmeter.junit.JMeterTestCase;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("Changes the JVM default formatting locale")
class CounterConfigTest extends JMeterTestCase {
    @AfterEach
    void clearContext() {
        JMeterContextService.getContext().clear();
    }

    private CounterConfig counter(String pattern) {
        CounterConfig counter = new CounterConfig();
        counter.setStart(1);
        counter.setEnd(9999);
        counter.setIncrement(1);
        counter.setIsPerUser(true);
        counter.setVarName("counter");
        counter.setFormat(pattern);
        JMeterContextService.getContext().setVariables(new JMeterVariables());
        return counter;
    }

    private String next(CounterConfig counter) {
        counter.iterationStart(new LoopIterationEvent(counter, 1));
        return JMeterContextService.getContext().getVariables().get("counter");
    }

    @Test
    void repeatedFormattingPreservesIncrementWrapAndThreadLifecycle() {
        CounterConfig counter = counter("000");
        counter.setEnd(2);
        assertEquals("001", next(counter));
        assertEquals("002", next(counter));
        assertEquals("001", next(counter));
        counter.threadFinished();
        counter.threadStarted();
        assertEquals("001", next(counter));
        counter.threadFinished();
    }

    @Test
    void evaluatedPatternChangesAreObservedIncludingInvalidAndEmptyPatterns() throws Exception {
        CounterConfig counter = counter("${format}");
        new ValueReplacer().replaceValues(counter);
        counter.setRunningVersion(true);
        JMeterVariables variables = JMeterContextService.getContext().getVariables();
        variables.put("format", "000");
        assertEquals("001", next(counter));
        variables.put("format", "00000");
        assertEquals("00002", next(counter));
        variables.put("format", "0.0.0");
        assertEquals("3", next(counter));
        assertEquals("4", next(counter));
        variables.put("format", "");
        assertEquals("5", next(counter));
        variables.put("format", "000");
        assertEquals("006", next(counter));
        counter.threadFinished();
    }

    @Test
    void localeChangesInvalidateCachedSymbols() {
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        CounterConfig counter = counter("#,##0.00");
        counter.setStart(1234);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            assertEquals("1,234.00", next(counter));
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals("1.235,00", next(counter));
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            assertEquals("1,236.00", next(counter));
        } finally {
            counter.threadFinished();
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }

    @Test
    void sharedCounterKeepsConcurrentFormattersAndPatternsThreadLocal() throws Exception {
        CounterConfig counter = counter("${format}");
        counter.setEnd(99999);
        new ValueReplacer().replaceValues(counter);
        counter.setRunningVersion(true);
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> results = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                String pattern = "0".repeat(thread + 4);
                results.add(workers.submit(() -> {
                    JMeterVariables variables = new JMeterVariables();
                    variables.put("format", pattern);
                    JMeterContextService.getContext().setVariables(variables);
                    DecimalFormat expected = new DecimalFormat(pattern);
                    try {
                        for (int value = 1; value <= 500; value++) {
                            assertEquals(expected.format(value), next(counter));
                        }
                    } finally {
                        counter.threadFinished();
                        JMeterContextService.getContext().clear();
                    }
                }));
            }
            for (Future<?> result : results) {
                result.get();
            }
        }
    }

    @Test
    void globalCounterFormattingPreservesSequence() {
        CounterConfig counter = counter("000");
        counter.setIsPerUser(false);
        counter.setEnd(2);
        assertEquals("001", next(counter));
        assertEquals("002", next(counter));
        assertEquals("001", next(counter));
        counter.threadFinished();
    }
}
