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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class JSR223TestElementTest {

    @SuppressWarnings("serial")
    private static class TestElement extends JSR223TestElement {
    }

    private final JSR223TestElement element = new TestElement();

    @AfterEach
    void clearScriptCache() {
        element.testEnded();
    }

    private Object evaluate() throws Exception {
        return element.processFileOrScript(element.getScriptEngine(), null);
    }

    @Test
    void cachedScriptReusesEngineAndIsolatesBindings() throws Exception {
        element.setScript("binding.hasVariable('scratch') ? scratch : (scratch = 1)");
        ScriptEngine original = element.getScriptEngine();
        assertEquals(1, element.processFileOrScript(original, null));
        assertSame(original, element.getScriptEngine());
        assertEquals(1, evaluate());
        Bindings bindings = original.createBindings();
        bindings.put("scratch", 7);
        assertEquals(7, element.processFileOrScript(element.getScriptEngine(), bindings));
        assertEquals(1, evaluate());
        element.testEnded();
        assertNotSame(original, element.getScriptEngine());
    }

    @Test
    void cachedInlineEngineIsSharedAcrossElementsButBindingsAreFresh() throws Exception {
        element.setScript("assert !binding.hasVariable('scratch'); scratch = Label; scratch");
        element.setName("first");
        assertEquals("first", evaluate());
        JSR223TestElement other = new TestElement();
        other.setScript(element.getScript());
        other.setName("second");
        assertSame(element.getScriptEngine(), other.getScriptEngine());
        assertEquals("second", other.processFileOrScript(other.getScriptEngine(), null));
        assertEquals("first", evaluate());
    }

    @Test
    void fileSelectionDoesNotReuseAnInlineEngine(@TempDir Path directory) throws Exception {
        element.setScript("1");
        assertEquals(1, evaluate());
        ScriptEngine inlineEngine = element.getScriptEngine();
        element.setFilename(directory.resolve("missing.groovy").toString());
        assertNotSame(inlineEngine, element.getScriptEngine());
        assertThrows(ScriptException.class, this::evaluate);
    }

    @Test
    void cachedScriptEvaluationsKeepConcurrentBindingsSeparate() throws Exception {
        element.setScript("def value = marker; Thread.yield(); marker = value + 1; marker");
        ScriptEngine engine = element.getScriptEngine();
        Bindings warmup = engine.createBindings();
        warmup.put("marker", 0);
        assertEquals(1, element.processFileOrScript(engine, warmup));
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                final int value = i;
                results.add(workers.submit(() -> {
                    Bindings bindings = engine.createBindings();
                    bindings.put("marker", value);
                    return element.processFileOrScript(engine, bindings);
                }));
            }
            for (int i = 0; i < results.size(); i++) {
                assertEquals(i + 1, results.get(i).get());
            }
        }
    }

    @Test
    void cachedEngineUsesEachVirtualUsersCurrentContext() throws Exception {
        element.setScript("assert !binding.hasVariable('scratch'); "
                + "scratch = vars.get('user'); Thread.yield(); "
                + "assert ctx.getVariables().is(vars); assert ctx.getPreviousResult().is(prev); "
                + "assert prev.getSampleLabel() == scratch; scratch");
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final String user = "user-" + i;
                results.add(workers.submit(() -> {
                    JSR223TestElement local = new TestElement();
                    local.setScript(element.getScript());
                    try {
                        for (int execution = 0; execution < 10; execution++) {
                            String value = user + "-" + execution;
                            JMeterVariables variables = new JMeterVariables();
                            variables.put("user", value);
                            SampleResult previous = new SampleResult();
                            previous.setSampleLabel(value);
                            JMeterContextService.getContext().setVariables(variables);
                            JMeterContextService.getContext().setPreviousResult(previous);
                            assertEquals(value, local.processFileOrScript(local.getScriptEngine(), null));
                        }
                    } finally {
                        JMeterContextService.getContext().clear();
                    }
                    return null;
                }));
            }
            for (Future<?> result : results) {
                result.get();
            }
        }
    }

    @Test
    void preservesFirstEvaluationCachingUntilTestEndAndSeparatesLanguages() throws Exception {
        element.setScript("1");
        assertEquals(1, evaluate());
        element.setScript("2");
        assertEquals(1, evaluate());
        element.testEnded();
        assertEquals(2, evaluate());
        element.setScriptLanguage("missing-benchmark-test-language");
        assertThrows(ScriptException.class, element::getScriptEngine);
    }

    @Test
    void customProviderRetainsBindingsEvaluationAndFreshEngineLookup() throws Exception {
        element.setScript("custom-provider-script");
        ScriptEngine custom = new CustomEngine();
        assertEquals("bindings-overload", element.processFileOrScript(custom, null));
        assertNotSame(custom, element.getScriptEngine());
    }

    @Test
    void groovyEngineFromAnIsolatedLoaderIsReused() throws Exception {
        ScriptEngine standard = element.getScriptEngine();
        URL engineJar = standard.getClass().getProtectionDomain().getCodeSource().getLocation();
        URL groovyJar = Class.forName("groovy.lang.GroovyObject").getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {engineJar, groovyJar}, ClassLoader.getPlatformClassLoader())) {
            ScriptEngine isolated = (ScriptEngine) Class.forName(standard.getClass().getName(), true, loader)
                    .getConstructor().newInstance();
            assertNotSame(standard.getClass(), isolated.getClass());
            assertSame(loader, isolated.getClass().getClassLoader());
            element.setScript("assert !binding.hasVariable('scratch'); scratch = Label; scratch");
            element.setName("first");
            assertEquals("first", element.processFileOrScript(isolated, null));
            assertSame(isolated, element.getScriptEngine());
            element.setName("second");
            assertEquals("second", evaluate());
        }
    }

    @Test
    void groovyEngineSubclassIsNotReused() throws Exception {
        ScriptEngine custom = (ScriptEngine) element.getScriptEngine().eval(
                "new org.codehaus.groovy.jsr223.GroovyScriptEngineImpl() { }");
        element.setScript("42");
        assertEquals(42, element.processFileOrScript(custom, null));
        assertNotSame(custom, element.getScriptEngine());
        assertEquals(42, evaluate());
    }

    private static final class CustomEngine extends AbstractScriptEngine implements Compilable {
        @Override
        public Bindings createBindings() {
            return new SimpleBindings();
        }

        @Override
        public ScriptEngineFactory getFactory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object eval(String script, ScriptContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object eval(Reader reader, ScriptContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompiledScript compile(Reader reader) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompiledScript compile(String script) {
            return new CompiledScript() {
                @Override
                public Object eval(Bindings bindings) {
                    return "bindings-overload";
                }

                @Override
                public Object eval(ScriptContext context) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public ScriptEngine getEngine() {
                    return CustomEngine.this;
                }
            };
        }
    }

    @Test
    void disabledCachePreservesFreshEngineBehavior() throws Exception {
        element.setScript("1");
        assertEquals(1, evaluate());
        element.setCacheKey("false");
        ScriptEngine first = element.getScriptEngine();
        assertEquals(1, element.processFileOrScript(first, null));
        assertNotSame(first, element.getScriptEngine());
    }

    @Test
    void fileScriptsKeepFreshEngineLookupAndValidateChanges(@TempDir Path directory) throws Exception {
        Path script = directory.resolve("script.groovy");
        Files.writeString(script, "1");
        element.setFilename(script.toString());
        ScriptEngine first = element.getScriptEngine();
        assertEquals(1, element.processFileOrScript(first, null));
        assertNotSame(first, element.getScriptEngine());
        assertEquals(1, evaluate());
        long modified = Files.getLastModifiedTime(script).toMillis();
        Files.writeString(script, "2");
        Files.setLastModifiedTime(script, FileTime.fromMillis(modified + 2000));
        assertEquals(2, evaluate());
        Files.delete(script);
        assertThrows(ScriptException.class, this::evaluate);
    }

    @Test
    public void testGetScriptEngineDefault() throws Exception {
        element.setScriptLanguage("");
        assertEquals("Groovy", element.getScriptEngine().getFactory().getLanguageName());
    }

    @Test
    public void testLegacyJexlAliasesAreHiddenFromLanguageList() {
        String[] languages = Arrays.stream(JSR223BeanInfoSupport.getLanguageNames())
                .map(language -> language[0])
                .toArray(String[]::new);

        assertFalse(Arrays.asList(languages).contains("jexl"));
        assertFalse(Arrays.asList(languages).contains("jexl2"));
    }

    @Test
    public void testLegacyJexlAliasDetection() {
        assertTrue(JSR223BeanInfoSupport.isHiddenScriptLanguageAlias("jexl"));
        assertTrue(JSR223BeanInfoSupport.isHiddenScriptLanguageAlias("JEXL2"));
        assertFalse(JSR223BeanInfoSupport.isHiddenScriptLanguageAlias("jexl3"));
    }

}
