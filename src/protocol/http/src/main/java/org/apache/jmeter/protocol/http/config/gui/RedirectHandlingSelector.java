/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.protocol.http.config.gui;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;

import org.apache.jmeter.util.JMeterUtils;

/**
 * Presents the two redirect properties as one mutually exclusive choice.
 */
public final class RedirectHandlingSelector extends JComboBox<RedirectHandlingSelector.Option> {

    private static final long serialVersionUID = 1L;

    enum Option {
        NONE("follow_redirects_none"), // $NON-NLS-1$
        FOLLOW("follow_redirects"), // $NON-NLS-1$
        AUTOMATIC("follow_redirects_auto"); // $NON-NLS-1$

        private final String label;

        Option(String resourceKey) {
            label = JMeterUtils.getResString(resourceKey);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public RedirectHandlingSelector() {
        this(true, true);
    }

    public RedirectHandlingSelector(boolean showFollowRedirects, boolean showAutomaticRedirects) {
        super(new DefaultComboBoxModel<>());
        addItem(Option.NONE);
        if (showFollowRedirects) {
            addItem(Option.FOLLOW);
        }
        if (showAutomaticRedirects) {
            addItem(Option.AUTOMATIC);
        }
    }

    public boolean isFollowRedirects() {
        return getSelectedItem() == Option.FOLLOW;
    }

    public boolean isAutomaticRedirects() {
        return getSelectedItem() == Option.AUTOMATIC;
    }

    public void setRedirects(boolean followRedirects, boolean automaticRedirects) {
        Option option = automaticRedirects ? Option.AUTOMATIC
                : followRedirects ? Option.FOLLOW : Option.NONE;
        DefaultComboBoxModel<Option> model = (DefaultComboBoxModel<Option>) getModel();
        setSelectedItem(model.getIndexOf(option) >= 0 ? option : Option.NONE);
    }
}
