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

package org.apache.jmeter.protocol.sse;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.ParallelController;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.TestElement;

/** Child flow activated by incoming application messages on its parent HTTP Request. */
@TestElementMetadata(labelResource = "displayName")
public class SseMatchController extends GenericController implements TestBean, org.apache.jmeter.samplers.ChildControllerSampler.Handler {
    private static final long serialVersionUID = 1L;
    public static final String TEXT = "Exact text";
    public static final String REGEX = "Text regular expression";

    public String getMatchMode() { return getPropertyAsString("matchMode", TEXT); }
    public void setMatchMode(String value) { setProperty("matchMode", value); }
    public String getMatchValue() { return getPropertyAsString("matchValue", ""); }
    public void setMatchValue(String value) { setProperty("matchValue", value); }
    public String getSaveMessageVariable() { return getPropertyAsString("saveMessageVariable", ""); }
    public void setSaveMessageVariable(String value) { setProperty("saveMessageVariable", value); }

    public String getEventName() { return getPropertyAsString("eventName", ""); }
    public void setEventName(String value) { setProperty("eventName", value); }

    List<TestElement> children() { return List.copyOf(getSubControllers()); }

    GenericController execution(IdentityHashMap<Sampler, Sampler> sources) {
        GenericController controller = new GenericController();
        controller.setName(getName());
        for (TestElement child : children()) {
            ParallelController.addParallelChild(controller, child, sources);
        }
        return controller;
    }

    MessageMatcher matcher() {
        return new MessageMatcher(getMatchMode(), getMatchValue(), getSaveMessageVariable(), getEventName());
    }

    // A misplaced Match must not execute its children as part of the normal script.
    @Override
    public Sampler next() { return null; }

    static final class MessageMatcher {
        private final String eventName;
        private final String mode;
        private final String value;
        private final String saveVariable;
        private final Pattern pattern;

        MessageMatcher(String mode, String value, String saveVariable, String eventName) {
            this.eventName = eventName;
            if (!List.of(TEXT, REGEX).contains(mode)) {
                throw new IllegalArgumentException("Unknown SSE match mode: " + mode);
            }
            this.mode = mode;
            this.value = value;
            this.saveVariable = saveVariable.trim();
            if (this.saveVariable.equals(org.apache.jmeter.threads.JMeterThread.LAST_SAMPLE_OK)
                    || this.saveVariable.equals(org.apache.jmeter.threads.JMeterThread.PACKAGE_OBJECT)) {
                throw new IllegalArgumentException("Cannot save a message over an engine variable");
            }
            pattern = REGEX.equals(mode) ? Pattern.compile(value) : null;
        }

        boolean match(SampleResult message) {
            if (!eventName.isEmpty() && !eventName.equals(message.getResponseMessage())) {
                return false;
            }
            boolean text = SampleResult.TEXT.equals(message.getDataType());
            return switch (mode) {
                case TEXT -> text && value.equals(message.getResponseDataAsString());
                case REGEX -> text && pattern.matcher(message.getResponseDataAsString()).find();
                default -> false;
            };
        }

        void saveMessage(SampleResult message, org.apache.jmeter.threads.JMeterVariables variables) {
            if (!saveVariable.isEmpty()) {
                variables.put(saveVariable, message.getResponseDataAsString());
            }
        }
    }
}
