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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Differential tests: {@link GroovyDirectEval} must behave like
 * {@link CompiledScript#eval(Bindings)} of the standard Groovy engine.
 */
class GroovyDirectEvalTest {

    private record Outcome(Object result, Map<String, Object> bindings, String out, Throwable failure) {}

    private interface Evaluator {
        Object eval(CompiledScript script, Bindings bindings) throws ScriptException;
    }

    private static Outcome run(String source, Map<String, Object> initial, boolean direct) throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("groovy");
        StringWriter out = new StringWriter();
        engine.getContext().setWriter(new PrintWriter(out, true));
        engine.getBindings(ScriptContext.GLOBAL_SCOPE).put("globalValue", "from-global");
        CompiledScript compiled = ((Compilable) engine).compile(source);
        assertTrue(compiled.getClass() == JSR223TestElement.DirectGroovyClasses.COMPILED_SCRIPT);
        Bindings bindings = engine.createBindings();
        bindings.putAll(initial);
        Evaluator evaluator = direct ? GroovyDirectEval::eval : CompiledScript::eval;
        Object result = null;
        Throwable failure = null;
        try {
            result = evaluator.eval(compiled, bindings);
        } catch (ScriptException e) {
            failure = e;
        }
        Map<String, Object> after = new HashMap<>();
        // Each compilation defines new script and closure classes: compare them by normalized name
        bindings.forEach((key, value) -> after.put(key,
                value != null && value.getClass().getName().contains("_closure") ? normalize(value.getClass().getName()) : value));
        return new Outcome(result, after, out.toString(), failure);
    }

    private static String normalize(String text) {
        return text == null ? null : text.replaceAll("Script\\d+", "Script");
    }

    private static void assertSameBehaviour(String source, Map<String, Object> initial) throws Exception {
        Outcome expected = run(source, initial, false);
        Outcome actual = run(source, initial, true);
        if (expected.result() instanceof Class<?> expectedClass) {
            // Each compilation defines a distinct class; compare by name
            assertEquals(expectedClass.getName(), ((Class<?>) actual.result()).getName(), source);
        } else {
            assertEquals(expected.result(), actual.result(), source);
        }
        assertEquals(expected.bindings(), actual.bindings(), source);
        assertEquals(expected.out(), actual.out(), source);
        if (expected.failure() == null) {
            assertEquals(null, actual.failure(), source);
        } else {
            assertEquals(expected.failure().getClass(), actual.failure().getClass(), source);
            assertEquals(expected.failure().getCause().getClass(), actual.failure().getCause().getClass(), source);
            String expectedMessage = normalize(expected.failure().getCause().getMessage());
            if (source.startsWith("missingMethod")) {
                // Known difference: the engine reports missing methods from its global-closure
                // fallback, naming the engine class; the direct path names the script class.
                expectedMessage = expectedMessage.replace("org.codehaus.groovy.jsr223.GroovyScriptEngineImpl", "Script");
            }
            assertEquals(expectedMessage, normalize(actual.failure().getCause().getMessage()), source);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "input + 1",
            "created = input * 2; created",
            "input = input + 10; input",
            "def local = 5; local + input",
            "globalValue",
            "globalValue = 'changed'; globalValue",
            "def twice(x) { x * 2 }; twice(input)",
            "fn = { it + 1 }; fn(input)",
            "println 'hello'; print input; null",
            "out.println('via out'); 1",
            "context.getAttribute('input')",
            "binding.hasVariable('input') && !binding.hasVariable('nope')",
            "missingVariable",
            "missingMethod(1)",
            "throw new IllegalStateException('boom')",
            "class Declared { }",
            "this.metaClass.respondsTo(this, 'run').size()",
    })
    void behavesLikeCompiledScriptEval(String source) throws Exception {
        assertSameBehaviour(source, Map.of("input", 41));
    }

    @Test
    void globalScopeAssignmentUpdatesGlobalBindings() throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("groovy");
        Bindings global = engine.getBindings(ScriptContext.GLOBAL_SCOPE);
        global.put("shared", 1);
        CompiledScript compiled = ((Compilable) engine).compile("shared = shared + 1");
        Bindings bindings = engine.createBindings();
        GroovyDirectEval.eval(compiled, bindings);
        assertEquals(2, global.get("shared"));
        assertTrue(!bindings.containsKey("shared"), "Global variable stays in global scope");
    }

    @Test
    void contextIsStableWithinOneEvaluation() throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("groovy");
        CompiledScript compiled = ((Compilable) engine).compile("context.is(context)");
        assertEquals(true, GroovyDirectEval.eval(compiled, engine.createBindings()));
    }

    @Test
    void wrapsScriptExceptionsLikeTheEngine() throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("groovy");
        CompiledScript compiled = ((Compilable) engine).compile("throw new java.io.IOException('io')");
        ScriptException e = assertThrows(ScriptException.class,
                () -> GroovyDirectEval.eval(compiled, engine.createBindings()));
        assertSame(java.io.IOException.class, e.getCause().getClass());
    }
}
