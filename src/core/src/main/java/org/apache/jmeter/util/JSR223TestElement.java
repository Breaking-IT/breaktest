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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.jorphan.util.StringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Base class for JSR223 Test elements
 */
public abstract class JSR223TestElement extends ScriptingTestElement
    implements Serializable, TestStateListener
{
    private static final long serialVersionUID = 233L;

    private static final Logger logger = LoggerFactory.getLogger(JSR223TestElement.class);
    /**
     * Cache of compiled scripts
     */
    private static final Cache<ScriptCacheKey, CompiledScript> COMPILED_SCRIPT_CACHE =
            Caffeine
                    .newBuilder()
                    .maximumSize(JMeterUtils.getPropDefault("jsr223.compiled_scripts_cache_size", 100))
                    .build();

    /**
     * Lambdas can't throw checked exceptions, so we wrap cache loading failure with a runtime one.
     */
    static class ScriptCompilationInvocationTargetException extends RuntimeException {
        public ScriptCompilationInvocationTargetException(Throwable cause) {
            super(cause);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final boolean GROOVY_DIRECT_EVAL =
            JMeterUtils.getPropDefault("jsr223.groovy.direct_eval", true);

    /** If not empty then script in ScriptText will be compiled and cached */
    private String cacheKey = "";

    /** md5 of the script, used as an unique key for the cache */
    private ScriptCacheKey scriptMd5;
    private transient String hashedLanguage;
    private transient volatile ElementLogger elementLogger;

    /**
     * Initialization On Demand Holder pattern
     */
    private static class LazyHolder {
        private static final ScriptEngineManager INSTANCE = new ScriptEngineManager();
    }

    /**
     * @return ScriptEngineManager singleton
     */
    public static ScriptEngineManager getInstance() {
            return LazyHolder.INSTANCE;
    }

    protected JSR223TestElement() {
        super();
    }

    /**
     * Obtains an engine for the configured language, defaulting to Groovy.
     *
     * <p>For cached inline Groovy scripts, the engine can be shared by multiple
     * elements and virtual users. Callers and overrides must not put per-execution
     * state into the engine with {@code put}, {@code setBindings}, or
     * {@code setContext}, or mutate its default context. Instead, create fresh
     * bindings with {@link ScriptEngine#createBindings()} and pass them to
     * {@link #processFileOrScript(ScriptEngine, Bindings)}. Do not assume exclusive
     * ownership of the returned engine.
     *
     * @return a possibly shared {@link ScriptEngine}
     * @throws ScriptException when no {@link ScriptEngine} could be found
     */
    protected ScriptEngine getScriptEngine() throws ScriptException {
        String lang = getScriptLanguageWithDefault();
        // A compiled script already owns the engine used to evaluate it. Do not
        // construct and discard another engine merely to create its bindings.
        // File scripts retain fresh-engine lookup: their metadata is validated
        // only in processFileOrScript, without an extra stat on every sample.
        ScriptCacheKey key = StringUtilities.isNotEmpty(getFilename())
                ? null : getInlineScriptCacheKey(getScript());
        CompiledScript cached = key == null ? null : COMPILED_SCRIPT_CACHE.getIfPresent(key);
        if (cached != null && isStandardGroovyScript(cached)) {
            return cached.getEngine();
        }
        ScriptEngine scriptEngine = getInstance().getEngineByName(lang);
        if (scriptEngine == null) {
            throw new ScriptException("Cannot find engine named: '"+lang+"', ensure you set language field in JSR223 element named: " + getName());
        }

        return scriptEngine;
    }

    /**
     * @return script language or DEFAULT_SCRIPT_LANGUAGE if none is set
     */
    private String getScriptLanguageWithDefault() {
        String lang = getScriptLanguage();
        if (StringUtilities.isNotEmpty(lang)) {
            return lang;
        }
        return DEFAULT_SCRIPT_LANGUAGE;
    }

    /**
     * Populate variables to be passed to scripts
     * @param bindings Bindings
     */
    protected void populateBindings(Bindings bindings) {
        final String label = getName();
        final String fileName = getFilename();
        final String scriptParameters = getParameters();
        // Use actual class name for log
        final Logger elementLogger = getElementLogger(label);
        bindings.put("log", elementLogger); // $NON-NLS-1$ (this name is fixed)
        bindings.put("Label", label); // $NON-NLS-1$ (this name is fixed)
        bindings.put("FileName", fileName); // $NON-NLS-1$ (this name is fixed)
        bindings.put("Parameters", scriptParameters); // $NON-NLS-1$ (this name is fixed)
        String[] args=JOrphanUtils.split(scriptParameters, " ");//$NON-NLS-1$
        bindings.put("args", args); // $NON-NLS-1$ (this name is fixed)
        // Add variables for access to context and variables
        JMeterContext jmctx = JMeterContextService.getContext();
        bindings.put("ctx", jmctx); // $NON-NLS-1$ (this name is fixed)
        JMeterVariables vars = jmctx.getVariables();
        bindings.put("vars", vars); // $NON-NLS-1$ (this name is fixed)
        Properties props = JMeterUtils.getJMeterProperties();
        bindings.put("props", props); // $NON-NLS-1$ (this name is fixed)
        // For use in debugging:
        bindings.put("OUT", System.out); // NOSONAR $NON-NLS-1$ (this name is fixed)

        // Most subclasses will need these:
        Sampler sampler = jmctx.getCurrentSampler();
        bindings.put("sampler", sampler); // $NON-NLS-1$ (this name is fixed)
        SampleResult prev = jmctx.getPreviousResult();
        bindings.put("prev", prev); // $NON-NLS-1$ (this name is fixed)
    }


    /**
     * Returns the logger named after the element class and name. The lookup goes
     * through the logging backend's context selection, which walks the call stack,
     * so the result is reused until the element name changes.
     */
    private Logger getElementLogger(String name) {
        ElementLogger cached = elementLogger;
        if (cached == null || !Objects.equals(cached.name(), name)) {
            cached = new ElementLogger(name, LoggerFactory.getLogger(getClass().getName() + "." + name));
            elementLogger = cached;
        }
        return cached.logger();
    }

    private record ElementLogger(String name, Logger logger) {}

    /**
     * This method will run inline script or file script with special behaviour for file script:
     * - If ScriptEngine implements Compilable script will be compiled and cached
     * - If not if will be run
     * @param scriptEngine ScriptEngine
     * @param pBindings {@link Bindings} might be null
     * @return Object returned by script
     * @throws IOException when reading the script fails
     * @throws ScriptException when compiling or evaluation of the script fails
     */
    protected Object processFileOrScript(ScriptEngine scriptEngine, final Bindings pBindings)
            throws IOException, ScriptException {
        Bindings bindings = pBindings;
        if (bindings == null) {
            bindings = scriptEngine.createBindings();
        }
        populateBindings(bindings);
        String filename = getFilename();
        boolean supportsCompilable = scriptEngine instanceof Compilable;
        try {
            if (StringUtilities.isNotEmpty(filename)) {
                File scriptFile = new File(filename);
                if (!scriptFile.isFile()) {
                    throw new ScriptException("Script file '" + scriptFile.getAbsolutePath()
                            + "' is not a file for JSR223 element named: " + getName());
                }
                if (!scriptFile.canRead()) {
                    throw new ScriptException("Script file '" + scriptFile.getAbsolutePath()
                            + "' is not readable for JSR223 element named: " + getName());
                }
                if (!supportsCompilable) {
                    try (BufferedReader fileReader = Files.newBufferedReader(scriptFile.toPath())) {
                        return scriptEngine.eval(fileReader, bindings);
                    }
                }
                CompiledScript compiledScript;
                ScriptCacheKey newCacheKey =
                        ScriptCacheKey.ofFile(getScriptLanguageWithDefault(), scriptFile.getAbsolutePath(), scriptFile.lastModified());
                compiledScript = getCompiledScript(newCacheKey, key -> {
                    try (BufferedReader fileReader = Files.newBufferedReader(scriptFile.toPath())) {
                        return ((Compilable) scriptEngine).compile(fileReader);
                    } catch (IOException | ScriptException e) {
                        throw new ScriptCompilationInvocationTargetException(e);
                    }
                });
                return evalCompiled(compiledScript, bindings);
            }
            String script = getScript();
            if (StringUtilities.isNotEmpty(script)) {
                ScriptCacheKey inlineKey = supportsCompilable ? getInlineScriptCacheKey(script) : null;
                if (inlineKey != null) {
                    CompiledScript compiledScript = getCompiledScript(inlineKey, key -> {
                        try {
                            return ((Compilable) scriptEngine).compile(script);
                        } catch (ScriptException e) {
                            throw new ScriptCompilationInvocationTargetException(e);
                        }
                    });
                    return evalCompiled(compiledScript, bindings);
                } else {
                    return scriptEngine.eval(script, bindings);
                }
            } else {
                throw new ScriptException("Both script file and script text are empty for JSR223 element named: " + getName());
            }
        } catch (ScriptException ex) {
            Throwable rootCause = ex.getCause();
            if(isStopCondition(rootCause)) {
                throw (RuntimeException) ex.getCause();
            } else {
                throw ex;
            }
        }
    }

    /**
     * Evaluates standard Groovy compiled scripts directly, see {@link GroovyDirectEval}.
     * Set {@code jsr223.groovy.direct_eval=false} to use {@link CompiledScript#eval(Bindings)}.
     */
    private static Object evalCompiled(CompiledScript compiledScript, Bindings bindings) throws ScriptException {
        if (GROOVY_DIRECT_EVAL && DirectGroovyClasses.COMPILED_SCRIPT != null
                && compiledScript.getClass() == DirectGroovyClasses.COMPILED_SCRIPT) {
            return GroovyDirectEval.eval(compiledScript, bindings);
        }
        return compiledScript.eval(bindings);
    }

    /**
     * Groovy compiled-script class visible to this class loader, the one {@link GroovyDirectEval}
     * links against. Scripts of engines from other class loaders use {@link CompiledScript#eval}.
     */
    static final class DirectGroovyClasses {
        static final Class<?> COMPILED_SCRIPT = resolve();

        private DirectGroovyClasses() {
        }

        private static Class<?> resolve() {
            try {
                ClassLoader loader = JSR223TestElement.class.getClassLoader();
                Class.forName("groovy.lang.Script", false, loader);
                return Class.forName("org.codehaus.groovy.jsr223.GroovyCompiledScript", false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                // Groovy is optional; other providers keep the standard evaluation path.
                return null;
            }
        }
    }

    private static boolean isStandardGroovyScript(CompiledScript script) {
        // Other providers can have engine-specific createBindings behavior.
        // Preserve their existing fresh-engine lifecycle.
        return script.getClass() == STANDARD_GROOVY_SCRIPT.get(script.getEngine().getClass());
    }

    // Resolve once per engine class, using its defining loader for embedded deployments.
    // ClassValue allows plugin class loaders to be reclaimed when their engines are unloaded.
    private static final ClassValue<Class<?>> STANDARD_GROOVY_SCRIPT = new ClassValue<>() {
        @Override
        protected Class<?> computeValue(Class<?> engineClass) {
            try {
                ClassLoader loader = engineClass.getClassLoader();
                if (engineClass == Class.forName("org.codehaus.groovy.jsr223.GroovyScriptEngineImpl", false, loader)) {
                    return Class.forName("org.codehaus.groovy.jsr223.GroovyCompiledScript", false, loader);
                }
            } catch (ClassNotFoundException | LinkageError e) {
                // Groovy is optional for installations using only other providers.
                logger.debug("Cached Groovy engine reuse is unavailable for engine {}. "
                        + "Fresh-engine lookup will be used; no action is required. Reason: {}",
                        engineClass.getName(), e.toString());
            }
            return null;
        }
    };

    private static <T extends ScriptCacheKey> CompiledScript getCompiledScript(
            T newCacheKey,
            Function<? super ScriptCacheKey, ? extends CompiledScript> compiler
    ) throws IOException, ScriptException {
        try {
            CompiledScript compiledScript = COMPILED_SCRIPT_CACHE.get(newCacheKey, compiler);
            if (compiledScript == null) {
                throw new ScriptException("Script compilation returned null: " + newCacheKey);
            }
            return compiledScript;
        } catch (ScriptCompilationInvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                cause.addSuppressed(new IllegalStateException("Unable to compile JSR223 script: " + newCacheKey));
                throw ioException;
            }
            if (cause instanceof ScriptException scriptException) {
                cause.addSuppressed(new IllegalStateException("Unable to compile JSR223 script: " + newCacheKey));
                throw scriptException;
            }
            throw e;
        }
    }

    /**
     * @return boolean true if element is not compilable or if compilation succeeds
     * @throws IOException if script is missing
     * @throws ScriptException if compilation fails
     */
    public boolean compile()
        throws ScriptException, IOException {
        String lang = getScriptLanguageWithDefault();
        ScriptEngine scriptEngine = getInstance().getEngineByName(lang);
        boolean supportsCompilable = scriptEngine instanceof Compilable;
        if(!supportsCompilable) {
            return true;
        }
        if (!(getScript() == null || getScript().isEmpty())) {
            try {
                ((Compilable) scriptEngine).compile(getScript());
                return true;
            } catch (ScriptException e) { // NOSONAR
                logger.error("Error compiling script for JSR223 element named: '{}', error: {}", getName(), e.getMessage());
                return false;
            }
        } else {
            File scriptFile = new File(getFilename());
            try (BufferedReader fileReader = Files.newBufferedReader(scriptFile.toPath())) {
                try {
                    ((Compilable) scriptEngine).compile(fileReader);
                    return true;
                } catch (ScriptException e) { // NOSONAR
                    logger.error("Error compiling script for JSR223 element named: '{}', error: {}", getName(), e.getMessage());
                    return false;
                }
            }
        }
    }

    /**
     * Returns the language-aware key for the first nonempty inline script, or
     * {@code null} when inline caching is disabled or the script is empty.
     * Preserves first-expansion caching until the language changes or the test ends.
     */
    private ScriptCacheKey getInlineScriptCacheKey(String script) {
        if (ScriptingBeanInfoSupport.FALSE_AS_STRING.equals(cacheKey) || StringUtilities.isEmpty(script)) {
            return null;
        }
        String language = getScriptLanguageWithDefault();
        if (scriptMd5 == null || !language.equals(hashedLanguage)) {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(language.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(script.getBytes(StandardCharsets.UTF_8));
                scriptMd5 = ScriptCacheKey.ofDigest(md.digest());
                hashedLanguage = language;
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("MessageDigest.getInstance(MD5) was not found", e);
            }
        }
        return scriptMd5;
    }

    /**
     * @return the cacheKey
     */
    public String getCacheKey() {
        return cacheKey;
    }

    /**
     * @param cacheKey the cacheKey to set
     */
    public void setCacheKey(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    /**
     * @see org.apache.jmeter.testelement.TestStateListener#testStarted()
     */
    @Override
    public void testStarted() {
        // NOOP
    }

    /**
     * @see org.apache.jmeter.testelement.TestStateListener#testStarted(java.lang.String)
     */
    @Override
    public void testStarted(String host) {
        // NOOP
    }

    /**
     * @see org.apache.jmeter.testelement.TestStateListener#testEnded()
     */
    @Override
    public void testEnded() {
        testEnded("");
    }

    /**
     * @see org.apache.jmeter.testelement.TestStateListener#testEnded(java.lang.String)
     */
    @Override
    public void testEnded(String host) {
        COMPILED_SCRIPT_CACHE.invalidateAll();
        scriptMd5 = null;
        hashedLanguage = null;
    }

    public String getScriptLanguage() {
        return scriptLanguage;
    }

    public void setScriptLanguage(String s) {
        scriptLanguage = s;
    }
}
