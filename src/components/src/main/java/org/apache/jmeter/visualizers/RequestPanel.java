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

import java.awt.BorderLayout;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.swing.JPanel;

import org.apache.jmeter.samplers.SampleResult;

/**
 * Manipulate all classes which implements request view panel interface
 * and return a super panel with a bottom tab list of this classes
 *
 */
public class RequestPanel {

    private final Deque<RequestView> listRequestView;

    private final JPanel panel;

    /**
     * Find and instantiate all classes that extend RequestView
     * and Create Request Panel
     */
    public RequestPanel() {
        listRequestView = new ArrayDeque<>();
        RequestView requestView = new RequestViewRaw();
        requestView.init();
        listRequestView.add(requestView);

        panel = new JPanel(new BorderLayout());
        panel.add(requestView.getPanel());
    }

    /**
     * Clear data in all request view
     */
    public void clearData() {
        for (RequestView requestView : listRequestView) {
            requestView.clearData();
        }
    }

    /**
     * Put SamplerResult in all request view
     *
     * @param samplerResult The {@link SampleResult} to be put in all {@link RequestView}s
     */
    public void setSamplerResult(SampleResult samplerResult) {
        for (RequestView requestView : listRequestView) {
            requestView.setSamplerResult(samplerResult);
        }
    }

    /**
     * @return a tabbed panel for view request
     */
    public JPanel getPanel() {
        return panel;
    }

}
