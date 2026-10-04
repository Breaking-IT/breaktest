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

package org.apache.jmeter.visualizers;

import javax.swing.JPanel;

import org.apache.jmeter.gui.util.BinaryDataPanel;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;

import com.google.auto.service.AutoService;

/** Displays response bytes without decoding or treating them as an image. */
@AutoService(ResultRenderer.class)
public class RenderAsBinary extends SamplerResultTab implements ResultRenderer {
    private final BinaryDataPanel binary = new BinaryDataPanel();

    @Override
    protected JPanel createResponseDataPanel() {
        super.createResponseDataPanel();
        return binary;
    }

    @Override
    public void clearData() {
        super.clearData();
        binary.clearData();
    }

    @Override
    public void renderResult(SampleResult sampleResult) {
        binary.setData(sampleResult.getResponseData());
    }

    @Override
    public void renderImage(SampleResult sampleResult) {
        renderResult(sampleResult);
    }

    static String[] formatColumns(byte[] bytes, int limit) {
        return BinaryDataPanel.formatColumns(bytes, limit);
    }

    @Override
    String responseDataText() {
        return binary.getDisplayText();
    }

    @Override
    public String toString() {
        return JMeterUtils.getResString("view_results_render_binary");
    }
}
