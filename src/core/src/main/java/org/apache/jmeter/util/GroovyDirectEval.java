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

import java.io.PrintWriter;
import java.io.Writer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import groovy.lang.Binding;
import groovy.lang.MissingPropertyException;
import groovy.lang.Script;

/**
 * Evaluates a compiled standard Groovy JSR223 script without the per-evaluation
 * bookkeeping of {@code GroovyScriptEngineImpl.eval(Class, ScriptContext)}.
 *
 * <p>The engine registers a {@code MethodClosure} for every public method of the
 * script class in its engine-wide "global closures" map, and replaces the new
 * script instance's metaclass with a {@code DelegatingMetaClass} that falls back
 * to those closures. Both happen on every evaluation. JMeter evaluates each
 * compiled script on the engine that compiled only that script, so the global
 * closures never contain a method the script does not already define. The
 * per-instance metaclass also prevents Groovy's invokedynamic call sites on the
 * script itself (for example {@code vars}, {@code props}, {@code log}) from being
 * cached, so every such access re-runs method selection.
 *
 * <p>This class reproduces the variable lookup of the engine's binding exactly:
 * engine-scope bindings, then global-scope bindings, then {@code out} and
 * {@code context}, otherwise {@link MissingPropertyException}. Assignments update
 * the scope that already defines the name, or engine scope. Checked and runtime
 * exceptions are wrapped in {@link ScriptException} as the engine does.
 *
 * <p>Only used for scripts whose class is {@link JSR223TestElement.DirectGroovyClasses#COMPILED_SCRIPT},
 * so the Groovy classes it links against are those of the engine.
 */
final class GroovyDirectEval {
    private static final VarHandle SCRIPT_CLASS;
    private static final MethodType SCRIPT_CONSTRUCTOR = MethodType.methodType(void.class, Binding.class);

    static {
        try {
            Class<?> compiledScript = JSR223TestElement.DirectGroovyClasses.COMPILED_SCRIPT;
            SCRIPT_CLASS = MethodHandles.privateLookupIn(compiledScript, MethodHandles.lookup())
                    .findVarHandle(compiledScript, "clasz", Class.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Script constructors taking a {@link Binding}, or {@code null} for non-script classes. */
    private static final ClassValue<MethodHandle> CONSTRUCTORS = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            if (!Script.class.isAssignableFrom(type)) {
                return null;
            }
            try {
                return MethodHandles.publicLookup().findConstructor(type, SCRIPT_CONSTRUCTOR)
                        .asType(MethodType.methodType(Script.class, Binding.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                // Non-standard Script subclass: use Groovy's own instantiation.
                return null;
            }
        }
    };

    private GroovyDirectEval() {
    }

    static Object eval(CompiledScript compiledScript, Bindings bindings) throws ScriptException {
        Class<?> scriptClass = (Class<?>) SCRIPT_CLASS.get(compiledScript);
        if (!Script.class.isAssignableFrom(scriptClass)) {
            // Same as the engine: a compiled class declaration evaluates to the class.
            return scriptClass;
        }
        ScriptEngine engine = compiledScript.getEngine();
        Binding binding = new ContextBinding(engine, bindings);
        try {
            MethodHandle constructor = CONSTRUCTORS.get(scriptClass);
            Script script = constructor != null
                    ? (Script) constructor.invokeExact(binding)
                    : org.codehaus.groovy.runtime.InvokerHelper.createScript(scriptClass, binding);
            return script.run();
        } catch (Exception e) {
            throw new ScriptException(e);
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ScriptException(new RuntimeException(t));
        }
    }

    /**
     * Equivalent of the engine's binding over the context that
     * {@link CompiledScript#eval(Bindings)} would create: engine scope is
     * {@code bindings}, global scope and writers come from the engine's default context.
     */
    private static final class ContextBinding extends Binding {
        private final ScriptEngine engine;
        private final Bindings engineScope;
        private ScriptContext context;

        ContextBinding(ScriptEngine engine, Bindings engineScope) {
            super(engineScope);
            this.engine = engine;
            this.engineScope = engineScope;
        }

        @Override
        public Object getVariable(String name) {
            if (engineScope.containsKey(name)) {
                return engineScope.get(name);
            }
            Bindings global = engine.getBindings(ScriptContext.GLOBAL_SCOPE);
            if (global != null && global.containsKey(name)) {
                return global.get(name);
            }
            if ("out".equals(name)) {
                Writer writer = engine.getContext().getWriter();
                if (writer != null) {
                    return writer instanceof PrintWriter ? writer : new PrintWriter(writer, true);
                }
            }
            if ("context".equals(name)) {
                return getContext();
            }
            throw new MissingPropertyException(name, getClass());
        }

        @Override
        public void setVariable(String name, Object value) {
            Bindings global = engine.getBindings(ScriptContext.GLOBAL_SCOPE);
            if (!engineScope.containsKey(name) && global != null && global.containsKey(name)) {
                global.put(name, value);
            } else {
                engineScope.put(name, value);
            }
        }

        private ScriptContext getContext() {
            if (context == null) {
                ScriptContext defaults = engine.getContext();
                SimpleScriptContext created = new SimpleScriptContext();
                created.setReader(defaults.getReader());
                created.setWriter(defaults.getWriter());
                created.setErrorWriter(defaults.getErrorWriter());
                created.setBindings(engine.getBindings(ScriptContext.GLOBAL_SCOPE), ScriptContext.GLOBAL_SCOPE);
                created.setBindings(engineScope, ScriptContext.ENGINE_SCOPE);
                context = created;
            }
            return context;
        }
    }
}
