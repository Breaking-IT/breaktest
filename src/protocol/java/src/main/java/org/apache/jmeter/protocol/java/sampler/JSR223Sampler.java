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

package org.apache.jmeter.protocol.java.sampler;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.engine.util.ConfigMergabilityIndicator;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JSR223TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GUIMenuSortOrder(4)
@TestElementMetadata(labelResource = "displayName")
public class JSR223Sampler extends JSR223TestElement implements Cloneable, Sampler, TestBean, ConfigMergabilityIndicator, Interruptible {
    private static final Set<String> APPLIABLE_CONFIG_CLASSES = new HashSet<>(
            Collections.singletonList("org.apache.jmeter.config.gui.SimpleConfigGui"));

    private static final long serialVersionUID = 235L;

    private static final Logger log = LoggerFactory.getLogger(JSR223Sampler.class);

    private transient Thread samplingThread;

    private transient boolean interruptRequested;

    @Override
    public SampleResult sample(Entry entry) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(getName());
        result.setSuccessful(true);
        result.setResponseCodeOK();
        result.setResponseMessageOK();

        final String filename = getFilename();
        if (!filename.isEmpty()){
            result.setSamplerData("File: "+filename);
        } else {
            result.setSamplerData(getScript());
        }
        result.setDataType(SampleResult.TEXT);
        result.sampleStart();
        synchronized (this) {
            samplingThread = Thread.currentThread();
            interruptRequested = false;
        }
        try {
            ScriptEngine scriptEngine = getScriptEngine();
            Bindings bindings = scriptEngine.createBindings();
            bindings.put("SampleResult",result);
            Object ret = processFileOrScript(scriptEngine, bindings);
            if (ret != null && (result.getResponseData() == null || result.getResponseData().length==0)){
                result.setResponseData(ret.toString(), null);
            }
        } catch (IOException | ScriptException e) {
            result.setSuccessful(false);
            if (isInterruption(e)) {
                log.debug("JSR223 element named: '{}' was interrupted", getName(), e);
                result.setResponseCode("499"); // $NON-NLS-1$
                result.setResponseMessage("Script interrupted");
            } else {
                log.error("Problem in JSR223 element named: '{}'", getName(), e);
                result.setResponseCode("500"); // $NON-NLS-1$
                result.setResponseMessage(e.toString());
            }
        } finally {
            synchronized (this) {
                samplingThread = null;
                if (interruptRequested) {
                    // A script that ignored or restored our interrupt must not leave it set for
                    // timers and samplers that run later on this thread. Engine shutdown is
                    // tracked by its own state, not by this flag.
                    Thread.interrupted();
                }
            }
        }
        if (result.getEndTime() == 0) {
            result.sampleEnd();
        }
        return result;
    }

    /**
     * Interrupts the thread running the script. Only interrupt-aware calls such as
     * {@link Thread#sleep(long)}, blocking queues and interruptible I/O are cancelled;
     * busy loops and Groovy's {@code sleep(long)} method, which ignores interrupts, run on.
     */
    @Override
    public synchronized boolean interrupt() {
        if (samplingThread == null) {
            return false;
        }
        interruptRequested = true;
        samplingThread.interrupt();
        return true;
    }

    private boolean isInterruption(Exception e) {
        synchronized (this) {
            if (interruptRequested) {
                return true;
            }
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * @see org.apache.jmeter.samplers.AbstractSampler#applies(org.apache.jmeter.config.ConfigTestElement)
     */
    @Override
    public boolean applies(ConfigTestElement configElement) {
        String guiClass = configElement.getProperty(TestElement.GUI_CLASS).getStringValue();
        return APPLIABLE_CONFIG_CLASSES.contains(guiClass);
    }

    @Override
    @SuppressWarnings("RedundantOverride")
    public Object clone() {
        return super.clone();
    }

}
