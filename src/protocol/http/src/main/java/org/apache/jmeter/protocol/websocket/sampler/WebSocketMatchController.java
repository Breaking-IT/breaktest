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

package org.apache.jmeter.protocol.websocket.sampler;

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

/** Child flow activated by incoming application messages on its parent Connect. */
@TestElementMetadata(labelResource = "displayName")
public class WebSocketMatchController extends GenericController implements TestBean, org.apache.jmeter.samplers.ChildControllerSampler.Handler {
    private static final long serialVersionUID = 1L;
    public static final String TEXT = "Exact text";
    public static final String REGEX = "Text regular expression";
    public static final String BINARY = "Binary sequence (hex)";

    public String getMatchMode() { return getPropertyAsString("matchMode", TEXT); }
    public void setMatchMode(String value) { setProperty("matchMode", value); }
    public String getMatchValue() { return getPropertyAsString("matchValue", ""); }
    public void setMatchValue(String value) { setProperty("matchValue", value); }
    public String getSaveMessageVariable() { return getPropertyAsString("saveMessageVariable", ""); }
    public void setSaveMessageVariable(String value) { setProperty("saveMessageVariable", value); }

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
        return new MessageMatcher(getMatchMode(), getMatchValue(), getSaveMessageVariable());
    }

    // A misplaced Match must not execute its children as part of the normal script.
    @Override
    public Sampler next() { return null; }

    static final class MessageMatcher {
        private final String mode;
        private final String value;
        private final String saveVariable;
        private final Pattern pattern;
        private final java.util.function.Predicate<SampleResult> binary;

        MessageMatcher(String mode, String value, String saveVariable) {
            if (!List.of(TEXT, REGEX, BINARY).contains(mode)) {
                throw new IllegalArgumentException("Unknown WebSocket match mode: " + mode);
            }
            this.mode = mode;
            this.value = value;
            this.saveVariable = saveVariable.trim();
            if (this.saveVariable.equals(org.apache.jmeter.threads.JMeterThread.LAST_SAMPLE_OK)
                    || this.saveVariable.equals(org.apache.jmeter.threads.JMeterThread.PACKAGE_OBJECT)) {
                throw new IllegalArgumentException("Cannot save a message over an engine variable");
            }
            pattern = REGEX.equals(mode) ? Pattern.compile(value) : null;
            binary = BINARY.equals(mode) ? WebSocketBinary.matcher(WebSocketBinary.parse(value)) : null;
        }

        boolean match(SampleResult message) {
            boolean text = SampleResult.TEXT.equals(message.getDataType());
            return switch (mode) {
                case TEXT -> text && value.equals(message.getResponseDataAsString());
                case REGEX -> text && pattern.matcher(message.getResponseDataAsString()).find();
                case BINARY -> !text && binary.test(message);
                default -> false;
            };
        }

        void saveMessage(SampleResult message, org.apache.jmeter.threads.JMeterVariables variables) {
            if (!saveVariable.isEmpty()) {
                String completeMessage = SampleResult.BINARY.equals(message.getDataType())
                        ? java.util.HexFormat.of().formatHex(message.getResponseData())
                        : message.getResponseDataAsString();
                variables.put(saveVariable, completeMessage);
            }
        }
    }
}
