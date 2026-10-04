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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public String getVariablePrefix() { return getPropertyAsString("variablePrefix", "ws"); }
    public void setVariablePrefix(String value) { setProperty("variablePrefix", value); }
    public String getCaptureVariable() { return getPropertyAsString("captureVariable", ""); }
    public void setCaptureVariable(String value) { setProperty("captureVariable", value); }

    List<TestElement> children() { return List.copyOf(getSubControllers()); }

    GenericController execution(IdentityHashMap<Sampler, Sampler> sources) {
        GenericController controller = new GenericController();
        controller.setName(getName());
        for (TestElement child : children()) {
            ParallelController.addParallelChild(controller, child, sources);
        }
        return controller;
    }

    MessageMatcher matcher(String sessionName) {
        return new MessageMatcher(getMatchMode(), getMatchValue(), getVariablePrefix(), getCaptureVariable(), sessionName);
    }

    // A misplaced Match must not execute its children as part of the normal script.
    @Override
    public Sampler next() { return null; }

    static final class MessageMatcher {
        private final String mode;
        private final String value;
        private final String prefix;
        private final String capture;
        private final String session;
        private final Pattern pattern;
        private final java.util.function.Predicate<SampleResult> binary;

        MessageMatcher(String mode, String value, String prefix, String capture, String session) {
            if (!List.of(TEXT, REGEX, BINARY).contains(mode)) {
                throw new IllegalArgumentException("Unknown WebSocket match mode: " + mode);
            }
            if (!prefix.matches("[A-Za-z_][A-Za-z_0-9]*")
                    || !capture.isEmpty() && !capture.matches("[A-Za-z_][A-Za-z_0-9]*")) {
                throw new IllegalArgumentException("Match variable names must be identifiers");
            }
            if (capture.equals(org.apache.jmeter.threads.JMeterThread.LAST_SAMPLE_OK)) {
                throw new IllegalArgumentException("Match capture cannot overwrite an engine variable");
            }
            this.mode = mode;
            this.value = value;
            this.prefix = prefix;
            this.capture = REGEX.equals(mode) ? capture : "";
            this.session = session;
            pattern = REGEX.equals(mode) ? Pattern.compile(value) : null;
            binary = BINARY.equals(mode) ? WebSocketBinary.matcher(WebSocketBinary.parse(value)) : null;
            if (!this.capture.isEmpty() && pattern.matcher("").groupCount() < 1) {
                throw new IllegalArgumentException("Capture variable requires a regular expression with a capture group");
            }
        }

        Map<String, Object> match(SampleResult message) {
            boolean text = SampleResult.TEXT.equals(message.getDataType());
            String content = message.getResponseDataAsString();
            var matcher = pattern == null || !text ? null : pattern.matcher(content);
            boolean matched = switch (mode) {
                case TEXT -> text && value.equals(content);
                case REGEX -> matcher != null && matcher.find();
                case BINARY -> !text && binary.test(message);
                default -> false;
            };
            if (!matched) {
                return null;
            }
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put(prefix + "_message", content);
            variables.put(prefix + "_hex", java.util.HexFormat.of().formatHex(message.getResponseData()));
            variables.put(prefix + "_session", session);
            variables.put(prefix + "_binary", Boolean.toString(!text));
            if (matcher != null) {
                for (int i = 0; i <= matcher.groupCount(); i++) {
                    variables.put(prefix + "_g" + i, matcher.group(i) == null ? "" : matcher.group(i));
                }
                if (!capture.isEmpty()) {
                    variables.put(capture, matcher.group(1) == null ? "" : matcher.group(1));
                }
            }
            return variables;
        }
    }
}
